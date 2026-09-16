package dev.otectus.mcacrime.recipe;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * This mod's recipe type and serializer (0.7.2 §7.1). One of each, both on the MOD bus.
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

    private CrimeRecipes() {
    }

    public static void register(IEventBus modBus) {
        RECIPE_TYPES.register(modBus);
        RECIPE_SERIALIZERS.register(modBus);
    }
}
