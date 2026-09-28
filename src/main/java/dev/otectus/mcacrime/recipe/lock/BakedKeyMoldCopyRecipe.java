package dev.otectus.mcacrime.recipe.lock;

import dev.otectus.mcacrime.recipe.CrimeRecipes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;

import java.util.Optional;

/**
 * A fired mold and an iron ingot cast one key (M3.2, ledger A2-S6).
 *
 * <p>The mold comes back one cast poorer, and is consumed on its last. The cast key carries the
 * recorded identity <em>and</em> revision, so a mold taken before a rekey casts keys that do not work
 * — which is the point of recording the revision at all.
 */
public class BakedKeyMoldCopyRecipe extends LockCraftingRecipe {

    public BakedKeyMoldCopyRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    protected Optional<KeyCraftLogic.Result> resolve(CraftingContainer grid) {
        return KeyCraftLogic.bakedMoldCopy(inputs(read(grid)), moldQuality(grid));
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return CrimeRecipes.BAKED_KEY_MOLD_COPY.get();
    }
}
