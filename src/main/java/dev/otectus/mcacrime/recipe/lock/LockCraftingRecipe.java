package dev.otectus.mcacrime.recipe.lock;

import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.item.lock.BakedKeyMoldItem;
import dev.otectus.mcacrime.locks.LockInteractions;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The adapter half of key crafting: a grid in, {@link KeyCraftLogic} asked, stacks out (M3.2).
 *
 * <p>Six recipes share it because they share the awkward part — walking the grid, remembering which
 * slot each input came from, and turning the logic's per-input remainder list back into the
 * {@code NonNullList} vanilla wants. The rules themselves are all in {@code KeyCraftLogic}, where
 * they can be tested without a crafting table.
 */
public abstract class LockCraftingRecipe extends CustomRecipe {

    protected LockCraftingRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    /** One occupied grid slot and what the logic layer should call it. */
    protected record Slot(int index, KeyCraftLogic.Input input) {
    }

    /** Every occupied slot of the grid, in order. */
    protected static List<Slot> read(@Nullable CraftingContainer grid) {
        List<Slot> slots = new ArrayList<>();
        if (grid == null) {
            return slots;
        }
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack stack = grid.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            CompoundTag tag = stack.getTag();
            slots.add(new Slot(i, new KeyCraftLogic.Input(kindOf(stack),
                    tag == null ? new CompoundTag() : tag.copy())));
        }
        return slots;
    }

    protected static List<KeyCraftLogic.Input> inputs(List<Slot> slots) {
        List<KeyCraftLogic.Input> inputs = new ArrayList<>();
        slots.forEach(slot -> inputs.add(slot.input()));
        return inputs;
    }

    /** What one stack is, as far as these recipes are concerned. */
    protected static KeyCraftLogic.Kind kindOf(ItemStack stack) {
        if (stack.is(CrimeItems.KEY.get())) {
            return KeyCraftLogic.Kind.KEY;
        }
        if (stack.is(CrimeItems.KEY_RING.get())) {
            return KeyCraftLogic.Kind.RING;
        }
        if (stack.is(CrimeItems.KEY_MOLD.get())) {
            return KeyCraftLogic.Kind.RAW_MOLD;
        }
        if (stack.is(CrimeItems.BAKED_KEY_MOLD.get())) {
            return KeyCraftLogic.Kind.BAKED_MOLD;
        }
        if (stack.is(Items.CLAY_BALL)) {
            return KeyCraftLogic.Kind.CLAY;
        }
        if (stack.is(Items.IRON_INGOT)) {
            return KeyCraftLogic.Kind.INGOT;
        }
        return KeyCraftLogic.Kind.OTHER;
    }

    /** The output stack for a logic result. */
    protected static ItemStack build(KeyCraftLogic.Result result) {
        ItemStack stack = switch (result.resultKind()) {
            case KEY -> new ItemStack(CrimeItems.KEY.get());
            case RING -> new ItemStack(CrimeItems.KEY_RING.get());
            case RAW_MOLD -> new ItemStack(CrimeItems.KEY_MOLD.get());
            case BAKED_MOLD -> new ItemStack(CrimeItems.BAKED_KEY_MOLD.get());
            case CLAY -> new ItemStack(Items.CLAY_BALL);
            case INGOT -> new ItemStack(Items.IRON_INGOT);
            case OTHER -> ItemStack.EMPTY;
        };
        if (stack.isEmpty()) {
            return stack;
        }
        stack.setCount(result.resultCount());
        if (!result.resultTag().isEmpty()) {
            stack.setTag(result.resultTag().copy());
        }
        return stack;
    }

    /** The per-slot remainders vanilla asks for, built from the logic's own answer. */
    protected static NonNullList<ItemStack> remainders(CraftingContainer grid, List<Slot> slots,
                                                       KeyCraftLogic.Result result) {
        NonNullList<ItemStack> remaining =
                NonNullList.withSize(grid.getContainerSize(), ItemStack.EMPTY);
        for (int i = 0; i < slots.size() && i < result.remaining().size(); i++) {
            Optional<KeyCraftLogic.Input> kept = result.remaining().get(i);
            if (kept.isEmpty()) {
                continue;
            }
            ItemStack original = grid.getItem(slots.get(i).index()).copy();
            original.setCount(1);
            if (kept.get().tag().isEmpty()) {
                original.setTag(null);
            } else {
                original.setTag(kept.get().tag().copy());
            }
            remaining.set(slots.get(i).index(), original);
        }
        return remaining;
    }

    /** The configured ring capacity, so every ring recipe asks the same question. */
    protected static int capacity() {
        return LockInteractions.maxKeysPerRing();
    }

    /** The casts left in whichever fired mold the grid holds, or zero. */
    protected static int moldQuality(CraftingContainer grid) {
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack stack = grid.getItem(i);
            if (stack.is(CrimeItems.BAKED_KEY_MOLD.get())) {
                return BakedKeyMoldItem.quality(stack);
            }
        }
        return 0;
    }

    /** Shared shape: any grid with room for two ingredients. */
    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public boolean matches(CraftingContainer grid, Level level) {
        return resolve(grid).isPresent();
    }

    @Override
    public ItemStack assemble(CraftingContainer grid, RegistryAccess access) {
        return resolve(grid).map(LockCraftingRecipe::build).orElse(ItemStack.EMPTY);
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingContainer grid) {
        List<Slot> slots = read(grid);
        return resolve(grid)
                .map(result -> remainders(grid, slots, result))
                .orElseGet(() -> NonNullList.withSize(grid.getContainerSize(), ItemStack.EMPTY));
    }

    /** The one thing each recipe defines: which rule this grid is asked against. */
    protected abstract Optional<KeyCraftLogic.Result> resolve(CraftingContainer grid);
}
