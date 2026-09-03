package dev.otectus.mcacrime.captivity;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.jail.JailRegion;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Soft-tether for a PLAYER kidnapping captive (spec §8), the throttled (~1/s) counterpart of {@code
 * JailConfine} — but deliberately inverted: straying past the tether is a legitimate <em>escape</em>
 * (never a crime, §8.1), not a jailbreak. With {@code captiveCanEscapeByDistance} on, a strayed captive is
 * released ({@code ESCAPED}); with it off, they are pulled back to the hold point. NPC captives are held by
 * a vanilla leash (persisted on the entity) and are not tethered here. Reuses {@link JailRegion} geometry.
 */
public final class CustodyConfine {

    private CustodyConfine() {
    }

    public static void tick(ServerPlayer captive) {
        MinecraftServer server = captive.getServer();
        if (server == null) {
            return;
        }
        CustodyRecord record = CrimeWorldData.get(server).getCustody(captive.getUUID());
        if (record == null || record.isLawful() || !record.isCaptivePlayer() || !record.hasValidHold()) {
            return;
        }
        ServerLevel level = JailService.resolveLevel(server, record.getHoldDim());
        if (level == null) {
            return; // dimension gone — cap / reconcile handles release
        }
        ResourceLocation posDim = captive.level().dimension().location();
        int radius = (int) Math.ceil(McaCrimeConfig.COMMON.captiveTetherBlocks.get());
        boolean inRange = JailRegion.contains(record.getHoldPos(), radius, record.getHoldDim(),
                captive.blockPosition(), posDim);
        if (inRange) {
            return;
        }
        if (McaCrimeConfig.COMMON.captiveCanEscapeByDistance.get()) {
            CustodyService.release(server, captive.getUUID(), CustodyReleaseReason.ESCAPED); // no crime (§8.1)
            return;
        }
        BlockPos hold = record.getHoldPos();
        captive.teleportTo(level, hold.getX() + 0.5, hold.getY(), hold.getZ() + 0.5,
                captive.getYRot(), captive.getXRot());
    }
}
