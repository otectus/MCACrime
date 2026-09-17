package dev.otectus.mcacrime.integration;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.compat.ReputationBridge;
import dev.otectus.mcacrime.compat.ReputationDelivery;
import dev.otectus.mcacrime.compat.ReputationOps;
import dev.otectus.mcacrime.compat.TownsteadBridge;
import dev.otectus.mcacrime.ledger.CrimeCaseService;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.relationship.RelationshipConsequences;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Drains the integration outbox: takes queued cross-mod writes and actually delivers them.
 *
 * <p>This is the only thing in the mod that talks to a companion. Everything else queues work and
 * moves on, which is what makes a crash between our commit and theirs a delay rather than a
 * permanent disagreement about what happened.
 *
 * <h2>When it runs</h2>
 *
 * <ul>
 *   <li><b>Server started</b> — one larger drain, to clear anything a crash or an uninstalled
 *       companion left behind.</li>
 *   <li><b>Player login</b> — that player's work, so somebody returning after a week does not wait
 *       on the periodic tick to have their standing reconciled.</li>
 *   <li><b>Every {@code pumpIntervalTicks}</b> — a small budgeted pass. An idle server with an empty
 *       queue does one map lookup and returns.</li>
 * </ul>
 *
 * <p>Delivery is never attempted while loading saved data. That rule exists because calling into an
 * optional mod during deserialisation is how a missing class turns a world load into a crash.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeIntegrationPump {

    /** How much of a backlog the start-up and login drains may clear in one go. */
    private static final int STARTUP_BUDGET_MULTIPLIER = 20;

    /**
     * The most villagers one public reaction is played for.
     *
     * <p>A crowd of eight is already more than a player reads as a reaction; past that it is a village
     * doing one thing in unison, which looks like a bug. It is also the only bound on the entity work
     * the outbox does.
     */
    private static final int MAX_REACTION_AUDIENCE = 8;

    private static int tickCounter;

    private CrimeIntegrationPump() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        tickCounter = 0;
        // The capability handshake runs whether or not the queue is being drained: it is a read-only
        // question about the installed companion, and /crime debug integrations should be able to
        // answer it even on a server that has replayPendingOperations switched off.
        ReputationBridge.negotiate(event.getServer());
        // Townstead binds beside the Reputation handshake and for the same reason: it is a read-only
        // question about an installed companion, it needs a server to be meaningful, and an operator
        // reading the log wants both answers in one place. Binding is idempotent and never throws.
        TownsteadBridge.bind();
        if (!McaCrimeConfig.COMMON.replayPendingOperations.get()) {
            return;
        }
        // Authority is claimed here rather than at common setup: the incident definitions we are about
        // to produce come from a datapack, and there is no datapack yet when mods are still loading.
        ReputationBridge.claimAuthority();
        int pending = CrimeWorldData.get(event.getServer()).pendingOperationCount();
        if (pending > 0) {
            McaCrime.LOGGER.info("MCA: Crime has {} queued cross-mod write(s) from a previous session; "
                    + "replaying them now.", pending);
            drain(event.getServer(), McaCrimeConfig.COMMON.pumpBudgetPerTick.get() * STARTUP_BUDGET_MULTIPLIER);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // Hand detection back before we go, so a second world in the same session does not start with
        // an authority claim nobody is honouring.
        ReputationBridge.releaseAuthority();
        // Drop the Townstead binding too, so a second world in one session re-binds against whatever is
        // installed then rather than inheriting this server's answer.
        TownsteadBridge.release();
        TownsteadReactions.clearAll();
        tickCounter = 0;
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!McaCrimeConfig.COMMON.replayPendingOperations.get()
                || event.getEntity().getServer() == null) {
            return;
        }
        drain(event.getEntity().getServer(),
                McaCrimeConfig.COMMON.pumpBudgetPerTick.get() * STARTUP_BUDGET_MULTIPLIER);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!McaCrimeConfig.COMMON.replayPendingOperations.get()) {
            return;
        }
        if (++tickCounter < McaCrimeConfig.COMMON.pumpIntervalTicks.get()) {
            return;
        }
        tickCounter = 0;
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        if (data.pendingOperationCount() > 0) {
            drain(server, McaCrimeConfig.COMMON.pumpBudgetPerTick.get());
        }
        data.pruneDedupe(server.overworld().getGameTime());
    }

    // ------------------------------------------------------------------ delivery

    /** Attempts up to {@code budget} due operations, oldest first. */
    public static void drain(MinecraftServer server, int budget) {
        CrimeWorldData data = CrimeWorldData.get(server);
        long now = server.overworld().getGameTime();
        List<CrimeIntegrationOperation> due = data.dueOperations(now, budget);
        for (CrimeIntegrationOperation operation : due) {
            DeliveryOutcome outcome = deliver(server, operation);
            record(server, data, operation, outcome, now);
        }
    }

    private static void record(MinecraftServer server, CrimeWorldData data,
                               CrimeIntegrationOperation operation, DeliveryOutcome outcome, long now) {
        if (IntegrationTargets.isTownstead(operation.target())) {
            recordTownstead(data, operation, outcome);
            return;
        }
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        if (outcome.successful()) {
            data.updateOperation(operation.complete());
            return;
        }
        // An absent companion is a delay, not a failure, so it must not burn the attempt budget --
        // otherwise a week away from the game dead-letters everything that was pending.
        int attempts = DeliveryPolicy.countsAgainstBudget(outcome) ? operation.attempts() : 0;
        CrimeIntegrationOperation.Status next =
                DeliveryPolicy.classify(outcome, attempts, c.maxDeliveryAttempts.get());

        if (next == CrimeIntegrationOperation.Status.DEAD_LETTER) {
            data.updateOperation(operation.deadLetter(outcome.name()));
            McaCrime.LOGGER.warn("MCA: Crime gave up delivering {} for crime {} after {} attempt(s): {}. "
                            + "The crime itself is intact; see /crime debug outbox. If this says "
                            + "UNKNOWN_TARGET the companion mod does not recognise an incident this mod "
                            + "ships, which usually means a datapack is overriding or missing it.",
                    operation.target(), operation.crimeRecordId(), operation.attempts() + 1, outcome);
            // Nobody recorded the civic consequence, so apply our own after all -- otherwise the deed
            // costs the player nothing publicly, which is worse than counting it locally. The rule is
            // DeliveryPolicy's rather than an inline check, because it is the one decision on this queue
            // that can take something from a player and it must never be reachable from another mod's
            // target: see appliesLocalVillagePenalty.
            if (DeliveryPolicy.appliesLocalVillagePenalty(operation.target(), operation.action(), next)) {
                RelationshipConsequences.applyDeferredVillagePenalty(server, operation.crimeRecordId());
            }
            return;
        }
        long nextAttempt = DeliveryPolicy.nextAttemptTime(attempts, now,
                c.retryBaseDelayTicks.get(), c.retryMaxDelayTicks.get());
        data.updateOperation(operation.withAttempt(nextAttempt, outcome.name()));
    }

    /**
     * What happens to a Townstead delivery, which is nothing like what happens to a civic one.
     *
     * <p>Two rules, and the second is the whole reason this method exists rather than an extra branch
     * in {@link #record}:
     *
     * <ul>
     *   <li><b>Success is recorded once, durably.</b> The one-shot receipt is what makes a replayed
     *       queue, a relog and an operator retry produce one reaction between them rather than three.</li>
     *   <li><b>Failure is logged and dropped, here and nowhere else.</b> Not retried, because a
     *       reaction is a thing that happens at a moment and playing it a minute later is worse than
     *       not playing it. Not dead-lettered, because the dead-letter list is an operator's queue of
     *       work still owed. And emphatically never routed through the civic path above, where giving
     *       up applies a local village-standing penalty on the grounds that the crime went unrecorded
     *       — the crime was recorded, and charging a player standing because an animation did not play
     *       would be a punishment with no deed behind it.</li>
     * </ul>
     */
    private static void recordTownstead(CrimeWorldData data, CrimeIntegrationOperation operation,
                                        DeliveryOutcome outcome) {
        if (outcome.successful()) {
            data.recordOneShotReceipt(operation.operationId());
            data.updateOperation(operation.complete());
            return;
        }
        McaCrime.LOGGER.debug("MCA: Crime — Townstead did not play the {} reaction for {} ({}); dropping "
                        + "it. The case, its Heat and its sentence are unaffected.",
                operation.payload().getString(IntegrationTargets.PAYLOAD_EVENT),
                operation.crimeRecordId(), outcome);
        data.discardOperation(operation.operationId());
    }

    /**
     * Plays one public reaction for the villagers who could plausibly know about it.
     *
     * <p>Bounded three ways, because this is the only place in the outbox that touches loaded entities:
     * the radius comes from the datapack binding and is capped there, the audience is capped here, and
     * an unloaded destination simply does not happen rather than forcing a chunk load.
     *
     * <p>An empty street is a success, not a failure. Nobody was there to react; there is nothing owed
     * and nothing to retry.
     */
    private static DeliveryOutcome deliverTownsteadReaction(MinecraftServer server,
                                                            CrimeIntegrationOperation operation) {
        if (!TownsteadBridge.has(dev.otectus.mcacrime.compat.TownsteadCapability.DISPATCH_REACTION)) {
            return DeliveryOutcome.UNAVAILABLE;
        }
        CompoundTag payload = operation.payload();
        ResourceLocation dimension =
                ResourceLocation.tryParse(payload.getString(IntegrationTargets.PAYLOAD_DIMENSION));
        ResourceLocation reaction =
                ResourceLocation.tryParse(payload.getString(IntegrationTargets.PAYLOAD_REACTION_ID));
        if (dimension == null || reaction == null) {
            return DeliveryOutcome.INVALID;
        }
        net.minecraft.server.level.ServerLevel level = server.getLevel(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        dimension));
        if (level == null) {
            return DeliveryOutcome.INVALID;
        }
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                payload.getInt(IntegrationTargets.PAYLOAD_X),
                payload.getInt(IntegrationTargets.PAYLOAD_Y),
                payload.getInt(IntegrationTargets.PAYLOAD_Z));
        int radius = Math.max(1, Math.min(
                dev.otectus.mcacrime.compat.TownsteadReactionBindings.MAX_RADIUS,
                payload.getInt(IntegrationTargets.PAYLOAD_RADIUS)));
        if (!level.isLoaded(pos)) {
            // Nobody is there to see it. Not an error and not worth loading a chunk for.
            return DeliveryOutcome.SUCCESS;
        }
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(pos).inflate(radius);
        String phase = payload.getString(IntegrationTargets.PAYLOAD_EVENT);
        int asked = 0;
        for (net.minecraft.world.entity.LivingEntity villager : level.getEntitiesOfClass(
                net.minecraft.world.entity.LivingEntity.class, box,
                entity -> entity.isAlive()
                        && dev.otectus.mcacrime.compat.McaCompat.isMcaVillager(entity))) {
            if (asked >= MAX_REACTION_AUDIENCE) {
                break;
            }
            asked++;
            if (TownsteadBridge.dispatchReaction(level, villager, reaction, phase).isFailed()) {
                return DeliveryOutcome.TRANSIENT_FAILURE;
            }
        }
        return DeliveryOutcome.SUCCESS;
    }

    private static DeliveryOutcome deliver(MinecraftServer server, CrimeIntegrationOperation operation) {
        if (IntegrationTargets.isTownstead(operation.target())) {
            try {
                return deliverTownsteadReaction(server, operation);
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA: Crime — a Townstead reaction delivery threw; dropping it", t);
                return DeliveryOutcome.TRANSIENT_FAILURE;
            }
        }
        Optional<ReputationOps> bridge = ReputationBridge.ops();
        if (bridge.isEmpty()) {
            return DeliveryOutcome.UNAVAILABLE;
        }
        ReputationOps ops = bridge.get();
        try {
            if (!ops.acceptsWrites()) {
                return DeliveryOutcome.UNAVAILABLE;
            }
            if (operation.action().equals(IntegrationTargets.ACTION_CREATE)) {
                return deliverCreate(server, ops, operation);
            }
            if (operation.action().equals(IntegrationTargets.ACTION_RESOLVE)) {
                return deliverResolve(server, ops, operation);
            }
            return DeliveryOutcome.INVALID;
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — delivering {} threw; will retry", operation.target(), t);
            return DeliveryOutcome.TRANSIENT_FAILURE;
        }
    }

    /**
     * Files the civic incident for a committed case.
     *
     * <p>Three things changed in 0.7.3 and they are all about not guessing. The write goes through the
     * companion's keyed delivery, so it is exactly-once across a crash and answers with a typed
     * outcome rather than an optional id. A lost answer is repaired by reading the receipt back, never
     * by sending another write. And a killing that finished an assault we already filed supersedes it
     * instead of stacking on top of it, which is what the companion's own detector would have done with
     * the deed we took off it.
     */
    private static DeliveryOutcome deliverCreate(MinecraftServer server, ReputationOps ops,
                                                 CrimeIntegrationOperation operation) {
        CompoundTag payload = operation.payload();
        ResourceLocation incidentType =
                ResourceLocation.tryParse(payload.getString(IntegrationTargets.PAYLOAD_INCIDENT_TYPE));
        Optional<CrimeCommunityKey> community =
                CrimeCommunityKey.load(payload.getCompound(IntegrationTargets.PAYLOAD_COMMUNITY));
        Optional<CrimeRecordView> view = CrimeCaseService.view(server, operation.crimeRecordId());
        if (incidentType == null || community.isEmpty() || view.isEmpty()) {
            return DeliveryOutcome.INVALID;
        }
        // Already linked: a previous attempt got through and we simply never saw the answer.
        if (view.get().linkedReputationIncidentId().isPresent()) {
            return DeliveryOutcome.ALREADY_DONE;
        }
        String operationKey = payload.getString(IntegrationTargets.PAYLOAD_DEDUPE_KEY);

        long window = supersedeWindowTicks();
        UUID precursor = precursorFor(server, incidentType, view.get(), window);
        ReputationDelivery delivery = ops.deliverIncident(server, view.get(), incidentType, operationKey,
                OptionalInt.empty(), precursor, window);
        if (delivery.outcome() == ReputationDelivery.Outcome.UNKNOWN) {
            // The answer was lost rather than refused, and the write may well have landed. Ask -- with
            // a read-only receipt lookup, never with another write -- before a retry files the same
            // crime twice.
            ReputationDelivery stored =
                    ops.findDelivery(server, operation.playerId(), community.get(), operationKey);
            if (stored.settled()) {
                delivery = stored;
            }
        }
        delivery.incidentId().ifPresent(incidentId ->
                CrimeCaseService.linkReputationIncident(server, operation.crimeRecordId(), incidentId));
        if (delivery.outcome() == ReputationDelivery.Outcome.ACCEPTED_NO_PUBLIC_INCIDENT) {
            // Accepted with nothing public to show for it -- an unwitnessed deed the definition keeps
            // privately. The operation is finished: there is no incident to link and no resolution to
            // deliver later, and the legal case is entirely unaffected.
            McaCrime.LOGGER.debug("MCA: Crime — {} for crime {} was accepted without a public incident; "
                            + "the case stands and nothing further is owed.",
                    incidentType, operation.crimeRecordId());
        }
        return DeliveryOutcome.forCreate(delivery.outcome());
    }

    /**
     * The civic incident a fatal encounter should absorb, or null.
     *
     * <p>Gated on the companion advertising supersession rather than on its version: an older build
     * simply records the killing on its own terms, which is the pre-0.7.3 behaviour.
     */
    @Nullable
    private static UUID precursorFor(MinecraftServer server, ResourceLocation incidentType,
                                     CrimeRecordView view, long windowTicks) {
        if (windowTicks <= 0L || !SupersedePolicy.isFatal(incidentType)
                || !ReputationBridge.capabilities().supportsSupersede()) {
            return null;
        }
        List<CrimeRecordView> prior = CrimeWorldData.get(server)
                .recordsForOffender(view.offenderId()).stream()
                .map(CrimeRecord::view)
                .toList();
        return SupersedePolicy.precursorFor(incidentType, view, prior, windowTicks).orElse(null);
    }

    private static long supersedeWindowTicks() {
        return McaCrimeConfig.COMMON.reputationSupersedeWindowTicks.get();
    }

    /**
     * Moves a linked incident to its settled status.
     *
     * <p>The incident id comes from the payload when the link existed at enqueue time, and from the
     * case itself when it did not — a fine paid in the same tick the crime was committed reaches this
     * point before the create has been delivered. Waiting for that link is the honest answer;
     * resolving whatever incident a selector happened to pick would settle somebody else's crime.
     */
    private static DeliveryOutcome deliverResolve(MinecraftServer server, ReputationOps ops,
                                                  CrimeIntegrationOperation operation) {
        CompoundTag payload = operation.payload();
        Optional<CrimeCommunityKey> community =
                CrimeCommunityKey.load(payload.getCompound(IntegrationTargets.PAYLOAD_COMMUNITY));
        String status = payload.getString(IntegrationTargets.PAYLOAD_STATUS);
        if (community.isEmpty() || status.isEmpty()) {
            return DeliveryOutcome.INVALID;
        }
        Optional<UUID> incidentId = payload.hasUUID(IntegrationTargets.PAYLOAD_INCIDENT_ID)
                ? Optional.of(payload.getUUID(IntegrationTargets.PAYLOAD_INCIDENT_ID))
                : CrimeCaseService.view(server, operation.crimeRecordId())
                        .flatMap(CrimeRecordView::linkedReputationIncidentId);
        if (incidentId.isEmpty()) {
            return DeliveryOutcome.AWAITING_LINK;
        }
        ReputationDelivery result = ops.resolveIncident(server, operation.playerId(), community.get(),
                incidentId.get(), status, payload.getString(IntegrationTargets.PAYLOAD_DEDUPE_KEY));
        return DeliveryOutcome.forResolve(result.outcome());
    }
}
