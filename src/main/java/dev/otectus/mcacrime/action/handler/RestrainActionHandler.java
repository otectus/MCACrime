package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.restraint.AppliedRestraint;
import dev.otectus.mcacrime.restraint.ApplicationTransaction;
import dev.otectus.mcacrime.restraint.RestraintService;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Restraining somebody from the action menu (0.7.5 M2.11).
 *
 * <p>The menu row is now a second door into the physical engine rather than a second engine. What it
 * does is exactly what walking up and right-clicking with a pair of cuffs does — one
 * {@link ApplicationTransaction}, one reserved item, one commit — and the custody paperwork follows
 * the gear instead of preceding it. The capture channel this used to open is gone with
 * {@code captivity/CaptureService}: an application is decided when it is attempted, and the work that
 * genuinely takes time is the victim struggling back out of it.
 *
 * <p>Unless {@code restraints.application.channelTicks} says otherwise. At its shipped 0 (the parity
 * behaviour) nothing here waits; above 0 an application by somebody else is a channel: the applier has
 * to stay within {@code maxRangeBlocks}, keep sight when {@code requireLineOfSight} is on and keep
 * holding the restraint for that many ticks, and only then is the one {@link ApplicationTransaction}
 * committed. The interaction router opens the same channel for a right-click, so both doors take the
 * same time. A self-application or a device never channels.
 */
public final class RestrainActionHandler implements CrimeActionHandler {

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.of(CrimeActionIds.RESTRAIN,
            ActionCategory.RESTRAIN, ActionLegality.CRIMINAL, ActionDuration.SHORT, true,
            ActionRequirement.RESTRAINT, ActionRequirement.TARGET_VULNERABLE);

    /** The menu always asks for the arms. A different region is the interaction router's business. */
    private static final RestraintSlot SLOT = RestraintSlot.ARMS;

    /**
     * What each open channel will commit when it completes, by session id.
     *
     * <p>{@link ActionSession} carries who and whom but not which hand, slot or item, and the commit
     * needs all three. Removed on completion and in {@link #cancel}, which every ending that does not
     * complete goes through, so an entry never outlives its session.
     */
    private static final Map<UUID, Channel> CHANNELS = new ConcurrentHashMap<>();

    private record Channel(InteractionHand hand, RestraintSlot slot, Item item,
                           AppliedRestraint.ApplicationContext context) {
    }

    @Override
    public ActionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public boolean coercive() {
        return true;
    }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        ServerPlayer player = actor.asPlayer();
        if (player == null || target == null) {
            return ActionAvailability.hidden("mcacrime.capture.invalid");
        }
        boolean targetIsPlayer = target instanceof ServerPlayer;
        if (targetIsPlayer ? !McaCrimeConfig.COMMON.enableKidnappingPlayer.get()
                : !McaCrimeConfig.COMMON.enableKidnappingNpc.get()) {
            return ActionAvailability.hidden("mcacrime.capture.disabled");
        }
        if (!targetIsPlayer && !McaCompat.isMcaVillager(target)) {
            return ActionAvailability.hidden("mcacrime.capture.invalid");
        }
        if (target.getUUID().equals(player.getUUID())) {
            return ActionAvailability.hidden("mcacrime.capture.invalid");
        }
        if (CustodyRegistry.isCaptive(level.getServer(), target.getUUID())) {
            return ActionAvailability.blocked("mcacrime.capture.already");
        }
        if (unlawfulCaptives(player, level) >= McaCrimeConfig.COMMON.maxUnlawfulCaptivesPerCaptor.get()) {
            return ActionAvailability.blocked("mcacrime.capture.capacity");
        }
        if (ActionSessionManager.targetLocked(target.getUUID())) {
            return ActionAvailability.blocked("mcacrime.action.conflict");
        }
        InteractionHand hand = restraintHand(player);
        if (hand == null) {
            return ActionAvailability.blocked("mcacrime.capture.need_restraint");
        }
        return availability(RestraintService.evaluate(player, target, player.getItemInHand(hand), SLOT));
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        ServerPlayer player = actor.asPlayer();
        if (player == null) {
            return ActionResult.rejected("mcacrime.capture.invalid");
        }
        ActionAvailability availability = evaluate(actor, target, level, level.getGameTime());
        if (!availability.isAvailable()) {
            return ActionResult.rejected(availability.reason());
        }
        InteractionHand hand = restraintHand(player);
        if (hand == null) {
            return ActionResult.rejected("mcacrime.capture.need_restraint");
        }
        if (channelTicks() > 0) {
            return beginChannel(player, target, hand, SLOT, AppliedRestraint.ApplicationContext.UNLAWFUL, nonce)
                    ? ActionResult.accepted("mcacrime.capture.channeling")
                    : ActionResult.rejected("mcacrime.action.conflict");
        }
        return commit(player, target, hand, SLOT, AppliedRestraint.ApplicationContext.UNLAWFUL);
    }

    /**
     * Commits the application and reports what the law made of it.
     *
     * <p>The gear is on, and the paperwork has already followed it: as of 0.7.5 M4.8
     * restraint/CustodyTransitionService is the only class that turns a committed physical event into a
     * legal one, and RestraintService.apply calls it. Capturing again here would be the second attempt
     * at one kidnapping -- refused as ALREADY_HELD, and reported to the player as "restrained only" for
     * a capture that in fact succeeded. So this asks what the bridge did rather than doing it a second
     * time.
     */
    private static ActionResult commit(ServerPlayer player, LivingEntity target, InteractionHand hand,
                                       RestraintSlot slot, AppliedRestraint.ApplicationContext context) {
        ApplicationTransaction.Result result = RestraintService.apply(player, target, hand, slot, context);
        if (!result.applied()) {
            return ActionResult.rejected(availability(result.refusal()).reason());
        }
        boolean held = heldBy(player, target);
        return held
                ? ActionResult.accepted("mcacrime.kidnap.holding", McaCompat.getVillagerDisplayName(target))
                : ActionResult.accepted("mcacrime.capture.restrained_only");
    }

    /** {@code restraints.application.channelTicks}, or 0 (instant) before the config has loaded. */
    public static int channelTicks() {
        try {
            return McaCrimeConfig.COMMON.applicationChannelTicks.get();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    /**
     * Opens an application channel against {@code target}.
     *
     * <p>The caller has already decided the application would stand right now; this only reserves the
     * pair and remembers what to commit. The item is not taken until the channel completes, so a
     * channel that breaks off costs nothing.
     *
     * @return whether a channel opened; false when the applier or the target is already busy
     */
    public static boolean beginChannel(ServerPlayer player, LivingEntity target, InteractionHand hand,
                                       RestraintSlot slot, AppliedRestraint.ApplicationContext context,
                                       UUID nonce) {
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty() || !(player.level() instanceof ServerLevel level)) {
            return false;
        }
        ActionSession session = new ActionSession(UUID.randomUUID(), nonce, CrimeActionIds.RESTRAIN,
                player.getUUID(), target.getUUID(), level.dimension().location(), player.position(),
                level.getGameTime(), channelTicks());
        CHANNELS.put(session.sessionId(), new Channel(hand, slot, held.getItem(), context));
        if (!ActionSessionManager.begin(session)) {
            CHANNELS.remove(session.sessionId());
            return false;
        }
        return true;
    }

    /** Whether {@code target} is now in {@code player}'s unlawful custody. */
    private static boolean heldBy(ServerPlayer player, LivingEntity target) {
        if (player.getServer() == null) {
            return false;
        }
        dev.otectus.mcacrime.captivity.CustodyRecord record = dev.otectus.mcacrime.state.world
                .CrimeWorldData.get(player.getServer()).getCustody(target.getUUID());
        return record != null && !record.isLawful()
                && record.getOwner().isKidnapper(player.getUUID());
    }

    /**
     * Advances an open channel, and commits it on its last tick.
     *
     * <p>The same three things are checked every tick that the commit itself would check, so a channel
     * never completes into a refusal the player could have seen coming: still in reach, still in sight
     * when sight is required, and still holding the restraint the channel was opened with.
     */
    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {
        Channel channel = CHANNELS.get(session.sessionId());
        ServerPlayer player = actor.asPlayer();
        if (channel == null || player == null) {
            ActionSessionManager.cancel(session, CancelReason.CONFLICT);
            return;
        }
        ItemStack held = player.getItemInHand(channel.hand());
        if (held.isEmpty() || held.getItem() != channel.item()) {
            ActionSessionManager.cancel(session, CancelReason.CONFLICT);
            return;
        }
        if (!RestraintService.withinApplicationReach(player, target)) {
            boolean close = player.distanceToSqr(target) <= maxRangeSqr();
            ActionSessionManager.cancel(session, close ? CancelReason.LOST_SIGHT : CancelReason.OUT_OF_RANGE);
            return;
        }
        if (!session.advance()) {
            return;
        }
        CHANNELS.remove(session.sessionId());
        ActionSessionManager.finish(session, commit(player, target, channel.hand(), channel.slot(),
                channel.context()));
    }

    @Override
    public void cancel(ActionSession session, CancelReason reason) {
        CHANNELS.remove(session.sessionId());
    }

    private static double maxRangeSqr() {
        double range;
        try {
            range = McaCrimeConfig.COMMON.applicationMaxRangeBlocks.get();
        } catch (IllegalStateException e) {
            range = 4.0D;
        }
        return range * range;
    }

    /** Whichever hand holds something that can restrain, main hand first. */
    private static InteractionHand restraintHand(ServerPlayer player) {
        if (CrimeItems.familyFor(player.getMainHandItem()).isPresent()) {
            return InteractionHand.MAIN_HAND;
        }
        if (CrimeItems.familyFor(player.getOffhandItem()).isPresent()) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }

    private static long unlawfulCaptives(ServerPlayer player, ServerLevel level) {
        return CustodyRegistry.byOwner(level.getServer(), player.getUUID()).stream()
                .filter(record -> !record.isLawful()).count();
    }

    /** The menu's answer for each refusal the transaction can give, so a row explains itself. */
    private static ActionAvailability availability(ApplicationTransaction.Refusal refusal) {
        return switch (refusal) {
            case NONE -> ActionAvailability.available();
            case NO_SUBJECT -> ActionAvailability.hidden("mcacrime.capture.invalid");
            case NO_DEFINITION -> ActionAvailability.blocked("mcacrime.capture.need_restraint");
            case SLOT_OCCUPIED -> ActionAvailability.blocked("mcacrime.capture.already");
            case NOT_VULNERABLE -> ActionAvailability.blocked("mcacrime.capture.not_vulnerable");
            case SELF_APPLICATION_DISABLED -> ActionAvailability.blocked("mcacrime.capture.invalid");
            case OUT_OF_REACH -> ActionAvailability.blocked("mcacrime.capture.range");
            default -> ActionAvailability.blocked("mcacrime.action.conflict");
        };
    }
}
