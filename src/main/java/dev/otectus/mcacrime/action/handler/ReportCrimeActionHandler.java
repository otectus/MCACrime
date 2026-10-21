package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.action.ActionAvailability;
import dev.otectus.mcacrime.action.ActionCategory;
import dev.otectus.mcacrime.action.ActionDescriptor;
import dev.otectus.mcacrime.action.ActionDuration;
import dev.otectus.mcacrime.action.ActionLegality;
import dev.otectus.mcacrime.action.ActionResult;
import dev.otectus.mcacrime.action.ActionSession;
import dev.otectus.mcacrime.action.CrimeActionHandler;
import dev.otectus.mcacrime.action.CrimeActionIds;
import dev.otectus.mcacrime.action.CrimeActor;
import dev.otectus.mcacrime.config.CrimeWorldSettings;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.report.PlayerCrimeReportService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/** Peaceful guard action that opens the server-authored report picker. */
public final class ReportCrimeActionHandler implements CrimeActionHandler {
    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.of(CrimeActionIds.REPORT_CRIME,
            ActionCategory.RESOLVE, ActionLegality.LAWFUL, ActionDuration.INSTANT, false);

    @Override public ActionDescriptor descriptor() { return DESCRIPTOR; }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        ServerPlayer player = actor.asPlayer();
        if (player == null || !EntitySelectors.isResponder(target)) return ActionAvailability.hidden("");
        CrimeWorldSettings settings = CrimeWorldSettings.resolve(level.getServer());
        if (!settings.playerReports()) return ActionAvailability.blocked("mcacrime.report.rejected.disabled");
        if (!settings.observations()) return ActionAvailability.blocked("mcacrime.report.rejected.observations_disabled");
        if (!EntitySelectors.isAvailableResponder(target))
            return ActionAvailability.blocked("mcacrime.report.rejected.guard_unavailable");
        return ActionAvailability.available();
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        ActionAvailability available = evaluate(actor, target, level, level.getGameTime());
        if (!available.isAvailable()) return ActionResult.rejected(available.reason());
        var menu = PlayerCrimeReportService.open(actor.asPlayer(), target);
        if (menu == null) return ActionResult.rejected("mcacrime.report.rejected.guard_unavailable");
        CrimeNetwork.sendReportMenu(actor.asPlayer(), menu);
        return ActionResult.accepted("mcacrime.action.feedback_sent");
    }

    @Override public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) { }
}
