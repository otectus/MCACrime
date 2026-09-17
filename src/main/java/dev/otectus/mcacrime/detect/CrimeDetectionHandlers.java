package dev.otectus.mcacrime.detect;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.ActionSessionManager;
import dev.otectus.mcacrime.action.CancelReason;
import dev.otectus.mcacrime.captivity.CaptureChannels;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.mug.MuggingService;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.EventPriority;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * The NeoForge-bus entry points for crime detection (spec §5.3). Server-side only (guarded by the
 * {@link ServerLevel} check); the master toggle short-circuits before any work. All real logic lives in
 * {@link CrimeDetector} / {@link CrimeGate}.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeDetectionHandlers {

    /**
     * One-shot kill switch for the detection path. {@code McaCompat} can no longer raise a linkage
     * error, but detection runs on every damage and death tick in the world, so anything that does
     * escape must cost one log line rather than one per tick — and on a dedicated server must not turn
     * a bug into a crash loop, which is exactly what MCA's package rename did here. Set once and never
     * reset: a detection path that has already thrown is not trustworthy for the rest of the session.
     */
    private static volatile boolean detectionDisabled;

    private CrimeDetectionHandlers() {
    }

    private static void guarded(String what, Runnable body) {
        if (detectionDisabled) {
            return;
        }
        try {
            body.run();
        } catch (Throwable t) {
            detectionDisabled = true;
            McaCrime.LOGGER.error("MCA: Crime {} failed; crime detection is disabled for the rest of this "
                    + "session. Your server will keep running. Please report this with your MCA version.",
                    what, t);
        }
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingDamageEvent.Post event) {
        // A channeling kidnapper who is hit breaks their capture (§8.2) — independent of the detection toggle.
        CaptureChannels.onKidnapperHurt(event.getEntity().getUUID());
        if (event.getEntity().level() instanceof ServerLevel hurtLevel) {
            ActionSessionManager.clearFor(event.getEntity().getUUID(), CancelReason.DAMAGED);
            CustodyService.interruptEscape(event.getEntity().getUUID(), hurtLevel.getServer());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDamage(LivingDamageEvent.Pre event) {
        if (McaCrimeConfig.COMMON.enableCrimeDetection.get()
                && event.getEntity().level() instanceof ServerLevel level) {
            guarded("damage sampling", () -> DamageIncidentService.damage(event, level));
        }
    }

    /**
     * Samples a death, without yet believing it happened.
     *
     * <h2>The ordering this depends on</h2>
     *
     * <p>A villager death is not final when {@code LivingDeathEvent} is posted. Any listener may
     * cancel it, and mods that keep villagers alive do exactly that: MCA: Crime is installed alongside
     * companions whose death listeners run at the default priority and cancel the event outright for
     * an immortal or protected villager, and the same companions spawn extra loot of their own from
     * that listener rather than from {@code LivingDropsEvent}.
     *
     * <p>So there is deliberately no second death handler anywhere in this mod, and this one commits
     * nothing. It records the event and registers its cancellation flag as a <em>supplier</em>;
     * {@link DamageFinality#resolve(boolean)} re-reads it, together with the victim's live
     * {@code isDeadOrDying()}, when {@link DamageIncidentService#flush} runs from
     * {@code ServerTickEvent.Post}. A death that was cancelled — at any priority, before or after this
     * handler — resolves as {@code HARM} or {@code NONE} there, so the finality pipeline, the death
     * consequences and the custody cleanup only ever run for a death that actually completed.
     *
     * <p>{@code LOWEST} with {@code receiveCanceled} therefore buys observation, not authority: it
     * means this handler sees a death some other mod already cancelled rather than missing it, and the
     * cancellation is honoured all the same. Death loot is a separate question and is answered where
     * it belongs, on {@code LivingDropsEvent}, which vanilla posts only for a death that reached the
     * drop stage — {@code LivingEntity.die} returns before {@code dropAllDeathLoot} when
     * {@code CommonHooks.onLivingDeath} reports the event cancelled.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().level() instanceof ServerLevel level) {
            guarded("death sampling", () -> DamageIncidentService.death(event, level));
        }
    }

    /** Cleanup shares confirmed death finality, so downstream death cancellation cannot free a captive. */
    static void confirmedDeath(LivingEntity victim, ServerLevel level) {
        var server = level.getServer();
        var dead = victim.getUUID();
        CaptureChannels.clearFor(dead);
        ActionSessionManager.clearFor(dead, CancelReason.DEATH);
        if (CustodyRegistry.isCaptive(server, dead)) {
            CustodyService.release(server, dead, CustodyReleaseReason.CAPTIVE_DIED);
        }
        for (CustodyRecord held : CustodyRegistry.byOwner(server, dead)) {
            CustodyService.release(server, held.getCaptive(), CustodyReleaseReason.CAPTOR_GONE);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(ServerTickEvent.Post event) {
        reconcileBeforePlayerSave(event.getServer());
    }

    /** Also called before vanilla saves/logs out or copies a player's crime attachment. */
    public static void reconcileBeforePlayerSave(net.minecraft.server.MinecraftServer server) {
        if (server != null) guarded("damage reconciliation", () -> DamageIncidentService.flush(server));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        DamageIncidentService.clear(event.getServer());
        dev.otectus.mcacrime.memory.ApologyClaimLedger.clear();
        detectionDisabled = false;
    }

    /**
     * An entity that leaves the level takes its reaction with it. Without this a villager unloaded
     * mid-flee keeps a controller until the next tick notices it is gone, and — far worse — keeps the
     * transient movement-speed modifier the reaction applied, which nothing else would ever take off.
     */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            dev.otectus.mcacrime.ai.CrimeReactionService.clear(level, event.getEntity().getUUID());
        }
    }

    /**
     * A player who changes dimension takes every in-flight interaction with them.
     *
     * <p>An action session and a capture channel are both bound to one dimension at their start and
     * neither re-checks it every tick; a portal is the one way a target can leave the world an actor
     * is standing in without dying, disconnecting or moving. Both parties are covered, because the
     * session is just as broken when it is the victim who steps through.
     */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        java.util.UUID mover = event.getEntity().getUUID();
        ActionSessionManager.clearFor(mover, CancelReason.DIMENSION_CHANGED);
        CaptureChannels.clearFor(mover);
        // Combat provenance expires after disengagement; a quick portal trip must not reset who attacked first.
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)
            reconcileBeforePlayerSave(player.getServer());
        // Keep bounded recent aggression until its normal expiry; relogging cannot legalize retaliation.
        CaptureChannels.clearFor(event.getEntity().getUUID()); // drop any in-progress channel by/of this player
        MuggingService.onLogout(event.getEntity().getUUID()); // drop any pending mug markers
        ActionSessionManager.clearFor(event.getEntity().getUUID(), CancelReason.ACTOR_GONE);
        // clearFor only ends sessions this player is party to; forgetActor also releases their nonce
        // replay cache and any open menu, neither of which any other path ever removes.
        ActionSessionManager.forgetActor(event.getEntity().getUUID());
        dev.otectus.mcacrime.action.CrimeActionService.forgetMenu(event.getEntity().getUUID());
        dev.otectus.mcacrime.memory.ApologyClaimLedger.forget(event.getEntity().getUUID());
        // Same class of leak, three more maps: a guard encounter, an enforcement alert, and a dossier
        // cooldown stamp all keyed by player and all previously removed by nothing.
        dev.otectus.mcacrime.enforcement.GuardChallengeService.forget(event.getEntity().getUUID());
        dev.otectus.mcacrime.enforcement.GuardEnforcement.forget(event.getEntity().getUUID());
        // An escort is a walk in progress, and there is nobody to walk any more. The lawful custody
        // record behind it persists, and ArrestService finishes the arrest on the next login.
        dev.otectus.mcacrime.enforcement.EscortService.forget(event.getEntity().getUUID());
        dev.otectus.mcacrime.enforcement.RestraintHandlers.forget(event.getEntity().getUUID());
        dev.otectus.mcacrime.network.RequestBudget.forget(event.getEntity().getUUID());
        // And the Mask Station's refusal stamp, which is the same shape of per-player map (0.7.2 §8.1).
        dev.otectus.mcacrime.network.SelectMaskRecipeC2SPacket.forget(event.getEntity().getUUID());
        dev.otectus.mcacrime.dialogue.CrimeDialogueService.forget(event.getEntity().getUUID());
        if (event.getEntity().level() instanceof ServerLevel level) {
            dev.otectus.mcacrime.ai.CrimeReactionService.clear(level, event.getEntity().getUUID());
        }
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            CustodyService.onCaptorLogout(player);
        }
    }
}
