package dev.otectus.mcacrime.recipe.lock;

import dev.otectus.mcacrime.recipe.CrimeRecipes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;

import java.util.Optional;

/** Two or more bound keys become one ring holding all of them (M3.2, ledger A2-S1). */
public class KeyRingCreateRecipe extends LockCraftingRecipe {

    public KeyRingCreateRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    protected Optional<KeyCraftLogic.Result> resolve(CraftingContainer grid) {
        return KeyCraftLogic.ringCreate(inputs(read(grid)), capacity());
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return CrimeRecipes.KEY_RING_CREATE.get();
    }
}
