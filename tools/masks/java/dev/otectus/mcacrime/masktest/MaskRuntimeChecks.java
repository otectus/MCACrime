package dev.otectus.mcacrime.masktest;

import dev.otectus.mcacrime.item.MaskItem;
import dev.otectus.mcacrime.item.MaskVariant;
import dev.otectus.mcacrime.client.render.mask.MaskArmorRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Mod("crime_mask_checks")
public final class MaskRuntimeChecks {
    private final Minecraft mc = Minecraft.getInstance();
    private final Path output = Path.of(System.getProperty("crime.maskTest.output"));
    private final long started = System.currentTimeMillis();
    private int ticks, stage, entered;
    private boolean creating, finished;
    private Gallery gallery;
    private CompletableFuture<Void> reload;

    public MaskRuntimeChecks() { MinecraftForge.EVENT_BUS.register(this); }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        ticks++;
        try {
            if (System.currentTimeMillis() - started > 240_000) throw new AssertionError("Client fixture timed out");
            if (!creating && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
                creating = true;
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(2);
                mc.options.guiScale().set(1);
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                mc.resizeDisplay();
                mc.createWorldOpenFlows().createFreshLevel("mask-checks", new LevelSettings("Mask checks",
                        GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(42L, false, false), registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                                .getOrThrow(WorldPresets.FLAT).createWorldDimensions());
            }
            if (mc.level == null || mc.player == null || ticks - entered < 40) return;
            if (stage == 0) {
                gallery = new Gallery();
                mc.setScreen(gallery);
                next();
            } else if (stage == 1) {
                shot("01-armor-stands.png");
                gallery.mode = 1;
                gallery.dye();
                next();
            } else if (stage == 2) {
                shot("02-dyed-player.png");
                gallery.mode = 2;
                next();
            } else if (stage == 3) {
                shot("03-mca-villagers.png");
                reload = mc.reloadResourcePacks();
                next();
            } else if (stage == 4 && reload.isDone()) {
                reload.join();
                gallery.mode = 0;
                next();
            } else if (stage == 5) {
                shot("04-after-reload.png");
                next();
            } else if (stage == 6) {
                for (String name : List.of("01-armor-stands.png", "02-dyed-player.png", "03-mca-villagers.png", "04-after-reload.png")) {
                    if (!Files.isRegularFile(output.resolve("screenshots/" + name))) throw new AssertionError("Missing " + name);
                }
                Files.writeString(output.resolve("PASS.txt"), "PASS: all 16 masks rendered as items and armor on armor stands, dyed player, MCA villagers, and after resource reload.\n");
                finished = true;
                mc.stop();
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private void next() { stage++; entered = ticks; }
    private void shot(String name) {
        Screenshot.grab(output.toFile(), name, mc.getMainRenderTarget(), message -> System.out.println(message.getString()));
    }
    private void fail(Throwable failure) {
        if (finished) return;
        finished = true;
        failure.printStackTrace();
        try { Files.writeString(output.resolve("FAIL.txt"), failure.toString()); } catch (Exception ignored) { }
        mc.execute(mc::stop);
    }

    private final class Gallery extends Screen {
        private final List<ItemStack> masks = new ArrayList<>();
        private final List<LivingEntity> stands = new ArrayList<>();
        private final List<LivingEntity> villagers = new ArrayList<>();
        private int mode;

        Gallery() {
            super(Component.literal("Mask rendering checks"));
            for (MaskVariant variant : MaskVariant.values()) {
                ItemStack stack = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(variant.styleId())));
                if (!(stack.getItem() instanceof MaskItem)) throw new AssertionError("Missing mask " + variant);
                if (mc.getItemRenderer().getModel(stack, mc.level, mc.player, 0) == mc.getModelManager().getMissingModel())
                    throw new AssertionError("Missing item model " + variant);
                var renderer = new MaskArmorRenderer();
                renderer.getGeoModel().getBakedModel(renderer.getGeoModel().getModelResource((MaskItem) stack.getItem()));
                masks.add(stack);
                ArmorStand stand = new ArmorStand(mc.level, 0, 0, 0);
                stand.setItemSlot(EquipmentSlot.HEAD, stack);
                stands.add(stand);
                var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("mca", "male_villager"));
                if (type == null) throw new AssertionError("MCA villager unavailable");
                LivingEntity villager = (LivingEntity) type.create(mc.level);
                try {
                    villager.getClass().getMethod("initializeSkin", boolean.class).invoke(villager, true);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Could not initialize fixture villager skin", failure);
                }
                villager.setItemSlot(EquipmentSlot.HEAD, stack);
                villagers.add(villager);
            }
        }

        void dye() {
            for (ItemStack stack : masks) ((net.minecraft.world.item.DyeableLeatherItem) stack.getItem()).setColor(stack, 0x4ABDED);
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            try {
                graphics.fill(0, 0, width, height, 0xFF303641);
                graphics.drawString(font, "MCA: Crime | " + (mode == 0 ? "armor stands" : mode == 1 ? "dyed player" : "MCA villagers"), 12, 8, 0xFFFFFF);
                for (int i = 0; i < masks.size(); i++) {
                    int cellWidth = width / 4, cellHeight = (height - 28) / 4;
                    int x = (i % 4) * cellWidth, y = 28 + (i / 4) * cellHeight;
                    graphics.fill(x + 3, y + 3, x + cellWidth - 3, y + cellHeight - 3, 0xFF414957);
                    graphics.drawString(font, masks.get(i).getHoverName(), x + 10, y + 9, 0xFFFFFF);
                    graphics.renderItem(masks.get(i), x + 12, y + 30);
                    LivingEntity entity = mode == 1 ? mc.player : (mode == 2 ? villagers : stands).get(i);
                    entity.setInvisible(false);
                    entity.setItemSlot(EquipmentSlot.HEAD, masks.get(i));
                    graphics.enableScissor(x + 35, y + 25, x + cellWidth - 5, y + cellHeight - 5);
                    InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, x + cellWidth / 2,
                            y + 320, 145, -25, 0, entity);
                    graphics.disableScissor();
                }
            } catch (Throwable failure) { fail(failure); }
        }
    }
}
