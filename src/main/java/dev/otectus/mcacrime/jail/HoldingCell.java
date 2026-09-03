package dev.otectus.mcacrime.jail;

import net.minecraft.core.BlockPos;
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
        return new HoldingCell(tag.getUUID("prisoner"),
                tag.hasUUID("sentence") ? tag.getUUID("sentence") : null,
                new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
                dim, Math.max(1, tag.getInt("radius")), tag.getLong("created"), replaced, placed);
    }
}
