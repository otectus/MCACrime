package dev.otectus.mcacrime.recipe.lock;

import dev.otectus.mcacrime.recipe.CrimeRecipes;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;

import java.util.Optional;

/** One ring gives back its last key and keeps the rest (M3.2, ledger A2-S3). Exactly one ring: "several rings in, one key out" is the loss the specification names, and it is refused here rather than mitigated. */
public class KeyRingDisassembleRecipe extends LockCraftingRecipe {

    public KeyRingDisassembleRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    protected Optional<KeyCraftLogic.Result> resolve(CraftingInput grid) {
        return KeyCraftLogic.ringDisassemble(inputs(read(grid)));
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return CrimeRecipes.KEY_RING_DISASSEMBLE.get();
    }
}
