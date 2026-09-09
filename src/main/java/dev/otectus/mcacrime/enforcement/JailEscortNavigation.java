package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.ai.CrimeNavigation;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.jail.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Routes the escort to a reachable intake stand; only the prisoner is moved through the bars. */
public final class JailEscortNavigation {
    private static final Map<UUID, Route> ROUTES = new HashMap<>();
    public record Progress(boolean arrived, boolean stuck) { }
    private static final class Route {
        UUID guard;
        JailAnchor jail;
        BlockPos target;
        BlockPos segment;
        Vec3 guardPosition;
        Vec3 prisonerPosition;
        int idleScans;
        int candidateOffset;
        long nextNavigation;
    }
    private JailEscortNavigation() { }

    /** Following a detour is progress even when it temporarily leads away from the cell. */
    public static int idleScans(double guardMovedSqr, double prisonerMovedSqr, int previous) {
        return guardMovedSqr >= 0.04D || prisonerMovedSqr >= 0.04D ? 0 : Math.max(0, previous) + 1;
    }

    public static boolean usefulSegment(double startDistanceSqr, double endDistanceSqr, int nodes) {
        return nodes > 1 && endDistanceSqr + 1D < startDistanceSqr;
    }

    public static Progress advance(ServerLevel level, Entity guard, Entity prisoner, JailAnchor anchor) {
        if (!anchor.dim().equals(level.dimension().location())) return new Progress(false, true);
        if (!(guard instanceof Mob mob)) return new Progress(false, true);
        Route route = ROUTES.computeIfAbsent(prisoner.getUUID(), id -> new Route());
        if (!guard.getUUID().equals(route.guard) || !anchor.equals(route.jail)) {
            route = new Route();
            route.guard = guard.getUUID();
            route.jail = anchor;
            ROUTES.put(prisoner.getUUID(), route);
        }
        if (route.guardPosition != null) route.idleScans = idleScans(
                guard.position().distanceToSqr(route.guardPosition),
                prisoner.position().distanceToSqr(route.prisonerPosition), route.idleScans);
        route.guardPosition = guard.position();
        route.prisonerPosition = prisoner.position();
        if (route.target != null && guard.distanceToSqr(Vec3.atBottomCenterOf(route.target)) <= 2.25D
                && prisoner.distanceToSqr(guard) <= 16D) {
            McaCompat.stopModNavigation(guard);
            return new Progress(true, false);
        }
        double leash = McaCrimeConfig.COMMON.escortLeashBlocks.get();
        double tether = McaCrimeConfig.COMMON.escortTetherBlocks.get();
        double waitDistance = Math.max(1D, Math.min(leash + 1D, tether - 1D));
        if (guard.distanceToSqr(prisoner) > waitDistance * waitDistance) {
            // A guard should wait for the person on the lead before its own route breaks the tether.
            McaCompat.holdPosition(guard);
            return new Progress(false, EscortService.isStuck(route.idleScans,
                    McaCrimeConfig.COMMON.escortStuckScans.get()));
        }
        long now = level.getGameTime();
        if (now >= route.nextNavigation) {
            route.nextNavigation = now + Math.max(1, McaCrimeConfig.COMMON.escortNavigationIntervalTicks.get());
            // Retain a working path. Re-plan when it ends early or stops making progress.
            var current = mob.getNavigation().getPath();
            if (route.target == null || mob.getNavigation().isDone() || route.idleScans >= 3
                    || current == null || !current.getTarget().equals(route.segment)) {
                findRoute(level, mob, prisoner, anchor, route);
            }
        }
        return new Progress(false, EscortService.isStuck(route.idleScans,
                McaCrimeConfig.COMMON.escortStuckScans.get()));
    }

    private static void findRoute(ServerLevel level, Mob guard, Entity prisoner, JailAnchor anchor, Route route) {
        HoldingCell cell = HoldingCellService.existingFor(level.getServer(), prisoner.getUUID());
        List<BlockPos> columns = new ArrayList<>();
        if (cell != null && cell.dim().equals(anchor.dim())) {
            columns.addAll(CellOccupants.perimeter(cell.anchor(), CellBlueprint.RADIUS + 1));
            columns.addAll(CellOccupants.perimeter(cell.anchor(), CellBlueprint.RADIUS + 2));
        } else {
            columns.add(anchor.pos());
            for (int r = 1; r <= Math.min(4, anchor.radius()); r++)
                columns.addAll(CellOccupants.perimeter(anchor.pos(), r));
        }
        columns.sort(Comparator.comparingDouble(pos -> pos.distSqr(guard.blockPosition())));
        List<BlockPos> stands = new ArrayList<>();
        for (BlockPos column : columns) {
            for (int dy : new int[] {0, -1, 1, -2, 2}) {
                BlockPos feet = column.offset(0, dy, 0);
                if (cell == null && !JailRegion.contains(anchor.pos(), anchor.radius(), anchor.dim(),
                        feet, level.dimension().location())) continue;
                if (CellOccupants.fits(level, guard, feet)) {
                    stands.add(feet);
                    break;
                }
            }
        }
        // Bounded per re-plan. Rotate through the other entrances after a failed batch.
        for (int i = 0; i < Math.min(4, stands.size()); i++) {
            BlockPos target = stands.get((route.candidateOffset + i) % stands.size());
            var path = guard.getNavigation().createPath(target, 0, 48);
            BlockPos segment = target;
            // A distant assigned jail is walked in bounded segments. A partial path is never arrival.
            if (path != null && !path.canReach() && path.getEndNode() != null
                    && usefulSegment(guard.blockPosition().distSqr(target),
                    path.getEndNode().asBlockPos().distSqr(target), path.getNodeCount())) {
                segment = path.getEndNode().asBlockPos();
                if (!CellOccupants.fits(level, guard, segment)) continue;
                path = guard.getNavigation().createPath(segment, 0, 48);
            }
            if (CrimeNavigation.start(guard, path, segment, McaCrimeConfig.COMMON.escortWalkSpeed.get(),
                    McaCompat.isMcaVillager(guard))) {
                route.target = target;
                route.segment = segment;
                route.candidateOffset = 0;
                return;
            }
        }
        route.candidateOffset = stands.isEmpty() ? 0 : (route.candidateOffset + 4) % stands.size();
        route.target = null;
    }

    public static void forget(UUID prisoner) { ROUTES.remove(prisoner); }
    public static void clear() { ROUTES.clear(); }
}
