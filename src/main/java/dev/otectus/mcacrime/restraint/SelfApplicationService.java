package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.item.CrimeItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import javax.annotation.Nullable;

/**
 * Putting a restraint on oneself (0.7.5 M2.6).
 *
 * <p>An explicit slot, chosen in the self panel and sent as a slot. The source derives the slot from
 * the player's view pitch and gets it wrong in a way worth naming, because the same mistake is easy
 * to repeat: it compares a pitch expressed in <em>degrees</em> against bounds expressed in
 * <em>radians</em> ({@code mixin/PlayerMixin}), so two of the three regions are unreachable and the
 * third catches everything. An explicit selector has no such failure mode.
 *
 * <p>A self-application is {@link AppliedRestraint.ApplicationContext#VOLUNTARY}. It is not a
 * kidnapping, it files no case, it costs nobody Heat and it creates no custody — which is exactly the
 * §1.4 boundary, stated as a consequence tests assert: "a self-applied hood is not a kidnapping".
 *
 * <p>Gated by {@code restraints.application.allowSelfApplication}. A server that turns it off gets a
 * refusal here, not a silently ignored click.
 */
public final class SelfApplicationService {

    private SelfApplicationService() {
    }

    /**
     * Applies what {@code hand} is holding to the sender's own {@code slot}.
     *
     * <p>Goes through the same {@link RestraintService#apply} as every other application, including
     * the vulnerability gates — which it then passes trivially, because a subject asking for a
     * restraint has consented and the gates are about openings in somebody who has not.
     */
    public static ApplicationTransaction.Result apply(@Nullable ServerPlayer subject,
                                                      @Nullable RestraintSlot slot,
                                                      @Nullable InteractionHand hand) {
        if (subject == null || slot == null) {
            return ApplicationTransaction.Result.refused(ApplicationTransaction.Refusal.NO_SUBJECT);
        }
        InteractionHand using = hand == null ? holdingHand(subject) : hand;
        if (using == null) {
            return ApplicationTransaction.Result.refused(ApplicationTransaction.Refusal.NO_DEFINITION);
        }
        return RestraintService.apply(subject, subject, using, slot,
                AppliedRestraint.ApplicationContext.VOLUNTARY);
    }

    /** Whichever hand holds a restraint, main hand first. Null when neither does. */
    @Nullable
    private static InteractionHand holdingHand(ServerPlayer subject) {
        if (CrimeItems.familyFor(subject.getMainHandItem()).isPresent()) {
            return InteractionHand.MAIN_HAND;
        }
        if (CrimeItems.familyFor(subject.getOffhandItem()).isPresent()) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }
}
