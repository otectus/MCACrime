package dev.otectus.mcacrime.recipe.lock;

import dev.otectus.mcacrime.item.lock.BakedKeyMoldItem;
import dev.otectus.mcacrime.item.lock.KeyMoldItem;
import dev.otectus.mcacrime.recipe.CrimeRecipes;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;

/**
 * Firing a clay key mold, keeping what it recorded (M3.2, ledger A2-S5).
 *
 * <p>A custom serializer because vanilla smelting builds its result from the recipe's declared output
 * stack and throws the input's components away — which for this recipe means firing a mold of
 * somebody's front-door key and getting a blank one. This is the only non-simple serializer of the
 * six.
 *
 * <p>The recipe type stays {@code minecraft:smelting}, so an ordinary furnace finds it and no new
 * block or menu is needed. Only the serializer is ours.
 *
 * <p>1.21.1 note: recipe serializers are a {@code MapCodec} plus a {@code StreamCodec} rather than a
 * pair of hand-written JSON and buffer readers, and {@link net.minecraft.world.item.crafting.SimpleCookingSerializer}
 * already builds both from a factory — so the JSON shape a datapack author writes is exactly vanilla
 * smelting's, with no reader of ours in the way.
 */
public class KeyMoldBakeRecipe extends SmeltingRecipe {

    public KeyMoldBakeRecipe(String group, CookingBookCategory category, Ingredient ingredient,
                             ItemStack result, float experience, int cookingTime) {
        super(group, category, ingredient, result, experience, cookingTime);
    }

    /** Only a mold that actually recorded a key: firing a blank one produces nothing. */
    @Override
    public boolean matches(SingleRecipeInput input, Level level) {
        return super.matches(input, level) && KeyMoldItem.recorded(input.item()).isPresent();
    }

    @Override
    public ItemStack assemble(SingleRecipeInput input, HolderLookup.Provider registries) {
        return BakedKeyMoldItem.fromRawMold(input.item());
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return CrimeRecipes.KEY_MOLD_BAKE.get();
    }
}
