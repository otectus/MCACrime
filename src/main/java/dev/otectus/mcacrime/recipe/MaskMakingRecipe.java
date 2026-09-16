package dev.otectus.mcacrime.recipe;

import com.google.gson.JsonObject;
import dev.otectus.mcacrime.mask.MaskCustomization;
import dev.otectus.mcacrime.mask.MaskRestyleRejection;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.crafting.CraftingHelper;

import javax.annotation.Nullable;
import java.util.List;

/**
 * One Mask Station style: fixed inputs, one deterministic output (0.7.2 §7.1).
 *
 * <p>Several of these share exactly the same material and binding on purpose — that is how "same
 * materials, four different faces" works without an ambiguous crafting-table recipe or a random roll.
 * The station gathers <em>every</em> recipe these inputs satisfy and lets the player choose; nothing
 * here is a first-match lookup.
 *
 * <p>Three slots, fixed by the station's layout: material, binding, and an optional dye.
 * {@link #matches} reads counts as well as {@link Ingredient#test}, because an ingredient test alone
 * says nothing about quantity and would let one clay ball buy a four-clay mask. Neither matching nor
 * {@link #assemble} writes to the container it is handed; assembly returns a fresh stack every time,
 * so the preview a player is looking at is never the stack the commit later debits against.
 */
public class MaskMakingRecipe implements Recipe<Container> {

    /** Working-inventory slot indices. The station's layout is fixed, so these are too. */
    public static final int MATERIAL = 0;
    public static final int BINDING = 1;
    public static final int DYE = 2;
    public static final int INPUT_SLOTS = 3;

    private final ResourceLocation id;
    private final MaskOperation operation;
    private final String group;
    private final Ingredient material;
    private final int materialCount;
    private final Ingredient binding;
    private final int bindingCount;
    private final boolean allowDye;
    private final ItemStack result;
    private final int sortOrder;

    public MaskMakingRecipe(ResourceLocation id, MaskOperation operation, String group,
                            Ingredient material, int materialCount, Ingredient binding, int bindingCount,
                            boolean allowDye, ItemStack result, int sortOrder) {
        this.id = id;
        this.operation = operation;
        this.group = group == null ? "" : group;
        this.material = material;
        this.materialCount = materialCount;
        this.binding = binding;
        this.bindingCount = bindingCount;
        this.allowDye = allowDye;
        this.result = result;
        this.sortOrder = sortOrder;
    }

    public MaskOperation operation() {
        return operation;
    }

    public int materialCount() {
        return materialCount;
    }

    public int bindingCount() {
        return bindingCount;
    }

    public boolean allowDye() {
        return allowDye;
    }

    public int sortOrder() {
        return sortOrder;
    }

    public Ingredient material() {
        return material;
    }

    public Ingredient binding() {
        return binding;
    }

    /** This recipe's place in the station catalogue. */
    public MaskStationCatalog.Entry catalogEntry() {
        return new MaskStationCatalog.Entry(id, group, sortOrder);
    }

    @Override
    public boolean matches(Container container, @Nullable Level level) {
        if (container.getContainerSize() < INPUT_SLOTS) {
            return false;
        }
        ItemStack materialStack = container.getItem(MATERIAL);
        ItemStack bindingStack = container.getItem(BINDING);
        ItemStack dyeStack = container.getItem(DYE);
        if (!material.test(materialStack) || materialStack.getCount() < materialCount) {
            return false;
        }
        if (!binding.test(bindingStack) || bindingStack.getCount() < bindingCount) {
            return false;
        }
        if (!dyeStack.isEmpty() && (!allowDye || !(dyeStack.getItem() instanceof DyeItem))) {
            // §7.2: a non-dye in that slot is rejected, not ignored — a recipe that matched around it
            // would consume the material and leave the player's diamond sitting in the station.
            return false;
        }
        if (operation != MaskOperation.RESTYLE) {
            return true;
        }
        // A restyle spends a mask, so the material has to actually be one however permissive the
        // recipe's own ingredient is, and it has to be one this conversion is allowed to touch: same
        // family, registered style, and an actual change (0.7.2 sections 5.4 and 7.4). The tag alone is
        // not permission to convert an arbitrary item.
        //
        // UNSUPPORTED_DATA is deliberately let through. That refusal is about the particular stack in
        // the slot rather than about the pair of styles, and a player holding a mask that cannot be
        // converted deserves the sentence saying so rather than an empty style grid with no
        // explanation; the menu turns the empty assembly below into that message.
        MaskRestyleRejection pairing = MaskCustomization.conversion(materialStack, result);
        if (pairing != MaskRestyleRejection.NONE && pairing != MaskRestyleRejection.UNSUPPORTED_DATA) {
            return false;
        }
        DyeItem dye = allowDye && dyeStack.getItem() instanceof DyeItem chosen ? chosen : null;
        MaskRestyleRejection change = MaskCustomization.wouldChange(materialStack, result, dye);
        return change == MaskRestyleRejection.NONE || change == MaskRestyleRejection.UNSUPPORTED_DATA;
    }

    /** Why this restyle would be refused for the stack currently in the material slot. */
    public MaskRestyleRejection restyleRejection(Container container) {
        if (operation != MaskOperation.RESTYLE) {
            return MaskRestyleRejection.NONE;
        }
        return MaskCustomization.restyleResult(container.getItem(MATERIAL), result).rejection();
    }

    @Override
    public ItemStack assemble(Container container, RegistryAccess registries) {
        ItemStack output = result.copy();
        if (operation == MaskOperation.RESTYLE) {
            output = MaskCustomization.restyle(container.getItem(MATERIAL), output).orElse(ItemStack.EMPTY);
            if (output.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }
        ItemStack dyeStack = container.getItem(DYE);
        if (allowDye && dyeStack.getItem() instanceof DyeItem dye) {
            output = MaskCustomization.tint(output, dye);
        }
        return output;
    }

    /** How many dye items one craft spends: one when a dye is present and allowed, none otherwise. */
    public int dyeCount(Container container) {
        ItemStack dyeStack = container.getItem(DYE);
        return allowDye && dyeStack.getItem() instanceof DyeItem ? 1 : 0;
    }

    /**
     * What the player gets back from the three input slots.
     *
     * <p>Only container items — a bucket, a bottle, a datapack ingredient with a remainder. A restyled
     * mask is <em>consumed</em>, not returned: handing it back would duplicate the very stack whose
     * identity was just transferred (CRAFT-03).
     */
    @Override
    public NonNullList<ItemStack> getRemainingItems(Container container) {
        NonNullList<ItemStack> remaining = NonNullList.withSize(INPUT_SLOTS, ItemStack.EMPTY);
        for (int slot = 0; slot < INPUT_SLOTS; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.hasCraftingRemainingItem()) {
                remaining.set(slot, stack.getCraftingRemainingItem());
            }
        }
        return remaining;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= INPUT_SLOTS;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess registries) {
        return result;
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        ingredients.add(material);
        ingredients.add(binding);
        return ingredients;
    }

    @Override
    public String getGroup() {
        return group;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return CrimeRecipes.MASK_MAKING_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return CrimeRecipes.MASK_MAKING.get();
    }

    @Override
    public boolean isIncomplete() {
        return material.isEmpty() || binding.isEmpty() || result.isEmpty();
    }

    /**
     * Reads a station recipe, having already refused every shape {@link MaskRecipeJson} knows is wrong.
     *
     * <p>The split is the point: the JSON contract is validated by a class with no registry in it, so
     * the malformed-field matrix is a unit test rather than an in-world experiment, and by the time
     * anything here resolves an item the file is already known to be well formed.
     */
    public static class Serializer implements RecipeSerializer<MaskMakingRecipe> {

        @Override
        public MaskMakingRecipe fromJson(ResourceLocation id, JsonObject json) {
            List<String> problems = MaskRecipeJson.problems(json);
            if (!problems.isEmpty()) {
                throw new com.google.gson.JsonSyntaxException(
                        "Invalid mask_making recipe " + id + ": " + String.join("; ", problems));
            }
            MaskRecipeJson.Fields fields = MaskRecipeJson.parse(json).orElseThrow();
            Ingredient material = CraftingHelper.getIngredient(MaskRecipeJson.ingredient(json, "material"), false);
            Ingredient binding = CraftingHelper.getIngredient(MaskRecipeJson.ingredient(json, "binding"), false);
            ItemStack result = ShapedRecipe.itemStackFromJson(json.getAsJsonObject("result"));
            if (result.isEmpty()) {
                throw new com.google.gson.JsonSyntaxException(
                        "Invalid mask_making recipe " + id + ": result.item is not a registered item");
            }
            result.setCount(1);
            return new MaskMakingRecipe(id, fields.operation(), fields.group(), material,
                    MaskRecipeJson.count(json, "material"), binding, MaskRecipeJson.count(json, "binding"),
                    fields.allowDye(), result, fields.sortOrder());
        }

        @Override
        public MaskMakingRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            MaskOperation operation = MaskOperation.parse(buf.readUtf(32))
                    .orElseThrow(() -> new io.netty.handler.codec.DecoderException(
                            "Unknown mask_making operation for " + id));
            String group = buf.readUtf(MaskRecipeJson.MAX_GROUP_LENGTH);
            Ingredient material = Ingredient.fromNetwork(buf);
            int materialCount = readCount(buf);
            Ingredient binding = Ingredient.fromNetwork(buf);
            int bindingCount = readCount(buf);
            boolean allowDye = buf.readBoolean();
            ItemStack result = buf.readItem();
            int sortOrder = buf.readVarInt();
            return new MaskMakingRecipe(id, operation, group, material, materialCount, binding,
                    bindingCount, allowDye, result, sortOrder);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, MaskMakingRecipe recipe) {
            buf.writeUtf(recipe.operation.key(), 32);
            buf.writeUtf(recipe.group, MaskRecipeJson.MAX_GROUP_LENGTH);
            recipe.material.toNetwork(buf);
            buf.writeVarInt(recipe.materialCount);
            recipe.binding.toNetwork(buf);
            buf.writeVarInt(recipe.bindingCount);
            buf.writeBoolean(recipe.allowDye);
            buf.writeItem(recipe.result);
            buf.writeVarInt(recipe.sortOrder);
        }

        /** The same bound the JSON side enforces, so a hostile server cannot widen it on the wire. */
        private static int readCount(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            if (count < 1 || count > MaskRecipeJson.MAX_INGREDIENT_COUNT) {
                throw new io.netty.handler.codec.DecoderException(
                        "mask_making ingredient count " + count + " outside [1, "
                                + MaskRecipeJson.MAX_INGREDIENT_COUNT + "]");
            }
            return count;
        }
    }
}
