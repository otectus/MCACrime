package dev.otectus.mcacrime.client.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import dev.otectus.mcacrime.network.ActionMenuS2CPacket;
import dev.otectus.mcacrime.network.ActionProgressS2CPacket;
import dev.otectus.mcacrime.network.BandBulkSyncS2CPacket;
import dev.otectus.mcacrime.network.BandSyncS2CPacket;
import dev.otectus.mcacrime.network.CaptiveStatusS2CPacket;
import dev.otectus.mcacrime.network.CaseLedgerS2CPacket;
import dev.otectus.mcacrime.network.CrimeClientPayloadRouter;
import dev.otectus.mcacrime.network.GuardChallengeS2CPacket;
import dev.otectus.mcacrime.network.RestraintBulkSyncS2CPacket;
import dev.otectus.mcacrime.network.RestraintSyncS2CPacket;
import dev.otectus.mcacrime.network.SelfStatusS2CPacket;

/**
 * The client half of the payload seam (spec §9.4): installed by {@code McaCrimeClient}, and the only
 * place where a class a dedicated server loads leads to one it must not.
 *
 * <p>Nothing but delegation. The behaviour still lives in {@link CrimeClientHandlers}, which is where
 * it lived under the Forge build's {@code DistExecutor} indirection; this replaces the indirection,
 * not the handlers.
 */
public final class CrimeClientPayloadHandler implements CrimeClientPayloadRouter.Handler {

    @Override
    public void onSelfStatus(SelfStatusS2CPacket payload) {
        CrimeClientHandlers.onSelfStatus(payload);
    }

    @Override
    public void onBandSync(BandSyncS2CPacket payload) {
        CrimeClientHandlers.onBandSync(payload);
    }

    @Override
    public void onBandBulkSync(BandBulkSyncS2CPacket payload) {
        CrimeClientHandlers.onBandBulk(payload);
    }

    @Override
    public void onCaptiveStatus(CaptiveStatusS2CPacket payload) {
        CrimeClientHandlers.onCaptiveStatus(payload);
    }

    @Override
    public void onActionMenu(ActionMenuS2CPacket payload) {
        CrimeClientHandlers.onActionMenu(payload);
    }

    @Override
    public void onActionProgress(ActionProgressS2CPacket payload) {
        CrimeClientHandlers.onActionProgress(payload);
    }

    @Override
    public void onGuardChallenge(GuardChallengeS2CPacket payload) {
        CrimeClientHandlers.onGuardChallenge(payload);
    }

    @Override
    public void onCaseLedger(CaseLedgerS2CPacket payload) {
        CrimeClientHandlers.onCaseLedger(payload);
    }

    @Override
    public void onRestraintSync(RestraintSyncS2CPacket payload) {
        CrimeClientHandlers.onRestraint(payload);
    }

    @Override
    public void onRestraintBulkSync(RestraintBulkSyncS2CPacket payload) {
        CrimeClientHandlers.onRestraintBulk(payload);
    }
}
