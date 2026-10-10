package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.captivity.CustodyOwnerType;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.enforcement.ArrestPhase;
import dev.otectus.mcacrime.enforcement.ArrestPhases;
import dev.otectus.mcacrime.enforcement.ArrestStates;
import dev.otectus.mcacrime.restraint.AppliedRestraint;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import dev.otectus.mcacrime.restraint.RestraintService;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import dev.otectus.mcacrime.state.CrimeAttachments;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Finds, explains and repairs the states that leave a player stuck in or around a jail (0.7.7).
 *
 * <p>Each problem is a named {@link Finding} with a precise reason, so {@code /crime repair} can say what
 * is wrong before it changes anything and an operator never has to read world data by hand or delete a
 * save. Every repair is idempotent -- running it twice changes nothing the second time -- and none of
 * them forgives a crime: a jail that cannot be reached releases with nothing served, an orphaned record
 * is closed, a stale flag is cleared. The {@link Finding#automatic() automatic} subset also runs at login,
 * which is how a record orphaned by a crash or an interrupted escort is reconciled without anybody asking.
 */
public final class JailRecovery {

    /** One problem, and whether this class can put it right. */
    public enum Finding {
        /** The sentence names a jail whose dimension is gone and there is no fallback jail. */
        JAIL_UNREACHABLE(true, true),
        /** Escaped, but back inside the jail and still in lawful custody: the sentence is paused for nothing. */
        STALE_ESCAPE(true, true),
        /** Every case the sentence was for has been pardoned or paid; nothing is left to hold them for. */
        RESOLVED_ELSEWHERE(true, true),
        /** The clock reached zero but the release never happened. */
        OVERDUE(true, true),
        /** A holding cell still stands for somebody no longer jailed or in custody. */
        ORPHAN_HOLDING_CELL(true, true),
        /** Lawful custody with no sentence and no arrest under way to explain it. */
        ORPHAN_CUSTODY(true, true),
        /** The arrest record says JAILED for somebody who is not. */
        STALE_ARREST_PHASE(true, true),
        /** Restraints held for a custody that no longer exists. */
        ORPHAN_RESTRAINT(true, true),
        /** Escaped from a built cell that has come down: only a recapture or a surrender resumes it. */
        AWAITING_RECAPTURE(false, false);

        private final boolean repairable;
        private final boolean automatic;

        Finding(boolean repairable, boolean automatic) {
            this.repairable = repairable;
            this.automatic = automatic;
        }

        public boolean repairable() {
            return repairable;
        }

        /** Safe to repair at login without an operator. */
        public boolean automatic() {
            return automatic;
        }

        /** {@code mcacrime.repair.finding.<name>}, the operator-facing explanation. */
        public String messageKey() {
            return "mcacrime.repair.finding." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private JailRecovery() {
    }

    /** What is wrong with this player's jail, custody and arrest state, in a stable order. */
    public static Set<Finding> inspect(ServerPlayer player) {
        Set<Finding> found = new LinkedHashSet<>();
        MinecraftServer server = player == null ? null : player.getServer();
        PlayerCrimeData data = player == null ? null : CrimeAttachments.get(player);
        if (server == null || data == null) {
            return found;
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        JailState jail = data.getJail();
        CustodyRecord custody = world.getCustody(player.getUUID());
        if (jail != null) {
            if (!jail.hasValidAnchor() || JailService.resolveLevel(server, jail.getJailDim()) == null
                    && JailService.configFallbackAnchor().isEmpty()) {
                found.add(Finding.JAIL_UNREACHABLE);
            }
            if (jail.isEscaped()) {
                if (JailConfine.backInLawfulCustody(server, player, jail)) {
                    found.add(Finding.STALE_ESCAPE);
                } else if (jail.isTemporaryCell() && HoldingCellService.existingFor(server, player.getUUID()) == null) {
                    found.add(Finding.AWAITING_RECAPTURE);
                }
            }
            if (JailService.resolvedElsewhere(server, player, jail) != null) {
                found.add(Finding.RESOLVED_ELSEWHERE);
            }
            if (jail.getRemainingOnlineTicks() <= 0L && !jail.isEscaped()) {
                found.add(Finding.OVERDUE);
            }
        } else {
            if (HoldingCellService.existingFor(server, player.getUUID()) != null && custody == null) {
                found.add(Finding.ORPHAN_HOLDING_CELL);
            }
            ArrestPhase phase = ArrestStates.phaseOf(player);
            if (phase == ArrestPhase.JAILED) {
                found.add(Finding.STALE_ARREST_PHASE);
            }
            if (custody != null && custody.isLawful() && orphanedLawfulCustody(custody, phase)) {
                found.add(Finding.ORPHAN_CUSTODY);
            }
        }
        if (!orphanRestraints(world, player.getUUID(), custody).isEmpty()) {
            found.add(Finding.ORPHAN_RESTRAINT);
        }
        return found;
    }

    /** Repairs what can be repaired, and returns what was found (repaired or not). */
    public static Set<Finding> repair(ServerPlayer player, boolean automaticOnly) {
        Set<Finding> found = inspect(player);
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null || !ServerMutationGate.allows(server)) {
            return found;
        }
        PlayerCrimeData data = CrimeAttachments.get(player);
        for (Finding finding : found) {
            if (!finding.repairable() || automaticOnly && !finding.automatic()) {
                continue;
            }
            JailState jail = data == null ? null : data.getJail();
            switch (finding) {
                case JAIL_UNREACHABLE -> JailService.release(player, ReleaseReason.INVALID_JAIL);
                case STALE_ESCAPE -> {
                    if (jail != null) {
                        jail.setEscaped(false);
                        dev.otectus.mcacrime.network.CrimeNetwork.sendSelfStatus(player);
                    }
                }
                case RESOLVED_ELSEWHERE -> {
                    ReleaseReason reason = jail == null ? null : JailService.resolvedElsewhere(server, player, jail);
                    if (reason != null) {
                        JailService.release(player, reason);
                    }
                }
                case OVERDUE -> JailService.release(player, ReleaseReason.SENTENCE_SERVED);
                case ORPHAN_HOLDING_CELL -> HoldingCellService.releaseAndDismantle(server, player);
                case ORPHAN_CUSTODY -> CustodyService.release(server, player.getUUID(), CustodyReleaseReason.ADMIN);
                case STALE_ARREST_PHASE -> ArrestStates.clear(player);
                case ORPHAN_RESTRAINT -> {
                    CrimeWorldData world = CrimeWorldData.get(server);
                    for (UUID custodyId : orphanRestraints(world, player.getUUID(), world.getCustody(player.getUUID()))) {
                        RestraintService.releaseFor(server, player.getUUID(), custodyId);
                    }
                }
                default -> { }
            }
        }
        return found;
    }

    /**
     * A guard's or a jail's custody explains itself only while an arrest is walking the player somewhere
     * or a sentence holds them; with neither (the caller checked the sentence), nothing is holding them
     * for anything. Every other lawful owner -- a bounty hunter, an authority -- legitimately exists
     * before any arrest and is left alone.
     */
    static boolean orphanedLawfulCustody(CustodyRecord custody, ArrestPhase phase) {
        if (custody.getOwner() == null) {
            return false;
        }
        CustodyOwnerType type = custody.getOwner().type();
        return (type == CustodyOwnerType.JAIL || type == CustodyOwnerType.GUARD) && !ArrestPhases.inProgress(phase);
    }

    /** The custody ids this subject's worn restraints were applied for that no longer exist. */
    static List<UUID> orphanRestraints(CrimeWorldData world, UUID subject, CustodyRecord custody) {
        List<UUID> orphaned = new ArrayList<>();
        PhysicalRestraintState state = world.physicalRestraint(subject);
        if (state == null) {
            return orphaned;
        }
        UUID live = custody == null ? null : custody.getCustodyId();
        for (RestraintSlot slot : RestraintSlot.values()) {
            AppliedRestraint worn = state.slot(slot).orElse(null);
            if (worn != null && worn.custodyId() != null && !worn.custodyId().equals(live)
                    && !orphaned.contains(worn.custodyId())) {
                orphaned.add(worn.custodyId());
            }
        }
        return orphaned;
    }
}
