package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A holding cell this mod built, and every block it had to replace to build it.
 *
 * <p>The mod places blocks in exactly one situation — an arrest with nowhere to put the prisoner — and
 * the rule for that is that it must be able to undo itself completely. A cell is therefore never just a
 * position: it carries the {@link BlockState} of every block it overwrote, so releasing a prisoner puts
 * the world back as it was rather than leaving a cage and a patch of stone floor standing in a meadow
 * for the rest of the save's life.
 *
 * <p>Keyed by prisoner, because a player can only be in one cell at a time. It also carries the
 * {@code sentenceId} it was raised for, which is the same id {@code SentenceResolutionService} uses to
 * close the cases a sentence settled — that is what lets a restart tell a live cell from an orphan.
 *
 * <p>{@code createdGameTime} exists for one reason: a player who is arrested and never logs in again
 * must not leave a cage standing forever. The sweep takes down anything older than its lifetime cap
 * whether or not the prisoner ever comes back.
 *
 * <p>The snapshot is an explicit position-to-state map rather than a region box, so restoring can skip
 * any block that has since changed. If somebody broke a bar and put a chest in the gap, that position
 * is left alone: the same "never destroy someone else's work" rule that governs building, applied on
 * the way back out.
 *
 * <p>Since the redesign around the prison set, a cell also knows its door: where the lower half is,
 * which way it faces, the lock that holds it shut and the padlock entity that carries that lock. The
 * door is the one intended way out, and whether its lock still holds is what decides whether a
 * prisoner found outside the cell has escaped or has merely been thrown out of it (see
 * {@link #breached}). Cells built before the door existed carry none of this and behave as they did.
 */
public final class HoldingCell {

    private final UUID prisoner;
    private final UUID sentenceId;
    private final BlockPos anchor;
    private final ResourceLocation dim;
    private final int radius;
    private final long createdGameTime;
    /** Insertion-ordered: restoration replays it in reverse, so the interior refills before the walls go. */
    private final Map<BlockPos, BlockState> replaced;
    /** What this mod actually put in each of those positions, so a changed block can be recognised. */
    private final Map<BlockPos, BlockState> placed;
    /**
     * Whether the pre-0.6.0 case binding has already been attempted for this cell's sentence (0.6.0).
     *
     * <p>The NPC half of {@code JailState.legacyBound}: an arrested villager has no capability to hang
     * a flag on, and the cell is the only thing that outlives a restart and names the sentence. Same
     * rule, same reason -- the inference is a one-time upgrade guess, not a standing policy.
     */
    private boolean legacyBound;
    /** The lower half of the cell door, or null for a cell built before doors existed. */
    @Nullable
    private BlockPos door;
    /** Which way the door faces: the side of the cell it is on, and the side a released prisoner exits to. */
    @Nullable
    private Direction facing;
    /** The lock holding the door shut, in the {@code locks} table. */
    @Nullable
    private UUID lockId;
    /** The padlock entity carrying that lock, hanging on the door's outside face. */
    @Nullable
    private UUID padlock;

    public HoldingCell(UUID prisoner, UUID sentenceId, BlockPos anchor, ResourceLocation dim, int radius,
                       long createdGameTime, Map<BlockPos, BlockState> replaced,
                       Map<BlockPos, BlockState> placed) {
        this.prisoner = prisoner;
        this.sentenceId = sentenceId;
        this.anchor = anchor;
        this.dim = dim;
        this.radius = Math.max(1, radius);
        this.createdGameTime = createdGameTime;
        this.replaced = new LinkedHashMap<>(replaced);
        this.placed = new LinkedHashMap<>(placed);
    }

    /**
     * The same cell with its door recorded.
     *
     * <p>A copy rather than a setter so a cell is complete the moment it enters the roster: the
     * builder only has a door to record once the padlock is hanging, and a record that could be
     * stored half-described is a record that will be.
     */
    public HoldingCell withDoor(@Nullable BlockPos door, @Nullable Direction facing, @Nullable UUID lockId,
                                @Nullable UUID padlock) {
        HoldingCell copy = new HoldingCell(prisoner, sentenceId, anchor, dim, radius, createdGameTime,
                replaced, placed);
        copy.legacyBound = legacyBound;
        copy.door = door == null ? null : door.immutable();
        copy.facing = facing == null ? null : CellBlueprint.horizontal(facing);
        copy.lockId = lockId;
        copy.padlock = padlock;
        return copy;
    }

    public UUID prisoner() {
        return prisoner;
    }

    @Nullable
    public UUID sentenceId() {
        return sentenceId;
    }

    public BlockPos anchor() {
        return anchor;
    }

    public ResourceLocation dim() {
        return dim;
    }

    public int radius() {
        return radius;
    }

    public long createdGameTime() {
        return createdGameTime;
    }

    /** The blocks this cell overwrote, in placement order. */
    public Map<BlockPos, BlockState> replaced() {
        return new LinkedHashMap<>(replaced);
    }

    /** What this cell put in each of those positions. */
    public Map<BlockPos, BlockState> placed() {
        return new LinkedHashMap<>(placed);
    }

    /** True once the legacy case binding has been attempted for this cell's sentence. */
    public boolean isLegacyBound() {
        return legacyBound;
    }

    public void setLegacyBound(boolean legacyBound) {
        this.legacyBound = legacyBound;
    }

    /** The lower half of the door, or null for a cell that has none. */
    @Nullable
    public BlockPos door() {
        return door;
    }

    /** The side the door is on, or null for a cell that has none. */
    @Nullable
    public Direction facing() {
        return facing;
    }

    /** The lock on the door, or null for a cell that has none. */
    @Nullable
    public UUID lockId() {
        return lockId;
    }

    /** The padlock entity on the door, or null for a cell that has none. */
    @Nullable
    public UUID padlock() {
        return padlock;
    }

    /** True for a cell built with a door, a lock and a padlock. */
    public boolean hasDoor() {
        return door != null && lockId != null;
    }

    /**
     * Whether the door's lock no longer holds.
     *
     * <p>Derived from the {@code locks} table rather than stored, so it can never disagree with the
     * padlock: the lock row is gone, it has been detached from its target (a picked padlock comes off
     * and takes the lock with it), or somebody with a key turned it. A cell with no door was never
     * lockable and is never breached; a prisoner found outside one got there some other way, and the
     * containment mode decides what that means.
     */
    public boolean breached(@Nullable CrimeWorldData data) {
        if (lockId == null) {
            return false;
        }
        LockRecord lock = data == null ? null : data.lock(lockId);
        return lock == null || !lock.target().present() || !lock.locked();
    }

    /**
     * Where somebody leaving this cell is stood: two blocks clear of the wall on the door side.
     *
     * <p>A cell without a door keeps the pre-door side, so an old record releases exactly where it
     * always did.
     */
    public BlockPos outsideStand() {
        Direction side = facing == null ? CellBlueprint.DEFAULT_FACING : facing;
        int reach = CellBlueprint.RADIUS + 2;
        return anchor.offset(side.getStepX() * reach, 0, side.getStepZ() * reach);
    }

    /**
     * The same cell narrowed to the positions in {@code positions}.
     *
     * <p>Used by the restoration journal: what is left of a demolition that only partly ran is the
     * original cell minus everything that did come back, and stating it that way means the retry needs
     * nothing the first attempt did not already have. The door, lock and padlock come along, so the
     * retry can still take the padlock down before the door under it goes.
     */
    public HoldingCell retaining(Set<BlockPos> positions) {
        Map<BlockPos, BlockState> keptReplaced = new LinkedHashMap<>();
        Map<BlockPos, BlockState> keptPlaced = new LinkedHashMap<>();
        replaced.forEach((pos, state) -> {
            if (positions.contains(pos)) {
                keptReplaced.put(pos, state);
                BlockState put = placed.get(pos);
                if (put != null) {
                    keptPlaced.put(pos, put);
                }
            }
        });
        HoldingCell narrowed = new HoldingCell(prisoner, sentenceId, anchor, dim, radius, createdGameTime,
                keptReplaced, keptPlaced);
        narrowed.legacyBound = legacyBound;
        narrowed.door = door;
        narrowed.facing = facing;
        narrowed.lockId = lockId;
        narrowed.padlock = padlock;
        return narrowed;
    }

    /** Whether this cell has outlived its cap and should come down regardless of its prisoner. */
    public boolean expired(long now, long lifetimeTicks) {
        return lifetimeTicks > 0L && now - createdGameTime >= lifetimeTicks;
    }

    /**
     * The cell as a {@link JailAnchor}, which is what {@link JailService} teleports to and what
     * {@link JailRegion} confines against.
     *
     * <p>The region is centred one block <em>above</em> the stored anchor, and that offset is
     * load-bearing rather than cosmetic. The stored anchor is where the prisoner stands, but the
     * structure runs from the floor course one below them to the roof three above; a Chebyshev cube
     * centred on their feet would leave the roof outside the region, so {@code ContainmentHandler}
     * would not protect it and a prisoner could simply mine the ceiling out. Centring on the middle of
     * the structure makes the region contain the cell exactly — no more, so an escape by ender pearl is
     * still caught, and no less, so every block of the cage is protected.
     */
    public JailAnchor toAnchor() {
        return new JailAnchor(anchor.above(), dim, radius);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("prisoner", prisoner);
        if (sentenceId != null) {
            tag.putUUID("sentence", sentenceId);
        }
        tag.putInt("x", anchor.getX());
        tag.putInt("y", anchor.getY());
        tag.putInt("z", anchor.getZ());
        tag.putString("dim", dim.toString());
        tag.putInt("radius", radius);
        tag.putLong("created", createdGameTime);
        ListTag blocks = new ListTag();
        replaced.forEach((pos, state) -> {
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", pos.getX());
            entry.putInt("y", pos.getY());
            entry.putInt("z", pos.getZ());
            entry.put("was", NbtUtils.writeBlockState(state));
            BlockState put = placed.get(pos);
            if (put != null) {
                entry.put("put", NbtUtils.writeBlockState(put));
            }
            blocks.add(entry);
        });
        tag.put("blocks", blocks);
        if (legacyBound) {
            tag.putBoolean("legacyBound", true);
        }
        if (door != null) {
            tag.putInt("doorX", door.getX());
            tag.putInt("doorY", door.getY());
            tag.putInt("doorZ", door.getZ());
        }
        if (facing != null) {
            tag.putString("facing", facing.getSerializedName());
        }
        if (lockId != null) {
            tag.putUUID("lock", lockId);
        }
        if (padlock != null) {
            tag.putUUID("padlock", padlock);
        }
        return tag;
    }

    /**
     * Loads a cell, or null when the record is unusable.
     *
     * <p>House rule: one malformed entry is skipped, never a failed world load. A block state that no
     * longer resolves — a mod removed since the arrest — is dropped from the snapshot rather than
     * failing the whole cell, because the remaining blocks still restore and that is strictly better
     * than leaving all of them standing.
     */
    @Nullable
    public static HoldingCell load(CompoundTag tag) {
        if (tag == null || !tag.hasUUID("prisoner")) {
            return null;
        }
        ResourceLocation dim = ResourceLocation.tryParse(tag.getString("dim"));
        if (dim == null) {
            return null;
        }
        Map<BlockPos, BlockState> replaced = new LinkedHashMap<>();
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        ListTag list = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            try {
                BlockPos pos = new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
                replaced.put(pos, NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),
                        entry.getCompound("was")));
                if (entry.contains("put", Tag.TAG_COMPOUND)) {
                    placed.put(pos, NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),
                            entry.getCompound("put")));
                }
            } catch (Throwable ignored) {
                // Unresolvable state: skip this block, keep the rest of the cell restorable.
            }
        }
        HoldingCell cell = new HoldingCell(tag.getUUID("prisoner"),
                tag.hasUUID("sentence") ? tag.getUUID("sentence") : null,
                new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
                dim, Math.max(1, tag.getInt("radius")), tag.getLong("created"), replaced, placed);
        // Absent means the inference has not run, which is right for every pre-0.6.0 cell.
        cell.legacyBound = tag.getBoolean("legacyBound");
        // All absent on a cell built before the door existed, and that is a complete description of
        // such a cell: no door, no lock, nothing to breach.
        if (tag.contains("doorX") && tag.contains("doorY") && tag.contains("doorZ")) {
            cell.door = new BlockPos(tag.getInt("doorX"), tag.getInt("doorY"), tag.getInt("doorZ"));
        }
        if (tag.contains("facing")) {
            Direction parsed = Direction.byName(tag.getString("facing"));
            cell.facing = parsed == null ? null : CellBlueprint.horizontal(parsed);
        }
        cell.lockId = tag.hasUUID("lock") ? tag.getUUID("lock") : null;
        cell.padlock = tag.hasUUID("padlock") ? tag.getUUID("padlock") : null;
        return cell;
    }
}
