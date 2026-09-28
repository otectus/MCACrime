package dev.otectus.mcacrime.recipe.lock;

import dev.otectus.mcacrime.recipe.CrimeRecipes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;

import java.util.Optional;

/** A bound key and a lump of clay make an impression of that key (M3.2, ledger A2-S4). */
public class KeyMoldCopyRecipe extends LockCraftingRecipe {

    public KeyMoldCopyRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    protected Optional<KeyCraftLogic.Result> resolve(CraftingContainer grid) {
        return KeyCraftLogic.moldCopy(inputs(read(grid)));
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return CrimeRecipes.KEY_MOLD_COPY.get();
    }
}
