package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.ai.CrimeReactionService;
import dev.otectus.mcacrime.ai.thief.ThiefBehaviorService;
import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyOwnerType;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.jail.HoldingCell;
import dev.otectus.mcacrime.facility.CareHandoverPolicy;
import dev.otectus.mcacrime.facility.CellReservation;
import dev.otectus.mcacrime.facility.CrimeFacilityService;
import dev.otectus.mcacrime.facility.FacilityAssignment;
import dev.otectus.mcacrime.facility.FacilityRole;
import dev.otectus.mcacrime.jail.HoldingCellService;
import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.jail.SafeCustodyDestination;
import dev.otectus.mcacrime.ledger.SentenceResolutionService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sentence of an arrested villager: escort, cell, clock, release (spec §"Guards and thief arrests").
 *
 * <p>{@code CustodyService.tickNpcCaptives} deliberately skips lawful records — it is the kidnapping
 * ticker, and its real-time cap, escape work and tether are all wrong for a prisoner. This is the
 * other half: the same table, the records that one leaves alone.
 *
 * <p>It is a separate service rather than an extension of {@code EscortService} and {@code JailService}
 * for the reason those two are the way they are. Both are {@code ServerPlayer}-typed through and
 * through and store their state in a player capability, so an arrested villager cannot be represented
 * in either without a parallel non-player path inside each of them. The escort here is a much smaller
 * thing anyway: a guard, a destination, and a leash.
 *
 * <p>The orphan rules are what stop an arrest from becoming permanent scenery. A guard that dies or
 * wanders off hands its prisoner to any other responder standing nearby; if none arrives within
 * {@code npcEscortOrphanTicks}, the thief is jailed where it stands rather than left cuffed in a field
 * for the rest of the save.
 */
public final class NpcCustodyService {

    /** How close to the anchor counts as arrived. Three blocks. */
    private static final double ARRIVAL_REACH_SQR = 9.0D;
    /** How far a replacement escort may be found when the original guard is gone. */
    private static final double REPLACEMENT_RADIUS = 16.0D;

    /** captive id -> live escort bookkeeping. Memory-only; the custody record is what persists. */
    private static final Map<UUID, Escort> ESCORTS = new ConcurrentHashMap<>();

    private static final class Escort {
        private long nextNavAt;
        /** When the escort lost its guard, or 0 while it still has one. */
        private long orphanSince;
        /**
         * The prisoner's total held time when this escort began.
         *
         * <p>Zero for an arrest, which is the case the deadline was written for: custody starts at the
         * moment the escort does, so the two clocks are the same clock. They stop being the same clock
         * the moment an escort starts <em>during</em> a sentence — the care-room handover below — where
         * a prisoner who has served an hour would be overdue on the first tick and the walk would end
         * before it began. Subtracting the baseline measures this escort rather than this captivity,
         * and leaves the arrest path arithmetically identical.
         */
        private long heldTicksAtStart;
    }

    private NpcCustodyService() {
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the walk to jail. Called by {@link NpcArrestService} the moment custody is written. */
    public static void beginEscort(ServerLevel level, LivingEntity thief, LivingEntity guard) {
        if (level == null || thief == null || guard == null) {
            return;
        }
        ESCORTS.put(thief.getUUID(), new Escort());
    }

    /**
     * Picks up every lawful NPC custody record a restart inherited.
     *
     * <p>Only the memory-side bookkeeping is rebuilt; the record itself already says who holds the
     * prisoner and how much of the sentence is left. Loaded escort time also persists, so a restart
     * pauses its deadline instead of resetting it or charging time while the server was down.
     */
    public static void reconcile(MinecraftServer server) {
        ESCORTS.clear();
        if (server == null || !ServerMutationGate.allows(server)) {
            return;
        }
        int found = 0;
        for (CustodyRecord record : CrimeWorldData.get(server).custodyRecords()) {
            if (record.isCaptivePlayer() || !record.isLawful()) {
                continue;
            }
            found++;
            if (record.getOwner().type() == CustodyOwnerType.GUARD) {
                ESCORTS.put(record.getCaptive(), new Escort());
            }
            inferLegacySentenceMembership(server, record.getCaptive());
        }
        if (found > 0) {
            CrimeDebug.crime("reconciled {} lawful NPC custody record(s) on server start", found);
        }
    }

    /**
     * The NPC half of the login-time inference {@code JailService} does for players.
     *
     * <p>A villager sentence handed down before 0.6.0 has a cell and a clock and nothing linking it to
     * a case, so the release below would settle nothing at all. Binding what stands against the thief
     * to the sentence it is already serving is the same assumption, made in the same words, and it runs
     * here because a server start is the only moment an NPC sentence is looked at as a whole.
     */
    private static void inferLegacySentenceMembership(MinecraftServer server, UUID captiveId) {
        CrimeWorldData data = CrimeWorldData.get(server);
        CustodyRecord custody = data.getCustody(captiveId);
        if (custody == null || custody.getSentenceId() != null) return;
        HoldingCell cell = HoldingCellService.existingFor(server, captiveId);
        UUID sentenceId = cell == null || cell.sentenceId() == null ? UUID.randomUUID() : cell.sentenceId();
        custody.setSentenceId(sentenceId);
        data.setDirty();
        List<UUID> bound = (cell != null && cell.isLegacyBound())
                || !data.casesForSentence(captiveId, sentenceId).isEmpty() ? List.of()
                : data.bindLegacySentence(captiveId, sentenceId, server.overworld().getGameTime());
        // Stamped whether or not anything was bound, and stamped on the cell because a villager has no
        // capability of its own. Same rule as the player side: a one-time upgrade guess runs once.
        if (cell != null) {
            cell.setLegacyBound(true);
            data.putHoldingCell(cell);
        }
        if (!bound.isEmpty()) {
            CrimeDebug.crime("bound {} pre-0.6.0 case(s) to the sentence {} is serving", bound.size(),
                    captiveId);
        }
    }

    public static void clearAll() {
        ESCORTS.clear();
        CARE_HANDOVER_NEXT.clear();
    }

    public static int activeCount() {
        return ESCORTS.size();
    }

    // ------------------------------------------------------------------ ticking

    /**
     * Advances every lawful NPC custody record.
     *
     * @param elapsedTicks ticks since the last call; this rides the throttled guard scan rather than
     *                     the raw tick, so a sentence is served in scan-sized steps
     */
    public static void tick(MinecraftServer server, long elapsedTicks) {
        if (server == null || elapsedTicks <= 0L || !ServerMutationGate.allows(server)) {
            return;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        for (CustodyRecord record : List.copyOf(data.custodyRecords())) {
            if (record.isCaptivePlayer() || !record.isLawful()) {
                continue; // players are JailService's, unlawful captives are CustodyService's
            }
            ServerLevel level = JailService.resolveLevel(server, record.getHoldDim());
            if (level == null) {
                CustodyService.release(server, record.getCaptive(), CustodyReleaseReason.ADMIN);
                ThiefBehaviorService.markReleased(record.getCaptive());
                ESCORTS.remove(record.getCaptive());
                CARE_HANDOVER_NEXT.remove(record.getCaptive());
                JailEscortNavigation.forget(record.getCaptive());
                continue;
            }
            inferLegacySentenceMembership(server, record.getCaptive());
            // Custody outlives any activity lease, so the claim is re-asserted on the scan that is
            // already walking these records rather than taken once and left to expire under a prisoner.
            if (level.getEntity(record.getCaptive()) instanceof LivingEntity heldEntity) {
                CustodyService.assertCustodyClaim(heldEntity);
            }
            switch (record.getOwner().type()) {
                case GUARD -> tickEscort(server, data, level, record, elapsedTicks);
                case JAIL -> tickSentence(server, data, level, record, elapsedTicks);
                default -> {
                    // AUTHORITY / NONE: nothing is walking anywhere and no clock is running. Left
                    // alone rather than guessed at; an operator put it there.
                }
            }
        }
    }

    /** The walk to the cell, and everything that can go wrong on the way. */
    private static void tickEscort(MinecraftServer server, CrimeWorldData data, ServerLevel level,
                                   CustodyRecord record, long elapsedTicks) {
        UUID captiveId = record.getCaptive();
        if (!(level.getEntity(captiveId) instanceof LivingEntity thief) || !thief.isAlive()) {
            return; // unloaded or dead; the record waits, and death releases it elsewhere
        }
        long now = level.getGameTime();
        record.setRealTicksHeld(record.getRealTicksHeld() > Long.MAX_VALUE - elapsedTicks
                ? Long.MAX_VALUE : record.getRealTicksHeld() + elapsedTicks);
        data.setDirty();
        Escort escort = ESCORTS.computeIfAbsent(captiveId, id -> new Escort());

        UUID guardId = record.getOwner().ownerUuid().orElse(null);
        LivingEntity guard = guardId != null && level.getEntity(guardId) instanceof LivingEntity found
                && dev.otectus.mcacrime.ai.NpcAwareness.canRespondAsGuard(found) ? found : null;
        int timeout = McaCrimeConfig.COMMON.arrestEscortTimeoutTicks.get();
        boolean overdue = escortOverdue(
                Math.max(0L, record.getRealTicksHeld() - escort.heldTicksAtStart), timeout);
        if (overdue) {
            JailAnchor anchor = nearestAnchor(data, level, thief.blockPosition(), captiveId);
            commit(server, data, level, record, thief, guard,
                    anchor == null ? thief.blockPosition() : anchor.pos());
            return;
        }
        if (guard == null) {
            handleOrphan(server, data, level, record, thief, escort, now);
            return;
        }
        escort.orphanSince = 0L;
        LawHold.hold(guard, now + 3L * Math.max(1, McaCrimeConfig.COMMON.guardScanIntervalTicks.get()),
                dev.otectus.mcacrime.activity.CrimeActivityView.Kind.ESCORT);

        JailAnchor anchor = nearestAnchor(data, level, thief.blockPosition(), captiveId);
        if (anchor == null) {
            // No jail anywhere in this dimension. The cell is built where the arrest happened rather
            // than marching the pair toward a destination that does not exist.
            commit(server, data, level, record, thief, guard, thief.blockPosition());
            return;
        }
        BlockPos target = anchor.pos();
        if (thief.distanceToSqr(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D) <= ARRIVAL_REACH_SQR) {
            commit(server, data, level, record, thief, guard, target);
            return;
        }
        JailEscortNavigation.Progress progress = JailEscortNavigation.advance(level, guard, thief, anchor);
        if (progress.arrived() || progress.stuck()) {
            commit(server, data, level, record, thief, guard, target);
            return;
        }
        McaCompat.leashTo(thief, guard); // re-secured: a leash does not survive a chunk round-trip
    }

    /** Nobody is holding this prisoner any more. Find a replacement, or stop pretending. */
    private static void handleOrphan(MinecraftServer server, CrimeWorldData data, ServerLevel level,
                                     CustodyRecord record, LivingEntity thief, Escort escort, long now) {
        LivingEntity replacement = nearestResponder(level, thief);
        if (replacement != null) {
            CustodyService.transferLawfulCustody(server, record.getCaptive(),
                    CustodyOwner.guard(replacement.getUUID()));
            McaCompat.leashTo(thief, replacement);
            escort.nextNavAt = 0L;
            escort.orphanSince = 0L;
            CrimeDebug.crime("escort of {} was handed to guard {}", record.getCaptive(),
                    replacement.getUUID());
            return;
        }
        if (escort.orphanSince == 0L) {
            escort.orphanSince = now;
            return;
        }
        if (now - escort.orphanSince < McaCrimeConfig.COMMON.npcEscortOrphanTicks.get()) {
            return;
        }
        JailAnchor anchor = nearestAnchor(data, level, thief.blockPosition(), record.getCaptive());
        BlockPos near = anchor == null ? thief.blockPosition() : anchor.pos();
        CrimeDebug.crime("escort of {} was orphaned; jailing in place", record.getCaptive());
        commit(server, data, level, record, thief, null, near);
    }

    /**
     * Arrival: the cell goes up, custody passes from the guard to the jail, and the sentence starts.
     *
     * <p>Custody is <em>transferred</em> rather than released and re-taken, for the same reason the
     * player escort does it: a release would fire the public events and leave the prisoner briefly
     * free, in a cell, with nothing holding them.
     */
    private static void commit(MinecraftServer server, CrimeWorldData data, ServerLevel level,
                               CustodyRecord record, LivingEntity thief, @Nullable LivingEntity guard,
                               BlockPos near) {
        UUID captiveId = record.getCaptive();
        // The cell projects the custody sentence. Its absence never loses the assessed cases.
        UUID sentenceId = record.getSentenceId();
        if (sentenceId == null) return; // reconcile assigns legacy identity before this path
        // Arrival revalidation: the reserved cell is confirmed and spent here, or released because the
        // building went away while the pair were walking to it. Either way the slot is not left held.
        CrimeFacilityService.Arrival arrival = CrimeFacilityService.arrive(level, captiveId);
        if (arrival == CrimeFacilityService.Arrival.INVALID) {
            CrimeDebug.crime("the facility {} was being escorted to no longer validates; "
                    + "the cell is provisioned at the destination instead", captiveId);
        }
        dev.otectus.mcacrime.jail.CellBuilder.Outcome provisioning =
                HoldingCellService.provisionChecked(level, near, captiveId, sentenceId);
        HoldingCell cell = provisioning.cell();
        if (cell == null && provisioning.refusal() != dev.otectus.mcacrime.jail.CellBuilder.Refusal.NONE) {
            // Never silent: a settlement-aware refusal cannot be fixed by walking somewhere else, and an
            // operator reading the log is the only person who can act on it.
            CrimeDebug.crime("no holding cell for {}: {}", captiveId, provisioning.describe());
        }
        BlockPos hold = SafeCustodyDestination.validate(level, cell == null ? near : cell.anchor(), 4)
                .orElseGet(() -> SafeCustodyDestination.validate(level, thief.blockPosition(), 4).orElse(null));
        if (hold == null) {
            CrimeFacilityService.releaseFor(server, captiveId);
            CustodyService.release(server, captiveId, CustodyReleaseReason.ADMIN);
            HoldingCellService.releaseAndDismantle(server, captiveId);
            CrimeReactionService.endCaptive(level, captiveId);
            ThiefBehaviorService.markReleased(captiveId);
            ESCORTS.remove(captiveId);
            JailEscortNavigation.forget(captiveId);
            return;
        }
        if (cell != null) {
            cell.setLegacyBound(true);
            data.putHoldingCell(cell);
        }
        OptionalInt village = guard == null ? McaCompat.getHomeVillageId(thief)
                : McaCompat.getHomeVillageId(guard);
        CustodyService.transferLawfulCustody(server, captiveId,
                CustodyOwner.jail(village.orElse(-1), hold, level.dimension().location()));
        if (guard != null) {
            LawHold.clear(guard.getUUID());
            McaCompat.stopModNavigation(guard);
        }
        McaCompat.clearLeash(thief);
        McaCompat.stopModNavigation(thief);
        thief.teleportTo(hold.getX() + 0.5D, hold.getY(), hold.getZ() + 0.5D);
        record.setHoldPos(hold);
        data.setDirty();
        RestraintSync.broadcast(thief);
        ESCORTS.remove(captiveId);
        JailEscortNavigation.forget(captiveId);
        CrimeDebug.crime("thief {} is serving {} ticks at {}", captiveId, record.getRemainingJailTicks(), hold);
    }

    /** The sentence clock, and the release at the end of it. */
    private static void tickSentence(MinecraftServer server, CrimeWorldData data, ServerLevel level,
                                     CustodyRecord record, long elapsedTicks) {
        // Care first, because it can stop the clock. A prisoner whose needs have collapsed is not
        // serving: custody-recovery suspends the confinement and keeps the sentence, so the remaining
        // ticks stand still until they are well enough to be held again.
        dev.otectus.mcacrime.captivity.CustodyCareService.tick(level, data, record,
                level.getEntity(record.getCaptive()) instanceof LivingEntity held ? held : null);
        if (record.isInRecovery()) {
            considerCareHandover(server, data, level, record);
            return;
        }
        long remaining = record.getRemainingJailTicks() - elapsedTicks;
        record.setRemainingJailTicks(Math.max(0L, remaining));
        data.setDirty();
        if (remaining > 0L) {
            return;
        }
        UUID captiveId = record.getCaptive();
        // Custody, rather than a generated structure, owns the sentence identity.
        if (record.getSentenceId() != null) {
            SentenceResolutionService.markServed(server, captiveId, record.getSentenceId());
        }
        CustodyService.release(server, captiveId, CustodyReleaseReason.SENTENCE_SERVED);
        dev.otectus.mcacrime.captivity.CustodyCareService.forget(captiveId);
        CrimeFacilityService.releaseFor(server, captiveId);
        HoldingCellService.releaseAndDismantle(server, captiveId);
        CrimeReactionService.endCaptive(level, captiveId);
        // The criminal job survives the sentence (spec §"Guards and thief arrests"): a thief comes out
        // of jail still a thief, and goes back to work after its ordinary cooldown.
        ThiefBehaviorService.markReleased(captiveId);
        ESCORTS.remove(captiveId);
        CARE_HANDOVER_NEXT.remove(captiveId);
        JailEscortNavigation.forget(captiveId);
        CrimeDebug.crime("thief {} served its sentence and was released", captiveId);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Where this captive is being walked, facility first.
     *
     * <p>An assigned {@code JAIL_CELL} outranks a manual anchor for the same reason it does on the
     * player side: it carries a capacity that can be reserved and a building that can be revalidated on
     * arrival. A captive who already holds a reservation keeps walking to the cell it names rather than
     * being re-routed every scan, which also means the expensive validation runs once per escort and
     * not once per tick.
     */
    @Nullable
    private static JailAnchor nearestAnchor(CrimeWorldData data, ServerLevel level, BlockPos from,
                                            @Nullable UUID captive) {
        if (captive != null) {
            long now = level.getGameTime();
            CellReservation held = data.cellReservationForPrisoner(captive, now);
            FacilityAssignment reserved = held == null ? null : data.facility(held.facilityId());
            if (reserved != null) {
                return facilityAnchor(reserved);
            }
            FacilityAssignment chosen = CrimeFacilityService
                    .selectDestination(level, FacilityRole.JAIL_CELL, from).orElse(null);
            if (chosen != null && CrimeFacilityService.reserve(data, chosen, captive, now,
                    Math.max(CellReservation.DEFAULT_LEASE_TICKS,
                            McaCrimeConfig.COMMON.arrestEscortTimeoutTicks.get())).isPresent()) {
                return facilityAnchor(chosen);
            }
        }
        return nearestAnchor(data, level, from);
    }

    // ------------------------------------------------------------------ care-room handover

    /** How often one recovering prisoner is reconsidered for a care room. Ten seconds. */
    private static final int CARE_HANDOVER_INTERVAL_TICKS = 200;

    /** How close to a care room's anchor counts as already being in it. */
    private static final double CARE_ROOM_REACH_SQR = 36.0D;

    /** captive id -> the next game time a care-room handover may be considered. Memory-only. */
    private static final Map<UUID, Long> CARE_HANDOVER_NEXT = new ConcurrentHashMap<>();

    /**
     * Walks a recovering prisoner to an assigned care room, when there is one and somebody to walk them.
     *
     * <p>Custody recovery already stopped the sentence clock and suspended the confinement — that is
     * {@code CustodyCareService}'s decision and this cannot reverse it. What this adds is the other half
     * of reference §8.6 step 2: a settlement that has designated a care room has said where an unfit
     * prisoner should be, and the alternative to walking them there is leaving them in a cell that has
     * already been established as having nothing they can use.
     *
     * <p>Every way this could go wrong is {@link CareHandoverPolicy}'s to say, and it says so as a pure
     * function so the rule is asserted rather than inferred. This method is the plumbing: look the
     * inputs up, ask, and — only on a yes — reserve the room, hand the prisoner to the guard and start
     * the ordinary escort, which already knows how to lose a guard, get stuck, and arrive.
     *
     * <p>Bounded twice over: once per prisoner per ten seconds, and only while the prisoner is loaded.
     * A world with no care room assigned pays one map lookup and a facility list scan that finds
     * nothing.
     */
    private static void considerCareHandover(MinecraftServer server, CrimeWorldData data,
                                             ServerLevel level, CustodyRecord record) {
        UUID captiveId = record.getCaptive();
        long now = level.getGameTime();
        Long next = CARE_HANDOVER_NEXT.get(captiveId);
        if (next != null && now < next) {
            return;
        }
        CARE_HANDOVER_NEXT.put(captiveId, now + CARE_HANDOVER_INTERVAL_TICKS);

        if (!(level.getEntity(captiveId) instanceof LivingEntity prisoner) || !prisoner.isAlive()) {
            return; // unloaded or dead: nothing to walk anywhere
        }
        BlockPos from = record.getHoldPos() == null ? prisoner.blockPosition() : record.getHoldPos();
        FacilityAssignment careRoom = CrimeFacilityService
                .selectDestination(level, FacilityRole.CARE_ROOM, from).orElse(null);
        boolean alreadyThere = careRoom != null && careRoom.anchor().distSqr(from) <= CARE_ROOM_REACH_SQR;
        boolean escorting = ESCORTS.containsKey(captiveId)
                || record.getOwner().type() == CustodyOwnerType.GUARD;
        LivingEntity guard = careRoom == null || alreadyThere || escorting
                ? null // do not pay for the entity scan on a decision that is already made
                : nearestResponder(level, prisoner);

        CareHandoverPolicy.Decision decision = CareHandoverPolicy.decide(record.isInRecovery(), careRoom,
                alreadyThere, escorting, guard != null,
                careRoom == null ? Double.MAX_VALUE : careRoom.anchor().distSqr(from), 0.0D);
        if (!decision.handOver() || careRoom == null || guard == null) {
            return;
        }
        // The reservation is what makes the escort walk to the care room rather than to the nearest
        // jail: nearestAnchor prefers a prisoner's live reservation over everything else. Without one
        // the pair would set off for a cell, which is the place custody has already decided is not
        // keeping this prisoner alive.
        if (CrimeFacilityService.reserve(data, careRoom, captiveId, now,
                Math.max(CellReservation.DEFAULT_LEASE_TICKS,
                        McaCrimeConfig.COMMON.arrestEscortTimeoutTicks.get())).isEmpty()) {
            return;
        }
        if (!CustodyService.transferLawfulCustody(server, captiveId, CustodyOwner.guard(guard.getUUID()))) {
            CrimeFacilityService.releaseFor(server, captiveId);
            return;
        }
        Escort escort = new Escort();
        // This escort's own clock. See Escort.heldTicksAtStart: a prisoner who has already served an
        // hour would otherwise be overdue before taking a step.
        escort.heldTicksAtStart = record.getRealTicksHeld();
        ESCORTS.put(captiveId, escort);
        McaCompat.leashTo(prisoner, guard);
        CrimeDebug.crime("recovering prisoner {} is being walked to the care room {}: {}", captiveId,
                careRoom.shortId(), decision.reason());
    }

    private static JailAnchor facilityAnchor(FacilityAssignment facility) {
        return new JailAnchor(facility.anchor(), facility.ref().dimension(),
                McaCrimeConfig.COMMON.jailRadiusDefault.get());
    }

    @Nullable
    private static JailAnchor nearestAnchor(CrimeWorldData data, ServerLevel level, BlockPos from) {
        JailAnchor best = null;
        double bestDistance = Double.MAX_VALUE;
        for (JailAnchor anchor : data.jailAnchors()) {
            if (!anchor.dim().equals(level.dimension().location())) {
                continue;
            }
            double distance = anchor.pos().distSqr(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = anchor;
            }
        }
        return best;
    }

    @Nullable
    private static LivingEntity nearestResponder(ServerLevel level, LivingEntity thief) {
        AABB box = thief.getBoundingBox().inflate(REPLACEMENT_RADIUS);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box,
                entity -> entity != thief && entity.isAlive() && EntitySelectors.isAvailableResponder(entity))) {
            if (!candidate.hasLineOfSight(thief)
                    || ResponderAssignments.isEscorting(level.getServer(), candidate.getUUID(), thief.getUUID())
                    || NpcCriminalPursuit.isAssignedElsewhere(candidate.getUUID(), thief.getUUID())) continue;
            double distance = candidate.distanceToSqr(thief);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** Total loaded escort time is persisted; guard replacement/restart never restarts the deadline. */
    public static boolean escortOverdue(long elapsed, long timeout) {
        return timeout <= 0L || elapsed >= timeout;
    }
}
