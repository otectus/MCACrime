package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.captivity.CaptureCommitResult;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.CrimeDetector;
import dev.otectus.mcacrime.detect.WitnessResult;
import dev.otectus.mcacrime.detention.DetentionKind;
import dev.otectus.mcacrime.detention.DetentionRecord;
import dev.otectus.mcacrime.detention.DetentionService;
import dev.otectus.mcacrime.detention.ExecutionAuthorization;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.tether.TetherKind;
import dev.otectus.mcacrime.tether.TetherRecord;
import dev.otectus.mcacrime.tether.TetherService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * The one bridge between what is on a body and what the law thinks of it (0.7.5 M4.8, §1.4, §14.1).
 *
 * <p>Two independent state machines meet here and nowhere else. {@code restraint/}, {@code tether/}
 * and {@code detention/} know what is physically true and nothing about cases or sentences;
 * {@code captivity/}, {@code enforcement/}, {@code jail/}, {@code ransom/} and {@code bounty/} know
 * the law and nothing about wrists. Before this class existed a right-click that put cuffs on
 * somebody changed the physical table and filed nothing at all — no arrest, no kidnapping, no
 * incident — which is exactly the gap the specification's §14.1 table describes.
 *
 * <h2>The rules this class is shaped by</h2>
 *
 * <ul>
 *   <li><b>Removing one restraint is not a release.</b> {@code RemovalService} empties a slot and
 *       stops; whether that was an escape is decided here, against what is <em>left</em>.</li>
 *   <li><b>Opening a cell door is not a pardon.</b> A physical event never resolves a case.</li>
 *   <li><b>Escaping a kidnapper is not a crime.</b> No jailbreak, no Heat, no charge against the
 *       victim, ever.</li>
 *   <li><b>One transition per episode.</b> The first involuntary restraint begins the custody and
 *       files one kidnapping; the second, third and the anchor update the physical state only.</li>
 * </ul>
 *
 * <p>The decisions themselves are pure static functions returning enum rows, one per line of the
 * §14.1 table, so the whole table can be asserted in a unit test with no world, no entity and no
 * server — and so a future change to one row cannot quietly change another.
 */
public final class CustodyTransitionService {

    /** What applying a restraint means legally. One value per applicable §14.1 row. */
    public enum Application {
        /** A self-applied restraint, a demonstration, an administrative test: no legal effect. */
        NONE,
        /** The first involuntary restraint on an eligible subject: unlawful custody and one kidnapping. */
        BEGIN_UNLAWFUL_CUSTODY,
        /** Another slot, hood or anchor during an existing custody: physical state only. */
        PHYSICAL_ONLY,
        /** A guard or a bounty claim: the existing lawful authority has already decided everything. */
        LAWFUL_AUTHORITY
    }

    /** What taking a restraint off means legally. One value per applicable §14.1 row. */
    public enum Removal {
        /** Something is still holding them. Physical state only; no custody change. */
        PHYSICAL_ONLY,
        /** A caregiver or an operator taking gear off with authority: not an escape and not a pardon. */
        AUTHORISED_EQUIPMENT_CHANGE,
        /** Nothing is holding them and the custody was unlawful: release, and no penalty at all. */
        ESCAPE_FROM_UNLAWFUL_CUSTODY,
        /** Nothing is holding them and the confinement was lawful: release, and exactly one jailbreak. */
        DEFEATED_LAWFUL_CONFINEMENT
    }

    private CustodyTransitionService() {
    }

    // --- the table, as pure functions ----------------------------------------------------------------

    /**
     * What an application means, from the four facts that decide it.
     *
     * <p>{@code lawfulAuthority} is the server's own judgement — an arrest in progress, a standing
     * bounty, an operator command — never something an actor can claim in a packet. §14.1's last
     * paragraph is explicit about that, and it is why this parameter is a boolean the caller has
     * already computed from server state rather than a field on a request.
     */
    public static Application classifyApplication(boolean selfApplied, boolean lawfulAuthority,
                                                  boolean alreadyInCustody, boolean subjectEligible) {
        if (selfApplied) {
            return Application.NONE; // a self-applied hood is not a kidnapping
        }
        if (lawfulAuthority) {
            return Application.LAWFUL_AUTHORITY;
        }
        if (alreadyInCustody) {
            return Application.PHYSICAL_ONLY; // no duplicate kidnapping, no reset of the custody age
        }
        return subjectEligible ? Application.BEGIN_UNLAWFUL_CUSTODY : Application.NONE;
    }

    /**
     * What a removal means, from the four facts that decide it.
     *
     * <p>The ordering is the design. An authorised removal is checked <em>before</em> "is anything
     * left", because a caregiver taking a prisoner's cuffs off to treat them has not helped them
     * escape even though nothing is holding them at that instant.
     */
    public static Removal classifyRemoval(boolean authorisedActor, boolean anyClaimRemains,
                                          boolean inCustody, boolean lawfulCustody) {
        if (authorisedActor) {
            return Removal.AUTHORISED_EQUIPMENT_CHANGE;
        }
        if (anyClaimRemains || !inCustody) {
            return Removal.PHYSICAL_ONLY;
        }
        return lawfulCustody ? Removal.DEFEATED_LAWFUL_CONFINEMENT
                : Removal.ESCAPE_FROM_UNLAWFUL_CUSTODY;
    }

    /**
     * Whether a subject still has any physical claim on them.
     *
     * <p>Pure, and the input to {@link #classifyRemoval}: gear in any slot, a tether nobody has
     * suspended, an escort, or a device. Taking the cuffs off somebody who is still chained to a
     * fence has not freed them, and calling that an escape is how one prisoner files four jailbreaks.
     */
    public static boolean anyClaimRemains(boolean wearsAnything, boolean tethered, boolean escorted,
                                          boolean detained) {
        return wearsAnything || tethered || escorted || detained;
    }

    // --- capital rows (§3.19, M4.10) --------------------------------------------------------------------

    /** The three capital rows the working interpretation adds to the §14.1 table. */
    public enum Capital {
        /** Not condemned at all. */
        NOT_CAPITAL,
        /** A capital binding exists; confinement rules are otherwise unchanged. */
        CONDEMNED_IN_CUSTODY,
        /** A device claim is held for this subject and the delay window is running. */
        PENDING_EXECUTION,
        /** Confirmed death. */
        EXECUTED
    }

    /**
     * Which of the three capital rows a subject is on.
     *
     * <p>Pure. Only {@link Capital#PENDING_EXECUTION} authorises a device to act, and every clearing
     * rule in §3.19 — pardon, commutation, rescue, escape, guard death, device destruction, chunk
     * unload — moves the subject back to {@link Capital#CONDEMNED_IN_CUSTODY} by removing the
     * authorisation, never to freedom and never onward to {@link Capital#EXECUTED}.
     */
    public static Capital capitalState(boolean condemned, boolean authorisationLive, boolean dead) {
        if (dead) {
            return condemned ? Capital.EXECUTED : Capital.NOT_CAPITAL;
        }
        if (!condemned) {
            return Capital.NOT_CAPITAL;
        }
        return authorisationLive ? Capital.PENDING_EXECUTION : Capital.CONDEMNED_IN_CUSTODY;
    }

    // --- the live transitions ------------------------------------------------------------------------------

    /**
     * A restraint just went on somebody. File whatever the law makes of it, once.
     *
     * <p>Called from the application path after the commit, never before: a refused application has
     * changed nothing and must file nothing.
     */
    public static Application onRestraintApplied(@Nullable ServerPlayer actor,
                                                 @Nullable LivingEntity subject,
                                                 AppliedRestraint.ApplicationContext context) {
        if (subject == null || subject.level().isClientSide()) {
            return Application.NONE;
        }
        MinecraftServer server = subject.getServer();
        if (server == null) {
            return Application.NONE;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        boolean selfApplied = actor == null || actor.getUUID().equals(subject.getUUID())
                || context == AppliedRestraint.ApplicationContext.VOLUNTARY;
        boolean lawful = context == AppliedRestraint.ApplicationContext.LAWFUL
                || context == AppliedRestraint.ApplicationContext.ADMINISTRATIVE;
        boolean alreadyHeld = data.getCustody(subject.getUUID()) != null;
        boolean eligible = RestraintService.restrainable(subject);

        Application row = classifyApplication(selfApplied, lawful, alreadyHeld, eligible);
        if (row != Application.BEGIN_UNLAWFUL_CUSTODY || actor == null) {
            return row;
        }
        // One capture, which is also the one kidnapping incident: CustodyService.capture commits the
        // crime, posts the event and refuses a second capture of the same subject, so the "no
        // duplicate kidnapping" half of the row is structural rather than a flag kept here.
        CaptureCommitResult committed = CustodyService.capture(actor, subject);
        return committed.ok() ? Application.BEGIN_UNLAWFUL_CUSTODY : Application.PHYSICAL_ONLY;
    }

    /**
     * A restraint just came off. Decide whether anything legal follows it.
     *
     * @param authorisedActor whether the remover had authority over this subject's gear — a guard, a
     *                        caregiver, an operator tool — which makes it an equipment change
     */
    public static Removal onRestraintRemoved(@Nullable MinecraftServer server,
                                             @Nullable LivingEntity subject,
                                             boolean authorisedActor) {
        if (server == null || subject == null) {
            return Removal.PHYSICAL_ONLY;
        }
        return settle(server, subject, authorisedActor, CustodyReleaseReason.ESCAPED);
    }

    /**
     * The subject got free by their own effort: struggled through, picked the lock, broke the device.
     *
     * <p>The same settlement as a removal with no authority behind it, named separately because the
     * escape path reaches it from {@code EscapeService} rather than from {@code RemovalService} and
     * the two should not have to know about each other.
     */
    public static Removal onEscaped(@Nullable MinecraftServer server, @Nullable LivingEntity subject) {
        return onRestraintRemoved(server, subject, false);
    }

    /**
     * Works out what is left and acts on it, exactly once.
     *
     * <p>The jailbreak is filed only when the lawful custody record was <em>actually</em> removed by
     * this call. That is the deduplication, and it is structural rather than a marker: a second path
     * reaching here in the same tick finds no custody to release and therefore files nothing.
     */
    private static Removal settle(MinecraftServer server, LivingEntity subject, boolean authorisedActor,
                                  CustodyReleaseReason reason) {
        if (Boolean.TRUE.equals(SETTLING.get())) {
            // Releasing a custody takes that custody's gear off, which arrives back here. The outer
            // call has already decided; the inner one has nothing left to decide and must not decide
            // it again.
            return Removal.PHYSICAL_ONLY;
        }
        SETTLING.set(Boolean.TRUE);
        try {
            return settleOnce(server, subject, authorisedActor, reason);
        } finally {
            SETTLING.remove();
        }
    }

    /** Re-entrancy guard for {@link #settle}; see the comment there. */
    private static final ThreadLocal<Boolean> SETTLING = new ThreadLocal<>();

    private static Removal settleOnce(MinecraftServer server, LivingEntity subject,
                                      boolean authorisedActor, CustodyReleaseReason reason) {
        CrimeWorldData data = CrimeWorldData.get(server);
        UUID subjectId = subject.getUUID();
        PhysicalRestraintState state = data.physicalRestraint(subjectId);
        boolean wears = state != null && state.restrained();
        boolean tethered = TetherService.forSubject(data, subjectId).stream()
                .anyMatch(tether -> !tether.suspended() && tether.kind() != TetherKind.ESCORT);
        boolean escorted = TetherService.forSubject(data, subjectId).stream()
                .anyMatch(tether -> tether.kind() == TetherKind.ESCORT);
        boolean detained = DetentionService.forSubject(data, subjectId).isPresent();
        CustodyRecord custody = data.getCustody(subjectId);

        Removal row = classifyRemoval(authorisedActor,
                anyClaimRemains(wears, tethered, escorted, detained),
                custody != null, custody != null && custody.isLawful());
        switch (row) {
            case PHYSICAL_ONLY, AUTHORISED_EQUIPMENT_CHANGE -> {
                return row;
            }
            case ESCAPE_FROM_UNLAWFUL_CUSTODY -> {
                // Escaping a kidnapper costs the victim nothing: no charge, no Heat, no jailbreak.
                CustodyService.release(server, subjectId, CustodyReleaseReason.ESCAPED);
                // Post-commit, and it says plainly that no jailbreak was filed, so a companion mod
                // cannot treat a kidnapping victim's escape as a crime (M6.4).
                PhysicalApiEvents.escaped(subject, false, false);
                return row;
            }
            case DEFEATED_LAWFUL_CONFINEMENT -> {
                boolean stillHeld = data.getCustody(subjectId) != null;
                CustodyService.release(server, subjectId, CustodyReleaseReason.ESCAPED);
                boolean filed = false;
                if (stillHeld && data.getCustody(subjectId) == null) {
                    filed = fileJailbreak(server, subject);
                }
                // The sentence survives the escape. Physical freedom is not a pardon: the term is kept,
                // marked escaped, and it is recapture or surrender -- never walking back into the cell's
                // radius -- that resumes serving it. This is the seam the deleted CuffEscapeService used
                // to own; without it an escaped prisoner's own confinement tick would file a second
                // jailbreak for the escape this branch has already filed.
                if (subject instanceof ServerPlayer escapee) {
                    dev.otectus.mcacrime.jail.JailService.escapeCuffs(escapee);
                }
                PhysicalApiEvents.escaped(subject, true, filed);
                return row;
            }
            default -> {
                return Removal.PHYSICAL_ONLY;
            }
        }
    }

    /**
     * The one jailbreak this episode gets.
     *
     * <p>Witnessed by the authority itself with no villager named, exactly as {@code JailConfine}
     * files the equivalent for a prisoner who walks out of a cell: nobody has to have seen it for the
     * law to know.
     */
    private static boolean fileJailbreak(MinecraftServer server, LivingEntity subject) {
        if (!(subject instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return false; // a villager's escape is the NPC custody path's business, not a player's charge
        }
        CrimeDetector.commitDirect(player, CrimeIds.JAILBREAK, null, level, WitnessResult.official(),
                "jailbreak");
        return true;
    }

    // --- devices ---------------------------------------------------------------------------------------------

    /**
     * A device closed on somebody.
     *
     * <p>Deliberately <b>not</b> a custody transition on its own. Being locked in a pillory by a
     * passing player is a physical fact; whether it is an arrest is decided by the same authority
     * test as a restraint, and a device that filed its own kidnapping would let a guard's pillory
     * charge the guard.
     */
    public static Application onDetentionBegan(@Nullable MinecraftServer server,
                                               @Nullable LivingEntity subject, DetentionKind kind,
                                               @Nullable Player actor) {
        if (server == null || subject == null) {
            return Application.NONE;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        boolean selfApplied = actor == null || actor.getUUID().equals(subject.getUUID());
        boolean alreadyHeld = data.getCustody(subject.getUUID()) != null;
        // A device is never a lawful authority by itself; an arrest that used one already holds the
        // subject, which is the alreadyInCustody branch.
        return classifyApplication(selfApplied, false, alreadyHeld,
                RestraintService.restrainable(subject));
    }

    /**
     * A device let somebody go.
     *
     * <p>Opening a pillory is not a pardon, so nothing legal happens here on its own — but it may
     * have been the last thing holding a lawfully confined prisoner, so the settlement runs, and it
     * clears any execution order armed at that device.
     */
    public static Removal onDetentionEnded(@Nullable MinecraftServer server,
                                           @Nullable DetentionRecord record,
                                           DetentionService.ReleaseReason reason,
                                           @Nullable Player actor) {
        if (server == null || record == null) {
            return Removal.PHYSICAL_ONLY;
        }
        ExecutionAuthorization.clear(record.subject(), switch (reason) {
            case BROKE_OUT -> ExecutionAuthorization.ClearReason.ESCAPED;
            case DEVICE_GONE -> ExecutionAuthorization.ClearReason.DEVICE_DESTROYED;
            case OCCUPANT_DIED -> ExecutionAuthorization.ClearReason.CARRIED_OUT;
            default -> ExecutionAuthorization.ClearReason.RESCUED;
        });
        Entity subject = TetherService.find(server, record.subject());
        if (!(subject instanceof LivingEntity living)) {
            return Removal.PHYSICAL_ONLY;
        }
        // An operator or a guard opening the device is an authorised equipment change; the occupant
        // forcing it open is not.
        boolean authorised = reason != DetentionService.ReleaseReason.BROKE_OUT
                && (actor == null || !actor.getUUID().equals(record.subject()));
        return settle(server, living, authorised, CustodyReleaseReason.ESCAPED);
    }

    /**
     * The execution happened, and the subject is confirmed dead.
     *
     * <p>Everything downstream of a capital death — closing the sentence under
     * {@code ReleaseReason.EXECUTED}, resolving the warrant, evaluating the bounty, dropping or
     * banking possessions — belongs to M6.7 and is deliberately not invented here. What M4 owns is
     * the physical half: the order is spent, the custody ends once, and the death itself has already
     * gone through the ordinary damage pipeline with the actor attributed.
     */
    public static Capital onExecuted(@Nullable MinecraftServer server, @Nullable LivingEntity subject,
                                     @Nullable Entity actor) {
        if (server == null || subject == null) {
            return Capital.NOT_CAPITAL;
        }
        UUID subjectId = subject.getUUID();
        ExecutionAuthorization.clear(subjectId, ExecutionAuthorization.ClearReason.CARRIED_OUT);
        CrimeWorldData data = CrimeWorldData.get(server);
        // The legal settlement first, while the custody record still names the sentence: closing the
        // custody before reading it would leave the cases open and the warrant standing (M6.8).
        dev.otectus.mcacrime.ledger.CapitalDeathOutcome.onExecuted(server, subject,
                actor == null ? null : actor.getUUID(), subject.blockPosition());
        if (data.getCustody(subjectId) != null) {
            CustodyService.release(server, subjectId, CustodyReleaseReason.CAPTIVE_DIED);
        }
        // The walk is over, whatever state it was in.
        dev.otectus.mcacrime.enforcement.CondemnedEscortService.cancel(server, subjectId,
                "the sentence was carried out");
        clearPhysicalClaims(server, subjectId, null);
        return Capital.EXECUTED;
    }

    // --- custody ending from the legal side --------------------------------------------------------------------

    /**
     * A custody ended: ransom, pardon, sentence served, admin, rescue.
     *
     * <p>Clears the physical claims that custody <em>owned</em> and nothing else. An escort exists
     * only because somebody was being walked somewhere, so it ends; a chain somebody paid for and a
     * device somebody locked are independent physical facts and are left exactly as they are, for the
     * same reason removing one restraint is not a release.
     */
    public static void clearPhysicalClaims(@Nullable MinecraftServer server, @Nullable UUID subject,
                                           @Nullable UUID custodyId) {
        if (server == null || subject == null) {
            return;
        }
        TetherService.endEscort(server, subject, TetherService.DetachReason.ADMINISTRATIVE);
        ExecutionAuthorization.clear(subject, ExecutionAuthorization.ClearReason.PARDONED);
    }

    /**
     * A rescue, an escape or a guard's death ended a pending execution.
     *
     * <p>Named separately from {@link #clearPhysicalClaims} because the reason is part of the record
     * §3.19 asks for: each of these returns the subject to <i>condemned in custody</i>, and knowing
     * which one it was is what makes an operator's report of "my prisoner is not dead" answerable.
     */
    public static void clearExecutionOrder(@Nullable UUID subject,
                                           ExecutionAuthorization.ClearReason reason) {
        ExecutionAuthorization.clear(subject, reason);
    }

    /** Every tether that exists because of an escort, for diagnostics and the tests. */
    public static boolean escorted(@Nullable CrimeWorldData data, @Nullable UUID subject) {
        for (TetherRecord tether : TetherService.forSubject(data, subject)) {
            if (tether.kind() == TetherKind.ESCORT) {
                return true;
            }
        }
        return false;
    }
}
