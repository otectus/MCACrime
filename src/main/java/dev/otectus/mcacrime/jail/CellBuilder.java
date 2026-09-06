package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds and removes {@link HoldingCell}s — the physical half of an arrest.
 *
 * <p>This is the only code in the mod that places blocks, and it exists for one reason: before it,
 * surrendering to a guard could not put anybody anywhere. {@code JailService.jail} refuses outright
 * when no anchor can be resolved, anchors come only from {@code /crime assignjail} or a config
 * coordinate that ships disabled, so on any server where an operator had not hand-built a jail an
 * arrest had nowhere to go and surrender quietly became a Heat discount.
 *
 * <h2>Rules it holds to</h2>
 * <ul>
 *   <li><b>It only builds where nothing was.</b> The footprint must be air, replaceable foliage, or
 *       plain terrain. One block that could have been placed by a player, one block entity, one fluid,
 *       and the site is rejected — a jail that eats a player's house is worse than an arrest that
 *       fails to find anywhere to put them.</li>
 *   <li><b>It only builds in loaded chunks.</b> Force-loading terrain to raise a cage would make one
 *       player's arrest a server-wide cost.</li>
 *   <li><b>Everything it replaces is recorded.</b> Release restores the exact prior {@link BlockState}
 *       of every block, so the world goes back to what it was rather than keeping a cage standing in a
 *       field. The snapshot is persisted, so a restart mid-sentence cannot orphan a cell.</li>
 * </ul>
 */
public final class CellBuilder {

    private CellBuilder() {
    }

    /**
     * Builds a cell near {@code near}, or null when no site qualifies.
     *
     * <p>Null is an ordinary outcome rather than an error: dense terrain, a village that is wall-to-wall
     * buildings, or unloaded surroundings all produce it. The caller has to be able to say "there is
     * nowhere to hold you" instead of forcing a cage into somebody's living room.
     */
    @Nullable
    public static HoldingCell build(ServerLevel level, BlockPos near, UUID prisoner, UUID sentenceId) {
        if (level == null || near == null || prisoner == null) {
            return null;
        }
        BlockPos anchor = findSite(level, near, McaCrimeConfig.COMMON.holdingCellSearchRadius.get());
        if (anchor == null) {
            return null;
        }
        Map<BlockPos, BlockState> replaced = new LinkedHashMap<>();
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        try {
            for (CellBlueprint.Placement placement : CellBlueprint.placements()) {
                BlockPos pos = anchor.offset(placement.dx(), placement.dy(), placement.dz());
                BlockState previous = level.getBlockState(pos);
                BlockState target = stateFor(placement.role());
                if (previous == target) {
                    continue; // nothing to change, so nothing to undo
                }
                replaced.put(pos, previous);
                level.setBlockAndUpdate(pos, target);
                // Read back rather than trusting the target: iron bars recompute their connection
                // shape on placement, so the state now in the world is not the one handed to setBlock,
                // and demolition compares against what is actually there.
                placed.put(pos, level.getBlockState(pos));
            }
        } catch (Throwable t) {
            // Half a cell is worse than none: undo what went down and report failure.
            McaCrime.LOGGER.warn("MCA: Crime failed to build a holding cell at {}; rolling back", anchor, t);
            restoreBlocks(level, replaced, Map.of());
            return null;
        }
        return new HoldingCell(prisoner, sentenceId, anchor, level.dimension().location(),
                CellBlueprint.RADIUS, level.getGameTime(), replaced, placed);
    }

    /**
     * Puts back everything a cell replaced, and says what it could not reach.
     *
     * <p>Idempotent: a second call finds the blocks already right. The return value is the point of the
     * 0.6.0 shape — a position in an unloaded chunk is skipped rather than restored, and until this
     * method could say so, the caller dropped the cell's record anyway and left the cage standing
     * forever with nothing in the world pointing at it.
     *
     * @return the positions still holding this cell's blocks, empty when everything came back
     */
    public static Set<BlockPos> demolish(ServerLevel level, HoldingCell cell) {
        if (level == null || cell == null) {
            return Set.of();
        }
        return restoreBlocks(level, cell.replaced(), cell.placed());
    }

    /**
     * Restores a snapshot in reverse placement order, so the interior is filled back in before the
     * walls holding it open come down.
     *
     * <p>A position is only restored when it still holds the block this mod put there. If somebody
     * broke a bar and filled the gap with their own, that is now their block and the cell has no
     * business overwriting it — the same rule that governs where a cell may be built, applied on the
     * way back out. An empty {@code placed} map means "restore unconditionally", which is only used by
     * the rollback path, where every block was placed moments ago by this same call.
     */
    private static Set<BlockPos> restoreBlocks(ServerLevel level, Map<BlockPos, BlockState> replaced,
                                               Map<BlockPos, BlockState> placed) {
        List<Map.Entry<BlockPos, BlockState>> entries = List.copyOf(replaced.entrySet());
        int skipped = 0;
        // Only genuinely unfinished work goes in here. A position somebody else has since built on is
        // deliberately left alone and is *not* unresolved: it is settled, by the rule that says this mod
        // never overwrites another player's block. Retrying it forever would be the opposite of that.
        Set<BlockPos> unresolved = new LinkedHashSet<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Map.Entry<BlockPos, BlockState> entry = entries.get(i);
            try {
                if (!level.isLoaded(entry.getKey())) {
                    unresolved.add(entry.getKey());
                    continue;
                }
                BlockState expected = placed.get(entry.getKey());
                if (expected != null && !level.getBlockState(entry.getKey()).equals(expected)) {
                    skipped++;
                    continue; // somebody changed this block; it is theirs now
                }
                level.setBlockAndUpdate(entry.getKey(), entry.getValue());
            } catch (Throwable t) {
                // One block failing to restore must not leave the rest of the cell standing, but it is
                // still one of this cell's blocks in the world, so it is retried later.
                McaCrime.LOGGER.debug("MCA: Crime could not restore {}; continuing", entry.getKey(), t);
                unresolved.add(entry.getKey());
            }
        }
        if (skipped > 0) {
            McaCrime.LOGGER.debug("MCA: Crime left {} changed block(s) in place while removing a cell", skipped);
        }
        return unresolved;
    }

    private static BlockState stateFor(CellBlueprint.Role role) {
        return switch (role) {
            case FLOOR -> Blocks.STONE_BRICKS.defaultBlockState();
            case BARS -> Blocks.IRON_BARS.defaultBlockState();
            case LIGHT -> Blocks.GLOWSTONE.defaultBlockState();
            case INTERIOR -> Blocks.AIR.defaultBlockState();
        };
    }

    /**
     * Searches outward from {@code near} for ground a cell can stand on.
     *
     * <p>Rings of increasing radius rather than one exhaustive box, so the cell lands as close to the
     * arrest as the terrain allows and the search costs only as much as the area is built up. The
     * height comes from the surface of each column rather than from the arrest, so an arrest on a roof
     * does not produce a cell hanging in the air.
     */
    @Nullable
    private static BlockPos findSite(ServerLevel level, BlockPos near, int searchRadius) {
        for (int ring = 2; ring <= searchRadius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue; // covered by a previous, tighter ring
                    }
                    BlockPos candidate = surfaceAnchor(level, near.getX() + dx, near.getZ() + dz, near.getY());
                    if (candidate != null && siteIsClear(level, candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /**
     * The anchor for a cell in this column: one above the first solid ground near the reference height,
     * or null when the column is unloaded or has no usable surface. The vertical search is bounded so a
     * cell is never sited far below the arrest, which would read as the prisoner being swallowed.
     */
    @Nullable
    private static BlockPos surfaceAnchor(ServerLevel level, int x, int z, int referenceY) {
        BlockPos probe = new BlockPos(x, referenceY, z);
        if (!level.isLoaded(probe)) {
            return null;
        }
        for (int y = referenceY + 3; y >= referenceY - 6; y--) {
            BlockPos ground = new BlockPos(x, y, z);
            if (level.isOutsideBuildHeight(ground) || level.isOutsideBuildHeight(ground.above())) {
                continue;
            }
            if (level.getBlockState(ground).isSolidRender(level, ground)
                    && level.getBlockState(ground.above()).isAir()) {
                return ground.above();
            }
        }
        return null;
    }

    /**
     * Whether the whole blueprint footprint may be built over.
     *
     * <p>Deliberately conservative. Anything that is not air, replaceable foliage, or naturally
     * generated ground is treated as somebody's work — including any block entity at all, which covers
     * chests, signs, beds and every modded container in one check rather than by keeping a list that
     * would be wrong the moment a new mod is installed.
     */
    private static boolean siteIsClear(ServerLevel level, BlockPos anchor) {
        for (CellBlueprint.Placement placement : CellBlueprint.placements()) {
            BlockPos pos = anchor.offset(placement.dx(), placement.dy(), placement.dz());
            if (!level.isLoaded(pos) || level.isOutsideBuildHeight(pos)) {
                return false;
            }
            BlockState state = level.getBlockState(pos);
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                return false; // never build into water or lava: the cell would flood or burn
            }
            if (level.getBlockEntity(pos) != null) {
                return false;
            }
            if (state.isAir() || state.canBeReplaced()) {
                continue;
            }
            // A solid block is acceptable only underfoot, where the floor course goes, and only when it
            // is ordinary terrain rather than something that had to be placed by hand.
            if (placement.role() == CellBlueprint.Role.FLOOR && isPlainTerrain(state)) {
                continue;
            }
            return false;
        }
        return true;
    }

    /** True for the handful of block types a cell floor may be laid over. */
    private static boolean isPlainTerrain(BlockState state) {
        return state.is(BlockTags.DIRT)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.SAND)
                || state.is(BlockTags.TERRACOTTA)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.SNOW_BLOCK);
    }
}
