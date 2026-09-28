package dev.otectus.mcacrime.recipe;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.recipe.lock.BakedKeyMoldCopyRecipe;
import dev.otectus.mcacrime.recipe.lock.KeyMoldBakeRecipe;
import dev.otectus.mcacrime.recipe.lock.KeyMoldCopyRecipe;
import dev.otectus.mcacrime.recipe.lock.KeyRingAddRecipe;
import dev.otectus.mcacrime.recipe.lock.KeyRingCreateRecipe;
import dev.otectus.mcacrime.recipe.lock.KeyRingDisassembleRecipe;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * This mod's recipe types and serializers: the Mask Station's (0.7.2 §7.1) and the six key-crafting
 * serializers of 0.7.5 M3.2.
 *
 * <p>A custom type rather than a crafting-table recipe because the station's question is not "what do
 * these items make" but "which of the things these items can make did the player ask for" — a question
 * a first-match lookup cannot answer without hiding three styles out of four.
 */
public final class CrimeRecipes {

    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(ForgeRegistries.RECIPE_TYPES, McaCrime.MOD_ID);

    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, McaCrime.MOD_ID);

    public static final RegistryObject<RecipeType<MaskMakingRecipe>> MASK_MAKING =
            RECIPE_TYPES.register("mask_making", () -> RecipeType.simple(McaCrime.id("mask_making")));

    public static final RegistryObject<RecipeSerializer<MaskMakingRecipe>> MASK_MAKING_SERIALIZER =
            RECIPE_SERIALIZERS.register("mask_making", MaskMakingRecipe.Serializer::new);

    // --- key crafting (0.7.5 M3.2) ---------------------------------------------------------------
    // Six serializers, five of them simple. Key crafting is special-recipe work rather than shaped
    // work because every one of these carries NBT from an input to an output, which a shaped recipe
    // cannot do: firing a mold of somebody's key with a vanilla smelting recipe gives back a blank.
    // `key_reset` is deliberately absent: a vanilla shapeless recipe already produces an unbound key,
    // which is exactly what a reset is under this NBT scheme.

    public static final RegistryObject<RecipeSerializer<KeyRingCreateRecipe>> KEY_RING_CREATE =
            RECIPE_SERIALIZERS.register("key_ring_create",
                    () -> new SimpleCraftingRecipeSerializer<>(KeyRingCreateRecipe::new));

    public static final RegistryObject<RecipeSerializer<KeyRingAddRecipe>> KEY_RING_ADD =
            RECIPE_SERIALIZERS.register("key_ring_add",
                    () -> new SimpleCraftingRecipeSerializer<>(KeyRingAddRecipe::new));

    public static final RegistryObject<RecipeSerializer<KeyRingDisassembleRecipe>> KEY_RING_DISASSEMBLE =
            RECIPE_SERIALIZERS.register("key_ring_disassemble",
                    () -> new SimpleCraftingRecipeSerializer<>(KeyRingDisassembleRecipe::new));

    public static final RegistryObject<RecipeSerializer<KeyMoldCopyRecipe>> KEY_MOLD_COPY =
            RECIPE_SERIALIZERS.register("key_mold_copy",
                    () -> new SimpleCraftingRecipeSerializer<>(KeyMoldCopyRecipe::new));

    public static final RegistryObject<RecipeSerializer<BakedKeyMoldCopyRecipe>> BAKED_KEY_MOLD_COPY =
            RECIPE_SERIALIZERS.register("baked_key_mold_copy",
                    () -> new SimpleCraftingRecipeSerializer<>(BakedKeyMoldCopyRecipe::new));

    /** The one custom serializer: vanilla smelting would drop the mold's recorded key. */
    public static final RegistryObject<RecipeSerializer<KeyMoldBakeRecipe>> KEY_MOLD_BAKE =
            RECIPE_SERIALIZERS.register("key_mold_bake", KeyMoldBakeRecipe.Serializer::new);

    private CrimeRecipes() {
    }

    public static void register(IEventBus modBus) {
        RECIPE_TYPES.register(modBus);
        RECIPE_SERIALIZERS.register(modBus);
    }
}
