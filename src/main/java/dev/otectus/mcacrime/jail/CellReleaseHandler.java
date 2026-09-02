package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.api.event.PlayerReleasedFromJailEvent;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.enforcement.GuardChallengeService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * What happens the moment a sentence ends: the prisoner comes out, the cell comes down, and the law
 * stops looking for them.
 *
 * <p>Listening on the mod's own {@link PlayerReleasedFromJailEvent} rather than calling this from
 * {@code JailService.release} keeps every release path covered by construction — served, bailed,
 * pardoned, admin, captivity cap, and a jail whose dimension vanished all post that event, and each of
 * them leaves a cell standing and a custody record open if nothing tidies up.
 *
 * <p>The stand-down is the other half of a long-standing gap: {@code GuardChallengeService.standDown}
 * was only ever called when a player lost their legal basis, so finishing a sentence left guards with
 * a stale encounter and, before this release, a stale refusal.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CellReleaseHandler {

    private CellReleaseHandler() {
    }

    @SubscribeEvent
    public static void onReleased(PlayerReleasedFromJailEvent event) {
        ServerPlayer player = event.getPlayer();
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null) {
            return;
        }
        // Order matters: this moves the player clear of the cell before taking it down. Restoring the
        // floor and roof courses around somebody still standing inside would bury them in their own
        // release.
        HoldingCellService.releaseAndDismantle(server, player);
        CustodyService.release(server, player.getUUID(), custodyReasonFor(event.getReason()));
        // The arrest is over. This has to be explicit: standDown deliberately leaves a JAILED phase
        // alone, because it is also called one line after a successful escort sets it, and clearing
        // there would drop a prisoner who had just started their sentence back to "not being arrested".
        // Release is the other end of that same phase, and the only place entitled to end it.
        dev.otectus.mcacrime.enforcement.ArrestStates.clear(player);
        GuardChallengeService.standDown(player);
    }

    /**
     * Maps a jail release to the custody release that accompanies it.
     *
     * <p>{@code SENTENCE_SERVED} has existed on the custody side since the table was written and was
     * unreachable, because nothing ever put a player into lawful custody to begin with.
     */
    private static CustodyReleaseReason custodyReasonFor(ReleaseReason reason) {
        return switch (reason) {
            case SENTENCE_SERVED -> CustodyReleaseReason.SENTENCE_SERVED;
            case CAPTIVITY_CAP -> CustodyReleaseReason.CAPTIVITY_CAP;
            default -> CustodyReleaseReason.ADMIN;
        };
    }
}
