package dev.otectus.mcacrime.captivity;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.state.CrimeCapabilities;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * Orchestrates the start of a capture (spec §8.2): toggles → eligibility → guard/combat gate → vulnerability
 * gate → begin the channel. Server-authoritative; it never captures directly — the {@code CaptureTicker}
 * completes the channel into {@link CustodyService#capture}. Player capture requires a vulnerability;
 * ordinary villagers may skip it (config), but guards/combat NPCs never do.
 */
public final class CaptureService {

    /** How long after a {@code /crime surrender} a player remains a capture vulnerability (online ticks). */
    private static final long SURRENDER_VULNERABILITY_TICKS = 1200L;

    private CaptureService() {
    }

    public static boolean tryBeginCapture(ServerPlayer kidnapper, LivingEntity target, RestraintType restraint) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        MinecraftServer server = kidnapper.getServer();
        if (server == null || restraint == RestraintType.NONE) {
            return false;
        }
        boolean targetIsPlayer = target instanceof ServerPlayer;

        if (targetIsPlayer ? !c.enableKidnappingPlayer.get() : !c.enableKidnappingNpc.get()) {
            return fail(kidnapper, "mcacrime.capture.disabled");
        }
        if (!targetIsPlayer && !McaCompat.isMcaVillager(target)) {
            return false; // only players and MCA villagers are valid targets
        }
        if (target.getUUID().equals(kidnapper.getUUID())) {
            return false; // no self-capture
        }
        if (CustodyRegistry.isCaptive(server, target.getUUID())) {
            return fail(kidnapper, "mcacrime.capture.already");
        }
        if (CaptureChannels.has(kidnapper.getUUID())) {
            return false; // one channel per kidnapper
        }

        boolean combatNpc = !targetIsPlayer && McaCompat.isCombatCapable(target);
        boolean relaxedVillager = !targetIsPlayer && !combatNpc && c.villagerCaptureRelaxedVulnerability.get();
        if (!relaxedVillager && !isVulnerable(kidnapper, target, targetIsPlayer, server)) {
            return fail(kidnapper, combatNpc ? "mcacrime.capture.guard_immune" : "mcacrime.capture.not_vulnerable");
        }

        int required = Math.max(1, (int) Math.round(c.captureChannelTicks.get() * channelMultiplier(restraint)));
        CaptureChannels.begin(new CaptureChannel(kidnapper.getUUID(), target.getUUID(), targetIsPlayer, restraint,
                kidnapper.position(), required));
        kidnapper.displayClientMessage(Component.translatable("mcacrime.capture.channeling"), true);
        return true;
    }

    private static boolean isVulnerable(ServerPlayer kidnapper, LivingEntity target, boolean targetIsPlayer,
                                        MinecraftServer server) {
        float maxHealth = Math.max(1.0f, target.getMaxHealth());
        boolean lowHealth = target.getHealth() / maxHealth <= McaCrimeConfig.COMMON.captureLowHealthFraction.get();
        boolean sleeping = targetIsPlayer ? target.isSleeping() : McaCompat.isVillagerSleeping(target);
        boolean surrendered = targetIsPlayer && recentlySurrendered((ServerPlayer) target);
        boolean restrained = CustodyRegistry.isCaptive(server, target.getUUID());
        CaptureVulnerability.Context ctx = new CaptureVulnerability.Context(
                lowHealth, sleeping, false, surrendered, restrained, false, false, false);
        return CaptureVulnerability.meetsAny(ctx);
    }

    private static boolean recentlySurrendered(ServerPlayer player) {
        return CrimeCapabilities.get(player).map(d -> {
            long last = d.getLastSurrenderTick();
            return last > 0L && d.getOnlineTicksLived() - last < SURRENDER_VULNERABILITY_TICKS;
        }).orElse(false);
    }

    private static double channelMultiplier(RestraintType restraint) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        return switch (restraint) {
            case NONE -> 1.0;
            case ROPE -> c.captureChannelMultiplierRope.get();
            case CUFFS -> c.captureChannelMultiplierCuffs.get();
            case LOCKED_CUFFS -> c.captureChannelMultiplierLockedCuffs.get();
        };
    }

    private static boolean fail(ServerPlayer kidnapper, String key) {
        kidnapper.displayClientMessage(Component.translatable(key), true);
        return false;
    }
}
