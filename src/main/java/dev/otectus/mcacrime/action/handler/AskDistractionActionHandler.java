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
 * "Go and make a scene over there."
 *
 * <p>The relative walks a short way off and holds the attention of every ordinary villager within
 * {@code distractionRadius}; those villagers stop being candidate witnesses for the duration. Guards
 * are not covered, deliberately — a distraction that worked on the law would make the law optional.
 *
 * <p>The walk is MCA's own navigation ({@code moveVillagerTo}) and the control is handed straight back
 * when the effect ends, so a relative who was asked to cause trouble goes back to being a villager
 * rather than standing wherever they were pointed.
 */
public final class AskDistractionActionHandler implements CrimeActionHandler {

    /** How far off the relative goes to make the scene. Far enough to draw eyes, near enough to matter. */
    private static final double OFFSET_BLOCKS = 6.0D;
    private static final double WALK_SPEED = 1.0D;

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.of(CrimeActionIds.ASK_DISTRACTION,
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
                AccompliceService.ROLE_DISTRACTION,
                McaCrimeConfig.COMMON.distractionDurationTicks.get(), nonce);
        if (record == null) return ActionResult.rejected("mcacrime.accomplice.declined");
        // Away from the player, so the eyes the scene draws are eyes that were pointing at the crime.
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        double scale = length < 1.0E-4D ? 0.0D : OFFSET_BLOCKS / length;
        McaCompat.moveVillagerTo(target, target.getX() + dx * scale, target.getY(),
                target.getZ() + dz * scale, WALK_SPEED);
        AccompliceService.startDistraction(player, target, now);
        AccompliceExposure.onEffect(level, player, target, record);
        player.sendSystemMessage(Component.translatable("mcacrime.msg.accomplice.recruited",
                McaCompat.getVillagerDisplayName(target)));
        return ActionResult.accepted("mcacrime.action.feedback_sent");
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {}
}
