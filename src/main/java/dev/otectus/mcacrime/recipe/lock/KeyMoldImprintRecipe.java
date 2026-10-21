package dev.otectus.mcacrime.recipe.lock;

import dev.otectus.mcacrime.recipe.CrimeRecipes;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;

import java.util.Optional;

/**
 * A blank Key Mold and a bound Key make an impression of that key, and the key comes back (0.8.0).
 * The rule, its refusals and its remainders are {@link KeyCraftLogic#moldImprint}.
 */
public class KeyMoldImprintRecipe extends LockCraftingRecipe {

    public KeyMoldImprintRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    protected Optional<KeyCraftLogic.Result> resolve(CraftingInput grid) {
        return KeyCraftLogic.moldImprint(inputs(read(grid)));
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return CrimeRecipes.KEY_MOLD_IMPRINT.get();
    }
}
