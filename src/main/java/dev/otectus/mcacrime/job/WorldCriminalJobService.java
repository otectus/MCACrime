package dev.otectus.mcacrime.job;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.CriminalJobChangedEvent;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.compat.OccupationCompat;
import dev.otectus.mcacrime.compat.OccupationSnapshot;
import dev.otectus.mcacrime.compat.mca.McaHandles;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.world.CriminalVillagerRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import dev.otectus.mcacrime.util.CrimeDebug;
import dev.otectus.mcacrime.state.world.WorksiteRef;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;

import javax.annotation.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The {@link CriminalJobService} over {@code CrimeWorldData} (0.5.1) — the only writer of criminal jobs.
 *
 * <p>Two facts are kept, and only one of them is authoritative. The record in the world data is what
 * makes a villager a thief; the MCA-visible profession is a label this mod may additionally hang on
 * them, per job, per the {@code presentFenceAsMcaProfession} / {@code presentThiefAsMcaProfession}
 * keys. A fence is discoverable by default because a fence nobody can find is not a shop; a thief is
 * not, because a thief wearing a "Thief" sign has no cover.
 *
 * <p>The label is reversible. The profession the villager had before is persisted with the record, so
 * clearing the job — or an operator turning the key off and reloading — puts the cleric back to being
 * a cleric instead of leaving the village permanently short one because a setting changed once.
 */
public final class WorldCriminalJobService implements CriminalJobService {

    private final MinecraftServer server;

    private WorldCriminalJobService(MinecraftServer server) {
        this.server = server;
    }

    public static WorldCriminalJobService of(MinecraftServer server) {
        return new WorldCriminalJobService(server);
    }

    @Override
    public CriminalJob get(UUID villager) {
        CriminalVillagerRecord record = raw(villager);
        return record == null ? CriminalJob.NONE : record.job();
    }

    @Override
    public boolean isCriminal(UUID villager) {
        return get(villager) != CriminalJob.NONE;
    }

    @Override
    public Optional<CriminalVillagerRecord> record(UUID villager) {
        return Optional.ofNullable(raw(villager));
    }

    @Override
    public Collection<CriminalVillagerRecord> all() {
        return server == null ? List.of() : CrimeWorldData.get(server).criminalVillagers();
    }

    @Override
    public void set(UUID villager, CriminalJob job) {
        assign(villager, job, false);
    }

    /**
     * Everything {@link NpcMuggerEligibility} needs about one villager, read live.
     *
     * <p>Nothing is cached. A role result is only true until MCA, an operator or a config reload
     * changes it, and a cache would be the same stale-role bug in a different place.
     *
     * <p>{@code classifiable} is the careful one. A non-MCA entity is fully classifiable — the
     * responder selector answers from its entity id alone — while an MCA villager is only classifiable
     * when its profession actually read back. With MCA unbound, both the villager test and the
     * profession read fail, and this reports unknown rather than "not a guard".
     */
    public static NpcMuggerEligibility.Facts facts(@Nullable Entity entity, CriminalJob recorded) {
        boolean thief = recorded == CriminalJob.THIEF;
        boolean criminal = recorded != null && recorded != CriminalJob.NONE;
        if (entity == null || !entity.isAlive() || entity.isRemoved()) {
            return NpcMuggerEligibility.Facts.unloaded(thief, criminal);
        }
        boolean villager = McaCompat.isMcaVillager(entity);
        boolean classifiable = dev.otectus.mcacrime.compat.mca.McaHandles.available()
                && (!villager || McaCompat.getProfessionId(entity).isPresent());
        return new NpcMuggerEligibility.Facts(true, villager, classifiable,
                dev.otectus.mcacrime.detect.EntitySelectors.isResponder(entity),
                McaCompat.isAdultVillager(entity), thief, criminal);
    }

    /**
     * The shared role decision for a loaded entity, against this world's records.
     *
     * <p>0.7.2 adds one clause, and only to {@link NpcMuggerEligibility.Context#EXECUTION}: an actor
     * must also currently <em>be</em> a Thief — a Crime occupation state that is allowed to act, and
     * the native {@code mcacrime:thief} profession right now. It is added here rather than at the four
     * call sites for the same reason S1 put the role test in one pure function: four copies of a gate
     * is how the gate drifts.
     */
    public NpcMuggerEligibility.Result evaluate(@Nullable Entity entity, NpcMuggerEligibility.Context context) {
        CriminalJob recorded = entity == null ? CriminalJob.NONE : get(entity.getUUID());
        NpcMuggerEligibility.Result base = NpcMuggerEligibility.evaluate(facts(entity, recorded), context);
        if (base.rejected() || context != NpcMuggerEligibility.Context.EXECUTION) {
            return base;
        }
        return mayActAsThief(entity)
                ? base
                : new NpcMuggerEligibility.Result(false, NpcMuggerEligibilityReason.NOT_A_THIEF);
    }

    /** The same decision by id, for callers holding only a record. An unloaded villager is unknown. */
    public NpcMuggerEligibility.Result evaluate(UUID villager, NpcMuggerEligibility.Context context) {
        if (server == null || villager == null) {
            return new NpcMuggerEligibility.Result(false, NpcMuggerEligibilityReason.NOT_LOADED);
        }
        Entity entity = findLoaded(villager);
        NpcMuggerEligibility.Result base =
                NpcMuggerEligibility.evaluate(facts(entity, get(villager)), context);
        if (base.rejected() || context != NpcMuggerEligibility.Context.EXECUTION) {
            return base;
        }
        return mayActAsThief(entity)
                ? base
                : new NpcMuggerEligibility.Result(false, NpcMuggerEligibilityReason.NOT_A_THIEF);
    }

    /**
     * Assigns a job, remembering whether the villager was found outside any village.
     *
     * <p>{@code wildOrigin} is preserved rather than overwritten when a criminal changes job: a thief
     * who was born in the wilderness and later becomes a fence did not acquire a home village by
     * changing trade.
     *
     * <p>Kept {@code void} because {@link CriminalJobService#set} and every existing caller depend on
     * that shape; {@link #tryAssign} is the same work with the answer attached.
     */
    public void assign(UUID villager, CriminalJob job, boolean wildOrigin) {
        tryAssign(villager, job, wildOrigin);
    }

    /**
     * Assigns a job and says whether it took, and if not, why (0.7.2).
     *
     * <p>Every route into a criminal job passes through here — the sweep, {@code /crime job}, the API
     * delegate and any third party holding the {@link CriminalJobService} — so the role gate cannot be
     * walked around by picking a different entry point. Clearing a job is never gated: reverting a
     * guard who is wrongly carrying a record is the repair, and refusing it would make the defect
     * permanent.
     */
    public NpcMuggerEligibility.Result tryAssign(UUID villager, CriminalJob job, boolean wildOrigin) {
        if (server == null || villager == null || job == null || !ServerMutationGate.allows(server)) {
            return new NpcMuggerEligibility.Result(false, NpcMuggerEligibilityReason.NOT_LOADED);
        }
        if (job != CriminalJob.NONE) {
            NpcMuggerEligibility.Result role =
                    NpcMuggerEligibility.evaluate(facts(findLoaded(villager), job),
                            NpcMuggerEligibility.Context.ASSIGNMENT);
            if (role.rejected()) {
                CrimeDebug.crime("Criminal job {} refused for {}: {}", job, villager, role.reason());
                return role;
            }
        }
        applyAssignment(villager, job, wildOrigin);
        return new NpcMuggerEligibility.Result(true, NpcMuggerEligibilityReason.ELIGIBLE);
    }

    private void applyAssignment(UUID villager, CriminalJob job, boolean wildOrigin) {
        if (job == CriminalJob.NONE && get(villager) == CriminalJob.THIEF) {
            // Clearing an exclusive occupation is a transition too: the station claim has to be given
            // back and the previous profession restored, which a record deletion would not do.
            retireOccupation(villager, OccupationSource.OPERATOR);
            return;
        }
        if (job == CriminalJob.THIEF) {
            // Spec §10.1: every route into a Thief converges here, so a caller that still says
            // assign(id, THIEF, wild) gets the full transaction rather than a record write and a hope.
            requestThiefOccupation(OccupationRequest.unbound(villager,
                    wildOrigin ? OccupationSource.WILD : OccupationSource.OPERATOR, wildOrigin));
            return;
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        CriminalVillagerRecord existing = world.criminalVillager(villager);
        CriminalJob previous = existing == null ? CriminalJob.NONE : existing.job();
        if (previous == job) {
            return;
        }
        long day = day();
        if (job == CriminalJob.NONE) {
            // Reverted first: once the record is gone there is nowhere left to read what this villager
            // used to do for a living.
            revertPresentation(villager, existing);
            world.removeCriminalVillager(villager);
            CrimeDebug.crime("Criminal job cleared for {} (was {})", villager, previous);
        } else {
            CriminalVillagerRecord record = existing == null
                    ? CriminalVillagerRecord.fresh(villager, job, day, wildOrigin, seedFor(villager),
                            OccupationSource.OPERATOR)
                    : existing.withJob(job).withLastSeenDay(day);
            if (!world.putCriminalVillager(record).stored()) return;
            applyPresentation(villager);
            CrimeDebug.crime("Criminal job {} assigned to {} (wild={})", job, villager, record.wildOrigin());
        }
        refreshBehavior(villager, job);
        MinecraftForge.EVENT_BUS.post(new CriminalJobChangedEvent(villager, previous, job));
        broadcast(villager, job);
    }

    @Override
    public void touchMug(UUID villager, long now) {
        if (server == null || villager == null) {
            return;
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        CriminalVillagerRecord existing = world.criminalVillager(villager);
        if (existing == null) {
            return;
        }
        world.putCriminalVillager(existing.withLastMugAt(now));
    }

    /** Stamps that this criminal was seen loaded today, so a later sweep can retire stale records. */
    public void touchSeen(UUID villager, long day) {
        if (server == null || villager == null) {
            return;
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        CriminalVillagerRecord existing = world.criminalVillager(villager);
        if (existing == null || existing.lastSeenDay() == day) {
            return;
        }
        world.putCriminalVillager(existing.withLastSeenDay(day));
    }

    /**
     * Re-decides the visible profession of every loaded criminal against the current config.
     *
     * <p>Called on config reload, because both presentation keys are answers to a question that is
     * otherwise asked once per villager: a pack that turns the thief label on wants the thieves it
     * already has to wear it, and a pack that turns the fence label off wants its shopkeepers back.
     */
    public void refreshPresentation() {
        if (server == null) {
            return;
        }
        for (CriminalVillagerRecord record : all()) {
            if (present(record.job())) {
                applyPresentation(record.villager());
            } else {
                revertPresentation(record.villager(), record);
            }
        }
    }

    /** Reconnect loaded thieves after enable/disable changes without waiting for a chunk reload. */
    public void refreshBehaviors() {
        if (server == null || !ServerMutationGate.allows(server)) return;
        for (CriminalVillagerRecord record : all()) refreshBehavior(record.villager(), record.job());
    }

    private void refreshBehavior(UUID villager, CriminalJob job) {
        if (job == CriminalJob.THIEF && occupationStatus(villager).mayMug()) {
            ThiefWorkRegistry.markEmployed(villager);
        } else {
            ThiefWorkRegistry.clearEmployed(villager);
        }
        if (job == CriminalJob.THIEF && McaCrimeConfig.COMMON.enableThieves.get()) {
            if (findLoaded(villager) instanceof net.minecraft.world.entity.LivingEntity living)
                dev.otectus.mcacrime.ai.thief.ThiefBehaviorService.track(living);
        } else {
            dev.otectus.mcacrime.ai.thief.ThiefTicker.stop(villager);
        }
    }

    /**
     * Puts this mod's profession on a criminal, saving whatever they were doing before.
     *
     * <p>No-op when the villager is unloaded: presentation is a property of an entity that exists, and
     * the record is re-examined by {@link #refreshPresentation()} when it comes back.
     */
    private void applyPresentation(UUID villager) {
        CriminalVillagerRecord record = raw(villager);
        if (record == null || !present(record.job()) || record.job() == CriminalJob.THIEF) {
            // Thief presentation is no longer presentation: the profession *is* the occupation, and it
            // is owned by the transaction. Writing it from here would be the second writer spec §9.3
            // exists to remove, and it would bypass the ticket, the offers and the XP floor.
            return;
        }
        ResourceLocation target = CriminalProfessions.professionIdFor(record.job());
        Entity entity = findLoaded(villager);
        if (target == null || entity == null) {
            return;
        }
        ResourceLocation current = McaCompat.getProfessionId(entity).orElse(null);
        if (target.equals(current)) {
            return;
        }
        if (!McaCompat.setVillagerProfession(entity, target)) {
            CrimeDebug.compat("MCA has no profession setter bound; {} keeps its visible profession", villager);
            return;
        }
        // An empty string means "had no readable profession", which is still a fact worth persisting:
        // it is the difference between reverting to unemployed and never reverting at all.
        CrimeWorldData.get(server).putCriminalVillager(record.withPreviousProfession(
                current == null ? dev.otectus.mcacrime.job.HistoricalProfessionKind.UNREADABLE
                        : dev.otectus.mcacrime.job.HistoricalProfessionKind.ID,
                current == null ? null : current.toString()));
    }

    /** Puts back what the villager was doing before this mod relabelled them, if anything. */
    private void revertPresentation(UUID villager, @Nullable CriminalVillagerRecord record) {
        if (record == null || record.previousProfessionKind() == HistoricalProfessionKind.NONE
                || record.previousProfessionKind() == HistoricalProfessionKind.UNREADABLE) {
            // UNREADABLE is not nothing: it says a profession existed and could not be read, and
            // reverting to a guess would replace a cleric with an unemployed villager.
            return;
        }
        Entity entity = findLoaded(villager);
        if (entity != null) {
            ResourceLocation previous = record.previousProfessionId().isBlank()
                    ? new ResourceLocation("minecraft", "none")
                    : ResourceLocation.tryParse(record.previousProfessionId());
            if (previous != null) {
                McaCompat.setVillagerProfession(entity, previous);
            }
        }
        if (raw(villager) != null) {
            CrimeWorldData.get(server).putCriminalVillager(
                    record.withPreviousProfession(HistoricalProfessionKind.NONE, null));
        }
    }

    /**
     * Whether this job is shown to players as an MCA profession at all.
     *
     * <p>Fence is still the pack's choice. <b>Thief is not, as of 0.7.2</b>: spec §9.1 makes
     * {@code mcacrime:thief} a real exclusive occupation and §9.2 says the deprecated
     * {@code presentThiefAsMcaProfession=false} "must no longer allow a hidden Thief". The key is
     * still parsed, so a config file that sets it neither breaks nor is silently rewritten, and
     * {@link #warnAboutDeprecatedThiefPresentation()} says so once at load.
     */
    private static boolean present(CriminalJob job) {
        return switch (job) {
            case FENCE -> McaCrimeConfig.COMMON.presentFenceAsMcaProfession.get();
            case THIEF -> true;
            case NONE -> false;
        };
    }

    private static boolean deprecationLogged;

    /**
     * One line, once, when a config still asks for a hidden Thief (spec §9.2's migration notice).
     *
     * <p>Called from common setup rather than from {@link #present}: the answer is the same for every
     * villager, and a per-villager warning on a server with two hundred thieves is a log flood that
     * tells an operator nothing the first line did not.
     */
    public static synchronized void warnAboutDeprecatedThiefPresentation() {
        if (deprecationLogged) {
            return;
        }
        deprecationLogged = true;
        try {
            if (!McaCrimeConfig.COMMON.presentThiefAsMcaProfession.get()) {
                McaCrime.LOGGER.warn("MCA: Crime: criminalJobs.presentThiefAsMcaProfession is deprecated and "
                        + "no longer has any effect. As of 0.7.2 a Thief is a real, visible, exclusive "
                        + "villager profession ('mcacrime:thief') with its own Mask Station workplace; a "
                        + "hidden thief overlay on another job is no longer a supported state. The Fence "
                        + "presentation key is unchanged.");
            }
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("Thief presentation deprecation notice skipped (config not ready)", t);
        }
    }

    private void broadcast(UUID villager, CriminalJob job) {
        Entity entity = findLoaded(villager);
        if (entity != null) {
            CrimeNetwork.broadcastCriminalJob(entity, job);
        }
    }

    @Nullable
    private CriminalVillagerRecord raw(UUID villager) {
        return server == null || villager == null ? null : CrimeWorldData.get(server).criminalVillager(villager);
    }

    @Nullable
    private Entity findLoaded(UUID villager) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(villager);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    /**
     * A stable per-villager seed for behaviour that should differ between two thieves but never
     * between two sessions of the same one. Derived from the UUID, so it survives a record rewrite.
     */
    private static long seedFor(UUID villager) {
        return villager.getMostSignificantBits() ^ villager.getLeastSignificantBits();
    }

    private long day() {
        return server.overworld().getGameTime() / 24000L;
    }

    // --- the occupation transaction (0.7.2 §9.3) --------------------------------------------------

    /**
     * Villagers with a transition in flight.
     *
     * <p>The generation guard spec §9.3 asks for, in its smallest honest form. MCA's profession setter
     * refreshes the brain, which stops running behaviours, which can re-enter this service through a
     * behaviour's {@code doStop}; without this, that re-entry would snapshot a half-changed villager
     * and roll the first transaction back into the middle of the second.
     */
    private static final Set<UUID> IN_FLIGHT = Collections.synchronizedSet(new HashSet<>());

    /** So the missing-capability diagnostic is actionable without being a log flood (spec §9.4). */
    private static long lastCapabilityWarning;

    /**
     * The single validated entry point into a Thief occupation.
     *
     * <p>Order is the contract: everything that can refuse is asked before anything is written, the
     * writes themselves are {@link OccupationTransaction}'s, and the record, the behaviour controller
     * and {@link CriminalJobChangedEvent} follow only a committed result.
     */
    public OccupationTransitionResult requestThiefOccupation(OccupationRequest request) {
        if (server == null || request == null || request.villager() == null
                || !ServerMutationGate.allows(server)) {
            return OccupationTransitionResult.rejected(OccupationTransitionReason.NOT_MUTABLE);
        }
        UUID villager = request.villager();
        if (!McaCrimeConfig.COMMON.enableThieves.get() && request.source() != OccupationSource.MIGRATION) {
            // Disabling thieves blocks new requests; it does not strip the profession off the ones
            // that already exist (spec §"Disabling thieves"), which is why this is a rejection here
            // and an inert controller in refreshBehavior.
            return OccupationTransitionResult.rejected(OccupationTransitionReason.DISABLED);
        }
        if (!IN_FLIGHT.add(villager)) {
            return OccupationTransitionResult.rejected(OccupationTransitionReason.GENERATION_CONFLICT);
        }
        try {
            return runTransition(villager, request);
        } catch (Throwable t) {
            CrimeDebug.crime("Thief occupation transition for {} failed unexpectedly: {}", villager, t);
            return OccupationTransitionResult.rejected(OccupationTransitionReason.MUTATION_FAILED,
                    String.valueOf(t));
        } finally {
            IN_FLIGHT.remove(villager);
        }
    }

    private OccupationTransitionResult runTransition(UUID villager, OccupationRequest request) {
        Entity entity = findLoaded(villager);
        if (entity == null) {
            // Spec §9.4: never activate against an unloaded UUID that cannot be classified. The
            // request is reported as pending and revalidated by the lifecycle when the entity loads.
            return OccupationTransitionResult.pending(OccupationTransitionReason.NOT_LOADED);
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return OccupationTransitionResult.rejected(OccupationTransitionReason.NOT_MUTABLE);
        }
        List<String> missing = OccupationCompat.missingCapability();
        if (!missing.isEmpty()) {
            warnMissingCapability(missing);
            return OccupationTransitionResult.suspended(OccupationTransitionReason.CAPABILITY_MISSING,
                    String.join(", ", missing));
        }
        NpcMuggerEligibility.Result role = NpcMuggerEligibility.evaluate(
                facts(entity, CriminalJob.THIEF), NpcMuggerEligibility.Context.ASSIGNMENT);
        if (role.rejected()) {
            return OccupationTransitionResult.rejected(reasonFor(role.reason()));
        }
        if (CustodyRegistry.isCaptive(server, villager)) {
            return OccupationTransitionResult.rejected(OccupationTransitionReason.BUSY);
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        if (!world.hasCriminalCapacityFor(villager)) {
            return OccupationTransitionResult.rejected(OccupationTransitionReason.RECORD_CAPACITY);
        }
        CriminalVillagerRecord existing = world.criminalVillager(villager);
        boolean alreadyThief = existing != null && existing.job() == CriminalJob.THIEF
                && CriminalProfessions.THIEF_ID.equals(McaCompat.getProfessionId(entity).orElse(null));
        if (request.source().requiresStation()) {
            if (McaHandles.isProfessionImportant(entity)) {
                return OccupationTransitionResult.rejected(OccupationTransitionReason.PROTECTED_NPC);
            }
            if (!alreadyThief && employedElsewhere(entity)) {
                // Spec §10.1: the settlement path never secretly converts an employed villager.
                return OccupationTransitionResult.rejected(OccupationTransitionReason.ALREADY_EMPLOYED);
            }
        }

        OccupationSnapshot before = OccupationCompat.capture(entity).orElse(null);
        OccupationRequest effective = alreadyThief && !request.rebindOnly()
                ? new OccupationRequest(villager, request.source(), request.worksite(),
                        request.adoptExisting(), true, request.establishedAlready(), request.wildOrigin())
                : request;
        OccupationTransitionResult result = OccupationTransaction.run(
                new EntityOccupationMutator(level, entity, CriminalProfessions.THIEF_ID,
                        ThiefWorksiteService.heldReservation(existing)), effective);
        if (!result.committed()) {
            if (result.suspended() && existing != null) {
                world.putCriminalVillager(existing.withStatus(OccupationStatus.SUSPENDED));
            }
            CrimeDebug.crime("Thief occupation for {} did not commit: {} ({})",
                    villager, result.reason(), result.detail());
            return result;
        }
        persistCommitted(world, villager, existing, effective, result, before);
        return result;
    }

    /** Writes the record, starts the controller and publishes — in that order, and only once. */
    private void persistCommitted(CrimeWorldData world, UUID villager,
                                  @Nullable CriminalVillagerRecord existing, OccupationRequest request,
                                  OccupationTransitionResult result, @Nullable OccupationSnapshot before) {
        long day = day();
        CriminalJob previous = existing == null ? CriminalJob.NONE : existing.job();
        CriminalVillagerRecord record = existing == null
                ? CriminalVillagerRecord.fresh(villager, CriminalJob.THIEF, day, request.wildOrigin(),
                        seedFor(villager), request.source())
                : existing.withJob(CriminalJob.THIEF).withLastSeenDay(day).withSource(request.source());
        record = record.withStatus(result.status()).withWorksite(request.worksite());
        if (request.worksite() == null) {
            record = record.withReservation(null, 0L);
        }
        if (result.status().established() && record.establishedAt() == 0L) {
            record = record.withEstablishedAt(server.overworld().getGameTime());
        }
        if (before != null && record.previousProfessionKind() == HistoricalProfessionKind.NONE
                && previous != CriminalJob.THIEF) {
            record = record.withPreviousProfession(before.professionKind(),
                    before.professionId() == null ? null : before.professionId().toString());
        }
        if (!world.putCriminalVillager(record).stored()) {
            // Capacity was checked before the transaction, so this is a store that went full underneath
            // us. The villager is now a visible Thief with no record, which is the one disagreement
            // spec §10.1 forbids -- so it is reported rather than swallowed.
            McaCrime.LOGGER.error("MCA: Crime committed a Thief profession for {} but could not store its "
                    + "record. Run /crime job {} none to repair.", villager, villager);
            return;
        }
        stampVillageCooldown(request.source(), villager);
        refreshBehavior(villager, CriminalJob.THIEF);
        if (previous != CriminalJob.THIEF) {
            MinecraftForge.EVENT_BUS.post(new CriminalJobChangedEvent(villager, previous, CriminalJob.THIEF));
        }
        broadcast(villager, CriminalJob.THIEF);
        CrimeDebug.crime("Thief occupation committed for {} ({}, {})",
                villager, result.status(), request.source());
    }

    /**
     * Retires an occupation through the same transaction (spec §10.4's administrative row).
     *
     * <p>The station claim goes and the profession goes back to whatever was remembered, but the legal
     * history, the mug cooldown and the personality seed stay on a retired record: dropping them with
     * the role would make being fired a way to reset a cooldown.
     */
    public OccupationTransitionResult retireOccupation(UUID villager, OccupationSource source) {
        if (server == null || villager == null || !ServerMutationGate.allows(server)) {
            return OccupationTransitionResult.rejected(OccupationTransitionReason.NOT_MUTABLE);
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        CriminalVillagerRecord existing = world.criminalVillager(villager);
        if (existing == null || existing.job() != CriminalJob.THIEF) {
            return OccupationTransitionResult.rejected(OccupationTransitionReason.NOT_ELIGIBLE);
        }
        Entity entity = findLoaded(villager);
        WorksiteRef site = existing.worksite();
        if (entity != null && entity.level() instanceof ServerLevel level) {
            if (site != null && site.matches(level)) {
                OccupationCompat.releaseTicket(level, site.pos());
            }
            OccupationCompat.clearOccupationalMemories(entity);
            OccupationCompat.setVillagerXp(entity, 0);
            revertPresentation(villager, existing);
        }
        world.putCriminalVillager(existing.retired().withSource(source));
        dev.otectus.mcacrime.ai.thief.ThiefTicker.stop(villager);
        MinecraftForge.EVENT_BUS.post(new CriminalJobChangedEvent(villager, CriminalJob.THIEF, CriminalJob.NONE));
        broadcast(villager, CriminalJob.NONE);
        CrimeDebug.crime("Thief occupation retired for {} ({})", villager, source);
        return OccupationTransitionResult.committed(OccupationStatus.RETIRED);
    }

    /**
     * Charges the village's assignment cooldown, and only once a recruitment has actually committed.
     *
     * <p>Spec §10.1. The sweep used to stamp this the instant it rolled a thief, so a village whose
     * candidate never reached a station was put on cooldown for the failure.
     */
    private void stampVillageCooldown(OccupationSource source, UUID villager) {
        if (source != OccupationSource.SETTLEMENT_SWEEP && source != OccupationSource.STATION_RECRUITMENT) {
            return;
        }
        Entity entity = findLoaded(villager);
        if (entity == null || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        dev.otectus.mcacrime.detect.CrimeCommunityResolver.resolve(entity, level).ifPresent(key ->
                CrimeWorldData.get(server).setActionCounter(
                        "criminalJobs.lastAssignDay." + key.asString(), day() + 1L));
    }

    /** This villager's occupation state, or {@link OccupationStatus#NONE}. */
    public OccupationStatus occupationStatus(UUID villager) {
        CriminalVillagerRecord record = raw(villager);
        return record == null ? OccupationStatus.NONE : record.status();
    }

    /**
     * Whether this villager may act as a Thief right now (spec §11.3, extended for 0.7.2).
     *
     * <p>Three separate facts, all required. S1's role eligibility says they are not law; the record
     * says they are a thief; and this adds the two the exclusive profession introduced — the
     * occupation state must be one that is allowed to act, and the <em>native</em> profession must
     * actually be {@code mcacrime:thief} right now. A stale THIEF record alone never authorises a
     * mugging, which is what makes an externally converted guard stop immediately rather than at the
     * next sweep.
     */
    public boolean mayActAsThief(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        CriminalVillagerRecord record = raw(entity.getUUID());
        if (record == null || record.job() != CriminalJob.THIEF || !record.status().mayMug()) {
            return false;
        }
        return CriminalProfessions.THIEF_ID.equals(McaCompat.getProfessionId(entity).orElse(null));
    }

    /** True when this villager already holds some other profession (spec §10.1's "unemployed adults"). */
    private static boolean employedElsewhere(Entity entity) {
        ResourceLocation current = McaCompat.getProfessionId(entity).orElse(null);
        if (current == null) {
            return true; // unreadable is not unemployed
        }
        return !"none".equals(current.getPath()) && !CriminalProfessions.THIEF_ID.equals(current);
    }

    private static OccupationTransitionReason reasonFor(NpcMuggerEligibilityReason reason) {
        return switch (reason) {
            case RESPONDER -> OccupationTransitionReason.RESPONDER;
            case NOT_LOADED -> OccupationTransitionReason.NOT_LOADED;
            case CLASSIFICATION_UNAVAILABLE -> OccupationTransitionReason.CLASSIFICATION_UNAVAILABLE;
            default -> OccupationTransitionReason.NOT_ELIGIBLE;
        };
    }

    private void warnMissingCapability(List<String> missing) {
        long now = server.overworld().getGameTime();
        if (lastCapabilityWarning != 0L && now - lastCapabilityWarning < 6000L) {
            return;
        }
        lastCapabilityWarning = now;
        McaCrime.LOGGER.warn("MCA: Crime cannot create or maintain Thief occupations: this MCA build does "
                + "not provide {}. Existing thieves are suspended and no new one is assigned; every other "
                + "Crime feature is unaffected. Please report this with your MCA version.", missing);
    }
}
