package dev.otectus.mcacrime.client.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import dev.otectus.mcacrime.network.ActionMenuS2CPacket;
import dev.otectus.mcacrime.network.ActionProgressS2CPacket;
import dev.otectus.mcacrime.network.BailQuoteS2CPacket;
import dev.otectus.mcacrime.network.BandBulkSyncS2CPacket;
import dev.otectus.mcacrime.network.BandSyncS2CPacket;
import dev.otectus.mcacrime.network.CaptiveStatusS2CPacket;
import dev.otectus.mcacrime.network.CaseLedgerS2CPacket;
import dev.otectus.mcacrime.network.CrimeClientPayloadRouter;
import dev.otectus.mcacrime.network.CriminalJobSyncS2CPacket;
import dev.otectus.mcacrime.network.GuardChallengeS2CPacket;
import dev.otectus.mcacrime.network.LockpickBeginS2CPacket;
import dev.otectus.mcacrime.network.LockpickPhaseS2CPacket;
import dev.otectus.mcacrime.network.LockpickResultS2CPacket;
import dev.otectus.mcacrime.network.MaskSelectionS2CPacket;
import dev.otectus.mcacrime.network.PhysicalStateDeltaS2CPacket;
import dev.otectus.mcacrime.network.PhysicalStateRemoveS2CPacket;
import dev.otectus.mcacrime.network.PhysicalStateS2CPacket;
import dev.otectus.mcacrime.network.RestraintRigSyncS2CPacket;
import dev.otectus.mcacrime.network.SelfStatusS2CPacket;
import dev.otectus.mcacrime.network.VillageSecurityS2CPacket;
import dev.otectus.mcacrime.network.WeaponPolicyS2CPacket;

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
    public void onWeaponPolicy(WeaponPolicyS2CPacket payload) {
        CrimeClientHandlers.onWeaponPolicy(payload);
    }

    @Override
    public void onCriminalJob(CriminalJobSyncS2CPacket payload) {
        CrimeClientHandlers.onCriminalJob(payload);
    }

    @Override
    public void onBailQuote(BailQuoteS2CPacket payload) {
        CrimeClientHandlers.onBailQuote(payload);
    }

    @Override
    public void onMaskSelection(MaskSelectionS2CPacket payload) {
        CrimeClientHandlers.onMaskSelection(payload);
    }

    @Override
    public void onRestraintRig(RestraintRigSyncS2CPacket payload) {
        CrimeClientHandlers.onRestraintRig(payload);
    }

    @Override
    public void onVillageSecurity(VillageSecurityS2CPacket payload) {
        CrimeClientHandlers.onVillageSecurity(payload);
    }

    @Override
    public void onPhysicalState(PhysicalStateS2CPacket payload) {
        CrimeClientHandlers.onPhysicalState(payload);
    }

    @Override
    public void onPhysicalStateDelta(PhysicalStateDeltaS2CPacket payload) {
        CrimeClientHandlers.onPhysicalStateDelta(payload);
    }

    @Override
    public void onPhysicalStateRemove(PhysicalStateRemoveS2CPacket payload) {
        CrimeClientHandlers.onPhysicalStateRemoved(payload);
    }

    @Override
    public void onLockpickBegin(LockpickBeginS2CPacket payload) {
        CrimeClientHandlers.onLockpickBegin(payload);
    }

    @Override
    public void onLockpickPhase(LockpickPhaseS2CPacket payload) {
        CrimeClientHandlers.onLockpickPhase(payload);
    }

    @Override
    public void onLockpickResult(LockpickResultS2CPacket payload) {
        CrimeClientHandlers.onLockpickResult(payload);
    }

    @Override
    public void onFriskSnapshot(dev.otectus.mcacrime.network.FriskSnapshotS2CPacket payload) {
        dev.otectus.mcacrime.client.ClientFriskData.accept(payload);
    }
}
