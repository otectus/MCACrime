package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.dialogue.CrimeDialogueService;
import dev.otectus.mcacrime.dialogue.DialogueEvents;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.CrimeDetector;
import dev.otectus.mcacrime.detect.WitnessChecker;
import dev.otectus.mcacrime.economy.account.EconomicTransactionService;
import dev.otectus.mcacrime.enforcement.GuardEnforcement;
import dev.otectus.mcacrime.item.weapon.WeaponDetector;
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

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.of(CrimeActionIds.MUG,
            ActionCategory.COERCE, ActionLegality.CRIMINAL, ActionDuration.SHORT, true);

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
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        if (!c.enableMugging.get()) return ActionAvailability.hidden("mcacrime.mug.disabled");
        // Robbery pays into a player inventory and charges a player-owned case; an NPC mugger needs the
        // economy and detection services generalized first (spec 10.5), so it stays hidden until then.
        ServerPlayer player = actor.asPlayer();
        if (player == null) return ActionAvailability.hidden("mcacrime.mug.notarget");
        LivingEntity self = actor.entity();
        if (!self.isAlive() || player.isSpectator() || !target.isAlive() || !McaCompat.isMcaVillager(target))
            return ActionAvailability.hidden("mcacrime.mug.notarget");
        if (!c.allowHostileActionsAgainstChildren.get() && !McaCompat.isAdult(target))
            return ActionAvailability.hidden("mcacrime.mug.child");
        if (!actor.canReach(target, REACH_SQR))
            return ActionAvailability.blocked("mcacrime.mug.notarget");
        // A mugging is a threat, and an unarmed threat is a request. Blocked rather than hidden: the
        // row has to stay visible and say why, or drawing a weapon never looks like the answer.
        if (c.mugRequiresWeapon.get() && !WeaponDetector.isArmed(player))
            return ActionAvailability.blocked("mcacrime.action.requires_weapon");
        if (CustodyRegistry.isCaptive(level.getServer(), actor.id())
                || CustodyRegistry.isCaptive(level.getServer(), target.getUUID()))
            return ActionAvailability.blocked("mcacrime.mug.custody");
        VillagerCrimeProfile profile = CrimeMemoryService.profile(level.getServer(), target, now / 24000L);
        OffenderMemory memory = profile.memory(actor.id());
        if (c.muggingAttemptCooldownTicks.get() > 0 && memory.lastAttempt() > 0L
                && now - memory.lastAttempt() < c.muggingAttemptCooldownTicks.get())
            return ActionAvailability.blocked("mcacrime.mug.repeat");
        CrimeWorldData world = CrimeWorldData.get(level.getServer());
        world.pruneActionCounters(now / 24000L);
        if (world.actionCounter(actorSuccessKey(actor.id(), now)) >= c.muggingActorSuccessCapPerDay.get())
            return ActionAvailability.blocked("mcacrime.mug.daily_limit");
        return ActionAvailability.available();
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        long now = level.getGameTime();
        ActionAvailability availability = evaluate(actor, target, level, now);
        if (!availability.isAvailable()) return ActionResult.rejected(availability.reason());
        ServerPlayer player = actor.asPlayer();
        ActionSession session = new ActionSession(UUID.randomUUID(), nonce, CrimeActionIds.MUG,
                actor.id(), target.getUUID(), level.dimension().location(), actor.entity().position(), now,
                McaCrimeConfig.COMMON.muggingChannelTicks.get());
        if (!ActionSessionManager.begin(session)) return ActionResult.rejected("mcacrime.action.conflict");

        // The threat is the explicit point of no return. Repeat/cooldown/memory/crime apply even if interrupted.
        session.markPointOfNoReturn();
        CrimeMemoryService.recordMugAttempt(level.getServer(), target, actor.id(), now);
        MuggingService.markThreat(actor.id(), target.getUUID(), now);
        CrimeDetector.commitDirect(player, CrimeIds.THEFT, target, level,
                WitnessChecker.resolve(level, target), "mug_attempt");
        GuardEnforcement.alert(player, "mcacrime.msg.guardaggro.reported", 600L);

        // The victim says something and the street hears it. Both are new: a mugging used to be a
        // silent progress bar that only the mugger could perceive at all.
        CrimeSounds.mugStart(target);
        boolean repeat = memoryOf(level, target, actor.id()) > 1;
        var event = repeat ? DialogueEvents.MUG_REPEAT : DialogueEvents.MUG_OPENING;
        CrimeDialogueService.speak(target, player, event,
                CrimeDialogueService.context(level, target, player, session.sessionId(), event));
        return ActionResult.accepted("mcacrime.mug.started");
    }

    /** How many times this actor has already threatened this villager, memory included. */
    private static int memoryOf(ServerLevel level, LivingEntity target, java.util.UUID actor) {
        VillagerCrimeProfile profile = CrimeWorldData.get(level.getServer())
                .villagerProfile(target.getUUID()).orElse(null);
        OffenderMemory memory = profile == null ? null : profile.offenderMemories().get(actor);
        return memory == null ? 0 : memory.attempts();
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {
        LivingEntity self = actor.entity();
        // The threat is the weapon. Putting it away mid-channel ends the mugging on the spot, which is
        // why this is checked every tick rather than on the same interval as everything else: a mugger
        // who can sheathe for four ticks and still be paid has found the loophole.
        if (McaCrimeConfig.COMMON.mugRequiresWeapon.get() && WeaponDetector.drawnWeapon(self).isEmpty()) {
            ActionSessionManager.cancel(session, CancelReason.WEAPON_LOST);
            return;
        }
        if (self.distanceToSqr(session.actorStart()) > 2.25D) {
            ActionSessionManager.cancel(session, CancelReason.MOVED);
            return;
        }
        if (self.distanceToSqr(target) > REACH_SQR) {
            ActionSessionManager.cancel(session, CancelReason.OUT_OF_RANGE);
            return;
        }
        if (!self.hasLineOfSight(target)) {
            ActionSessionManager.cancel(session, CancelReason.LOST_SIGHT);
            return;
        }
        if (!session.advance()) {
            // Progress is the HUD channel bar's job now. It replaces an action-bar line that
            // overwrote itself every ten ticks and then vanished without saying why.
            return;
        }

        ServerPlayer player = actor.asPlayer();
        if (player == null) {
            ActionSessionManager.cancel(session, CancelReason.ACTOR_GONE);
            return;
        }
        long now = level.getGameTime();
        CrimeWorldData world = CrimeWorldData.get(level.getServer());
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        VillagerCrimeProfile profile = CrimeMemoryService.profile(level.getServer(), target, now / 24000L);
        profile.purse().refill(now / 24000L);
        long actorRemaining = Math.max(0L, c.muggingActorValueCapPerDay.get()
                - world.actionCounter(actorValueKey(actor.id(), now)));
        long villageRemaining = Math.max(0L, c.muggingVillageValueCapPerDay.get()
                - world.actionCounter(villageValueKey(target, level, now)));
        int requested = (int) Math.min(Math.min(actorRemaining, villageRemaining), c.muggingBaseLoot.get());
        int transferred = EconomicTransactionService.transferPurseToPlayer(world, session.sessionId(),
                profile.purse(), player, requested);
        if (transferred > 0) {
            world.addActionCounter(actorSuccessKey(actor.id(), now), 1L);
            world.addActionCounter(actorValueKey(actor.id(), now), transferred);
            world.addActionCounter(villageValueKey(target, level, now), transferred);
            CrimeMemoryService.recordMugSuccess(level.getServer(), target, actor.id(), now, transferred);
            actor.sendMessage(Component.translatable("mcacrime.mug.success", transferred));
            CrimeSounds.mugSuccess(target);
        } else {
            actor.sendMessage(Component.translatable("mcacrime.mug.empty"));
            CrimeSounds.mugEmpty(target);
        }
        if (player != null) {
            var outcome = transferred > 0 ? DialogueEvents.MUG_SUCCESS : DialogueEvents.MUG_EMPTY;
            CrimeDialogueService.speak(target, player, outcome,
                    CrimeDialogueService.context(level, target, player, session.sessionId(), outcome));
        }
        McaCompat.makeVillagerFlee(target, player);
        ActionSessionManager.finish(session, transferred > 0
                ? ActionResult.accepted("mcacrime.mug.success", transferred)
                : ActionResult.accepted("mcacrime.mug.empty"));
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
