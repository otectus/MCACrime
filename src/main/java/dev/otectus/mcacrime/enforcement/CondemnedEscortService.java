package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.activity.CrimeActivityRegistry;
import dev.otectus.mcacrime.activity.CrimeActivityView;
import dev.otectus.mcacrime.block.GuillotineBlock;
import dev.otectus.mcacrime.block.entity.GuillotineBlockEntity;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.detention.DetentionKind;
import dev.otectus.mcacrime.detention.DetentionRecord;
import dev.otectus.mcacrime.detention.DetentionService;
import dev.otectus.mcacrime.detention.ExecutionAuthorization;
import dev.otectus.mcacrime.facility.CellReservation;
import dev.otectus.mcacrime.facility.CrimeFacilityService;
import dev.otectus.mcacrime.facility.FacilityAssignment;
import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.ledger.CapitalSentenceService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import dev.otectus.mcacrime.tether.TetherService;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A guard walking a condemned prisoner to an execution site (0.7.5 §3.19, M6.7).
 *
 * <p>This is the "carried out by a player or an on-duty guard, and by nothing else" clause given a
 * guard. It is a sequence of deliberate acts by one entity — take the claim, reserve the site, walk
 * there, place the prisoner in the device, arm the order, wait out the ceremony, drop the blade — and
 * every step can fail. <b>Failure is always identical</b>: the claim is released, the reservation is
 * released, the prisoner is returned to custody and the sentence stands. Nobody is freed by a failed
 * execution and nobody dies of one.
 *
 * <p>The seven clearing rules of §3.19 all land here as the same ending. Guard death, logout,
 * dimension change, the timeout, the device being destroyed, a rescue, a pardon or a commutation each
 * remove the authorisation or the escort, and the subject goes back to <i>condemned in custody</i>.
 *
 * <p>It works for players and villagers alike, which is why it does not extend {@link EscortService}:
 * that class is {@code ServerPlayer}-typed through and through. What is shared is the walking, and
 * the walking already lives in {@link JailEscortNavigation}, which takes plain {@code Entity}s — so
 * the shared step logic is reused rather than duplicated, and the player-only parts of an arrest
 * escort (the arrest state machine, the capability) are simply not part of this.
 */
public final class CondemnedEscortService {

    /** How close to the device counts as arrived. Three blocks, matching the cell escort. */
    private static final double ARRIVAL_REACH_SQR = 9.0D;

    /** How often a walking escort re-issues navigation. Every half second is plenty. */
    private static final int NAV_INTERVAL_TICKS = 10;

    /** The activity owner token, so a release gives back this escort's claim and nobody else's. */
    private static final String CLAIM_OWNER = "condemned_escort";

    /** Live escorts, keyed by the condemned subject. Memory-only: the sentence is what persists. */
    private static final Map<UUID, Escort> ESCORTS = new ConcurrentHashMap<>();

    /** One walk in progress. */
    private static final class Escort {
        UUID guard;
        UUID facilityId;
        UUID reservationToken;
        BlockPos device;
        long startedAt;
        long claimGeneration;
        /** When the order was armed, or 0 while the pair are still walking. */
        long armedAt;
        /** Set once the blade has been dropped, so it is dropped exactly once. */
        boolean bladeDropped;
    }

    /** Why an escort could not begin, or ended. Each is a distinct message rather than a silent no-op. */
    public enum Outcome {
        /** The walk has begun. */
        BEGUN,
        /** Somebody is already walking this prisoner to a device. */
        ALREADY_ESCORTING,
        /** This prisoner is under no capital sentence. */
        NOT_CONDEMNED,
        /** The feature is off, or guards may not carry out sentences. */
        NOT_PERMITTED,
        /** No assigned execution site within range, or no device at the one there is. */
        NO_SITE,
        /** The site's single slot is already spoken for. */
        SITE_RESERVED,
        /** The guard could not take an activity claim on the prisoner. */
        NO_CLAIM
    }

    private CondemnedEscortService() {
    }

    // --- configuration ---------------------------------------------------------------------------------

    /** {@code sentencing.capitalPunishment.condemnedEscortTimeoutTicks}. */
    public static int timeoutTicks() {
        try {
            return McaCrimeConfig.COMMON.condemnedEscortTimeoutTicks.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return 2400;
        }
    }

    /** {@code detention.guillotineActivationDelayTicks}: how long the blade itself takes to land. */
    private static int bladeDelayTicks() {
        try {
            return McaCrimeConfig.COMMON.guillotineActivationDelayTicks.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return 5;
        }
    }

    /**
     * When the blade should be dropped, relative to the moment the order was armed.
     *
     * <p>Pure, and it exists because of an off-by-one that would be invisible in play and fatal to
     * the feature: the authorisation's window closes at {@code armedAt + delay}, and the blade takes
     * its own delay to land. Dropping it at the very end of the window would land it after the window
     * closed, {@link GuillotineBlockEntity#strikes} would refuse, and every guard-carried execution
     * would quietly do nothing. The ceremony is therefore the window minus the blade's own fall.
     */
    public static long dropAt(long armedAt, int windowTicks, int bladeTicks) {
        long ceremony = Math.max(1L, (long) windowTicks - Math.max(1, bladeTicks) - 2L);
        return armedAt + ceremony;
    }

    // --- beginning -------------------------------------------------------------------------------------

    /**
     * Starts the walk, or explains why it cannot.
     *
     * <p>Nothing here kills anybody and nothing here can: the most this does is reserve a site and
     * take a claim. Every order is armed later, at the device, with the prisoner in it.
     */
    public static Outcome begin(@Nullable ServerLevel level, @Nullable LivingEntity guard,
                                @Nullable LivingEntity captive) {
        MinecraftServer server = level == null ? null : level.getServer();
        if (server == null || guard == null || captive == null || !ServerMutationGate.allows(server)) {
            return Outcome.NOT_PERMITTED;
        }
        if (!ExecutionAuthorization.featureEnabled() || !ExecutionAuthorization.guardMayExecute()) {
            return Outcome.NOT_PERMITTED;
        }
        UUID captiveId = captive.getUUID();
        if (ESCORTS.containsKey(captiveId)) {
            return Outcome.ALREADY_ESCORTING;
        }
        if (!CapitalSentenceService.condemned(server, captiveId)) {
            return Outcome.NOT_CONDEMNED;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        CustodyRecord custody = data == null ? null : data.getCustody(captiveId);
        if (custody == null || !custody.isLawful()) {
            return Outcome.NOT_CONDEMNED; // an execution is a sentence being carried out, not a seizure
        }
        FacilityAssignment site = ExecutionSiteRegistry.nearestSite(level, captive.blockPosition())
                .orElse(null);
        BlockPos device = site == null ? null
                : ExecutionSiteRegistry.deviceAt(level, site).orElse(null);
        if (site == null || device == null) {
            // §3.19: with no usable device the condemned simply stays in custody. No substitute death,
            // no despawn, no automatic commutation, no expiry into freedom.
            return Outcome.NO_SITE;
        }
        long now = level.getGameTime();
        CellReservation reservation = CrimeFacilityService
                .reserve(data, site, captiveId, now, CellReservation.DEFAULT_LEASE_TICKS).orElse(null);
        if (reservation == null) {
            return Outcome.SITE_RESERVED;
        }
        long generation = CrimeActivityRegistry.claim(guard, CrimeActivityView.Kind.ESCORT, CLAIM_OWNER,
                now);
        if (generation == CrimeActivityRegistry.REFUSED) {
            CrimeFacilityService.release(data, reservation.token());
            return Outcome.NO_CLAIM;
        }
        Escort escort = new Escort();
        escort.guard = guard.getUUID();
        escort.facilityId = site.id();
        escort.reservationToken = reservation.token();
        escort.device = device.immutable();
        escort.startedAt = now;
        escort.claimGeneration = generation;
        ESCORTS.put(captiveId, escort);
        TetherService.escort(guard, captive);
        CrimeDebug.crime("guard {} is walking the condemned {} to the device at {}", guard.getUUID(),
                captiveId, device);
        return Outcome.BEGUN;
    }

    /**
     * Looks for a condemned prisoner a nearby guard could take to a device, and starts one walk.
     *
     * <p>Throttled, bounded and deliberately unambitious: one candidate per pass, a guard who is
     * already standing near them, and only ever a prisoner the law is already holding. It is what
     * makes the feature work in single player with one guard and one captive, and it is also why a
     * village with no assigned execution site never does anything at all — {@link #begin} answers
     * {@link Outcome#NO_SITE} and the prisoner stays in their cell.
     */
    public static void considerStart(@Nullable MinecraftServer server) {
        if (server == null || !ServerMutationGate.allows(server)
                || !ExecutionAuthorization.featureEnabled() || !ExecutionAuthorization.guardMayExecute()) {
            return;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        if (data == null) {
            return;
        }
        for (CustodyRecord record : data.custodyRecords()) {
            UUID captiveId = record.getCaptive();
            if (captiveId == null || !record.isLawful() || record.isInRecovery()
                    || ESCORTS.containsKey(captiveId)) {
                continue;
            }
            if (!record.isCondemned() && !data.sentenceKind(record.getSentenceId()).capital()) {
                continue;
            }
            LivingEntity captive = living(server, captiveId);
            if (captive == null || !captive.isAlive() || !(captive.level() instanceof ServerLevel level)) {
                continue; // an unloaded prisoner is held, not executed
            }
            LivingEntity guard = nearestGuard(level, captive);
            if (guard == null) {
                continue;
            }
            Outcome outcome = begin(level, guard, captive);
            if (outcome != Outcome.BEGUN) {
                CrimeDebug.crime("no condemned escort for {}: {}", captiveId, outcome);
            }
            return; // one walk per pass; the next pass considers the next prisoner
        }
    }

    /** The nearest on-duty responder who could take the prisoner. */
    @Nullable
    private static LivingEntity nearestGuard(ServerLevel level, LivingEntity captive) {
        net.minecraft.world.phys.AABB box = captive.getBoundingBox().inflate(16.0D);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box,
                entity -> entity != captive && entity.isAlive()
                        && dev.otectus.mcacrime.detect.EntitySelectors.isAvailableResponder(entity))) {
            double distance = candidate.distanceToSqr(captive);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    // --- ticking ---------------------------------------------------------------------------------------

    /**
     * Advances every escort.
     *
     * <p>Called from the server tick. Every branch that is not "still walking" ends the escort, and
     * every ending leaves the prisoner condemned and held.
     */
    public static void tick(@Nullable MinecraftServer server) {
        if (server == null || ESCORTS.isEmpty() || !ServerMutationGate.allows(server)) {
            return;
        }
        for (Map.Entry<UUID, Escort> entry : Map.copyOf(ESCORTS).entrySet()) {
            tickOne(server, entry.getKey(), entry.getValue());
        }
    }

    private static void tickOne(MinecraftServer server, UUID captiveId, Escort escort) {
        CrimeWorldData data = CrimeWorldData.get(server);
        LivingEntity captive = living(server, captiveId);
        LivingEntity guard = living(server, escort.guard);
        ServerLevel level = captive == null ? null : (ServerLevel) captive.level();
        long now = server.overworld().getGameTime();

        if (!CapitalSentenceService.condemned(server, captiveId)) {
            // Pardoned or commuted while the pair were walking. Nobody dies; the walk simply stops.
            end(server, captiveId, escort, "clemency");
            return;
        }
        if (captive == null || !captive.isAlive() || guard == null || !guard.isAlive() || level == null) {
            end(server, captiveId, escort, "the escort lost its guard or its prisoner");
            return;
        }
        if (now - escort.startedAt > timeoutTicks()) {
            UUID walker = escort.guard;
            end(server, captiveId, escort, "the escort timed out");
            returnToCell(server, captiveId, walker);
            return;
        }
        if (!ExecutionSiteRegistry.standing(level, escort.device)) {
            end(server, captiveId, escort, "the device is gone");
            return;
        }
        CrimeActivityRegistry.renew(escort.guard, escort.claimGeneration, now);
        LawHold.hold(escort.guard, now + 3L * NAV_INTERVAL_TICKS);

        if (escort.armedAt > 0L) {
            ceremony(server, level, captiveId, escort, now);
            return;
        }
        BlockPos device = escort.device;
        if (captive.distanceToSqr(device.getX() + 0.5D, device.getY(), device.getZ() + 0.5D)
                <= ARRIVAL_REACH_SQR) {
            arrive(server, level, data, captiveId, captive, guard, escort, now);
            return;
        }
        JailEscortNavigation.Progress progress = JailEscortNavigation.advance(level, guard, captive,
                new JailAnchor(device, level.dimension().location(), 4));
        if (progress.arrived()) {
            arrive(server, level, data, captiveId, captive, guard, escort, now);
        } else if (progress.stuck()) {
            end(server, captiveId, escort, "the escort could not reach the device");
        } else {
            TetherService.escort(guard, captive);
        }
    }

    /** The prisoner is at the device: put them in it and arm the order. */
    private static void arrive(MinecraftServer server, ServerLevel level, CrimeWorldData data,
                               UUID captiveId, LivingEntity captive, LivingEntity guard, Escort escort,
                               long now) {
        CrimeFacilityService.consume(data, escort.reservationToken);
        escort.reservationToken = null;
        BlockPos device = escort.device;
        // Placed in the device, under the law's authority: a guard putting a sentenced prisoner in a
        // guillotine is the law continuing, so the privacy policy is not consulted (M6.3).
        DetentionService.Refusal refusal = DetentionService.claim(data, captive, DetentionKind.GUILLOTINE,
                level.dimension().location(), device, DetentionKind.GUILLOTINE.id(), guard.getUUID(),
                true);
        if (refusal != DetentionService.Refusal.NONE
                && refusal != DetentionService.Refusal.SUBJECT_DETAINED) {
            end(server, captiveId, escort, "the device would not take the prisoner");
            return;
        }
        captive.teleportTo(device.getX() + 0.5D, device.getY(), device.getZ() + 0.5D);
        DetentionRecord detention = DetentionService.forSubject(data, captiveId).orElse(null);
        long generation = detention == null ? 1L : detention.occupantGeneration();
        Optional<ExecutionAuthorization.Pending> armed = ExecutionAuthorization.arm(server, captiveId,
                guard.getUUID(), false, true, level.dimension().location(), device, now, generation);
        if (armed.isEmpty()) {
            // Refused: the feature was switched off mid-walk, guards were forbidden, or somebody else
            // already armed this subject. The prisoner stays in the device, condemned and held.
            end(server, captiveId, escort, "the order was refused: "
                    + ExecutionAuthorization.lastRefusal());
            return;
        }
        escort.armedAt = now;
        McaCrime.LOGGER.info("MCA: Crime - an execution of {} is armed at {} by guard {}", captiveId,
                device, guard.getUUID());
    }

    /** The ceremony window: wait it out, then drop the blade exactly once. */
    private static void ceremony(MinecraftServer server, ServerLevel level, UUID captiveId,
                                 Escort escort, long now) {
        if (escort.bladeDropped) {
            if (ExecutionAuthorization.pending(captiveId, now).isEmpty()) {
                end(server, captiveId, escort, "the order is spent");
            }
            return;
        }
        if (ExecutionAuthorization.pending(captiveId, now).isEmpty()) {
            // Cleared inside the window by one of §3.19's rules. Back to condemned in custody.
            end(server, captiveId, escort, "the order was cleared inside the window");
            return;
        }
        if (now < dropAt(escort.armedAt, ExecutionAuthorization.delayTicks(), bladeDelayTicks())) {
            return; // still the rescue and pardon window; nothing happens in it by design
        }
        BlockPos frame = escort.device.above(2);
        if (!(level.getBlockEntity(frame) instanceof GuillotineBlockEntity blockEntity)
                || !(level.getBlockState(frame).getBlock() instanceof GuillotineBlock)) {
            end(server, captiveId, escort, "the device is gone");
            return;
        }
        escort.bladeDropped = blockEntity.dropBladeFor(level, frame, level.getBlockState(frame),
                captiveId, escort.guard);
        if (!escort.bladeDropped) {
            end(server, captiveId, escort, "the device would not operate");
        }
    }

    // --- ending ----------------------------------------------------------------------------------------

    /**
     * Ends one escort, whatever the reason.
     *
     * <p>One exit for every failure, and that is deliberate: claim released, reservation released,
     * escort tether dropped, prisoner left in custody under the same sentence. The only thing this
     * does not do is clear the execution order, because an order that has already been armed and is
     * still live belongs to the device, not to the walk.
     */
    public static void end(@Nullable MinecraftServer server, @Nullable UUID captiveId,
                           @Nullable Escort escort, String why) {
        if (captiveId == null) {
            return;
        }
        Escort ended = escort == null ? ESCORTS.remove(captiveId) : ESCORTS.remove(captiveId);
        Escort target = ended == null ? escort : ended;
        if (server == null || target == null) {
            return;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        if (target.reservationToken != null) {
            CrimeFacilityService.release(data, target.reservationToken);
        }
        CrimeActivityRegistry.release(target.guard, target.claimGeneration);
        LawHold.clear(target.guard);
        TetherService.endEscort(server, captiveId, TetherService.DetachReason.ADMINISTRATIVE);
        JailEscortNavigation.forget(captiveId);
        CrimeDebug.crime("the condemned escort of {} ended: {}", captiveId, why);
    }

    /**
     * Walks a condemned prisoner back to a cell after the escort gave up (§3.19, M6.7).
     *
     * <p>{@link #end} releases the execution-site reservation and the activity claim, which is what
     * stops the walk; it does not decide where the prisoner goes next, because for clemency, a dead
     * guard or a destroyed device the answer differs. For the <b>timeout</b> the plan says it plainly:
     * the guard gives up and returns them to a cell. That is this, and it is the ordinary route rather
     * than a second one — an NPC re-enters {@code NpcCustodyService}'s escort loop, which reserves a
     * {@code JAIL_CELL} through {@code CrimeFacilityService} exactly as an arrest escort does, and a
     * player is put back under jail confinement.
     *
     * <p>Never a release and never a teleport into a cell: a capital sentence with no usable device is
     * a prisoner who stays a prisoner (§3.19.4). If nothing here can find a cell, the custody record
     * and the sentence are untouched and the reconcile pass tries again.
     *
     * @return whether an explicit return was started
     */
    public static boolean returnToCell(@Nullable MinecraftServer server, @Nullable UUID captiveId,
                                       @Nullable UUID guardId) {
        if (server == null || captiveId == null) {
            return false;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        dev.otectus.mcacrime.captivity.CustodyRecord held = data == null ? null : data.getCustody(captiveId);
        if (held == null || !held.isLawful()) {
            return false; // no lawful custody to return anybody to; nothing to do and nothing to invent
        }
        LivingEntity captive = living(server, captiveId);
        if (captive instanceof net.minecraft.server.level.ServerPlayer player) {
            // A condemned player was never let out of their sentence, so confinement is already the
            // thing holding them; this is the explicit step that puts them back inside its radius if
            // the walk had taken them out of it.
            dev.otectus.mcacrime.jail.JailService.recapture(player);
            CrimeDebug.crime("condemned player {} returned to confinement after the escort timed out",
                    captiveId);
            return true;
        }
        LivingEntity guard = guardId == null ? null : living(server, guardId);
        if (captive == null || guard == null || !guard.isAlive()
                || !(captive.level() instanceof ServerLevel level)) {
            return false; // the reconcile pass owns an escort with no guard or an unloaded prisoner
        }
        NpcCustodyService.beginEscort(level, captive, guard);
        CrimeDebug.crime("condemned villager {} is being walked back to a cell after the escort timed out",
                captiveId);
        return true;
    }

    /** Ends the escort of one subject, if there is one. The public form of {@link #end}. */
    public static boolean cancel(@Nullable MinecraftServer server, @Nullable UUID captiveId, String why) {
        Escort escort = captiveId == null ? null : ESCORTS.get(captiveId);
        if (escort == null) {
            return false;
        }
        end(server, captiveId, escort, why);
        return true;
    }

    /** Ends every escort one guard is running. Their death, their logout, their dimension change. */
    public static int cancelByGuard(@Nullable MinecraftServer server, @Nullable UUID guard, String why) {
        if (guard == null) {
            return 0;
        }
        List<UUID> doomed = ESCORTS.entrySet().stream()
                .filter(entry -> guard.equals(entry.getValue().guard))
                .map(Map.Entry::getKey)
                .toList();
        doomed.forEach(captive -> end(server, captive, ESCORTS.get(captive), why));
        return doomed.size();
    }

    /** Whether somebody is currently walking this prisoner to a device. */
    public static boolean escorting(@Nullable UUID captiveId) {
        return captiveId != null && ESCORTS.containsKey(captiveId);
    }

    /** How many escorts are running. Diagnostics and the tests. */
    public static int activeCount() {
        return ESCORTS.size();
    }

    /** Whether this prisoner's order has been armed and the ceremony is running. */
    public static boolean ceremonyRunning(@Nullable UUID captiveId) {
        Escort escort = captiveId == null ? null : ESCORTS.get(captiveId);
        return escort != null && escort.armedAt > 0L;
    }

    /** Forgets every escort without touching custody. Server stop, and every test's setup. */
    public static void clearAll() {
        ESCORTS.clear();
    }

    @Nullable
    private static LivingEntity living(MinecraftServer server, @Nullable UUID id) {
        if (id == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = level.getEntity(id);
            if (found instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }
}
