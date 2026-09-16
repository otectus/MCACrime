package dev.otectus.mcacrime.recipe;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeInput;

/**
 * The three input slots of one Mask Station, as the 1.21.1 recipe system wants to see them.
 *
 * <p>1.21 replaced {@code Recipe<Container>} with {@code Recipe<? extends RecipeInput>}: a recipe is
 * now handed a read-only view of the slots rather than the live inventory. That is a better fit for
 * this station than it was for the 1.20.1 original, because {@link MaskMakingRecipe} was already
 * written never to touch what it is given — the preview a player is looking at must never be the stack
 * the commit later debits against.
 *
 * <p>{@link #of(Container)} is the adapter the menu uses; the record constructor is what tests use, so
 * the matching rules can be exercised without an inventory.
 */
public record MaskStationInput(ItemStack material, ItemStack binding, ItemStack dye) implements RecipeInput {

    /** An input with nothing in any slot. */
    public static final MaskStationInput EMPTY =
            new MaskStationInput(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);

    public MaskStationInput {
        material = material == null ? ItemStack.EMPTY : material;
        binding = binding == null ? ItemStack.EMPTY : binding;
        dye = dye == null ? ItemStack.EMPTY : dye;
    }

    /** A snapshot of the station's working inventory, in the fixed slot order. */
    public static MaskStationInput of(Container container) {
        if (container == null || container.getContainerSize() < MaskMakingRecipe.INPUT_SLOTS) {
            return EMPTY;
        }
        return new MaskStationInput(container.getItem(MaskMakingRecipe.MATERIAL),
                container.getItem(MaskMakingRecipe.BINDING),
                container.getItem(MaskMakingRecipe.DYE));
    }

    @Override
    public ItemStack getItem(int slot) {
        return switch (slot) {
            case MaskMakingRecipe.MATERIAL -> material;
            case MaskMakingRecipe.BINDING -> binding;
            case MaskMakingRecipe.DYE -> dye;
            default -> ItemStack.EMPTY;
        };
    }

    @Override
    public int size() {
        return MaskMakingRecipe.INPUT_SLOTS;
    }
}
