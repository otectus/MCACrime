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

import java.util.UUID;

/**
 * Restraining somebody from the action menu (0.7.5 M2.11).
 *
 * <p>The menu row is now a second door into the physical engine rather than a second engine. What it
 * does is exactly what walking up and right-clicking with a pair of cuffs does — one
 * {@link ApplicationTransaction}, one reserved item, one commit — and the custody paperwork follows
 * the gear instead of preceding it. The capture channel this used to open is gone with
 * {@code captivity/CaptureService}: an application is decided when it is attempted, and the work that
 * genuinely takes time is the victim struggling back out of it.
 */
public final class RestrainActionHandler implements CrimeActionHandler {

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.of(CrimeActionIds.RESTRAIN,
            ActionCategory.RESTRAIN, ActionLegality.CRIMINAL, ActionDuration.SHORT, true,
            ActionRequirement.RESTRAINT, ActionRequirement.TARGET_VULNERABLE);

    /** The menu always asks for the arms. A different region is the interaction router's business. */
    private static final RestraintSlot SLOT = RestraintSlot.ARMS;

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
        ApplicationTransaction.Result result = RestraintService.apply(player, target, hand, SLOT,
                AppliedRestraint.ApplicationContext.UNLAWFUL);
        if (!result.applied()) {
            return ActionResult.rejected(availability(result.refusal()).reason());
        }
        // The gear is on, and the paperwork has already followed it: as of 0.7.5 M4.8
        // restraint/CustodyTransitionService is the only class that turns a committed physical event
        // into a legal one, and RestraintService.apply calls it. Capturing again here would be the
        // second attempt at one kidnapping -- refused as ALREADY_HELD, and reported to the player as
        // "restrained only" for a capture that in fact succeeded. So this asks what the bridge did
        // rather than doing it a second time.
        boolean held = heldBy(player, target);
        return held
                ? ActionResult.accepted("mcacrime.kidnap.holding")
                : ActionResult.accepted("mcacrime.capture.restrained_only");
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

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {}

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
            case COEXISTENCE_REFUSED -> ActionAvailability.blocked("mcacrime.compat.cuffed.refused");
            default -> ActionAvailability.blocked("mcacrime.action.conflict");
        };
    }
}
