package dev.otectus.mcacrime.recipe.lock;

import dev.otectus.mcacrime.recipe.CrimeRecipes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;

import java.util.Optional;

/**
 * One ring plus keys becomes the same ring carrying them (M3.2, ledger A2-S2).
 *
 * <p>Two rings in the grid refuse the craft outright rather than merging: a merge that overflows the
 * capacity has to discard somebody's key, and the specification forbids exactly that.
 */
public class KeyRingAddRecipe extends LockCraftingRecipe {

    public KeyRingAddRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    protected Optional<KeyCraftLogic.Result> resolve(CraftingContainer grid) {
        return KeyCraftLogic.ringAdd(inputs(read(grid)), capacity());
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return CrimeRecipes.KEY_RING_ADD.get();
    }
}
