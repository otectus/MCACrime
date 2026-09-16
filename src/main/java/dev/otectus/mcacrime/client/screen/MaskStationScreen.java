package dev.otectus.mcacrime.client.screen;

import dev.otectus.mcacrime.mask.Masks;
import dev.otectus.mcacrime.mask.MaskCustomization;
import dev.otectus.mcacrime.mask.MaskRestyleRejection;
import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.menu.MaskStationLayout;
import dev.otectus.mcacrime.menu.MaskStationMenu;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.SelectMaskRecipeC2SPacket;
import dev.otectus.mcacrime.recipe.CrimeRecipes;
import dev.otectus.mcacrime.recipe.MaskMakingRecipe;
import dev.otectus.mcacrime.recipe.MaskOperation;
import dev.otectus.mcacrime.recipe.MaskStationCatalog;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Mask Station screen (0.7.2 §6.3, §6.4).
 *
 * <p>The gallery includes unaffordable styles so their ingredients can be discovered before filling
 * the inputs. Only recipes passing the server's {@code matches} check can be requested. The enlarged
 * preview is always the server's result stack, and taking the output is the only crafting action.
 *
 * <p>The catalogue is rebuilt when the inputs or the recipe generation change, and at
 * no other time — {@link #containerTick()} compares three stacks and an int, which is the cheapest
 * honest way to notice, and is a tick rather than a frame.
 *
 * <p>Chrome comes from {@link CrimeSprites} rather than a bespoke background PNG: the station is
 * another panel in the same mod, and a second sheet would be a second thing to keep in step.
 *
 * <p>1.21.1 differences from the 1.20.1 original, all mechanical: a recipe is reached through its
 * {@link RecipeHolder}, which is where the id now lives; {@code AbstractContainerScreen#render} calls
 * {@code renderBackground} itself, so this one only adds the item tooltips on top; and
 * {@code mouseScrolled} carries both scroll axes.
 */
public class MaskStationScreen extends AbstractContainerScreen<MaskStationMenu> {

    private static final int GRID_X = 103;
    private static final int GRID_Y = 49;
    private static final int GRID_COLUMNS = 4;
    private static final int GRID_ROWS = 2;
    private static final int CELL = 20;
    private static final ResourceLocation BASE_GHOST =
            ResourceLocation.withDefaultNamespace("textures/item/leather.png");
    private static final ResourceLocation BINDING_GHOST =
            ResourceLocation.withDefaultNamespace("textures/item/string.png");
    private static final ResourceLocation DYE_GHOST =
            ResourceLocation.withDefaultNamespace("textures/item/white_dye.png");
    private static final ResourceLocation PREVIEW_GHOST =
            ResourceLocation.fromNamespaceAndPath("mcacrime", "textures/item/clay_mask.png");

    /** What the grid was last built from, so it is rebuilt on change rather than per frame. */
    private final SimpleContainer lastInputs = new SimpleContainer(MaskMakingRecipe.INPUT_SLOTS);
    private int lastGeneration = -1;

    private final List<RecipeHolder<MaskMakingRecipe>> entries = new ArrayList<>();
    private MaskStyleGrid grid;

    public MaskStationScreen(MaskStationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = MaskStationLayout.WIDTH;
        this.imageHeight = MaskStationLayout.HEIGHT;
        this.titleLabelX = 31;
        this.titleLabelY = 12;
        this.inventoryLabelX = MaskStationLayout.INVENTORY_X;
        this.inventoryLabelY = 138;
    }

    @Override
    protected void init() {
        super.init();
        grid = new MaskStyleGrid(leftPos + GRID_X, topPos + GRID_Y,
                GRID_COLUMNS * CELL + CrimeSprites.SCROLLBAR_W + 2, GRID_ROWS * CELL);
        addRenderableWidget(grid);
        lastGeneration = -1;
        rebuild();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (menu.recipeGeneration() != lastGeneration || inputsChanged()) {
            rebuild();
        }
    }

    private boolean inputsChanged() {
        for (int slot = 0; slot < MaskMakingRecipe.INPUT_SLOTS; slot++) {
            if (!ItemStack.matches(lastInputs.getItem(slot), menu.inputsView().getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    /** Recomputes the catalogue this client can see. Never called from a render path. */
    private void rebuild() {
        for (int slot = 0; slot < MaskMakingRecipe.INPUT_SLOTS; slot++) {
            lastInputs.setItem(slot, menu.inputsView().getItem(slot).copy());
        }
        lastGeneration = menu.recipeGeneration();
        entries.clear();
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        List<MaskStationCatalog.Entry> order = new ArrayList<>();
        List<RecipeHolder<MaskMakingRecipe>> candidates = new ArrayList<>();
        ItemStack base = menu.inputsView().getItem(MaskMakingRecipe.MATERIAL);
        MaskOperation operation = Masks.isMask(base) ? MaskOperation.RESTYLE : MaskOperation.CRAFT;
        for (RecipeHolder<MaskMakingRecipe> holder
                : minecraft.level.getRecipeManager().getAllRecipesFor(CrimeRecipes.MASK_MAKING.get())) {
            MaskMakingRecipe recipe = holder.value();
            if (recipe.operation() == operation && (base.isEmpty() || recipe.material().test(base))) {
                candidates.add(holder);
                order.add(recipe.catalogEntry(holder.id()));
            }
        }
        // Exactly the server's order, from exactly the server's comparator.
        for (MaskStationCatalog.Entry entry : MaskStationCatalog.sorted(order)) {
            candidates.stream().filter(holder -> holder.id().equals(entry.id())).findFirst()
                    .ifPresent(entries::add);
        }
        if (grid != null) {
            grid.onCatalogChanged(entries.size());
        }
    }

    private void requestSelection(RecipeHolder<MaskMakingRecipe> holder) {
        if (!available(holder)) {
            return;
        }
        CrimeNetwork.sendToServer(new SelectMaskRecipeC2SPacket(
                menu.containerId, holder.id(), menu.recipeGeneration()));
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.4F, 1.0F);
        }
    }

    private boolean available(RecipeHolder<MaskMakingRecipe> holder) {
        return minecraft != null && minecraft.level != null
                && holder.value().matches(menu.recipeInput(), minecraft.level)
                && holder.value().restyleRejection(menu.recipeInput()).allowed();
    }

    private Component selectionHint(RecipeHolder<MaskMakingRecipe> holder) {
        MaskMakingRecipe recipe = holder.value();
        if (recipe.operation() == MaskOperation.RESTYLE) {
            MaskRestyleRejection conversion = recipe.restyleRejection(menu.recipeInput());
            if (!conversion.allowed()) return Component.translatable(conversion.labelKey());
            ItemStack dye = menu.inputsView().getItem(MaskMakingRecipe.DYE);
            MaskRestyleRejection rejection = MaskCustomization.wouldChange(
                    menu.inputsView().getItem(MaskMakingRecipe.MATERIAL), recipe.getResultItem(registryAccess()),
                    recipe.allowDye() && dye.getItem() instanceof DyeItem colour ? colour : null);
            if (!rejection.allowed()) return Component.translatable(rejection.labelKey());
        }
        return Component.translatable(available(holder)
                ? "mcacrime.mask_station.select_hint" : "mcacrime.mask_station.missing_hint");
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        // Container screens handle item dragging themselves instead of forwarding it to widgets.
        if (grid != null && grid.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (grid != null && grid.mouseReleased(mouseX, mouseY, button)) {
            setDragging(false);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 1.21.1's AbstractContainerScreen.render draws the background itself, so unlike the 1.20.1
        // original this does not call renderBackground; drawing it again would paint over the widgets.
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        for (int index = 0; index < MaskMakingRecipe.INPUT_SLOTS; index++) {
            var slot = menu.slots.get(index);
            if (!slot.hasItem() && isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
                Component hint = switch (index) {
                    case MaskMakingRecipe.MATERIAL -> Component.translatable("mcacrime.mask_station.hint.base");
                    case MaskMakingRecipe.BINDING -> Component.translatable("mcacrime.mask_station.hint.binding");
                    default -> Component.translatable("mcacrime.mask_station.hint.dye");
                };
                setTooltipForNextRenderPass(font.split(hint, 220));
            }
        }
        if (isHovering(201, 42, 46, 64, mouseX, mouseY) && !menu.previewStack().isEmpty()) {
            List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>(
                    font.split(menu.previewStack().getHoverName(), 220));
            RecipeHolder<MaskMakingRecipe> selected = selectedRecipe();
            if (selected != null) lines.addAll(font.split(cost(selected.value()), 220));
            lines.addAll(font.split(Component.translatable("mcacrime.mask_station.take_output"), 220));
            setTooltipForNextRenderPass(lines);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        CrimeSprites.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        graphics.renderItem(new ItemStack(CrimeBlocks.MASK_STATION.get()), leftPos + 10, topPos + 8);
        CrimeSprites.rule(graphics, leftPos + 9, topPos + 27, imageWidth - 18, 0xFF787878);
        CrimeSprites.panel(graphics, leftPos + 8, topPos + 30, 86, 78);
        CrimeSprites.panel(graphics, leftPos + 97, topPos + 30, 97, 78);
        CrimeSprites.panel(graphics, leftPos + 197, topPos + 30, 51, 78);
        CrimeSprites.well(graphics, leftPos + 205, topPos + 44, 38, 38);
        for (var slot : menu.slots) {
            slot(graphics, leftPos + slot.x, topPos + slot.y);
        }
        ghost(graphics, MaskStationLayout.MATERIAL_X, BASE_GHOST, MaskMakingRecipe.MATERIAL);
        ghost(graphics, MaskStationLayout.BINDING_X, BINDING_GHOST, MaskMakingRecipe.BINDING);
        ghost(graphics, MaskStationLayout.DYE_X, DYE_GHOST, MaskMakingRecipe.DYE);
        // A separate, noninteractive 2x preview. The slot below remains the actual output.
        ItemStack preview = menu.previewStack();
        if (!preview.isEmpty()) {
            graphics.pose().pushPose();
            graphics.pose().translate(leftPos + 208, topPos + 47, 0.0D);
            graphics.pose().scale(2.0F, 2.0F, 1.0F);
            graphics.renderItem(preview, 0, 0);
            graphics.pose().popPose();
        } else {
            graphics.blit(PREVIEW_GHOST, leftPos + 208, topPos + 47, 32, 32, 0, 0, 16, 16, 16, 16);
            graphics.fill(leftPos + 208, topPos + 47, leftPos + 240, topPos + 79, 0xB08B8B8B);
        }
        int arrow = preview.isEmpty() ? 0xFF777777 : 0xFF365F52;
        graphics.fill(leftPos + 223, topPos + 82, leftPos + 225, topPos + 84, arrow);
        graphics.fill(leftPos + 221, topPos + 84, leftPos + 227, topPos + 85, arrow);
        graphics.fill(leftPos + 222, topPos + 85, leftPos + 226, topPos + 86, arrow);
        CrimeSprites.rule(graphics, leftPos + 10, topPos + 134, imageWidth - 20, 0xFF999999);
    }

    private void ghost(GuiGraphics graphics, int x, ResourceLocation texture, int slot) {
        if (!menu.inputsView().getItem(slot).isEmpty()) return;
        int y = topPos + MaskStationLayout.INPUT_Y;
        graphics.blit(texture, leftPos + x, y, 0, 0, 16, 16, 16, 16);
        graphics.fill(leftPos + x, y, leftPos + x + 16, y + 16, 0xB08B8B8B);
    }

    private void slot(GuiGraphics graphics, int x, int y) {
        CrimeSprites.well(graphics, x - 1, y - 1, 18, 18);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        drawFitted(graphics, title, titleLabelX, titleLabelY, 157, PanelColours.TEXT, mouseX, mouseY);
        Component mode = Component.translatable(restyleMode()
                ? "mcacrime.mask_station.mode.restyle" : "mcacrime.mask_station.mode.make");
        CrimeSprites.row(graphics, 197, 8, 51, 17, CrimeSprites.RowState.IDLE);
        drawFitted(graphics, mode, 202, 12, 41, PanelColours.TEXT, mouseX, mouseY);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY,
                PanelColours.TEXT, false);
        drawFitted(graphics, Component.translatable("mcacrime.mask_station.materials"),
                14, 36, 74, PanelColours.TEXT, mouseX, mouseY);
        drawFitted(graphics, Component.translatable("mcacrime.mask_station.styles"),
                103, 36, 84, PanelColours.TEXT, mouseX, mouseY);
        drawFitted(graphics, Component.translatable("mcacrime.mask_station.output"),
                203, 36, 39, PanelColours.TEXT, mouseX, mouseY);
        drawFitted(graphics, Component.translatable("mcacrime.mask_station.base"),
                13, 76, 24, PanelColours.TEXT_MUTED, mouseX, mouseY);
        drawFitted(graphics, Component.translatable("mcacrime.mask_station.binding"),
                39, 76, 24, PanelColours.TEXT_MUTED, mouseX, mouseY);
        drawFitted(graphics, Component.translatable("mcacrime.mask_station.dye"),
                66, 76, 24, PanelColours.TEXT_MUTED, mouseX, mouseY);
        drawFitted(graphics, Component.translatable("mcacrime.mask_station.optional_dye"),
                14, 95, 74, PanelColours.TEXT_MUTED, mouseX, mouseY);
        drawFitted(graphics, Component.translatable("mcacrime.mask_station.style_count", entries.size()),
                103, 95, 84, PanelColours.TEXT_MUTED, mouseX, mouseY);

        RecipeHolder<MaskMakingRecipe> selected = selectedRecipe();
        RecipeHolder<MaskMakingRecipe> inspected = grid != null && grid.isFocused() && !entries.isEmpty()
                ? entries.get(Math.min(grid.focused, entries.size() - 1)) : selected;
        boolean ready = !menu.previewStack().isEmpty();
        Component primary = ready ? menu.previewStack().getHoverName()
                : inspected != null ? inspected.value().getResultItem(registryAccess()).getHoverName()
                : Component.translatable(entries.isEmpty() ? "mcacrime.mask_station.no_styles"
                : entries.stream().anyMatch(this::available) ? "mcacrime.mask_station.choose"
                : "mcacrime.mask_station.empty");
        Component secondary = ready ? Component.translatable("mcacrime.mask_station.take_output")
                : inspected != null ? cost(inspected.value())
                : Component.translatable("mcacrime.mask_station.browse_hint");
        drawFitted(graphics, primary, 12, 113, imageWidth - 24,
                ready ? 0x365F52 : PanelColours.TEXT, mouseX, mouseY);
        drawFitted(graphics, secondary, 12, 124, imageWidth - 24, PanelColours.TEXT_MUTED, mouseX, mouseY);
    }

    /** Keep long names/translations inside their region; hovering reveals the complete text. */
    private void drawFitted(GuiGraphics graphics, Component text, int x, int y, int width,
                            int colour, int mouseX, int mouseY) {
        if (font.width(text) <= width) {
            graphics.drawString(font, text, x, y, colour, false);
            return;
        }
        String shortened = font.plainSubstrByWidth(text.getString(), width - font.width("...")) + "...";
        graphics.drawString(font, shortened, x, y, colour, false);
        if (isHovering(x, y, width, font.lineHeight, mouseX, mouseY)) {
            setTooltipForNextRenderPass(font.split(text, 220));
        }
    }

    private net.minecraft.core.RegistryAccess registryAccess() {
        return minecraft != null && minecraft.level != null
                ? minecraft.level.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
    }

    /** "Needs 4 Clay Balls and 2 String." — the exact quantities the server will debit. */
    private Component cost(MaskMakingRecipe recipe) {
        Component material = ingredient(recipe.material(), recipe.materialCount(),
                menu.inputsView().getItem(MaskMakingRecipe.MATERIAL));
        Component binding = ingredient(recipe.binding(), recipe.bindingCount(),
                menu.inputsView().getItem(MaskMakingRecipe.BINDING));
        Component line = Component.translatable("mcacrime.mask_station.cost", material, binding);
        if (recipe.allowDye() && !menu.inputsView().getItem(MaskMakingRecipe.DYE).isEmpty()) {
            return Component.translatable("mcacrime.mask_station.cost.dyed", line,
                    menu.inputsView().getItem(MaskMakingRecipe.DYE).getHoverName());
        }
        return line;
    }

    private Component ingredient(Ingredient ingredient, int count, ItemStack input) {
        ItemStack[] items = ingredient.getItems();
        Component name = !input.isEmpty() && ingredient.test(input) ? input.getHoverName()
                : items.length == 0 ? Component.translatable("mcacrime.mask_station.any_material")
                : items[0].getHoverName();
        return Component.translatable("mcacrime.mask_station.quantity", count, name);
    }

    private boolean restyleMode() {
        RecipeHolder<MaskMakingRecipe> selected = selectedRecipe();
        if (selected != null) {
            return selected.value().operation() == MaskOperation.RESTYLE;
        }
        // §6.4: a mask in the material slot puts the station into Restyle before anything is chosen.
        return Masks.isMask(menu.inputsView().getItem(MaskMakingRecipe.MATERIAL));
    }

    private RecipeHolder<MaskMakingRecipe> selectedRecipe() {
        ResourceLocation id = menu.selected();
        if (id == null) {
            return null;
        }
        return entries.stream().filter(holder -> holder.id().equals(id)).findFirst().orElse(null);
    }

    /** A family name for narration and tooltips, from the recipe group, without inventing a lang key. */
    private static String family(MaskMakingRecipe recipe) {
        String group = recipe.getGroup();
        if (group.isEmpty()) {
            return "";
        }
        int colon = group.indexOf(':');
        return (colon < 0 ? group : group.substring(colon + 1)).replace('_', ' ').trim()
                .toLowerCase(Locale.ROOT);
    }

    /**
     * The style grid: a real focusable widget, so Tab reaches it, arrow keys move inside it, and the
     * narrator is told what is under the cursor (§6.4).
     */
    private final class MaskStyleGrid extends net.minecraft.client.gui.components.AbstractWidget {

        private int focused;
        private int scrollRow;
        private boolean draggingScrollbar;

        private MaskStyleGrid(int x, int y, int width, int height) {
            super(x, y, width, height, Component.translatable("mcacrime.mask_station.styles"));
        }

        private void onCatalogChanged(int size) {
            focused = Math.max(0, Math.min(focused, size - 1));
            scrollRow = Math.max(0, Math.min(scrollRow, Math.max(0, rows(size) - GRID_ROWS)));
        }

        private int rows(int size) {
            return (size + GRID_COLUMNS - 1) / GRID_COLUMNS;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            CrimeSprites.well(graphics, getX() - 1, getY() - 1, GRID_COLUMNS * CELL + 2, GRID_ROWS * CELL + 2);
            ResourceLocation selectedId = menu.selected();
            for (int cell = 0; cell < GRID_COLUMNS * GRID_ROWS; cell++) {
                int index = (scrollRow * GRID_COLUMNS) + cell;
                int x = getX() + (cell % GRID_COLUMNS) * CELL;
                int y = getY() + (cell / GRID_COLUMNS) * CELL;
                if (index >= entries.size()) {
                    CrimeSprites.row(graphics, x, y, CELL, CELL, CrimeSprites.RowState.DISABLED);
                    continue;
                }
                RecipeHolder<MaskMakingRecipe> holder = entries.get(index);
                boolean isSelected = holder.id().equals(selectedId);
                boolean canSelect = available(holder);
                boolean hovered = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
                CrimeSprites.RowState state = isSelected ? CrimeSprites.RowState.FOCUS
                        : isFocused() && index == focused ? CrimeSprites.RowState.HOVER
                        : hovered ? CrimeSprites.RowState.HOVER
                        : canSelect ? CrimeSprites.RowState.IDLE : CrimeSprites.RowState.DISABLED;
                CrimeSprites.row(graphics, x, y, CELL, CELL, state);
                if (isSelected) {
                    // A border as well as a state, so selection survives a colour-blind palette (§6.4).
                    CrimeSprites.rule(graphics, x, y, CELL, 0xFF404040);
                    CrimeSprites.rule(graphics, x, y + CELL - 1, CELL, 0xFF404040);
                }
                graphics.renderItem(holder.value().getResultItem(registryAccess()), x + 2, y + 2);
                if (!canSelect) {
                    // A corner notch makes missing ingredients visible without hiding the artwork.
                    graphics.fill(x + CELL - 5, y + CELL - 5, x + CELL - 2, y + CELL - 2, 0xFF555555);
                }
                if (hovered) {
                    // §6.4: hover reveals the material and the cost, not only the name.
                    List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>(
                            font.split(holder.value().getResultItem(registryAccess()).getHoverName(), 200));
                    lines.addAll(font.split(cost(holder.value()), 200));
                    lines.addAll(font.split(selectionHint(holder), 200));
                    MaskStationScreen.this.setTooltipForNextRenderPass(lines);
                }
            }
            int trackX = getX() + GRID_COLUMNS * CELL + 2;
            CrimeSprites.scrollTrack(graphics, trackX, getY(), GRID_ROWS * CELL);
            if (entries.size() > GRID_COLUMNS * GRID_ROWS) {
                int total = Math.max(1, rows(entries.size()) - GRID_ROWS);
                int travel = GRID_ROWS * CELL - 10;
                CrimeSprites.scrollThumb(graphics, trackX, getY() + scrollRow * travel / total, 10);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!active || !visible || button != 0 || !isMouseOver(mouseX, mouseY)) {
                return false;
            }
            if (mouseX >= getX() + GRID_COLUMNS * CELL + 2
                    && entries.size() > GRID_COLUMNS * GRID_ROWS) {
                draggingScrollbar = true;
                scrollTo(mouseY);
                return true;
            }
            int column = (int) ((mouseX - getX()) / CELL);
            int row = (int) ((mouseY - getY()) / CELL);
            if (column < 0 || column >= GRID_COLUMNS || row < 0 || row >= GRID_ROWS) {
                return false;
            }
            int index = (scrollRow + row) * GRID_COLUMNS + column;
            if (index >= entries.size()) {
                return false;
            }
            focused = index;
            setFocused(true);
            requestSelection(entries.get(index));
            return true;
        }

        private void scrollTo(double mouseY) {
            int maximum = Math.max(0, rows(entries.size()) - GRID_ROWS);
            double fraction = (mouseY - getY() - 5) / (GRID_ROWS * CELL - 10);
            scrollRow = Math.max(0, Math.min(maximum, (int) Math.round(fraction * maximum)));
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (button != 0 || !draggingScrollbar) return false;
            scrollTo(mouseY);
            return true;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (button != 0 || !draggingScrollbar) return false;
            draggingScrollbar = false;
            return true;
        }

        /**
         * Scrolls the grid and stops there: the hotbar is not this widget's business.
         *
         * <p>1.21.1 passes both axes; only the vertical one moves a vertical list, and a horizontal
         * wheel or trackpad swipe is left alone rather than being read as a row change.
         */
        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            if (!active || !visible || !isMouseOver(mouseX, mouseY)
                    || entries.size() <= GRID_COLUMNS * GRID_ROWS || scrollY == 0) {
                return false;
            }
            int maximum = Math.max(0, rows(entries.size()) - GRID_ROWS);
            scrollRow = Math.max(0, Math.min(maximum, scrollRow - (int) Math.signum(scrollY)));
            return true;
        }

        @Override
        public boolean keyPressed(int key, int scan, int modifiers) {
            if (!active || !visible || !isFocused() || entries.isEmpty()) {
                return false;
            }
            int moved = switch (key) {
                case 263 -> focused - 1;               // left
                case 262 -> focused + 1;               // right
                case 265 -> focused - GRID_COLUMNS;    // up
                case 264 -> focused + GRID_COLUMNS;    // down
                case 268 -> 0;                        // home
                case 269 -> entries.size() - 1;        // end
                case 266 -> focused - GRID_COLUMNS * GRID_ROWS; // page up
                case 267 -> focused + GRID_COLUMNS * GRID_ROWS; // page down
                default -> Integer.MIN_VALUE;
            };
            if (moved != Integer.MIN_VALUE) {
                focused = Math.max(0, Math.min(entries.size() - 1, moved));
                scrollRow = Math.max(Math.min(scrollRow, focused / GRID_COLUMNS),
                        focused / GRID_COLUMNS - (GRID_ROWS - 1));
                return true;
            }
            if (key == 257 || key == 335 || key == 32) { // enter, numpad enter, space
                requestSelection(entries.get(focused));
                return true;
            }
            return false;
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
            if (entries.isEmpty()) {
                output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
                        Component.translatable("mcacrime.mask_station.empty"));
                return;
            }
            RecipeHolder<MaskMakingRecipe> holder = entries.get(Math.min(focused, entries.size() - 1));
            MaskMakingRecipe recipe = holder.value();
            output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
                    Component.translatable("mcacrime.mask_station.narration.style",
                            recipe.getResultItem(registryAccess()).getHoverName(), family(recipe)));
            output.add(net.minecraft.client.gui.narration.NarratedElementType.HINT, cost(recipe));
            if (holder.id().equals(menu.selected()) && !menu.previewStack().isEmpty()) {
                output.add(net.minecraft.client.gui.narration.NarratedElementType.USAGE,
                        Component.translatable("mcacrime.mask_station.narration.craftable"));
            } else {
                output.add(net.minecraft.client.gui.narration.NarratedElementType.USAGE,
                        selectionHint(holder));
            }
        }
    }
}
