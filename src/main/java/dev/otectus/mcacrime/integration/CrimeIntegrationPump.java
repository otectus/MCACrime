package dev.otectus.mcacrime.integration;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.compat.ReputationBridge;
import dev.otectus.mcacrime.compat.ReputationOps;
import dev.otectus.mcacrime.ledger.CrimeCaseService;
import dev.otectus.mcacrime.relationship.RelationshipConsequences;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeIntegrationPump {

    /** How much of a backlog the start-up and login drains may clear in one go. */
    private static final int STARTUP_BUDGET_MULTIPLIER = 20;

    private static int tickCounter;

    private CrimeIntegrationPump() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        tickCounter = 0;
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
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || !McaCrimeConfig.COMMON.replayPendingOperations.get()) {
            return;
        }
        if (++tickCounter < McaCrimeConfig.COMMON.pumpIntervalTicks.get()) {
            return;
        }
        tickCounter = 0;
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
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
            // costs the player nothing publicly, which is worse than counting it locally.
            if (operation.action().equals(IntegrationTargets.ACTION_CREATE)) {
                RelationshipConsequences.applyDeferredVillagePenalty(server, operation.crimeRecordId());
            }
            return;
        }
        long nextAttempt = DeliveryPolicy.nextAttemptTime(attempts, now,
                c.retryBaseDelayTicks.get(), c.retryMaxDelayTicks.get());
        data.updateOperation(operation.withAttempt(nextAttempt, outcome.name()));
    }

    private static DeliveryOutcome deliver(MinecraftServer server, CrimeIntegrationOperation operation) {
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
        String dedupeKey = payload.getString(IntegrationTargets.PAYLOAD_DEDUPE_KEY);

        Optional<UUID> incidentId =
                ops.recordIncident(server, view.get(), incidentType, dedupeKey, OptionalInt.empty());
        if (incidentId.isEmpty()) {
            // The write may still have landed and only the answer been lost -- ask before retrying,
            // or a crash between their commit and our link write produces a second incident.
            incidentId = ops.findIncident(server, operation.playerId(), community.get(), dedupeKey);
        }
        if (incidentId.isEmpty()) {
            // Genuinely not recorded. A definition the companion does not know is the common cause and
            // is not worth retrying; anything else is.
            return ops.holdsAuthority() ? DeliveryOutcome.UNKNOWN_TARGET : DeliveryOutcome.UNAVAILABLE;
        }
        CrimeCaseService.linkReputationIncident(server, operation.crimeRecordId(), incidentId.get());
        return DeliveryOutcome.SUCCESS;
    }

    private static DeliveryOutcome deliverResolve(MinecraftServer server, ReputationOps ops,
                                                  CrimeIntegrationOperation operation) {
        CompoundTag payload = operation.payload();
        Optional<CrimeCommunityKey> community =
                CrimeCommunityKey.load(payload.getCompound(IntegrationTargets.PAYLOAD_COMMUNITY));
        if (community.isEmpty() || !payload.hasUUID(IntegrationTargets.PAYLOAD_INCIDENT_ID)) {
            return DeliveryOutcome.INVALID;
        }
        boolean resolved = ops.resolveIncident(server, operation.playerId(), community.get(),
                payload.getUUID(IntegrationTargets.PAYLOAD_INCIDENT_ID),
                payload.getString(IntegrationTargets.PAYLOAD_STATUS));
        // A same-or-stronger status already in place counts as done -- that is what makes a replayed
        // resolution harmless rather than an endless retry.
        return resolved ? DeliveryOutcome.SUCCESS : DeliveryOutcome.TRANSIENT_FAILURE;
    }
}
