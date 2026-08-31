package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.CrimeDetector;
import dev.otectus.mcacrime.detect.WitnessChecker;
import dev.otectus.mcacrime.economy.account.EconomicTransactionService;
import dev.otectus.mcacrime.enforcement.GuardEnforcement;
import dev.otectus.mcacrime.memory.CrimeMemoryService;
import dev.otectus.mcacrime.memory.OffenderMemory;
import dev.otectus.mcacrime.memory.VillagerCrimeProfile;
import dev.otectus.mcacrime.mug.MuggingService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.OptionalInt;
import java.util.UUID;

/** Complete mugging vertical slice: exact target, timed threat, finite purse, caps, memory, and flight. */
public final class MugActionHandler implements CrimeActionHandler {
    private static final double REACH_SQR = 16.0D;

    @Override
    public ActionAvailability evaluate(ServerPlayer actor, LivingEntity target, ServerLevel level, long now) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        if (!c.enableMugging.get()) return ActionAvailability.hidden("mcacrime.mug.disabled");
        if (!actor.isAlive() || actor.isSpectator() || !target.isAlive() || !McaCompat.isMcaVillager(target))
            return ActionAvailability.hidden("mcacrime.mug.notarget");
        if (!c.allowHostileActionsAgainstChildren.get() && !McaCompat.isAdult(target))
            return ActionAvailability.hidden("mcacrime.mug.child");
        if (actor.distanceToSqr(target) > REACH_SQR || !actor.hasLineOfSight(target))
            return ActionAvailability.blocked("mcacrime.mug.notarget");
        if (CustodyRegistry.isCaptive(level.getServer(), actor.getUUID())
                || CustodyRegistry.isCaptive(level.getServer(), target.getUUID()))
            return ActionAvailability.blocked("mcacrime.mug.custody");
        VillagerCrimeProfile profile = CrimeMemoryService.profile(level.getServer(), target, now / 24000L);
        OffenderMemory memory = profile.memory(actor.getUUID());
        if (c.muggingAttemptCooldownTicks.get() > 0 && memory.lastAttempt() > 0L
                && now - memory.lastAttempt() < c.muggingAttemptCooldownTicks.get())
            return ActionAvailability.blocked("mcacrime.mug.repeat");
        CrimeWorldData world = CrimeWorldData.get(level.getServer());
        world.pruneActionCounters(now / 24000L);
        if (world.actionCounter(actorSuccessKey(actor.getUUID(), now)) >= c.muggingActorSuccessCapPerDay.get())
            return ActionAvailability.blocked("mcacrime.mug.daily_limit");
        return ActionAvailability.available();
    }

    @Override
    public ActionResult start(ServerPlayer actor, LivingEntity target, ServerLevel level, UUID nonce) {
        long now = level.getGameTime();
        ActionAvailability availability = evaluate(actor, target, level, now);
        if (!availability.isAvailable()) return ActionResult.rejected(availability.reason());
        ActionSession session = new ActionSession(UUID.randomUUID(), nonce, CrimeActionIds.MUG,
                actor.getUUID(), target.getUUID(), level.dimension().location(), actor.position(), now,
                McaCrimeConfig.COMMON.muggingChannelTicks.get());
        if (!ActionSessionManager.begin(session)) return ActionResult.rejected("mcacrime.action.conflict");

        // The threat is the explicit point of no return. Repeat/cooldown/memory/crime apply even if interrupted.
        session.markPointOfNoReturn();
        CrimeMemoryService.recordMugAttempt(level.getServer(), target, actor.getUUID(), now);
        MuggingService.markThreat(actor.getUUID(), target.getUUID(), now);
        CrimeDetector.commitDirect(actor, CrimeIds.THEFT, target, level,
                WitnessChecker.resolve(level, target), "mug_attempt");
        GuardEnforcement.alert(actor, "mcacrime.msg.guardaggro.reported", 600L);
        actor.displayClientMessage(Component.translatable("mcacrime.mug.channeling"), true);
        return ActionResult.accepted("mcacrime.mug.started");
    }

    @Override
    public void tick(ActionSession session, ServerPlayer actor, LivingEntity target, ServerLevel level) {
        if (actor.distanceToSqr(session.actorStart()) > 2.25D) {
            ActionSessionManager.cancel(session, CancelReason.MOVED);
            return;
        }
        if (actor.distanceToSqr(target) > REACH_SQR) {
            ActionSessionManager.cancel(session, CancelReason.OUT_OF_RANGE);
            return;
        }
        if (!actor.hasLineOfSight(target)) {
            ActionSessionManager.cancel(session, CancelReason.LOST_SIGHT);
            return;
        }
        if (!session.advance()) {
            if (session.progress() % 10 == 0) actor.displayClientMessage(Component.translatable(
                    "mcacrime.mug.progress", session.progress(), session.requiredTicks()), true);
            return;
        }

        long now = level.getGameTime();
        CrimeWorldData world = CrimeWorldData.get(level.getServer());
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        VillagerCrimeProfile profile = CrimeMemoryService.profile(level.getServer(), target, now / 24000L);
        profile.purse().refill(now / 24000L);
        long actorRemaining = Math.max(0L, c.muggingActorValueCapPerDay.get()
                - world.actionCounter(actorValueKey(actor.getUUID(), now)));
        long villageRemaining = Math.max(0L, c.muggingVillageValueCapPerDay.get()
                - world.actionCounter(villageValueKey(target, level, now)));
        int requested = (int) Math.min(Math.min(actorRemaining, villageRemaining), c.muggingBaseLoot.get());
        int transferred = EconomicTransactionService.transferPurseToPlayer(world, session.sessionId(),
                profile.purse(), actor, requested);
        if (transferred > 0) {
            world.addActionCounter(actorSuccessKey(actor.getUUID(), now), 1L);
            world.addActionCounter(actorValueKey(actor.getUUID(), now), transferred);
            world.addActionCounter(villageValueKey(target, level, now), transferred);
            CrimeMemoryService.recordMugSuccess(level.getServer(), target, actor.getUUID(), now, transferred);
            actor.sendSystemMessage(Component.translatable("mcacrime.mug.success", transferred));
        } else {
            actor.sendSystemMessage(Component.translatable("mcacrime.mug.empty"));
        }
        McaCompat.makeVillagerFlee(target, actor);
        ActionSessionManager.finish(session, ActionResult.accepted(
                transferred > 0 ? "mcacrime.mug.success" : "mcacrime.mug.empty"));
    }

    @Override
    public void cancel(ActionSession session, CancelReason reason) {
        // Attempt consequences were stamped at the point of no return; cancellation cannot reset them.
    }

    private static String actorSuccessKey(UUID actor, long now) { return "mug:success:" + now / 24000L + ":" + actor; }
    private static String actorValueKey(UUID actor, long now) { return "mug:value:" + now / 24000L + ":" + actor; }
    private static String villageValueKey(LivingEntity target, ServerLevel level, long now) {
        OptionalInt village = McaCompat.getHomeVillageId(target);
        return "mug:village:" + now / 24000L + ":" + level.dimension().location() + ":"
                + (village.isPresent() ? village.getAsInt() : "wild");
    }
}
