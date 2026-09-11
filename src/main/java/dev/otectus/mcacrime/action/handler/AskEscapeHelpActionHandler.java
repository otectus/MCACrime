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
 * "Get them off me."
 *
 * <p>One shot and a short window. The shot is immediate: every responder currently onto the player
 * loses its target. The window is what makes the shot worth anything — for {@code
 * escapeHelpDurationTicks} the guard scan does not re-acquire the player, and a restraint comes off
 * faster. Without the window the guards would simply retarget on the next scan and the whole action
 * would be a flicker.
 */
public final class AskEscapeHelpActionHandler implements CrimeActionHandler {

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.of(CrimeActionIds.ASK_ESCAPE_HELP,
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
                AccompliceService.ROLE_ESCAPE_HELP,
                McaCrimeConfig.COMMON.escapeHelpDurationTicks.get(), nonce);
        if (record == null) return ActionResult.rejected("mcacrime.accomplice.declined");
        AccompliceService.shakeOffResponders(player, level);
        AccompliceService.startEscapeHelp(player, now);
        AccompliceExposure.onEffect(level, player, target, record);
        player.sendSystemMessage(Component.translatable("mcacrime.msg.accomplice.recruited",
                McaCompat.getVillagerDisplayName(target)));
        return ActionResult.accepted("mcacrime.action.feedback_sent");
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {}
}
