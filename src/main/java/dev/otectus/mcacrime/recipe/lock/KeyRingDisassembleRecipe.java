package dev.otectus.mcacrime.recipe.lock;

import dev.otectus.mcacrime.recipe.CrimeRecipes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;

import java.util.Optional;

/**
 * One ring gives back its last key and keeps the rest (M3.2, ledger A2-S3).
 *
 * <p>Exactly one ring. "Several rings in, one key out" is the loss the specification names, and it is
 * refused here rather than mitigated.
 */
public class KeyRingDisassembleRecipe extends LockCraftingRecipe {

    public KeyRingDisassembleRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    protected Optional<KeyCraftLogic.Result> resolve(CraftingContainer grid) {
        return KeyCraftLogic.ringDisassemble(inputs(read(grid)));
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return CrimeRecipes.KEY_RING_DISASSEMBLE.get();
    }
}
