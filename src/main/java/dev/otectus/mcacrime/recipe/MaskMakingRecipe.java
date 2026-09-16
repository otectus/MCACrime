package dev.otectus.mcacrime.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.otectus.mcacrime.mask.MaskCustomization;
import dev.otectus.mcacrime.mask.MaskRestyleRejection;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

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
 * {@link #assemble} writes to the input it is handed; assembly returns a fresh stack every time,
 * so the preview a player is looking at is never the stack the commit later debits against.
 *
 * <p>1.21.1 differences from the 1.20.1 original, all mechanical: the input is a
 * {@link MaskStationInput} rather than a live {@code Container}; a recipe no longer carries its own
 * id, so {@link #catalogEntry(ResourceLocation)} is handed the id its {@code RecipeHolder} carries;
 * and the serializer is a {@link MapCodec} plus a {@link StreamCodec} instead of
 * {@code fromJson}/{@code toNetwork}. The rules themselves are unchanged.
 */
public class MaskMakingRecipe implements Recipe<RecipeInput> {

    /** Working-inventory slot indices. The station's layout is fixed, so these are too. */
    public static final int MATERIAL = 0;
    public static final int BINDING = 1;
    public static final int DYE = 2;
    public static final int INPUT_SLOTS = 3;

    private final MaskOperation operation;
    private final String group;
    private final Ingredient material;
    private final int materialCount;
    private final Ingredient binding;
    private final int bindingCount;
    private final boolean allowDye;
    private final ItemStack result;
    private final int sortOrder;

    public MaskMakingRecipe(MaskOperation operation, String group,
                            Ingredient material, int materialCount, Ingredient binding, int bindingCount,
                            boolean allowDye, ItemStack result, int sortOrder) {
        this.operation = operation;
        this.group = group == null ? "" : group;
        this.material = material;
        this.materialCount = materialCount;
        this.binding = binding;
        this.bindingCount = bindingCount;
        this.allowDye = allowDye;
        // §7.5: one mask per operation, whatever the file asked for. The count is validated on the way
        // in as well, so this is belt and braces rather than a silent correction of a rejected file.
        this.result = result.getCount() == 1 ? result : result.copyWithCount(1);
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

    /**
     * This recipe's place in the station catalogue.
     *
     * <p>The id comes from the caller because 1.21 moved it onto {@code RecipeHolder}; passing the
     * holder's id keeps the catalogue keyed by the same thing the client selects by.
     */
    public MaskStationCatalog.Entry catalogEntry(ResourceLocation id) {
        return new MaskStationCatalog.Entry(id, group, sortOrder);
    }

    @Override
    public boolean matches(RecipeInput input, Level level) {
        if (input == null || input.size() < INPUT_SLOTS) {
            return false;
        }
        ItemStack materialStack = input.getItem(MATERIAL);
        ItemStack bindingStack = input.getItem(BINDING);
        ItemStack dyeStack = input.getItem(DYE);
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
    public MaskRestyleRejection restyleRejection(RecipeInput input) {
        if (operation != MaskOperation.RESTYLE) {
            return MaskRestyleRejection.NONE;
        }
        return MaskCustomization.restyleResult(input.getItem(MATERIAL), result).rejection();
    }

    @Override
    public ItemStack assemble(RecipeInput input, HolderLookup.Provider registries) {
        ItemStack output = result.copy();
        if (operation == MaskOperation.RESTYLE) {
            output = MaskCustomization.restyle(input.getItem(MATERIAL), output).orElse(ItemStack.EMPTY);
            if (output.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }
        ItemStack dyeStack = input.getItem(DYE);
        if (allowDye && dyeStack.getItem() instanceof DyeItem dye) {
            output = MaskCustomization.tint(output, dye);
        }
        return output;
    }

    /** How many dye items one craft spends: one when a dye is present and allowed, none otherwise. */
    public int dyeCount(RecipeInput input) {
        ItemStack dyeStack = input.getItem(DYE);
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
    public NonNullList<ItemStack> getRemainingItems(RecipeInput input) {
        NonNullList<ItemStack> remaining = NonNullList.withSize(INPUT_SLOTS, ItemStack.EMPTY);
        for (int slot = 0; slot < INPUT_SLOTS; slot++) {
            ItemStack stack = input.getItem(slot);
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
    public ItemStack getResultItem(HolderLookup.Provider registries) {
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
    public RecipeSerializer<?> getSerializer() {
        return CrimeRecipes.MASK_MAKING_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return CrimeRecipes.MASK_MAKING.get();
    }

    @Override
    public boolean isIncomplete() {
        return material.hasNoItems() || binding.hasNoItems() || result.isEmpty();
    }

    /**
     * Reads a station recipe, refusing every shape {@link MaskRecipeJson} knows is wrong.
     *
     * <p>The split is still the point: the contract is stated by a class with no registry in it, so
     * the malformed-field matrix (CRAFT-10) is a unit test rather than an in-world experiment. 1.21.1
     * parses recipes through a {@link MapCodec}, which is handed decoded values rather than the
     * {@code JsonObject} the 1.20.1 serializer saw, so the codec calls
     * {@link MaskRecipeJson#problems(String, String, int, int, int, int)} on what it decoded; the
     * ingredient shape is left to {@code Ingredient}'s own codec, which refuses more than the hand
     * written check ever did.
     *
     * <p>The network side re-runs the same bounds, so a hostile server cannot widen a count on the
     * wire past what a datapack would have been allowed to write.
     */
    public static class Serializer implements RecipeSerializer<MaskMakingRecipe> {

        /** One station slot as the file writes it: an ingredient plus how many of it a craft spends. */
        private record Slot(Ingredient ingredient, int count) {
        }

        private static final Codec<MaskOperation> OPERATION_CODEC = Codec.STRING.comapFlatMap(
                raw -> MaskOperation.parse(raw)
                        .map(DataResult::success)
                        .orElseGet(() -> DataResult.error(
                                () -> "operation: '" + raw + "' is not 'craft' or 'restyle'")),
                MaskOperation::key);

        private static final Codec<Slot> SLOT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Ingredient.CODEC_NONEMPTY.fieldOf("ingredient").forGetter(Slot::ingredient),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Slot::count)
        ).apply(instance, Slot::new));

        private static final MapCodec<MaskMakingRecipe> CODEC = RecordCodecBuilder.<MaskMakingRecipe>mapCodec(
                instance -> instance.group(
                        OPERATION_CODEC.fieldOf("operation").forGetter(recipe -> recipe.operation),
                        Codec.STRING.optionalFieldOf("group", "").forGetter(recipe -> recipe.group),
                        SLOT_CODEC.fieldOf("material")
                                .forGetter(recipe -> new Slot(recipe.material, recipe.materialCount)),
                        SLOT_CODEC.fieldOf("binding")
                                .forGetter(recipe -> new Slot(recipe.binding, recipe.bindingCount)),
                        Codec.BOOL.optionalFieldOf("allow_dye", true).forGetter(recipe -> recipe.allowDye),
                        ItemStack.STRICT_CODEC.fieldOf("result").forGetter(recipe -> recipe.result),
                        Codec.INT.optionalFieldOf("sort_order", 0).forGetter(recipe -> recipe.sortOrder)
                ).apply(instance, (operation, group, material, binding, allowDye, result, sortOrder) ->
                        new MaskMakingRecipe(operation, group, material.ingredient(), material.count(),
                                binding.ingredient(), binding.count(), allowDye, result, sortOrder)))
                .validate(recipe -> {
                    List<String> problems = MaskRecipeJson.problems(recipe.operation.key(), recipe.group,
                            recipe.materialCount, recipe.bindingCount, 1, recipe.sortOrder);
                    return problems.isEmpty()
                            ? DataResult.success(recipe)
                            : DataResult.error(() -> "Invalid mask_making recipe: "
                                    + String.join("; ", problems));
                });

        public static final StreamCodec<RegistryFriendlyByteBuf, MaskMakingRecipe> STREAM_CODEC =
                StreamCodec.of(Serializer::toNetwork, Serializer::fromNetwork);

        @Override
        public MapCodec<MaskMakingRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, MaskMakingRecipe> streamCodec() {
            return STREAM_CODEC;
        }

        private static MaskMakingRecipe fromNetwork(RegistryFriendlyByteBuf buf) {
            MaskOperation operation = MaskOperation.parse(buf.readUtf(32))
                    .orElseThrow(() -> new io.netty.handler.codec.DecoderException(
                            "Unknown mask_making operation"));
            String group = buf.readUtf(MaskRecipeJson.MAX_GROUP_LENGTH);
            Ingredient material = Ingredient.CONTENTS_STREAM_CODEC.decode(buf);
            int materialCount = readCount(buf);
            Ingredient binding = Ingredient.CONTENTS_STREAM_CODEC.decode(buf);
            int bindingCount = readCount(buf);
            boolean allowDye = buf.readBoolean();
            ItemStack result = ItemStack.STREAM_CODEC.decode(buf);
            int sortOrder = buf.readVarInt();
            return new MaskMakingRecipe(operation, group, material, materialCount, binding,
                    bindingCount, allowDye, result, sortOrder);
        }

        private static void toNetwork(RegistryFriendlyByteBuf buf, MaskMakingRecipe recipe) {
            buf.writeUtf(recipe.operation.key(), 32);
            buf.writeUtf(recipe.group, MaskRecipeJson.MAX_GROUP_LENGTH);
            Ingredient.CONTENTS_STREAM_CODEC.encode(buf, recipe.material);
            buf.writeVarInt(recipe.materialCount);
            Ingredient.CONTENTS_STREAM_CODEC.encode(buf, recipe.binding);
            buf.writeVarInt(recipe.bindingCount);
            buf.writeBoolean(recipe.allowDye);
            ItemStack.STREAM_CODEC.encode(buf, recipe.result);
            buf.writeVarInt(recipe.sortOrder);
        }

        /** The same bound the JSON side enforces, so a hostile server cannot widen it on the wire. */
        private static int readCount(RegistryFriendlyByteBuf buf) {
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
