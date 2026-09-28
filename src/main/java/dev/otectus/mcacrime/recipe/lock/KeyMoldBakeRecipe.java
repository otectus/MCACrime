package dev.otectus.mcacrime.recipe.lock;

import com.google.gson.JsonObject;
import dev.otectus.mcacrime.item.lock.BakedKeyMoldItem;
import dev.otectus.mcacrime.item.lock.KeyMoldItem;
import dev.otectus.mcacrime.recipe.CrimeRecipes;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;

/**
 * Firing a clay key mold, keeping what it recorded (M3.2, ledger A2-S5).
 *
 * <p>A custom serializer because vanilla smelting builds its result from the recipe's declared output
 * stack and throws the input's NBT away — which for this recipe means firing a mold of somebody's
 * front-door key and getting a blank one. This is the only non-simple serializer of the six.
 *
 * <p>The recipe type stays {@code minecraft:smelting}, so an ordinary furnace finds it and no new
 * block or menu is needed. Only the serializer is ours.
 */
public class KeyMoldBakeRecipe extends SmeltingRecipe {

    public KeyMoldBakeRecipe(ResourceLocation id, String group, Ingredient ingredient, ItemStack result,
                             float experience, int cookingTime) {
        super(id, group, CookingBookCategory.MISC, ingredient, result, experience, cookingTime);
    }

    /** Only a mold that actually recorded a key: firing a blank one produces nothing. */
    @Override
    public boolean matches(Container container, Level level) {
        ItemStack input = container.getItem(0);
        return super.matches(container, level) && KeyMoldItem.recorded(input).isPresent();
    }

    @Override
    public ItemStack assemble(Container container, RegistryAccess access) {
        return BakedKeyMoldItem.fromRawMold(container.getItem(0));
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return CrimeRecipes.KEY_MOLD_BAKE.get();
    }

    /**
     * The serializer. Reads exactly what a vanilla smelting recipe reads, so the JSON is the ordinary
     * shape a datapack author already knows.
     */
    public static class Serializer implements RecipeSerializer<KeyMoldBakeRecipe> {

        @Override
        public KeyMoldBakeRecipe fromJson(ResourceLocation id, JsonObject json) {
            String group = GsonHelper.getAsString(json, "group", "");
            Ingredient ingredient = Ingredient.fromJson(GsonHelper.isArrayNode(json, "ingredient")
                    ? GsonHelper.getAsJsonArray(json, "ingredient")
                    : GsonHelper.getAsJsonObject(json, "ingredient"));
            ItemStack result = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result"));
            float experience = GsonHelper.getAsFloat(json, "experience", 0.0F);
            int cookingTime = GsonHelper.getAsInt(json, "cookingtime", 200);
            return new KeyMoldBakeRecipe(id, group, ingredient, result, experience, cookingTime);
        }

        @Override
        public KeyMoldBakeRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            String group = buf.readUtf();
            Ingredient ingredient = Ingredient.fromNetwork(buf);
            ItemStack result = buf.readItem();
            float experience = buf.readFloat();
            int cookingTime = buf.readVarInt();
            return new KeyMoldBakeRecipe(id, group, ingredient, result, experience, cookingTime);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, KeyMoldBakeRecipe recipe) {
            buf.writeUtf(recipe.getGroup());
            recipe.getIngredients().get(0).toNetwork(buf);
            buf.writeItem(recipe.getResultItem(RegistryAccess.EMPTY));
            buf.writeFloat(recipe.getExperience());
            buf.writeVarInt(recipe.getCookingTime());
        }
    }
}
