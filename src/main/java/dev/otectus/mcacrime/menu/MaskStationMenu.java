package dev.otectus.mcacrime.menu;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.MaskSelectionS2CPacket;
import dev.otectus.mcacrime.recipe.CrimeRecipes;
import dev.otectus.mcacrime.recipe.MaskCraftPlan;
import dev.otectus.mcacrime.recipe.MaskCraftRejection;
import dev.otectus.mcacrime.recipe.MaskMakingRecipe;
import dev.otectus.mcacrime.recipe.MaskOperation;
import dev.otectus.mcacrime.recipe.MaskRecipeGeneration;
import dev.otectus.mcacrime.recipe.MaskStationCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * One player's private working session at one Mask Station (0.7.2 §6.2, §8.1).
 *
 * <p>The block stores nothing. Everything a craft needs — the three input stacks, the catalogue of
 * styles those stacks support, the chosen style and the preview — lives in this menu instance, which
 * exists only while one viewer has the screen open. Two players at the same station therefore have two
 * of these and cannot see, take, or race each other's materials, and the station itself never has to
 * arbitrate between them.
 *
 * <p>The server owns all of it. The client is told the generation (a data slot) and the selected id (a
 * small packet), and computes the same grid from the recipes vanilla already synced to it. It never
 * supplies the item, the count, the NBT or the entitlement — a selection packet names an id and the
 * session it was made in, and the server re-decides everything else (invariant 6).
 *
 * <p>Selecting is not crafting (invariant 5). Nothing in this class consumes anything until the result
 * slot commits, and the preview is never returned to anybody: on close, death, disconnect, a broken
 * station or a config that turned crafting off, only the three real input stacks come back.
 */
public class MaskStationMenu extends AbstractContainerMenu {

    public static final int MATERIAL_SLOT = 0;
    public static final int BINDING_SLOT = 1;
    public static final int DYE_SLOT = 2;
    public static final int RESULT_SLOT = 3;

    private static final int INVENTORY_START = 4;
    private static final int HOTBAR_START = 31;
    private static final int HOTBAR_END = 40;

    private final ContainerLevelAccess access;
    private final Player owner;
    private final BlockPos stationPos;

    /** The three real stacks. Returned once, to this viewer, when the menu closes. */
    private final SimpleContainer inputs = new SimpleContainer(MaskMakingRecipe.INPUT_SLOTS) {
        @Override
        public void setChanged() {
            super.setChanged();
            MaskStationMenu.this.slotsChanged(this);
        }
    };

    /** The preview. Never an entitlement, never returned, never dropped. */
    private final SimpleContainer preview = new SimpleContainer(1);

    /** Wide enough for the recipe generation, which is the only value narrow enough to travel here. */
    private final DataSlot generation = DataSlot.standalone();

    private MaskStationCatalog catalog = MaskStationCatalog.EMPTY;
    @Nullable
    private ResourceLocation selected;
    private int selectionGeneration;
    @Nullable
    private MaskMakingRecipe selectedRecipe;
    private MaskCraftPlan plan = MaskCraftPlan.refused(MaskCraftRejection.NO_SELECTION);
    @Nullable
    private ResourceLocation syncedSelection;
    private int syncedGeneration;

    public MaskStationMenu(int containerId, Inventory inventory, ContainerLevelAccess access, BlockPos pos) {
        super(CrimeMenus.MASK_STATION.get(), containerId);
        this.access = access;
        this.owner = inventory.player;
        this.stationPos = pos;

        addSlot(new Slot(inputs, MaskMakingRecipe.MATERIAL,
                MaskStationLayout.MATERIAL_X, MaskStationLayout.INPUT_Y));
        addSlot(new Slot(inputs, MaskMakingRecipe.BINDING,
                MaskStationLayout.BINDING_X, MaskStationLayout.INPUT_Y));
        addSlot(new Slot(inputs, MaskMakingRecipe.DYE,
                MaskStationLayout.DYE_X, MaskStationLayout.INPUT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                // §7.2: a non-dye is rejected here rather than accepted and quietly ignored.
                return stack.getItem() instanceof DyeItem;
            }
        });
        addSlot(new MaskStationResultSlot(this, preview, 0,
                MaskStationLayout.RESULT_X, MaskStationLayout.RESULT_Y));

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        MaskStationLayout.INVENTORY_X + column * 18, MaskStationLayout.INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column,
                    MaskStationLayout.INVENTORY_X + column * 18, MaskStationLayout.HOTBAR_Y));
        }
        addDataSlot(generation);
        generation.set(MaskRecipeGeneration.current());
        refresh();
    }

    /** Where the server says this station is. Presentation only; never read back from a client. */
    public BlockPos stationPos() {
        return stationPos;
    }

    public MaskStationCatalog catalog() {
        return catalog;
    }

    /** The generation the client should quote back in a selection request. */
    public int recipeGeneration() {
        return generation.get();
    }

    @Nullable
    public ResourceLocation selected() {
        return selected;
    }

    public MaskCraftPlan plan() {
        return plan;
    }

    /** Why the output cannot be taken right now, for the screen to explain. */
    public MaskCraftRejection rejection() {
        return plan.rejection();
    }

    /**
     * The three input stacks.
     *
     * <p>Exposed so the client screen can build the same catalogue from the recipes vanilla already
     * synced to it, rather than the server sending a list it can derive. Read only by convention: the
     * client's copy is a mirror, and writing to it would be corrected on the next slot update anyway.
     */
    public Container inputsView() {
        return inputs;
    }

    public ItemStack previewStack() {
        return preview.getItem(0);
    }

    /** Sets the client's copy of the selection, having been told it by the server. Client only. */
    public void acceptServerSelection(@Nullable ResourceLocation id, int atGeneration) {
        this.selected = id;
        this.selectionGeneration = atGeneration;
    }

    // ------------------------------------------------------------------ selection

    /**
     * Applies one validated selection request.
     *
     * @return what the server decided, so the caller can log a refusal without logging a success
     */
    public MaskSelectionOutcome select(ServerPlayer player, int requestedContainerId,
                                       @Nullable ResourceLocation requested, int requestedGeneration) {
        refresh();
        MaskSelectionOutcome outcome = MaskSelectionPolicy.evaluate(containerId, requestedContainerId,
                catalog, requested, requestedGeneration);
        if (!outcome.accepted()) {
            // A stale client still gets the truth about what is selected, rather than silence.
            syncSelection(player, true);
            return outcome;
        }
        this.selected = requested;
        this.selectionGeneration = catalog.generation();
        refresh();
        return outcome;
    }

    // ------------------------------------------------------------------ recompute

    /**
     * Rebuilds the catalogue, the selection, the preview and the plan from the current inputs.
     *
     * <p>Called when the inputs change, when a selection arrives, when a craft commits and when the
     * recipe generation moves — never per frame, and never on the client, which computes its own grid
     * from the recipes vanilla has already synced to it.
     */
    private void refresh() {
        Level level = owner.level();
        if (level.isClientSide) {
            return;
        }
        RecipeManager manager = level.getRecipeManager();
        int current = MaskRecipeGeneration.observe(manager);
        generation.set(current);

        List<MaskStationCatalog.Entry> entries = new ArrayList<>();
        List<MaskMakingRecipe> matching = new ArrayList<>();
        if (craftingEnabled()) {
            for (MaskMakingRecipe recipe : manager.getAllRecipesFor(CrimeRecipes.MASK_MAKING.get())) {
                if (!operationEnabled(recipe) || !recipe.matches(inputs, level)) {
                    continue;
                }
                matching.add(recipe);
                entries.add(recipe.catalogEntry());
            }
        }
        catalog = MaskStationCatalog.of(current, entries);

        // A reload invalidates the selection outright, including a recipe replaced under its own id:
        // the contents of that file may have changed completely, and re-adopting the id would show a
        // preview of something the player never chose (§7.5).
        if (selected != null && selectionGeneration != current) {
            selected = null;
        }
        if (selected != null && !catalog.contains(selected)) {
            selected = null;
        }
        selectionGeneration = selected == null ? 0 : current;
        selectedRecipe = selected == null ? null
                : matching.stream().filter(recipe -> recipe.getId().equals(selected)).findFirst().orElse(null);
        if (selectedRecipe == null) {
            selected = null;
        }

        ItemStack assembled = selectedRecipe == null ? ItemStack.EMPTY
                : selectedRecipe.assemble(inputs, level.registryAccess());
        preview.setItem(0, assembled);
        // The cursor is always a destination and a remainder can always fall on the floor, so the
        // single-craft route is never capacity-bound; only affordability decides it.
        plan = assembled.isEmpty() ? MaskCraftPlan.refused(emptyPreviewReason())
                : preparePlan(1, Integer.MAX_VALUE);
        if (owner instanceof ServerPlayer serverPlayer) {
            syncSelection(serverPlayer, false);
        }
        broadcastChanges();
    }

    /**
     * Why the preview is empty: a refused restyle explains itself, anything else is "choose a style".
     *
     * <p>A restyle whose material carries untransferable data is offered (the pair of styles is legal)
     * and then assembles to nothing, which without this would read to the player as an unselected
     * screen rather than as the rule it is.
     */
    private MaskCraftRejection emptyPreviewReason() {
        if (selectedRecipe != null && selectedRecipe.operation() == MaskOperation.RESTYLE
                && !selectedRecipe.restyleRejection(inputs).allowed()) {
            return MaskCraftRejection.RESTYLE_UNSUPPORTED;
        }
        return MaskCraftRejection.NO_SELECTION;
    }

    /** Tells this viewer's client which style is selected, when that has actually changed. */
    private void syncSelection(ServerPlayer player, boolean force) {
        int current = generation.get();
        if (!force && java.util.Objects.equals(syncedSelection, selected) && syncedGeneration == current) {
            return;
        }
        if (!force && selected == null && syncedSelection == null) {
            // Nothing to tell: a client with no selection learns that a reload cleared one from the
            // generation data slot, which it is already watching.
            syncedGeneration = current;
            return;
        }
        syncedSelection = selected;
        syncedGeneration = current;
        CrimeNetwork.sendMaskSelection(player,
                new MaskSelectionS2CPacket(containerId, selected, current));
    }

    private boolean craftingEnabled() {
        return McaCrimeConfig.COMMON.enableMaskStationCrafting.get();
    }

    private boolean operationEnabled(MaskMakingRecipe recipe) {
        return recipe.operation() != MaskOperation.RESTYLE
                || McaCrimeConfig.COMMON.enableMaskRestyling.get();
    }

    /**
     * The plan for one extraction, against the destinations that route actually has.
     *
     * @param batch        how many crafts this route is asking for
     * @param destinations whole free destinations the output and its remainders share
     */
    private MaskCraftPlan preparePlan(int batch, int destinations) {
        if (!craftingEnabled()) {
            return MaskCraftPlan.refused(MaskCraftRejection.DISABLED);
        }
        MaskMakingRecipe recipe = selectedRecipe;
        if (recipe == null) {
            return MaskCraftPlan.refused(MaskCraftRejection.NO_SELECTION);
        }
        if (!operationEnabled(recipe)) {
            return MaskCraftPlan.refused(MaskCraftRejection.DISABLED);
        }
        if (preview.getItem(0).isEmpty()) {
            return MaskCraftPlan.refused(MaskCraftRejection.NO_SELECTION);
        }
        return MaskCraftPlan.prepare(recipe.materialCount(), recipe.bindingCount(), recipe.dyeCount(inputs),
                countRemainders(recipe),
                inputs.getItem(MaskMakingRecipe.MATERIAL).getCount(),
                inputs.getItem(MaskMakingRecipe.BINDING).getCount(),
                inputs.getItem(MaskMakingRecipe.DYE).getCount(),
                destinations, batch);
    }

    private int countRemainders(MaskMakingRecipe recipe) {
        int count = 0;
        for (ItemStack stack : recipe.getRemainingItems(inputs)) {
            if (!stack.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ the one transaction

    /** Whether the result slot may be picked up at all. */
    boolean craftable() {
        return plan.craftable();
    }

    /**
     * Commits exactly one craft, after the preview has already been handed to the cursor.
     *
     * <p>The debit lives here and only here. {@code Slot#remove} deliberately does nothing but take the
     * preview out of its one-slot container, so there is no second place that could consume the same
     * ingredients (§8.2).
     */
    void commitOne(Player player) {
        MaskMakingRecipe recipe = selectedRecipe;
        if (recipe == null || !plan.craftable()) {
            refresh();
            return;
        }
        List<ItemStack> remainders = remainders(recipe);
        consumeOne(recipe);
        deliver(player, remainders);
        refresh();
    }

    private List<ItemStack> remainders(MaskMakingRecipe recipe) {
        List<ItemStack> remainders = new ArrayList<>();
        for (ItemStack stack : recipe.getRemainingItems(inputs)) {
            if (!stack.isEmpty()) {
                remainders.add(stack.copy());
            }
        }
        return remainders;
    }

    private void consumeOne(MaskMakingRecipe recipe) {
        inputs.getItem(MaskMakingRecipe.MATERIAL).shrink(recipe.materialCount());
        inputs.getItem(MaskMakingRecipe.BINDING).shrink(recipe.bindingCount());
        int dye = recipe.dyeCount(inputs);
        if (dye > 0) {
            inputs.getItem(MaskMakingRecipe.DYE).shrink(dye);
        }
    }

    /** Hands items back without ever deleting one: the inventory takes what fits, the floor the rest. */
    private void deliver(Player player, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                player.getInventory().placeItemBackInInventory(stack);
            }
        }
    }

    /**
     * Whole empty slots in this player's inventory.
     *
     * <p>Deliberately conservative: it counts empty slots rather than the space a partial stack could
     * absorb, so a batch plan can under-promise but can never authorise a craft whose output has
     * nowhere to go. A mask is unstackable, so for the output itself this is exact anyway.
     */
    private int emptySlots(Player player) {
        int empty = 0;
        for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
            if (player.getInventory().items.get(slot).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    // ------------------------------------------------------------------ routes

    @Override
    public void clicked(int slotId, int button, ClickType type, Player player) {
        if (slotId == RESULT_SLOT) {
            MaskStationRoute route = MaskStationRoute.of(type, button);
            if (route != null && !route.allowed()) {
                // Explicitly refused rather than left to a fallback that might treat a preview as a
                // real stack (§8.3). Nothing is consumed, nothing is minted, no state moves.
                return;
            }
        }
        super.clicked(slotId, button, type, player);
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != preview && super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return slot.container != preview && super.canDragTo(slot);
    }

    /**
     * Shift-click: a bounded batch out of the result slot, ordinary transfers everywhere else.
     *
     * <p>Each craft in the batch is validated, paid and delivered on its own before the next begins,
     * and the loop stops the moment one of them would not fit — so a nearly full inventory ends with
     * fewer masks, never with consumed materials and no mask (§8.2, CRAFT-02).
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index == RESULT_SLOT) {
            craftBatch(player);
            // EMPTY stops vanilla's repeat loop: this method already did the whole bounded batch.
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < RESULT_SLOT) {
            if (!moveItemStackTo(stack, INVENTORY_START, HOTBAR_END, true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, MATERIAL_SLOT, RESULT_SLOT, false)) {
            if (index < HOTBAR_START) {
                if (!moveItemStackTo(stack, HOTBAR_START, HOTBAR_END, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, INVENTORY_START, HOTBAR_START, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return original;
    }

    private void craftBatch(Player player) {
        if (owner.level().isClientSide) {
            return;
        }
        int room = emptySlots(player);
        MaskCraftPlan batch = preparePlan(MaskCraftPlan.MAX_BATCH, room);
        if (!batch.craftable()) {
            return;
        }
        Level level = owner.level();
        for (int made = 0; made < batch.crafts(); made++) {
            MaskMakingRecipe recipe = selectedRecipe;
            if (recipe == null || !recipe.matches(inputs, level)) {
                break;
            }
            ItemStack output = recipe.assemble(inputs, level.registryAccess());
            if (output.isEmpty()) {
                break;
            }
            List<ItemStack> remainders = remainders(recipe);
            if (emptySlots(player) < 1 + remainders.size()) {
                break;
            }
            consumeOne(recipe);
            List<ItemStack> delivery = new ArrayList<>(remainders.size() + 1);
            delivery.add(output);
            delivery.addAll(remainders);
            deliver(player, delivery);
        }
        refresh();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void slotsChanged(Container container) {
        if (container == inputs) {
            refresh();
        }
        super.slotsChanged(container);
    }

    @Override
    public void broadcastChanges() {
        // One int compare per tick, so a datapack reload reaches an open menu without a per-frame
        // rebuild of anything (§7.5).
        if (!owner.level().isClientSide && generation.get() != MaskRecipeGeneration.current()) {
            refresh();
            return;
        }
        super.broadcastChanges();
    }

    @Override
    public boolean stillValid(Player player) {
        if (!craftingEnabled()) {
            // Turning crafting off closes every open menu at the next tick, which returns the inputs
            // through the ordinary cleanup path rather than stranding them in a dead session.
            return false;
        }
        return stillValid(access, player, CrimeBlocks.MASK_STATION.get());
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // Server-side only, exactly like vanilla's crafting table: NULL access makes this a no-op on
        // the client, whose copy of the inputs is a mirror the server is about to correct anyway.
        access.execute((level, pos) -> clearContainer(player, inputs));
        preview.setItem(0, ItemStack.EMPTY);
    }
}
