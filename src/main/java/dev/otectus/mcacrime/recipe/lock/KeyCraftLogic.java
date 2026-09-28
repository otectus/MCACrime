package dev.otectus.mcacrime.recipe.lock;

import dev.otectus.mcacrime.locks.KeyBinding;
import dev.otectus.mcacrime.locks.KeyRingBindings;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every key-crafting rule, as arithmetic over tags (M3.2).
 *
 * <p>The recipes above this are adapters: they turn a crafting grid into {@link Input}s, ask one of
 * these methods, and turn the answer back into stacks. Everything that can go wrong with key
 * copying — a lost binding, a duplicated one, several rings collapsing into one key, a capacity that
 * silently erases — is decided here, where it can be asserted exactly and without a game.
 *
 * <p>The specification's two hard rules are both single lines in this file. "Key-ring recipes must
 * reject ambiguous multiple-ring combinations": every method that accepts a ring refuses a second
 * one. "Do not consume several rings and return one key": the same refusal, in
 * {@link #ringDisassemble}. Upstream's own disassembly reads whichever ring it saw last and returns
 * one key regardless.
 */
public final class KeyCraftLogic {

    /** What one grid slot holds, as far as key crafting is concerned. */
    public enum Kind {
        KEY,
        RING,
        RAW_MOLD,
        BAKED_MOLD,
        CLAY,
        INGOT,
        /** Anything else at all. Its presence refuses every recipe here. */
        OTHER
    }

    /**
     * One occupied grid slot.
     *
     * @param kind what it is
     * @param tag  a copy of its NBT, never null; an empty tag means "no NBT"
     */
    public record Input(Kind kind, CompoundTag tag) {
        public Input {
            tag = tag == null ? new CompoundTag() : tag;
        }

        public static Input of(Kind kind) {
            return new Input(kind, new CompoundTag());
        }
    }

    /**
     * What a recipe produces and what it hands back.
     *
     * @param resultKind the output item
     * @param resultTag  the output's NBT
     * @param resultCount how many
     * @param remaining  one entry per input, in the same order: the stack that stays in the grid, or
     *                   empty when that input is consumed
     */
    public record Result(Kind resultKind, CompoundTag resultTag, int resultCount,
                         List<Optional<Input>> remaining) {
        public Result {
            resultTag = resultTag == null ? new CompoundTag() : resultTag;
            resultCount = Math.max(1, resultCount);
            remaining = remaining == null ? List.of() : List.copyOf(remaining);
        }
    }

    private KeyCraftLogic() {
    }

    // --- rings ---------------------------------------------------------------------------------------

    /**
     * Two or more bound keys become one ring carrying all of them.
     *
     * <p>Refused if anything else is in the grid, if any key is blank, if two keys open the same lock,
     * or if the ring would start over capacity. Each refusal keeps a binding from being silently lost:
     * a blank key on a ring is a slot nobody can use, and a duplicate would take a slot from a key
     * that opens something.
     */
    public static Optional<Result> ringCreate(@Nullable List<Input> inputs, int capacity) {
        List<Input> grid = clean(inputs);
        if (grid.size() < 2) {
            return Optional.empty();
        }
        List<KeyBinding> bindings = new ArrayList<>();
        for (Input input : grid) {
            if (input.kind() != Kind.KEY) {
                return Optional.empty();
            }
            Optional<KeyBinding> binding = KeyBinding.read(input.tag());
            if (binding.isEmpty() || contains(bindings, binding.get())) {
                return Optional.empty();
            }
            bindings.add(binding.get());
        }
        if (bindings.size() > KeyRingBindings.clampCapacity(capacity)) {
            return Optional.empty();
        }
        CompoundTag ring = new CompoundTag();
        KeyRingBindings.write(ring, bindings);
        return Optional.of(new Result(Kind.RING, ring, 1, consumeAll(grid)));
    }

    /**
     * One ring plus one or more bound keys becomes the same ring carrying them too.
     *
     * <p>A second ring refuses the whole craft. Merging two rings is the recipe the specification
     * forbids in the same breath as "never several rings in, one key out", because a merge that
     * overflows has to throw something away.
     */
    public static Optional<Result> ringAdd(@Nullable List<Input> inputs, int capacity) {
        List<Input> grid = clean(inputs);
        Input ring = null;
        List<KeyBinding> added = new ArrayList<>();
        for (Input input : grid) {
            switch (input.kind()) {
                case RING -> {
                    if (ring != null) {
                        return Optional.empty(); // two rings: ambiguous, refused
                    }
                    ring = input;
                }
                case KEY -> {
                    Optional<KeyBinding> binding = KeyBinding.read(input.tag());
                    if (binding.isEmpty() || contains(added, binding.get())) {
                        return Optional.empty();
                    }
                    added.add(binding.get());
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
        if (ring == null || added.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag result = ring.tag().copy();
        int limit = KeyRingBindings.clampCapacity(capacity);
        for (KeyBinding binding : added) {
            if (KeyRingBindings.holds(result, binding.lockId())
                    || !KeyRingBindings.add(result, binding, limit)) {
                return Optional.empty(); // over capacity, or already there: refuse rather than drop it
            }
        }
        return Optional.of(new Result(Kind.RING, result, 1, consumeAll(grid)));
    }

    /**
     * One ring becomes one key — the last one added — and the ring keeps the rest.
     *
     * <p>Exactly one ring, and the ring comes back with one fewer key. Repeated disassembly walks the
     * ring backwards, key by key, and the last one leaves an empty ring rather than destroying it.
     */
    public static Optional<Result> ringDisassemble(@Nullable List<Input> inputs) {
        List<Input> grid = clean(inputs);
        if (grid.size() != 1 || grid.get(0).kind() != Kind.RING) {
            return Optional.empty();
        }
        CompoundTag ring = grid.get(0).tag().copy();
        Optional<KeyBinding> removed = KeyRingBindings.removeLast(ring);
        if (removed.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag key = new CompoundTag();
        removed.get().writeTo(key);
        List<Optional<Input>> remaining = new ArrayList<>();
        remaining.add(Optional.of(new Input(Kind.RING, ring)));
        return Optional.of(new Result(Kind.KEY, key, 1, remaining));
    }

    // --- molds ---------------------------------------------------------------------------------------

    /**
     * One bound key plus one lump of clay becomes a raw mold recording that key.
     *
     * <p>The key is handed back: taking an impression does not consume the original, which is the
     * whole reason a mold exists.
     */
    public static Optional<Result> moldCopy(@Nullable List<Input> inputs) {
        List<Input> grid = clean(inputs);
        Input key = single(grid, Kind.KEY);
        Input clay = single(grid, Kind.CLAY);
        if (key == null || clay == null || grid.size() != 2) {
            return Optional.empty();
        }
        Optional<KeyBinding> binding = KeyBinding.read(key.tag());
        if (binding.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag mold = new CompoundTag();
        mold.put(KeyBinding.TAG_COPIED_KEY, binding.get().save());
        List<Optional<Input>> remaining = new ArrayList<>();
        for (Input input : grid) {
            remaining.add(input.kind() == Kind.KEY ? Optional.of(input) : Optional.empty());
        }
        return Optional.of(new Result(Kind.RAW_MOLD, mold, 1, remaining));
    }

    /**
     * One fired mold plus one ingot becomes one key, and the mold comes back one cast poorer.
     *
     * <p>A mold on its last cast is consumed rather than returned empty, so "spent" is a state the
     * player never has to reason about.
     */
    public static Optional<Result> bakedMoldCopy(@Nullable List<Input> inputs, int quality) {
        List<Input> grid = clean(inputs);
        Input mold = single(grid, Kind.BAKED_MOLD);
        Input ingot = single(grid, Kind.INGOT);
        if (mold == null || ingot == null || grid.size() != 2 || quality <= 0) {
            return Optional.empty();
        }
        Optional<KeyBinding> binding = recorded(mold.tag());
        if (binding.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag key = new CompoundTag();
        binding.get().writeTo(key);
        List<Optional<Input>> remaining = new ArrayList<>();
        for (Input input : grid) {
            if (input.kind() != Kind.BAKED_MOLD) {
                remaining.add(Optional.empty());
                continue;
            }
            if (quality - 1 <= 0) {
                remaining.add(Optional.empty());
            } else {
                CompoundTag used = input.tag().copy();
                used.putInt("Quality", quality - 1);
                remaining.add(Optional.of(new Input(Kind.BAKED_MOLD, used)));
            }
        }
        return Optional.of(new Result(Kind.KEY, key, 1, remaining));
    }

    /** The binding a mold records, raw or fired. */
    public static Optional<KeyBinding> recorded(@Nullable CompoundTag moldTag) {
        if (moldTag == null || !moldTag.contains(KeyBinding.TAG_COPIED_KEY)) {
            return Optional.empty();
        }
        return KeyBinding.load(moldTag.getCompound(KeyBinding.TAG_COPIED_KEY));
    }

    /** Firing a mold: the binding survives, the quality is stamped on. */
    public static Optional<CompoundTag> bake(@Nullable CompoundTag rawMoldTag, int startingQuality) {
        Optional<KeyBinding> binding = recorded(rawMoldTag);
        if (binding.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag baked = new CompoundTag();
        baked.put(KeyBinding.TAG_COPIED_KEY, binding.get().save());
        baked.putInt("Quality", Math.max(1, startingQuality));
        return Optional.of(baked);
    }

    // --- helpers --------------------------------------------------------------------------------------

    private static List<Input> clean(@Nullable List<Input> inputs) {
        List<Input> grid = new ArrayList<>();
        if (inputs != null) {
            for (Input input : inputs) {
                if (input != null) {
                    grid.add(input);
                }
            }
        }
        return grid;
    }

    @Nullable
    private static Input single(List<Input> grid, Kind kind) {
        Input found = null;
        for (Input input : grid) {
            if (input.kind() != kind) {
                continue;
            }
            if (found != null) {
                return null; // two of something that must be one
            }
            found = input;
        }
        return found;
    }

    private static boolean contains(List<KeyBinding> bindings, KeyBinding candidate) {
        for (KeyBinding binding : bindings) {
            if (binding.lockId().equals(candidate.lockId())) {
                return true;
            }
        }
        return false;
    }

    private static List<Optional<Input>> consumeAll(List<Input> grid) {
        List<Optional<Input>> remaining = new ArrayList<>();
        for (int i = 0; i < grid.size(); i++) {
            remaining.add(Optional.empty());
        }
        return remaining;
    }
}
