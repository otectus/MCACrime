package dev.otectus.mcacrime.captivity;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.ActionAvailability;
import dev.otectus.mcacrime.action.ActionSessionManager;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.state.CrimeAttachments;
import dev.otectus.mcacrime.state.PlayerCrimeData;
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
        ActionAvailability availability = evaluate(kidnapper, target, restraint);
        if (!availability.isAvailable()) return fail(kidnapper, availability.reason());
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        boolean targetIsPlayer = target instanceof ServerPlayer;

        int required = Math.max(1, (int) Math.round(c.captureChannelTicks.get() * channelMultiplier(restraint)));
        if (!CaptureChannels.beginIfFree(new CaptureChannel(kidnapper.getUUID(), target.getUUID(), targetIsPlayer,
                restraint, kidnapper.position(), required))) return fail(kidnapper, "mcacrime.action.conflict");
        // No start message: CaptureTicker opens the HUD channel bar on its first tick, and the bar's
        // own label already reads "Restraining...". Two of them said the same thing twice.
        return true;
    }

    public static ActionAvailability evaluate(ServerPlayer kidnapper, LivingEntity target, RestraintType restraint) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        MinecraftServer server = kidnapper.getServer();
        if (server == null || restraint == RestraintType.NONE) return ActionAvailability.blocked("mcacrime.capture.need_restraint");
        boolean targetIsPlayer = target instanceof ServerPlayer;
        if (targetIsPlayer ? !c.enableKidnappingPlayer.get() : !c.enableKidnappingNpc.get())
            return ActionAvailability.hidden("mcacrime.capture.disabled");
        if (!targetIsPlayer && !McaCompat.isMcaVillager(target)) return ActionAvailability.hidden("mcacrime.capture.invalid");
        if (target.getUUID().equals(kidnapper.getUUID())) return ActionAvailability.hidden("mcacrime.capture.invalid");
        if (CustodyRegistry.isCaptive(server, target.getUUID())) return ActionAvailability.blocked("mcacrime.capture.already");
        if (unlawfulCaptiveCount(server, kidnapper) >= c.maxUnlawfulCaptivesPerCaptor.get())
            return ActionAvailability.blocked("mcacrime.capture.capacity");
        if (CaptureChannels.has(kidnapper.getUUID()) || CaptureChannels.targets(target.getUUID())
                || ActionSessionManager.targetLocked(target.getUUID()))
            return ActionAvailability.blocked("mcacrime.action.conflict");
        boolean combatNpc = !targetIsPlayer && McaCompat.isCombatCapable(target);
        boolean relaxedVillager = !targetIsPlayer && !combatNpc && c.villagerCaptureRelaxedVulnerability.get();
        if (!relaxedVillager && !isVulnerable(kidnapper, target, targetIsPlayer, server))
            return ActionAvailability.blocked(combatNpc ? "mcacrime.capture.guard_immune" : "mcacrime.capture.not_vulnerable");
        return ActionAvailability.available();
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
        PlayerCrimeData data = CrimeAttachments.get(player);
        long last = data.getLastSurrenderTick();
        return last > 0L && data.getOnlineTicksLived() - last < SURRENDER_VULNERABILITY_TICKS;
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

    private static int unlawfulCaptiveCount(MinecraftServer server, ServerPlayer captor) {
        int count = 0;
        for (CustodyRecord record : CustodyRegistry.byOwner(server, captor.getUUID())) {
            if (!record.isLawful()) count++;
        }
        return count;
    }
}
