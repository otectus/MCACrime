package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.TownsteadBridge;
import dev.otectus.mcacrime.compat.TownsteadBuildingView;
import dev.otectus.mcacrime.compat.TownsteadCapability;
import dev.otectus.mcacrime.compat.TownsteadQueryResult;
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
        return buildChecked(level, near, prisoner, sentenceId).cell();
    }

    /**
     * Why no cell was built, when the caller has somewhere to say it.
     *
     * <p>A refusal used to be a bare null, which reads as "the terrain was busy" and is exactly wrong
     * for the two cases this release adds. A site inside a settlement building is a deliberate
     * exclusion an operator configured; a site MCA: Crime could not ask about is a check that did not
     * happen. Both have to be distinguishable from ordinary dense terrain, because the fix is different
     * in each case and because building anyway would put a cage in somebody's workshop.
     */
    public enum Refusal {
        /** A site was found. */
        NONE,
        /** Nothing within the search radius was clear enough to build on. */
        NO_CLEAR_SITE,
        /** Every candidate overlapped a registered settlement building. */
        SETTLEMENT_BUILDING,
        /**
         * The settlement could not be asked, so no automatic build is allowed here.
         *
         * <p>Refusing rather than proceeding is the point (§8.5). "I could not check" is not
         * "there is nothing there", and a cage raised on that assumption lands in a village's granary.
         */
        UNANSWERABLE,
        /** The caller passed nothing to build for. */
        NO_REQUEST,
        /** {@code buildHoldingCell} is off, or the store is read-only, so nothing may be built. */
        DISABLED,
        /** The holding-cell roster is at its ceiling; a cage nothing points at can never come down. */
        ROSTER_FULL
    }

    /** A build attempt: the cell, or the reason there is not one. */
    public record Outcome(@Nullable HoldingCell cell, Refusal refusal) {

        public boolean built() {
            return cell != null;
        }

        /** One line for an operator or a debug log; never empty. */
        public String describe() {
            return switch (refusal) {
                case NONE -> "a holding cell was built";
                case NO_CLEAR_SITE -> "no clear site within the holding-cell search radius";
                case SETTLEMENT_BUILDING -> "every candidate site overlapped a registered settlement "
                        + "building, and townstead.excludeWorksitesFromTemporaryCells is on";
                case UNANSWERABLE -> "the settlement's buildings could not be read here, so no cell was "
                        + "built automatically; assign a jail with /crime assignjail or /crime facility";
                case NO_REQUEST -> "nothing to build for";
                case DISABLED -> "temporary holding cells are switched off, or the crime store is "
                        + "read-only this session";
                case ROSTER_FULL -> "the holding-cell roster is full";
            };
        }
    }

    /** The same as {@link #build}, keeping the reason a site was refused. */
    public static Outcome buildChecked(ServerLevel level, BlockPos near, UUID prisoner, UUID sentenceId) {
        if (level == null || near == null || prisoner == null) {
            return new Outcome(null, Refusal.NO_REQUEST);
        }
        Site site = findSite(level, near, McaCrimeConfig.COMMON.holdingCellSearchRadius.get());
        BlockPos anchor = site.anchor();
        if (anchor == null) {
            return new Outcome(null, site.refusal());
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
            return new Outcome(null, Refusal.NO_CLEAR_SITE);
        }
        return new Outcome(new HoldingCell(prisoner, sentenceId, anchor, level.dimension().location(),
                CellBlueprint.RADIUS, level.getGameTime(), replaced, placed), Refusal.NONE);
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
    /** A chosen site, or the reason there is not one. */
    public record Site(@Nullable BlockPos anchor, Refusal refusal) {
    }

    private static Site findSite(ServerLevel level, BlockPos near, int searchRadius) {
        // The strongest refusal seen wins the report. A search that found only settlement buildings
        // should say so rather than blaming the terrain, and one that could not ask at all outranks
        // both -- that is the case an operator has to act on.
        Refusal worst = Refusal.NO_CLEAR_SITE;
        for (int ring = 2; ring <= searchRadius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue; // covered by a previous, tighter ring
                    }
                    BlockPos candidate = surfaceAnchor(level, near.getX() + dx, near.getZ() + dz, near.getY());
                    if (candidate == null || !siteIsClear(level, candidate)) {
                        continue;
                    }
                    Refusal settlement = settlementRefusal(level, candidate);
                    if (settlement == Refusal.NONE) {
                        return new Site(candidate, Refusal.NONE);
                    }
                    if (settlement == Refusal.UNANSWERABLE) {
                        // No point continuing: the same question would be unanswerable at every other
                        // candidate in this village, and each attempt costs another read.
                        return new Site(null, Refusal.UNANSWERABLE);
                    }
                    worst = Refusal.SETTLEMENT_BUILDING;
                }
            }
        }
        return new Site(null, worst);
    }

    /**
     * Whether the settlement allows a cell here.
     *
     * <p>Three answers, and the third is the one that matters. With building enumeration bound, the
     * whole footprint is tested against every recognised building that overlaps it. With only the
     * single-position facade, the five probe points are the best question that can be asked. With
     * neither — no settlement mod, or the switch off — the answer is {@link Refusal#NONE} and behaviour
     * is exactly what it was before this release.
     */
    private static Refusal settlementRefusal(ServerLevel level, BlockPos anchor) {
        boolean exclude;
        try {
            exclude = McaCrimeConfig.COMMON.townsteadExcludeWorksitesFromTemporaryCells.get();
        } catch (Throwable t) {
            exclude = false; // no config loaded; behave as this code did before the switch existed
        }
        if (!exclude) {
            return Refusal.NONE;
        }
        boolean enumeration = TownsteadBridge.has(TownsteadCapability.BUILDING_ENUMERATION);
        boolean facade = TownsteadBridge.has(TownsteadCapability.READ_BUILDING);
        if (!enumeration && !facade) {
            return Refusal.NONE;
        }
        for (BlockPos probe : probePoints(anchor)) {
            if (enumeration) {
                TownsteadQueryResult<java.util.List<TownsteadBuildingView>> result =
                        TownsteadBridge.buildingsAt(level, probe);
                java.util.List<TownsteadBuildingView> here = result.orElse(null);
                if (here == null) {
                    return Refusal.UNANSWERABLE;
                }
                if (overlapsFootprint(here, anchor)) {
                    return Refusal.SETTLEMENT_BUILDING;
                }
                continue;
            }
            TownsteadQueryResult<TownsteadBuildingView> single = TownsteadBridge.buildingAt(level, probe);
            if (single.isFailed()) {
                return Refusal.UNANSWERABLE;
            }
            if (single.isAvailable()) {
                return Refusal.SETTLEMENT_BUILDING;
            }
        }
        return Refusal.NONE;
    }

    /** The footprint's four corners and its centre — the cheapest probe that still covers the box. */
    private static List<BlockPos> probePoints(BlockPos anchor) {
        int r = CellBlueprint.RADIUS;
        int y = anchor.getY();
        return List.of(anchor,
                new BlockPos(anchor.getX() - r, y, anchor.getZ() - r),
                new BlockPos(anchor.getX() - r, y, anchor.getZ() + r),
                new BlockPos(anchor.getX() + r, y, anchor.getZ() - r),
                new BlockPos(anchor.getX() + r, y, anchor.getZ() + r));
    }

    /**
     * Pure: whether the whole blueprint footprint at {@code anchor} overlaps any of these buildings.
     *
     * <p>The box, not the probe point. A building whose corner clips one block of the cell wall is
     * still a building the cell is being dug into, and testing only the point that found it would let
     * exactly that through.
     */
    public static boolean overlapsFootprint(List<TownsteadBuildingView> buildings, BlockPos anchor) {
        if (buildings == null || buildings.isEmpty() || anchor == null) {
            return false;
        }
        int r = CellBlueprint.RADIUS;
        int minX = anchor.getX() - r;
        int maxX = anchor.getX() + r;
        int minZ = anchor.getZ() - r;
        int maxZ = anchor.getZ() + r;
        int minY = anchor.getY() + CellBlueprint.lowestOffset();
        int maxY = anchor.getY() + CellBlueprint.highestOffset();
        for (TownsteadBuildingView building : buildings) {
            if (building != null && building.intersects(minX, minY, minZ, maxX, maxY, maxZ)) {
                return true;
            }
        }
        return false;
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
            if (!level.isLoaded(pos) || level.isOutsideBuildHeight(pos)
                    || !level.getWorldBorder().isWithinBounds(pos)) {
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
        // Placement runs synchronously on the server thread, so no entity can walk into a site
        // between this check and construction. Include the interior and a body-width edge margin.
        return level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                CellBlueprint.bounds(anchor).inflate(0.35D, 0D, 0.35D),
                net.minecraft.world.entity.Entity::isAlive).isEmpty();
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
