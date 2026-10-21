package dev.otectus.mcacrime.gameplay;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.item.MaskVariant;
import dev.otectus.mcacrime.mask.MaskColors;
import dev.otectus.mcacrime.menu.MaskStationMenu;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.SelectMaskRecipeC2SPacket;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The 0.8.0 mask rendering evidence (workstream D5), drawn by the game itself. The NeoForge copy of the
 * Forge build's {@code tools/gameplay} roles of the same names, adapted to 1.21.1's data components,
 * payload networking and inventory-entity drawing.
 *
 * <p>Roles:
 * <ul>
 *   <li>{@code masks-gallery} (single player): contact sheets of all 16 styles in their original art and all
 *   16 standard colours, as inventory icons and as worn on armour stands; a sheet of the other places a mask
 *   is drawn (hand, ground, item frame, local and remote players, MCA villagers); the Mask Station preview;
 *   creative search and localized names; and the icons again after a resource reload. A layout file records
 *   where every cell is, so the colours can be measured from the screenshots as well as looked at.</li>
 *   <li>{@code masks-wearer} and {@code masks-observer} (two clients, one server): the wearer crafts, recolours,
 *   restyles and washes a mask through the real station screen and a real cauldron, wearing each result,
 *   while the observer checks the colour it received for the wearer's head and photographs it; then the
 *   wearer places all 272 catalogue stacks on armour stands and the observer checks and photographs every
 *   one as its client received it.</li>
 *   <li>{@code masks-resume}: after the server restarted, the wearer's head and the armour stands come back.</li>
 * </ul>
 */
final class MaskScenarioChecks {

    private record Step(String name, int delay, int timeout, BooleanSupplier body) {
    }

    private static final String WEARER = "CrimeQa";
    private static final String OBSERVER = "CrimeBystander";
    private static final int COLUMNS = 17;
    private static final int LABEL_WIDTH = 132;
    private static final int HEADER = 30;

    private final Minecraft mc;
    private final Path output;
    private final Path sync;
    private final Consumer<String> passSink;
    private final Runnable finish;
    private final List<Step> steps = new ArrayList<>();
    private final List<String> layout = new ArrayList<>();
    private int index, entered = -1, now;
    private Sheet sheet;
    private CompletableFuture<Void> reload;
    private BlockPos station, cauldron;
    private final ArmorStand[] stands = new ArmorStand[COLUMNS];
    /**
     * Frames actually drawn. Client ticks run on regardless of how slowly software rendering draws, so a
     * screenshot taken a fixed number of ticks after a change could still hold the previous frame; every
     * shot waits for frames drawn after the change instead.
     */
    private static volatile int frames;
    private static boolean counting;

    MaskScenarioChecks(Minecraft mc, String role, Path output, Path sync, Consumer<String> pass, Runnable finish) {
        this.mc = mc;
        this.output = output;
        this.sync = sync;
        this.passSink = pass;
        this.finish = finish;
        if (!counting) {
            counting = true;
            NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> frames++);
        }
        switch (role) {
            case "masks-gallery" -> gallery();
            case "masks-wearer" -> wearer();
            case "masks-observer" -> observer();
            case "masks-resume" -> resume();
            default -> throw new AssertionError("Unknown mask role " + role);
        }
    }

    int step() {
        return index;
    }

    void tick(int tick) {
        now = tick;
        if (index >= steps.size()) return;
        if (entered < 0) entered = tick;
        Step step = steps.get(index);
        if (tick - entered < step.delay()) return;
        if (step.body().getAsBoolean()) {
            index++;
            entered = tick;
            return;
        }
        if (tick - entered > step.delay() + step.timeout()) {
            throw new AssertionError("mask scenario timed out at step " + index + " (" + step.name() + ")");
        }
    }

    private void add(String name, int delay, Runnable body) {
        steps.add(new Step(name, delay, 0, () -> {
            body.run();
            return true;
        }));
    }

    private void until(String name, int timeout, BooleanSupplier body) {
        steps.add(new Step(name, 1, timeout, body));
    }

    /** Waits until three frames have been drawn since this step began, so a following shot shows the change. */
    private void settle(String name) {
        final int[] mark = new int[1];
        add(name + ": mark frames", 0, () -> {
            // MCA's "set your destiny" hint and the recipe toast would otherwise sit over the sheets' labels.
            mc.getToasts().clear();
            mark[0] = frames;
        });
        until(name + ": frames drawn", 2400, () -> frames >= mark[0] + 3);
    }

    // ------------------------------------------------------------------ catalogue

    private static List<MaskVariant> styles() {
        List<MaskVariant> styles = new ArrayList<>(List.of(MaskVariant.values()));
        styles.sort(Comparator.comparing((MaskVariant variant) -> variant.family().ordinal())
                .thenComparingInt(MaskVariant::sortOrder));
        return styles;
    }

    private static Item item(MaskVariant variant) {
        return CrimeItems.MASKS.get(variant).get();
    }

    /** Column 0 is the original art, columns 1-16 the standard colours in the game's dye order. */
    private static ItemStack cell(MaskVariant variant, int column) {
        return column == 0 ? new ItemStack(item(variant)) : MaskColors.sample(item(variant), MaskColors.STANDARD.get(column - 1));
    }

    private static String columnName(int column) {
        return column == 0 ? "original" : MaskColors.STANDARD.get(column - 1).getName();
    }

    // ------------------------------------------------------------------ gallery (single player)

    private void gallery() {
        add("prepare", 20, () -> {
            mc.options.guiScale().set(1);
            mc.options.hideGui = false;
            mc.resizeDisplay();
            command("gamerule doDaylightCycle false");
            command("time set noon");
            sheet = new Sheet();
            sheet.mode = Mode.ICONS;
            mc.setScreen(sheet);
        });
        settle("icons");
        add("icons shot", 0, () -> shot("masks-icons.png", Mode.ICONS));
        add("worn 1", 1, () -> {
            sheet.mode = Mode.WORN;
            sheet.firstRow = 0;
        });
        settle("worn 1");
        add("worn 1 shot", 0, () -> shot("masks-worn-1.png", Mode.WORN));
        add("worn 2", 1, () -> sheet.firstRow = 8);
        settle("worn 2");
        add("worn 2 shot", 0, () -> shot("masks-worn-2.png", Mode.WORN));
        add("contexts", 1, () -> sheet.mode = Mode.CONTEXTS);
        settle("contexts");
        add("contexts shot", 0, () -> shot("masks-contexts.png", Mode.CONTEXTS));
        add("names and search", 1, this::namesAndSearch);
        add("station place", 1, () -> {
            mc.setScreen(null);
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
            station = mc.player.blockPosition().offset(2, 0, 0);
            command("setblock " + xyz(station) + " mcacrime:mask_station");
            command("give @s minecraft:leather 2");
            command("give @s minecraft:string 1");
            command("give @s minecraft:light_blue_dye 1");
        });
        stationOpen("gallery station");
        add("station fill", 5, () -> {
            AbstractContainerMenu menu = menu();
            place(menu, Items.LEATHER, MaskStationMenu.MATERIAL_SLOT);
            place(menu, Items.STRING, MaskStationMenu.BINDING_SLOT);
            place(menu, Items.LIGHT_BLUE_DYE, MaskStationMenu.DYE_SLOT);
        });
        add("station select", 5, () -> select("mcacrime:mask_station/leather/raven_mask"));
        until("station preview", 200, () -> !((MaskStationMenu) menu()).previewStack().isEmpty());
        settle("station preview drawn");
        add("station shot", 0, () -> {
            ItemStack preview = ((MaskStationMenu) menu()).previewStack();
            require(ItemStack.matches(preview, MaskColors.sample(item(MaskVariant.RAVEN), DyeColor.LIGHT_BLUE)),
                    "the station previewed " + preview + " " + preview.getComponentsPatch());
            screenshot("masks-station-preview.png");
            pass("the Mask Station screen previews a light-blue Plague Doctor Mask exactly as it will be made");
            mc.player.closeContainer();
            mc.options.guiScale().set(1);
            mc.resizeDisplay();
        });
        // The same masks in the world: worn and held by the local player, dropped, and on an armour stand.
        add("world setup", 5, () -> {
            command("tp @s ~ ~ ~ 0 12");
            command("item replace entity @s armor.head with mcacrime:owl_mask[minecraft:dyed_color={rgb:" + MaskColors.standardColor(DyeColor.RED) + "}]");
            command("item replace entity @s weapon.mainhand with mcacrime:hockey_mask[minecraft:dyed_color={rgb:" + MaskColors.standardColor(DyeColor.LIME) + "}]");
            command("summon item ~-1 ~0.4 ~3 {Item:{id:\"mcacrime:jackal_mask\",count:1,components:{\"minecraft:dyed_color\":{rgb:"
                    + MaskColors.standardColor(DyeColor.WHITE) + "}}},NoGravity:1b,PickupDelay:32767,Age:-32768}");
            command("summon armor_stand ~1.2 ~ ~3 {Rotation:[180f,0f],ArmorItems:[{},{},{},{id:\"mcacrime:raven_mask\",count:1,components:{\"minecraft:dyed_color\":{rgb:"
                    + MaskColors.standardColor(DyeColor.BLUE) + "}}}]}");
            mc.options.hideGui = false;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        });
        until("world ready", 400, () -> mc.player.getMainHandItem().is(item(MaskVariant.HOCKEY))
                && mc.player.getItemBySlot(EquipmentSlot.HEAD).is(item(MaskVariant.OWL))
                && mc.level.getEntitiesOfClass(ArmorStand.class, mc.player.getBoundingBox().inflate(8)).stream()
                        .anyMatch(stand -> stand.getItemBySlot(EquipmentSlot.HEAD).is(item(MaskVariant.RAVEN))));
        settle("world first person");
        add("world first person shot", 0, () -> screenshot("masks-world-first-person.png"));
        add("world third person", 1, () -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
        settle("world third person");
        add("world third person shot", 0, () -> {
            screenshot("masks-world-third-person.png");
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            pass("in the world: a red Owl Mask worn and a lime Hockey Mask held by the local player, a white Jackal Mask dropped and a blue Plague Doctor Mask on an armour stand");
        });
        add("reload", 5, () -> reload = mc.reloadResourcePacks());
        until("reload done", 1200, () -> reload.isDone());
        // The loading overlay keeps fading out after the reload completes; a shot taken under it shows the
        // splash screen, not the sheet.
        until("overlay gone", 1200, () -> mc.getOverlay() == null);
        add("after reload", 10, () -> {
            reload.join();
            mc.options.guiScale().set(1);
            mc.resizeDisplay();
            sheet = new Sheet();
            sheet.mode = Mode.ICONS;
            mc.setScreen(sheet);
        });
        settle("after reload");
        add("after reload shot", 0, () -> {
            shot("masks-icons-after-reload.png", Mode.ICONS);
            writeLayout();
            pass("contact sheets written: 272 icons, 272 worn masks, contexts, station preview, and the icons after a resource reload");
            finish.run();
        });
    }

    private void stationOpen(String what) {
        until(what + ": open", 400, () -> {
            if (mc.screen instanceof AbstractContainerScreen<?> screen && screen.getMenu() instanceof MaskStationMenu) return true;
            if ((now - entered) % 20 == 1) use(station);
            return false;
        });
    }

    private void namesAndSearch() {
        int checked = 0;
        for (MaskVariant variant : styles()) {
            String style = Component.translatable(variant.displayKey()).getString();
            require(!style.startsWith("item."), "untranslated style " + variant.displayKey());
            for (int column = 0; column <= 16; column++) {
                ItemStack stack = cell(variant, column);
                String expected = column == 0 ? style
                        : Component.translatable(MaskColors.colorNameKey(MaskColors.STANDARD.get(column - 1))).getString() + " " + style;
                require(stack.getHoverName().getString().equals(expected), "named '" + stack.getHoverName().getString() + "', expected '" + expected + "'");
                checked++;
            }
        }
        pass("all " + checked + " catalogue stacks carry their localized names (e.g. \""
                + cell(MaskVariant.RAVEN, 12).getHoverName().getString() + "\")");
        CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(), true, mc.level.registryAccess());
        // 1.21.1 builds the creative name index per session, from the search tab, on a background thread.
        mc.player.connection.searchTrees().updateCreativeTooltips(mc.level.registryAccess(),
                List.copyOf(CreativeModeTabs.searchTab().getSearchTabDisplayItems()));
        List<ItemStack> found = mc.player.connection.searchTrees().creativeNameSearch().search("blue plague");
        require(found.stream().anyMatch(stack -> stack.is(item(MaskVariant.RAVEN))
                && MaskColors.standardOf(stack).equals(Optional.of(DyeColor.BLUE))), "creative search for 'blue plague' found " + found);
        require(found.stream().anyMatch(stack -> stack.is(item(MaskVariant.RAVEN))
                && MaskColors.standardOf(stack).equals(Optional.of(DyeColor.LIGHT_BLUE))), "creative search missed the light blue mask");
        List<ItemStack> balaclavas = mc.player.connection.searchTrees().creativeNameSearch().search("black balaclava");
        require(balaclavas.stream().anyMatch(stack -> stack.is(item(MaskVariant.IRON_SKULL))), "creative search for 'black balaclava' found " + balaclavas);
        pass("creative search finds the colour variants by name: 'blue plague' -> " + found.size() + " stacks, 'black balaclava' -> " + balaclavas.size());
    }

    // ------------------------------------------------------------------ the sheet

    private enum Mode { ICONS, WORN, CONTEXTS, SUBJECTS }

    /** What the contexts sheet shows, one row each. */
    private static final List<Object[]> CONTEXT_ROWS = List.of(
            new Object[]{MaskVariant.HIGHWAYMANS_DOMINO, DyeColor.BLACK},
            new Object[]{MaskVariant.IRON_SKULL, DyeColor.BLACK},
            new Object[]{MaskVariant.JACKAL, DyeColor.WHITE},
            new Object[]{MaskVariant.WRAPPED_SCARF, DyeColor.GRAY},
            new Object[]{MaskVariant.RAVEN, DyeColor.BLUE},
            new Object[]{MaskVariant.OWL, DyeColor.RED},
            new Object[]{MaskVariant.HOCKEY, DyeColor.LIME},
            new Object[]{MaskVariant.BANDANA, null});

    private static final String[] CONTEXTS = {"icon", "first-person hand", "held", "on the ground", "item frame",
            "armour stand", "local player", "remote player", "MCA male", "MCA female"};

    private final class Sheet extends Screen {
        Mode mode = Mode.ICONS;
        int firstRow;
        /** Entities to photograph in SUBJECTS mode, with their labels. */
        final List<LivingEntity> subjects = new ArrayList<>();
        final List<String> subjectLabels = new ArrayList<>();
        private LivingEntity holder, remote, male, female;
        private ArmorStand contextStand;

        Sheet() {
            super(Component.literal("MCA: Crime mask contact sheets"));
            for (int i = 0; i < COLUMNS; i++) stands[i] = new ArmorStand(mc.level, 0, 0, 0);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, 0xFF8B8B8B);
            switch (mode) {
                case ICONS -> grid(graphics, 0, 16, false);
                case WORN -> grid(graphics, firstRow, 8, true);
                case CONTEXTS -> contexts(graphics);
                case SUBJECTS -> subjects(graphics);
            }
        }

        private int cellWidth() {
            return (width - LABEL_WIDTH) / COLUMNS;
        }

        private void grid(GuiGraphics graphics, int first, int rows, boolean worn) {
            List<MaskVariant> styles = styles();
            int w = cellWidth();
            int h = (height - HEADER) / rows;
            graphics.drawString(font, worn ? "worn (armour stands)" : "inventory icons (3x)", 4, 4, 0xFFFFFF);
            for (int column = 0; column < COLUMNS; column++) {
                graphics.drawString(font, columnName(column), LABEL_WIDTH + column * w + 2, 18, 0xFFFFFF);
            }
            for (int row = 0; row < rows; row++) {
                MaskVariant variant = styles.get(first + row);
                int y = HEADER + row * h;
                graphics.drawString(font, Component.translatable(variant.displayKey()), 4, y + h / 2 - 4, 0xFFFFFF);
                for (int column = 0; column < COLUMNS; column++) {
                    int x = LABEL_WIDTH + column * w;
                    graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, column == 0 ? 0xFF6F6F6F : 0xFF9C9C9C);
                    ItemStack stack = cell(variant, column);
                    if (worn) {
                        ArmorStand stand = stands[column];
                        stand.setItemSlot(EquipmentSlot.HEAD, stack);
                        graphics.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
                        head(graphics, x + 1, y + 1, x + w - 1, y + h - 1, 95, stand);
                        graphics.disableScissor();
                    } else {
                        graphics.pose().pushPose();
                        graphics.pose().translate(x + (w - 48) / 2.0F, y + (h - 48) / 2.0F, 0.0F);
                        graphics.pose().scale(3.0F, 3.0F, 1.0F);
                        graphics.renderItem(stack, 0, 0);
                        graphics.pose().popPose();
                    }
                }
            }
        }

        private void contexts(GuiGraphics graphics) {
            int columns = CONTEXTS.length;
            int w = (width - LABEL_WIDTH) / columns;
            int h = (height - HEADER) / CONTEXT_ROWS.size();
            if (holder == null) {
                holder = EntityType.ZOMBIE.create(mc.level);
                remote = new RemotePlayer(mc.level, new GameProfile(UUID.nameUUIDFromBytes("mask-remote".getBytes()), "RemoteWearer"));
                male = mca("male_villager");
                female = mca("female_villager");
                contextStand = new ArmorStand(mc.level, 0, 0, 0);
            }
            graphics.drawString(font, "where a mask is drawn", 4, 4, 0xFFFFFF);
            for (int column = 0; column < columns; column++) {
                graphics.drawString(font, CONTEXTS[column], LABEL_WIDTH + column * w + 2, 18, 0xFFFFFF);
            }
            for (int row = 0; row < CONTEXT_ROWS.size(); row++) {
                MaskVariant variant = (MaskVariant) CONTEXT_ROWS.get(row)[0];
                DyeColor dye = (DyeColor) CONTEXT_ROWS.get(row)[1];
                ItemStack stack = dye == null ? new ItemStack(item(variant)) : MaskColors.sample(item(variant), dye);
                int y = HEADER + row * h;
                graphics.drawString(font, stack.getHoverName(), 4, y + h / 2 - 4, 0xFFFFFF);
                for (int column = 0; column < columns; column++) {
                    int x = LABEL_WIDTH + column * w;
                    graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF9C9C9C);
                    graphics.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
                    int cx = x + w / 2;
                    int cy = y + h / 2;
                    switch (column) {
                        case 0 -> {
                            graphics.pose().pushPose();
                            graphics.pose().translate(cx - 24.0F, cy - 24.0F, 0.0F);
                            graphics.pose().scale(3.0F, 3.0F, 1.0F);
                            graphics.renderItem(stack, 0, 0);
                            graphics.pose().popPose();
                        }
                        case 1 -> renderStatic(graphics, stack, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, cx - 20, cy - 10, 70.0F);
                        case 2 -> {
                            holder.setItemSlot(EquipmentSlot.MAINHAND, stack);
                            InventoryScreen.renderEntityInInventoryFollowsAngle(graphics, x + 1, y + 1, x + w - 1, y + h - 1, 55, 0.0F, -0.7F, 0.0F, holder);
                        }
                        case 3 -> renderStatic(graphics, stack, ItemDisplayContext.GROUND, cx, cy + 10, 110.0F);
                        case 4 -> renderStatic(graphics, stack, ItemDisplayContext.FIXED, cx, cy, 70.0F);
                        case 5 -> worn(graphics, contextStand, stack, cx, y, h);
                        case 6 -> worn(graphics, mc.player, stack, cx, y, h);
                        case 7 -> worn(graphics, remote, stack, cx, y, h);
                        case 8 -> worn(graphics, male, stack, cx, y, h);
                        case 9 -> worn(graphics, female, stack, cx, y, h);
                        default -> {
                        }
                    }
                    graphics.disableScissor();
                }
            }
        }

        private void worn(GuiGraphics graphics, LivingEntity entity, ItemStack stack, int cx, int y, int h) {
            if (entity == null) return;
            ItemStack before = entity.getItemBySlot(EquipmentSlot.HEAD);
            entity.setItemSlot(EquipmentSlot.HEAD, stack);
            head(graphics, cx - 70, y + 1, cx + 70, y + h - 1, 85, entity);
            if (entity == mc.player) entity.setItemSlot(EquipmentSlot.HEAD, before);
        }

        private void subjects(GuiGraphics graphics) {
            int count = Math.max(1, subjects.size());
            int columns = Math.min(COLUMNS, count);
            int rows = (count + columns - 1) / columns;
            int w = (width - 8) / columns;
            int h = (height - HEADER) / rows;
            graphics.drawString(font, "as this client received them", 4, 4, 0xFFFFFF);
            for (int i = 0; i < subjects.size(); i++) {
                int x = 4 + (i % columns) * w;
                int y = HEADER + (i / columns) * h;
                graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF9C9C9C);
                graphics.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
                int scale = Math.min(95, Math.max(30, h * 3 / 4));
                head(graphics, x + 1, y + 1, x + w - 1, y + h - 1, scale, subjects.get(i));
                graphics.disableScissor();
                graphics.drawString(font, subjectLabels.get(i), x + 3, y + 3, 0xFFFFFF);
            }
        }
    }

    /** An MCA villager built on this client, with MCA's own skin initialiser, to wear masks in the sheet. */
    private LivingEntity mca(String path) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.fromNamespaceAndPath("mca", path)).orElse(null);
        if (type == null) return null;
        Entity entity = type.create(mc.level);
        if (!(entity instanceof LivingEntity living)) return null;
        try {
            living.getClass().getMethod("initializeSkin", boolean.class).invoke(living, true);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not initialize an MCA villager's skin", failure);
        }
        return living;
    }

    /**
     * The wearer's head, centred in the rectangle: 1.21.1 draws an inventory entity centred on its box, so
     * the offset lifts the eyes, rather than the middle of the body, to the centre.
     */
    private static void head(GuiGraphics graphics, int x1, int y1, int x2, int y2, int scale, LivingEntity entity) {
        float offset = entity.getEyeHeight() - entity.getBbHeight() / 2.0F;
        InventoryScreen.renderEntityInInventoryFollowsAngle(graphics, x1, y1, x2, y2, scale, offset, 0.6F, 0.0F, entity);
    }

    private void renderStatic(GuiGraphics graphics, ItemStack stack, ItemDisplayContext context, int cx, int cy, float scale) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(cx, cy, 150.0F);
        pose.mulPose(new Matrix4f().scaling(1.0F, -1.0F, 1.0F));
        pose.scale(scale, scale, scale);
        if (context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND) {
            // The first-person transform turns a flat item edge-on to a camera looking down the z axis;
            // a quarter turn shows its face, as the player sees it in their hand.
            pose.mulPose(new Quaternionf().rotateY((float) Math.toRadians(90.0)));
        }
        Lighting.setupFor3DItems();
        mc.getItemRenderer().renderStatic(stack, context, 0xF000F0, OverlayTexture.NO_OVERLAY, pose, graphics.bufferSource(), mc.level, 0);
        graphics.flush();
        pose.popPose();
    }

    // ------------------------------------------------------------------ evidence

    private void shot(String name, Mode mode) {
        screenshot(name);
        int w = (sheet.width - LABEL_WIDTH) / COLUMNS;
        if (mode == Mode.ICONS || mode == Mode.WORN) {
            int rows = mode == Mode.ICONS ? 16 : 8;
            int h = (sheet.height - HEADER) / rows;
            List<MaskVariant> styles = styles();
            double scale = mc.getWindow().getGuiScale();
            for (int row = 0; row < rows; row++) {
                MaskVariant variant = styles.get((mode == Mode.WORN ? sheet.firstRow : 0) + row);
                for (int column = 0; column < COLUMNS; column++) {
                    layout.add(String.format(Locale.ROOT,
                            "{\"shot\":\"%s\",\"style\":\"%s\",\"colour\":\"%s\",\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d}",
                            name, variant.textureName(), columnName(column),
                            (int) ((LABEL_WIDTH + column * w) * scale), (int) ((HEADER + row * h) * scale),
                            (int) (w * scale), (int) (h * scale)));
                }
            }
        }
    }

    private void writeLayout() {
        try {
            Files.writeString(output.resolve("mask-sheet-layout.json"), "[\n" + String.join(",\n", layout) + "\n]\n");
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private void screenshot(String name) {
        Screenshot.grab(output.toFile(), name, mc.getMainRenderTarget(), message -> { });
    }

    // ------------------------------------------------------------------ the station, through the client

    private AbstractContainerMenu menu() {
        if (mc.screen instanceof AbstractContainerScreen<?> screen) return screen.getMenu();
        throw new AssertionError("no container screen is open: " + mc.screen);
    }

    private void use(BlockPos pos) {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    /** Picks the first inventory stack of {@code item} up and puts it down in {@code target}, with two clicks. */
    private void place(AbstractContainerMenu menu, Item item, int target) {
        for (Slot slot : menu.slots) {
            if (slot.index > MaskStationMenu.RESULT_SLOT && slot.getItem().is(item)) {
                mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.PICKUP, mc.player);
                mc.gameMode.handleInventoryMouseClick(menu.containerId, target, 0, ClickType.PICKUP, mc.player);
                return;
            }
        }
        throw new AssertionError("no " + item + " in the inventory to put in slot " + target);
    }

    private void select(String recipe) {
        AbstractContainerMenu menu = menu();
        CrimeNetwork.sendToServer(new SelectMaskRecipeC2SPacket(menu.containerId, ResourceLocation.parse(recipe),
                ((MaskStationMenu) menu).recipeGeneration()));
    }

    // ------------------------------------------------------------------ two clients

    private static int colourOf(ItemStack stack) {
        return MaskColors.storedColor(stack).orElse(-1);
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty() ? "nothing" : stack.getItem().toString() + "/" + (MaskColors.hasColor(stack)
                ? MaskColors.hex(colourOf(stack)) : "undyed");
    }

    /** The four states the wearer passes through, in order, as the item and colour the observer should see. */
    private static List<Object[]> wearerStates() {
        int blue = MaskColors.standardColor(DyeColor.BLUE);
        ItemStack blueRaven = MaskColors.sample(item(MaskVariant.RAVEN), DyeColor.BLUE);
        int blend = colourOf(DyedItemColor.applyDyes(blueRaven.copy(), List.of(DyeItem.byColor(DyeColor.RED))));
        return List.of(
                new Object[]{MaskVariant.RAVEN, blue, "crafted at the station with blue dye"},
                new Object[]{MaskVariant.RAVEN, blend, "recoloured at the station with red dye (a blend)"},
                new Object[]{MaskVariant.JACKAL, blend, "restyled at the station into a Jackal Mask, colour kept"},
                new Object[]{MaskVariant.JACKAL, -1, "washed in a water cauldron"});
    }

    private void wearer() {
        add("setup", 40, () -> {
            command("gamerule doDaylightCycle false");
            command("gamerule doMobSpawning false");
            command("time set noon");
            command("clear @s");
            BlockPos at = mc.player.blockPosition();
            station = at.offset(-2, 0, 0);
            cauldron = at.offset(-2, 0, 2);
            command("setblock " + xyz(station) + " mcacrime:mask_station");
            command("setblock " + xyz(cauldron) + " minecraft:water_cauldron[level=3]");
            command("give @s minecraft:leather 2");
            command("give @s minecraft:string 3");
            command("give @s minecraft:blue_dye 1");
            command("give @s minecraft:red_dye 1");
            write("masks-wearer-joined", xyz(at));
        });
        until("observer joined", 2400, () -> seen("masks-observer-joined"));
        add("face each other", 20, () -> {
            BlockPos at = mc.player.blockPosition();
            command("tp " + OBSERVER + " " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 3.5) + " facing entity " + WEARER + " eyes");
            command("tp @s ~ ~ ~ facing entity " + OBSERVER + " eyes");
        });
        // 1: craft a blue Plague Doctor Mask at the station and wear it.
        stationSteps("craft", List.of(new Object[]{Items.LEATHER, MaskStationMenu.MATERIAL_SLOT},
                new Object[]{Items.STRING, MaskStationMenu.BINDING_SLOT},
                new Object[]{Items.BLUE_DYE, MaskStationMenu.DYE_SLOT}), "mcacrime:mask_station/leather/raven_mask");
        wearAndAnnounce(1);
        // 2: recolour it red (a blend) at the station.
        takeOff();
        stationSteps("recolour", List.of(new Object[]{item(MaskVariant.RAVEN), MaskStationMenu.MATERIAL_SLOT},
                new Object[]{Items.STRING, MaskStationMenu.BINDING_SLOT},
                new Object[]{Items.RED_DYE, MaskStationMenu.DYE_SLOT}), "mcacrime:mask_station/leather/restyle_raven_mask");
        wearAndAnnounce(2);
        // 3: restyle it into a Jackal Mask, keeping the colour.
        takeOff();
        stationSteps("restyle", List.of(new Object[]{item(MaskVariant.RAVEN), MaskStationMenu.MATERIAL_SLOT},
                new Object[]{Items.STRING, MaskStationMenu.BINDING_SLOT}), "mcacrime:mask_station/leather/restyle_jackal_mask");
        wearAndAnnounce(3);
        // 4: wash it in the cauldron.
        takeOff();
        add("wash", 10, () -> {
            hold(item(MaskVariant.JACKAL));
            use(cauldron);
        });
        until("washed", 200, () -> mc.player.getMainHandItem().is(item(MaskVariant.JACKAL)) && !MaskColors.hasColor(mc.player.getMainHandItem()));
        wearAndAnnounce(4);
        // 5: the whole catalogue on armour stands, for the observer to check one by one.
        add("stands", 10, () -> {
            BlockPos at = mc.player.blockPosition();
            List<MaskVariant> styles = styles();
            for (int row = 0; row < styles.size(); row++) {
                for (int column = 0; column < COLUMNS; column++) {
                    ItemStack stack = cell(styles.get(row), column);
                    String tag = MaskColors.hasColor(stack) ? ",components:{\"minecraft:dyed_color\":{rgb:" + colourOf(stack) + "}}" : "";
                    queued.add("summon minecraft:armor_stand " + (at.getX() + 4 + column) + " " + at.getY() + " " + (at.getZ() - 4 - row)
                            + " {NoGravity:1b,Invulnerable:1b,Rotation:[0f,0f],Tags:[\"crime_mask_sheet\"],ArmorItems:[{},{},{},{id:\""
                            + CrimeItems.MASKS.get(styles.get(row)).getId() + "\",count:1" + tag + "}]}");
                }
            }
        });
        until("stands summoned", 2400, () -> {
            for (int i = 0; i < 6 && !queued.isEmpty(); i++) command(queued.remove(0));
            return queued.isEmpty();
        });
        until("stands visible here", 1200, () -> sheetStands().size() == 16 * COLUMNS);
        add("stands announced", 20, () -> {
            checkStands(sheetStands());
            write("masks-stands", "272");
            pass("the wearer's own client received all 272 catalogue stacks on armour stands with their colours");
        });
        until("observer checked stands", 3600, () -> seen("masks-seen-stands"));
        add("done", 1, () -> {
            pass("the observer confirmed every state of the wearer's mask and all 272 armour stands");
            finish.run();
        });
    }

    private final List<String> queued = new ArrayList<>();

    private void stationSteps(String what, List<Object[]> inputs, String recipe) {
        stationOpen(what);
        add(what + ": fill", 5, () -> {
            AbstractContainerMenu menu = menu();
            for (Object[] input : inputs) place(menu, (Item) input[0], (Integer) input[1]);
        });
        add(what + ": select", 5, () -> select(recipe));
        until(what + ": preview", 200, () -> !((MaskStationMenu) menu()).previewStack().isEmpty());
        add(what + ": take", 5, () -> {
            AbstractContainerMenu menu = menu();
            mc.gameMode.handleInventoryMouseClick(menu.containerId, MaskStationMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE, mc.player);
        });
        add(what + ": close", 10, () -> mc.player.closeContainer());
    }

    private void hold(Item item) {
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getItem(slot).is(item)) {
                mc.player.getInventory().selected = slot;
                return;
            }
        }
        // Not on the hotbar: swap it there through the inventory menu, as a player's number key would.
        AbstractContainerMenu inventory = mc.player.inventoryMenu;
        for (Slot slot : inventory.slots) {
            if (slot.index >= 9 && slot.index < 36 && slot.getItem().is(item)) {
                mc.gameMode.handleInventoryMouseClick(inventory.containerId, slot.index, 8, ClickType.SWAP, mc.player);
                mc.player.getInventory().selected = 8;
                return;
            }
        }
        throw new AssertionError("no " + item + " to hold");
    }

    private void takeOff() {
        add("take off", 10, () -> mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, 5, 0,
                ClickType.QUICK_MOVE, mc.player));
        until("taken off", 200, () -> mc.player.getItemBySlot(EquipmentSlot.HEAD).isEmpty());
    }

    private void wearAndAnnounce(int state) {
        Object[] expected = wearerStates().get(state - 1);
        MaskVariant variant = (MaskVariant) expected[0];
        int colour = (Integer) expected[1];
        add("wear " + state, 10, () -> {
            hold(item(variant));
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        });
        until("worn " + state, 200, () -> {
            ItemStack head = mc.player.getItemBySlot(EquipmentSlot.HEAD);
            return head.is(item(variant)) && colourOf(head) == colour;
        });
        add("face the observer " + state, 1, () -> command("tp @s ~ ~ ~ facing entity " + OBSERVER + " eyes"));
        add("announce " + state, 10, () -> {
            write("masks-state-" + state, variant.textureName() + " " + colour);
            pass("state " + state + ": " + expected[2] + " -> wearing " + describe(mc.player.getItemBySlot(EquipmentSlot.HEAD)));
        });
        until("seen " + state, 1200, () -> seen("masks-seen-" + state));
    }

    private List<ArmorStand> sheetStands() {
        List<ArmorStand> found = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof ArmorStand stand && !stand.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                    && MaskVariant.byStack(stand.getItemBySlot(EquipmentSlot.HEAD)).isPresent()) {
                found.add(stand);
            }
        }
        return found;
    }

    /** Every stand holds exactly the catalogue stack its position says it should, as this client received it. */
    private void checkStands(List<ArmorStand> found) {
        int minX = found.stream().mapToInt(stand -> stand.getBlockX()).min().orElseThrow();
        int maxZ = found.stream().mapToInt(stand -> stand.getBlockZ()).max().orElseThrow();
        List<MaskVariant> styles = styles();
        for (ArmorStand stand : found) {
            int column = stand.getBlockX() - minX;
            int row = maxZ - stand.getBlockZ();
            require(column >= 0 && column < COLUMNS && row >= 0 && row < 16, "a stand is outside the grid at " + stand.blockPosition());
            ItemStack expected = cell(styles.get(row), column);
            ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
            require(head.is(expected.getItem()) && MaskColors.storedColor(head).equals(MaskColors.storedColor(expected)),
                    "the stand for " + styles.get(row) + "/" + columnName(column) + " shows " + describe(head));
        }
    }

    private void observer() {
        add("joined", 40, () -> write("masks-observer-joined", Integer.toString(now)));
        until("wearer joined", 2400, () -> seen("masks-wearer-joined"));
        for (int state = 1; state <= 4; state++) {
            final int n = state;
            until("state " + n, 6000, () -> seen("masks-state-" + n));
            until("received " + n, 600, () -> {
                Player wearer = wearerEntity();
                if (wearer == null) return false;
                String[] expected = read("masks-state-" + n).trim().split(" ");
                ItemStack head = wearer.getItemBySlot(EquipmentSlot.HEAD);
                return MaskVariant.byStack(head).map(MaskVariant::textureName).orElse("").equals(expected[0])
                        && colourOf(head) == Integer.parseInt(expected[1]);
            });
            add("world " + n, 10, () -> {
                mc.setScreen(null);
                mc.options.hideGui = true;
                Player wearer = wearerEntity();
                mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, wearer.getEyePosition());
            });
            settle("world " + n);
            add("world shot " + n, 0, () -> screenshot("observer-world-state-" + n + ".png"));
            add("close-up " + n, 1, () -> {
                mc.options.hideGui = false;
                mc.options.guiScale().set(1);
                mc.resizeDisplay();
                sheet = new Sheet();
                sheet.mode = Mode.SUBJECTS;
                sheet.subjects.add(wearerEntity());
                sheet.subjectLabels.add(WEARER + ": " + describe(wearerEntity().getItemBySlot(EquipmentSlot.HEAD)));
                mc.setScreen(sheet);
            });
            settle("close-up " + n);
            add("close-up shot " + n, 0, () -> {
                screenshot("observer-closeup-state-" + n + ".png");
                Object[] expected = wearerStates().get(n - 1);
                pass("observer saw state " + n + " (" + expected[2] + "): " + describe(wearerEntity().getItemBySlot(EquipmentSlot.HEAD)));
                mc.setScreen(null);
                write("masks-seen-" + n, Integer.toString(now));
            });
        }
        until("stands announced", 6000, () -> seen("masks-stands"));
        until("stands received", 1200, () -> sheetStands().size() == 16 * COLUMNS);
        add("stands checked", 10, () -> {
            List<ArmorStand> found = sheetStands();
            checkStands(found);
            int minX = found.stream().mapToInt(stand -> stand.getBlockX()).min().orElseThrow();
            int maxZ = found.stream().mapToInt(stand -> stand.getBlockZ()).max().orElseThrow();
            found.sort(Comparator.comparingInt((ArmorStand stand) -> maxZ - stand.getBlockZ()).thenComparingInt(stand -> stand.getBlockX() - minX));
            mc.options.guiScale().set(1);
            mc.resizeDisplay();
            sheet = new Sheet();
            sheet.mode = Mode.SUBJECTS;
            for (ArmorStand stand : found.subList(0, 8 * COLUMNS)) {
                sheet.subjects.add(stand);
                sheet.subjectLabels.add("");
            }
            mc.setScreen(sheet);
        });
        settle("stands 1");
        add("stands shot 1", 0, () -> {
            screenshot("observer-stands-1.png");
            List<ArmorStand> found = sheetStands();
            int minX = found.stream().mapToInt(stand -> stand.getBlockX()).min().orElseThrow();
            int maxZ = found.stream().mapToInt(stand -> stand.getBlockZ()).max().orElseThrow();
            found.sort(Comparator.comparingInt((ArmorStand stand) -> maxZ - stand.getBlockZ()).thenComparingInt(stand -> stand.getBlockX() - minX));
            sheet.subjects.clear();
            sheet.subjectLabels.clear();
            for (ArmorStand stand : found.subList(8 * COLUMNS, 16 * COLUMNS)) {
                sheet.subjects.add(stand);
                sheet.subjectLabels.add("");
            }
        });
        settle("stands 2");
        add("stands shot 2", 0, () -> {
            screenshot("observer-stands-2.png");
            pass("observer received all 272 catalogue stacks on armour stands with the right colours, and photographed them");
            write("masks-seen-stands", Integer.toString(now));
            mc.setScreen(null);
        });
        add("done", 40, finish);
    }

    private void resume() {
        until("joined", 600, () -> mc.player != null && mc.level != null);
        until("head restored", 600, () -> mc.player.getItemBySlot(EquipmentSlot.HEAD).is(item(MaskVariant.JACKAL)));
        add("head checked", 20, () -> {
            ItemStack head = mc.player.getItemBySlot(EquipmentSlot.HEAD);
            require(!MaskColors.hasColor(head), "the washed mask came back dyed: " + head.getComponentsPatch());
            pass("after the restart the wearer still wears the washed Jackal Mask, undyed");
        });
        until("stands restored", 1200, () -> sheetStands().size() == 16 * COLUMNS);
        add("stands checked", 20, () -> {
            checkStands(sheetStands());
            pass("after the restart all 272 armour stands came back with their exact colours");
            finish.run();
        });
    }

    private Player wearerEntity() {
        for (Player player : mc.level.players()) {
            if (player.getGameProfile().getName().equals(WEARER)) return player;
        }
        return null;
    }

    // ------------------------------------------------------------------ plumbing

    private static String xyz(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private void command(String command) {
        mc.player.connection.sendCommand(command);
    }

    private boolean seen(String name) {
        return Files.exists(sync.resolve(name));
    }

    private void write(String name, String value) {
        try {
            Files.writeString(sync.resolve(name + ".tmp"), value);
            Files.move(sync.resolve(name + ".tmp"), sync.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private String read(String name) {
        try {
            return Files.readString(sync.resolve(name));
        } catch (Exception failure) {
            throw new AssertionError("missing sync file " + name, failure);
        }
    }

    private void pass(String result) {
        passSink.accept(result);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
