package dev.otectus.mcacrime.captivity;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import dev.otectus.mcacrime.action.ActionAvailability;
import dev.otectus.mcacrime.action.ActionSession;
import dev.otectus.mcacrime.action.ActionSessionManager;
import dev.otectus.mcacrime.action.CancelReason;
import dev.otectus.mcacrime.action.CrimeActionIds;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.state.CrimeCapabilities;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

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
        return tryBeginCapture(kidnapper, target, restraint, UUID.randomUUID());
    }

    /**
     * Begins a capture channel under {@code nonce}'s authority.
     *
     * <p>The channel takes an {@link ActionSession} lease before it exists, which is the whole point:
     * a capture and an action are two names for the same claim on the same two entities, and while
     * they held separate locks a player could be mugged and restrained at once, or restrained by two
     * captors whose eligibility checks both passed a tick apart. {@link ActionSessionManager#begin} is
     * the atomic version of the conflict test {@link #evaluate} performs, so it is the one that decides.
     */
    public static boolean tryBeginCapture(ServerPlayer kidnapper, LivingEntity target, RestraintType restraint,
                                          UUID nonce) {
        ActionAvailability availability = evaluate(kidnapper, target, restraint);
        if (!availability.isAvailable()) return fail(kidnapper, availability.reason());
        // Asked before the channel starts rather than at the commit: a progress bar that always ends
        // in a refusal is a worse answer than the refusal on its own.
        if (!ServerMutationGate.allows(kidnapper.getServer())) return fail(kidnapper, "mcacrime.readonly");
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        boolean targetIsPlayer = target instanceof ServerPlayer;

        int required = Math.max(1, (int) Math.round(c.captureChannelTicks.get() * channelMultiplier(restraint)));
        CaptureChannel channel = new CaptureChannel(kidnapper.getUUID(), target.getUUID(), targetIsPlayer,
                restraint, kidnapper.position(), required);
        ActionSession session = new ActionSession(channel.barId(), nonce, CrimeActionIds.RESTRAIN,
                kidnapper.getUUID(), target.getUUID(), kidnapper.level().dimension().location(),
                kidnapper.position(), kidnapper.level().getGameTime(), required);
        if (!ActionSessionManager.begin(session)) return fail(kidnapper, "mcacrime.action.conflict");
        channel.attach(session);
        if (!CaptureChannels.beginIfFree(channel)) {
            ActionSessionManager.cancel(session, CancelReason.CONFLICT);
            return fail(kidnapper, "mcacrime.action.conflict");
        }
        // No start message: the session opens the HUD channel bar under the channel's own id, and the
        // bar's label already reads "Restraining...". Two of them said the same thing twice.
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

    private static int unlawfulCaptiveCount(MinecraftServer server, ServerPlayer captor) {
        int count = 0;
        for (CustodyRecord record : CustodyRegistry.byOwner(server, captor.getUUID())) {
            if (!record.isLawful()) count++;
        }
        return count;
    }
}
