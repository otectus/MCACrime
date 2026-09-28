package dev.otectus.mcacrime.recipe.lock;

import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.item.lock.BakedKeyMoldItem;
import dev.otectus.mcacrime.item.lock.KeyMoldItem;
import dev.otectus.mcacrime.locks.KeyBinding;
import dev.otectus.mcacrime.locks.KeyRingBindings;
import dev.otectus.mcacrime.locks.LockInteractions;
import dev.otectus.mcacrime.state.CrimeDataComponents;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The adapter half of key crafting: a grid in, {@link KeyCraftLogic} asked, stacks out (M3.2).
 *
 * <p>Five recipes share it because they share the awkward part — walking the grid, remembering which
 * slot each input came from, and turning the logic's per-input remainder list back into the
 * {@code NonNullList} vanilla wants. The rules themselves are all in {@code KeyCraftLogic}, where
 * they can be tested without a crafting table.
 *
 * <p>1.21.1 note: the grid arrives as a {@link CraftingInput} rather than a {@code CraftingContainer},
 * and a stack's lock data is a set of registered components rather than a tag — so {@link #read}
 * translates components into {@link KeyCraftLogic.State} on the way in and {@link #apply} translates
 * them back on the way out. A recipe carries no id here either: that lives on the {@code RecipeHolder}.
 */
public abstract class LockCraftingRecipe extends CustomRecipe {

    protected LockCraftingRecipe(CraftingBookCategory category) {
        super(category);
    }

    /** One occupied grid slot and what the logic layer should call it. */
    protected record Slot(int index, KeyCraftLogic.Input input) {
    }

    /** Every occupied slot of the grid, in order. */
    protected static List<Slot> read(@Nullable CraftingInput grid) {
        List<Slot> slots = new ArrayList<>();
        if (grid == null) {
            return slots;
        }
        for (int i = 0; i < grid.size(); i++) {
            ItemStack stack = grid.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            KeyCraftLogic.Kind kind = kindOf(stack);
            slots.add(new Slot(i, new KeyCraftLogic.Input(kind, stateOf(kind, stack))));
        }
        return slots;
    }

    /** The lock components on one stack, as the value the logic layer works in. */
    protected static KeyCraftLogic.State stateOf(KeyCraftLogic.Kind kind, ItemStack stack) {
        return switch (kind) {
            case KEY -> KeyCraftLogic.State.ofKey(KeyBinding.read(stack).orElse(null));
            case RING -> KeyCraftLogic.State.ofRing(KeyRingBindings.read(stack));
            case RAW_MOLD -> KeyCraftLogic.State.ofMold(KeyMoldItem.recorded(stack).orElse(null), 0);
            case BAKED_MOLD -> KeyCraftLogic.State.ofMold(KeyMoldItem.recorded(stack).orElse(null),
                    BakedKeyMoldItem.quality(stack));
            default -> KeyCraftLogic.State.EMPTY;
        };
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

    /**
     * Stamps one state's components onto a stack, and removes the ones it does not carry.
     *
     * <p>Removal matters as much as writing: a ring that just gave up its last key must stop carrying
     * a contents component, and a mold that was consumed must not leave a quality behind on a copy.
     */
    protected static void apply(ItemStack stack, KeyCraftLogic.Kind kind, KeyCraftLogic.State state) {
        if (stack.isEmpty()) {
            return;
        }
        stack.remove(CrimeDataComponents.LOCK_BINDING.get());
        stack.remove(CrimeDataComponents.KEY_RING_CONTENTS.get());
        stack.remove(CrimeDataComponents.KEY_MOLD_BINDING.get());
        stack.remove(CrimeDataComponents.KEY_MOLD_QUALITY.get());
        switch (kind) {
            case KEY -> state.bound().ifPresent(binding -> binding.writeTo(stack));
            case RING -> KeyRingBindings.write(stack, state.ring());
            case RAW_MOLD -> state.bound().ifPresent(binding -> KeyMoldItem.store(stack, binding));
            case BAKED_MOLD -> {
                state.bound().ifPresent(binding -> KeyMoldItem.store(stack, binding));
                if (state.quality() > 0) {
                    stack.set(CrimeDataComponents.KEY_MOLD_QUALITY.get(), state.quality());
                }
            }
            default -> {
                // clay and iron carry nothing of ours
            }
        }
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
        apply(stack, result.resultKind(), result.resultState());
        return stack;
    }

    /** The per-slot remainders vanilla asks for, built from the logic's own answer. */
    protected static NonNullList<ItemStack> remainders(CraftingInput grid, List<Slot> slots,
                                                       KeyCraftLogic.Result result) {
        NonNullList<ItemStack> remaining = NonNullList.withSize(grid.size(), ItemStack.EMPTY);
        for (int i = 0; i < slots.size() && i < result.remaining().size(); i++) {
            Optional<KeyCraftLogic.Input> kept = result.remaining().get(i);
            if (kept.isEmpty()) {
                continue;
            }
            ItemStack original = grid.getItem(slots.get(i).index()).copy();
            original.setCount(1);
            apply(original, kept.get().kind(), kept.get().state());
            remaining.set(slots.get(i).index(), original);
        }
        return remaining;
    }

    /** The configured ring capacity, so every ring recipe asks the same question. */
    protected static int capacity() {
        return LockInteractions.maxKeysPerRing();
    }

    /** The casts left in whichever fired mold the grid holds, or zero. */
    protected static int moldQuality(CraftingInput grid) {
        for (int i = 0; i < grid.size(); i++) {
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
    public boolean matches(CraftingInput grid, Level level) {
        return resolve(grid).isPresent();
    }

    @Override
    public ItemStack assemble(CraftingInput grid, HolderLookup.Provider registries) {
        return resolve(grid).map(LockCraftingRecipe::build).orElse(ItemStack.EMPTY);
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput grid) {
        List<Slot> slots = read(grid);
        return resolve(grid)
                .map(result -> remainders(grid, slots, result))
                .orElseGet(() -> NonNullList.withSize(grid.size(), ItemStack.EMPTY));
    }

    /** The one thing each recipe defines: which rule this grid is asked against. */
    protected abstract Optional<KeyCraftLogic.Result> resolve(CraftingInput grid);
}
