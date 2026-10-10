package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.enforcement.ArrestStates;
import dev.otectus.mcacrime.enforcement.GuardChallengeService;
import dev.otectus.mcacrime.enforcement.GuardEnforcement;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;

/**
 * What happens the moment a sentence ends: the prisoner comes out, the cell comes down, and the law
 * stops looking for them.
 *
 * <p>Until 0.7.7 this listened to {@code PlayerReleasedFromJailEvent}. That covered every release path
 * by construction, but an event bus stops at the first listener that throws, so a failure anywhere
 * before this one -- another mod's, or a companion's -- left a cell standing and a custody record open
 * around a prisoner the ledger said was free. {@code JailService.release} now calls {@link #cleanUp}
 * directly, before the event is posted, and each step here is guarded on its own: one that throws is
 * logged and every other step still runs. Each step is idempotent, so a release that repeats any of it
 * changes nothing.
 *
 * <p>The stand-down is the other half of a long-standing gap: {@code GuardChallengeService.standDown}
 * was only ever called when a player lost their legal basis, so finishing a sentence left guards with
 * a stale encounter and, before this release, a stale refusal.
 */
public final class CellReleaseHandler {

    private CellReleaseHandler() {
    }

    /** Ends the custody instance a sentence was. Called once per release, by {@code JailService}. */
    public static void cleanUp(@Nullable MinecraftServer server, ServerPlayer player, ReleaseReason reason) {
        if (server == null || player == null) {
            return;
        }
        // Order matters: this moves the player clear of the cell before taking it down. Restoring the
        // floor and roof courses around somebody still standing inside would bury them in their own
        // release.
        step(player, "holding cell", () -> HoldingCellService.releaseAndDismantle(server, player));
        // Custody carries its own restraints, tether, cell reservation and activity claims with it.
        step(player, "custody", () -> CustodyService.release(server, player.getUUID(), custodyReasonFor(reason)));
        step(player, "escort", () -> dev.otectus.mcacrime.enforcement.EscortService.forget(player.getUUID()));
        step(player, "cell reservation", () -> dev.otectus.mcacrime.facility.CrimeFacilityService.releaseFor(server,
                player.getUUID()));
        // Clears an open challenge, a refusal, nearby guards' targets on this player and their holds.
        step(player, "stand-down", () -> GuardChallengeService.standDown(player));
        step(player, "alerts", () -> GuardEnforcement.forget(player.getUUID()));
        // The arrest is over. This has to be explicit: standDown deliberately leaves a JAILED phase
        // alone, because it is also called one line after a successful escort sets it, and clearing
        // there would drop a prisoner who had just started their sentence back to "not being arrested".
        // Release is the other end of that same phase, and the only place entitled to end it. An
        // operator's release, or a jail that vanished, forgives nothing, so the guards are held off for a
        // while instead of putting the player straight back into the custody that was just ended.
        step(player, "arrest", () -> {
            int grace = SentenceDisposition.effectOf(reason) == SentenceDisposition.Effect.UNBIND
                    ? McaCrimeConfig.COMMON.adminReleaseRecoveryTicks.get() : 0;
            ArrestStates.clear(player);
            if (grace > 0) {
                ArrestStates.recover(player, grace);
            }
        });
    }

    private static void step(ServerPlayer player, String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            McaCrime.LOGGER.error("MCA: Crime could not finish the {} step of releasing {}; the other steps still ran",
                    what, player.getGameProfile().getName(), failure);
        }
    }

    /**
     * Maps a jail release to the custody release that accompanies it.
     *
     * <p>{@code SENTENCE_SERVED} has existed on the custody side since the table was written and was
     * unreachable, because nothing ever put a player into lawful custody to begin with.
     */
    static CustodyReleaseReason custodyReasonFor(ReleaseReason reason) {
        return switch (reason) {
            case SENTENCE_SERVED -> CustodyReleaseReason.SENTENCE_SERVED;
            case CAPTIVITY_CAP -> CustodyReleaseReason.CAPTIVITY_CAP;
            default -> CustodyReleaseReason.ADMIN;
        };
    }
}
