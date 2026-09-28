package dev.otectus.mcacrime.recipe;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.recipe.lock.BakedKeyMoldCopyRecipe;
import dev.otectus.mcacrime.recipe.lock.KeyMoldBakeRecipe;
import dev.otectus.mcacrime.recipe.lock.KeyMoldCopyRecipe;
import dev.otectus.mcacrime.recipe.lock.KeyRingAddRecipe;
import dev.otectus.mcacrime.recipe.lock.KeyRingCreateRecipe;
import dev.otectus.mcacrime.recipe.lock.KeyRingDisassembleRecipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.SimpleCookingSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * This mod's recipe type and serializer (0.7.2 §7.1). One of each, both on the MOD bus.
 *
 * <p>A custom type rather than a crafting-table recipe because the station's question is not "what do
 * these items make" but "which of the things these items can make did the player ask for" — a question
 * a first-match lookup cannot answer without hiding three styles out of four.
 *
 * <p>0.7.5 adds six serializers and no new type. Five are ordinary crafting-table recipes whose rule
 * is code rather than a shape, so they are {@code minecraft:crafting} recipes with a
 * {@link SimpleCraftingRecipeSerializer}; the sixth stays {@code minecraft:smelting} so an ordinary
 * furnace finds it, and only replaces vanilla's result-building — which would otherwise throw away
 * the impression the mold was fired with.
 */
public final class CrimeRecipes {

    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, McaCrime.MOD_ID);

    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, McaCrime.MOD_ID);

    public static final DeferredHolder<RecipeType<?>, RecipeType<MaskMakingRecipe>> MASK_MAKING =
            RECIPE_TYPES.register("mask_making", () -> RecipeType.simple(McaCrime.id("mask_making")));

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<MaskMakingRecipe>>
            MASK_MAKING_SERIALIZER =
            RECIPE_SERIALIZERS.register("mask_making", MaskMakingRecipe.Serializer::new);

    /** Two or more bound keys into one ring (M3.2). */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<KeyRingCreateRecipe>>
            KEY_RING_CREATE = RECIPE_SERIALIZERS.register("key_ring_create",
            () -> new SimpleCraftingRecipeSerializer<>(KeyRingCreateRecipe::new));

    /** A ring and more keys into the same ring. */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<KeyRingAddRecipe>>
            KEY_RING_ADD = RECIPE_SERIALIZERS.register("key_ring_add",
            () -> new SimpleCraftingRecipeSerializer<>(KeyRingAddRecipe::new));

    /** A ring back into its last key, and a ring with one fewer. */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<KeyRingDisassembleRecipe>>
            KEY_RING_DISASSEMBLE = RECIPE_SERIALIZERS.register("key_ring_disassemble",
            () -> new SimpleCraftingRecipeSerializer<>(KeyRingDisassembleRecipe::new));

    /** A bound key and clay into an impression of it. */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<KeyMoldCopyRecipe>>
            KEY_MOLD_COPY = RECIPE_SERIALIZERS.register("key_mold_copy",
            () -> new SimpleCraftingRecipeSerializer<>(KeyMoldCopyRecipe::new));

    /** A fired mold and an ingot into one cast key. */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<BakedKeyMoldCopyRecipe>>
            BAKED_KEY_MOLD_COPY = RECIPE_SERIALIZERS.register("baked_key_mold_copy",
            () -> new SimpleCraftingRecipeSerializer<>(BakedKeyMoldCopyRecipe::new));

    /**
     * Firing a clay mold in a furnace, keeping what it recorded.
     *
     * <p>200 is the default cooking time a recipe may override, exactly as vanilla's own smelting
     * serializer is constructed.
     */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<KeyMoldBakeRecipe>>
            KEY_MOLD_BAKE = RECIPE_SERIALIZERS.register("key_mold_bake",
            () -> new SimpleCookingSerializer<>(KeyMoldBakeRecipe::new, 200));

    private CrimeRecipes() {
    }

    public static void register(IEventBus modBus) {
        RECIPE_TYPES.register(modBus);
        RECIPE_SERIALIZERS.register(modBus);
    }
}
