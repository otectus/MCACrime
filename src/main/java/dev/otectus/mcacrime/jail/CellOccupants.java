package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.detect.EntitySelectors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Geometry shared by cell intake and the repair of NPCs caught in older generated cells. */
public final class CellOccupants {
    private CellOccupants() { }

    public static List<BlockPos> perimeter(BlockPos centre, int radius) {
        List<BlockPos> positions = new ArrayList<>();
        for (int d = -radius; d <= radius; d++) {
            positions.add(centre.offset(d, 0, -radius));
            positions.add(centre.offset(d, 0, radius));
            if (Math.abs(d) < radius) {
                positions.add(centre.offset(-radius, 0, d));
                positions.add(centre.offset(radius, 0, d));
            }
        }
        return positions;
    }

    public static boolean fits(ServerLevel level, Entity entity, BlockPos feet) {
        if (!SafeCustodyDestination.isSafeStand(level, feet)) return false;
        var body = entity.getBoundingBox().move(Vec3.atBottomCenterOf(feet).subtract(entity.position()));
        return level.noCollision(entity, body)
                && level.getEntitiesOfClass(LivingEntity.class, body,
                other -> other != entity && other.isAlive()).isEmpty();
    }

    public static void freeBystanders(ServerLevel level, HoldingCell cell) {
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, CellBlueprint.bounds(cell.anchor()),
                npc -> npc.isAlive() && !npc.getUUID().equals(cell.prisoner())
                        && (npc instanceof net.minecraft.world.entity.npc.AbstractVillager
                        || McaCompat.isMcaVillager(npc) || EntitySelectors.isResponder(npc)))) {
            BlockPos outside = outsideStand(level, cell, entity);
            if (outside == null) continue; // Retry on a later sweep, never guess a hazardous destination.
            entity.stopRiding();
            McaCompat.holdPosition(entity);
            entity.teleportTo(outside.getX() + 0.5D, outside.getY(), outside.getZ() + 0.5D);
        }
    }

    public static BlockPos outsideStand(ServerLevel level, HoldingCell cell, Entity entity) {
        for (int radius = CellBlueprint.RADIUS + 1; radius <= CellBlueprint.RADIUS + 6; radius++) {
            var candidates = perimeter(cell.anchor(), radius);
            candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(entity.blockPosition())));
            for (BlockPos candidate : candidates) {
                for (int dy : new int[] {0, 1, -1, 2, -2, 3, -3}) {
                    BlockPos feet = candidate.offset(0, dy, 0);
                    var body = entity.getBoundingBox().move(Vec3.atBottomCenterOf(feet).subtract(entity.position()));
                    if (!CellBlueprint.bounds(cell.anchor()).intersects(body) && fits(level, entity, feet)) return feet;
                }
            }
        }
        return null;
    }
}
