package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.enforcement.AccompliceExposure;
import dev.otectus.mcacrime.enforcement.AccompliceService;
import dev.otectus.mcacrime.state.world.AccompliceRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * "Keep an eye on the street for me."
 *
 * <p>The quietest of the three, and the most useful: while a relative is watching, the crime's witness
 * radius shrinks, so fewer people are ever considered as witnesses at all. It costs nothing visible
 * and buys the offender a smaller audience — which is also why the relative is exposed by the same
 * perception rules everybody else is, and can be arrested for it.
 */
public final class AskLookoutActionHandler implements CrimeActionHandler {

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.of(CrimeActionIds.ASK_LOOKOUT,
            ActionCategory.CONSPIRE, ActionLegality.SUSPICIOUS, ActionDuration.INSTANT, false,
            ActionRequirement.FAMILY);

    @Override
    public ActionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        if (McaCompat.isVillagerSleeping(target)) return ActionAvailability.blocked("mcacrime.action.target_asleep");
        return AccompliceGate.evaluate(actor, target, level, now);
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        ActionAvailability availability = evaluate(actor, target, level, level.getGameTime());
        if (!availability.isAvailable()) return ActionResult.rejected(availability.reason());
        ServerPlayer player = actor.asPlayer();
        long now = level.getGameTime();
        AccompliceRecord record = AccompliceService.recruit(player, target, level,
                AccompliceService.ROLE_LOOKOUT, McaCrimeConfig.COMMON.lookoutDurationTicks.get(), nonce);
        if (record == null) return ActionResult.rejected("mcacrime.accomplice.declined");
        AccompliceService.postLookout(player, target, now);
        AccompliceExposure.onEffect(level, player, target, record);
        player.sendSystemMessage(Component.translatable("mcacrime.msg.accomplice.recruited",
                McaCompat.getVillagerDisplayName(target)));
        return ActionResult.accepted("mcacrime.action.feedback_sent");
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {}
}
