package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.dialogue.CrimeDialogueService;
import dev.otectus.mcacrime.dialogue.DialogueEvents;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * Cutting somebody else's captive free (spec §14.3).
 *
 * <p>This is the counterplay kidnapping did not have. Until now a captivity ended in exactly three
 * ways — the captor chose to end it, the captive ground out the escape work, or the real-time cap
 * expired — and none of those involve anybody else in the world. A kidnapping nobody else can
 * intervene in is not a crime in a village, it is a private timer, and the rest of the mod's
 * machinery (witnesses telling guards, a ransom being negotiated, a family paying) has no ending it
 * can actually produce.
 *
 * <p>Restraint strength is expressed as the rescuer's time and equipment rather than as a die roll,
 * matching the escape redesign in §14.6: rope yields to any blade, cuffs take substantially longer
 * without a key, and locked cuffs need one. That keeps the counterplay legible — a rescuer can look
 * at what the captor used and know what they need.
 *
 * <p>Rescuing is never a crime, including when the captivity is lawful — but a lawful captive is a
 * prisoner, and freeing one is the {@code jailbreak} that {@code JailConfine} already commits. So
 * this handler refuses lawful custody outright and leaves that path where it is, rather than opening
 * a second, unpoliced way out of a jail sentence.
 */
public final class RescueActionHandler implements CrimeActionHandler {

    private static final double REACH_SQR = 16.0D;
    /** How much longer cuffs take than rope when the rescuer has no key. */
    private static final double CUFFS_WITHOUT_KEY = 2.5D;
    /** How much faster the right key makes it. */
    private static final double WITH_KEY = 0.5D;

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.of(CrimeActionIds.RESCUE,
            ActionCategory.RESOLVE, ActionLegality.LAWFUL, ActionDuration.SHORT, false,
            ActionRequirement.KEY);

    @Override
    public ActionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        if (!McaCrimeConfig.COMMON.enableRescue.get()) {
            return ActionAvailability.hidden("mcacrime.rescue.disabled");
        }
        ServerPlayer player = actor.asPlayer();
        MinecraftServer server = level.getServer();
        if (player == null || server == null) {
            return ActionAvailability.hidden("mcacrime.rescue.invalid");
        }
        if (actor.id().equals(target.getUUID())) {
            // Freeing yourself is escaping, and escaping has its own work, cooldown and single roll.
            // Offering it here as well would be a second way out with none of those.
            return ActionAvailability.hidden("mcacrime.rescue.self");
        }
        CustodyRecord record = CrimeWorldData.get(server).getCustody(target.getUUID());
        if (record == null) {
            return ActionAvailability.hidden("mcacrime.rescue.not_held");
        }
        if (record.isLawful()) {
            return ActionAvailability.blocked("mcacrime.rescue.lawful");
        }
        if (record.getOwner().ownerUuid().filter(actor.id()::equals).isPresent()) {
            // You are the captor. Releasing your own captive is its own action, and it is not a rescue.
            return ActionAvailability.hidden("mcacrime.rescue.own_captive");
        }
        if (CrimeWorldData.get(server).isCaptive(actor.id())) {
            return ActionAvailability.blocked("mcacrime.rescue.while_held");
        }
        if (!actor.canReach(target, REACH_SQR)) {
            return ActionAvailability.blocked("mcacrime.rescue.range");
        }
        if (record.getRestraint() == RestraintType.LOCKED_CUFFS && !CrimeItems.hasKey(player)) {
            return ActionAvailability.blocked("mcacrime.rescue.needs_key");
        }
        return ActionAvailability.available();
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        long now = level.getGameTime();
        ActionAvailability availability = evaluate(actor, target, level, now);
        if (!availability.isAvailable()) {
            return ActionResult.rejected(availability.reason());
        }
        ServerPlayer player = actor.asPlayer();
        CustodyRecord record = CrimeWorldData.get(level.getServer()).getCustody(target.getUUID());
        ActionSession session = new ActionSession(UUID.randomUUID(), nonce, CrimeActionIds.RESCUE,
                actor.id(), target.getUUID(), level.dimension().location(), actor.entity().position(), now,
                channelTicks(player, record));
        if (!ActionSessionManager.begin(session)) {
            return ActionResult.rejected("mcacrime.action.conflict");
        }
        // No point of no return is marked. A rescue that breaks off leaves the captive exactly as they
        // were, which is right: nothing irreversible happened, and half a rescue is not a crime.
        actor.sendMessage(Component.translatable("mcacrime.rescue.started"));
        return ActionResult.accepted("mcacrime.rescue.started");
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {
        LivingEntity self = actor.entity();
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
        MinecraftServer server = level.getServer();
        if (server == null || !server.isSameThread()) {
            return;
        }
        CustodyRecord record = CrimeWorldData.get(server).getCustody(target.getUUID());
        if (record == null) {
            // Somebody else got there first, or the cap expired mid-channel. Nothing to free.
            ActionSessionManager.cancel(session, CancelReason.TARGET_GONE);
            return;
        }
        if (!session.advance()) {
            return;
        }

        ServerPlayer rescuer = actor.asPlayer();
        CustodyService.release(server, target.getUUID(), CustodyReleaseReason.RESCUED);
        CrimeSounds.rescued(target);

        if (rescuer != null) {
            // The gratitude payout. rescueHeartGain has been in the config since 0.1.0 with nothing
            // reading it; this is the action it was always describing.
            int hearts = McaCrimeConfig.COMMON.rescueHeartGain.get();
            if (hearts > 0 && McaCompat.isMcaVillager(target)) {
                McaCompat.addHearts(rescuer, target, hearts);
                grantFamilyGratitude(server, rescuer, target, hearts);
            }
            rescuer.sendSystemMessage(Component.translatable("mcacrime.rescue.done",
                    McaCompat.getVillagerDisplayName(target)));
            if (McaCompat.isMcaVillager(target)) {
                CrimeDialogueService.speak(target, rescuer, DialogueEvents.CAPTIVE_RESCUED,
                        CrimeDialogueService.context(level, target, rescuer, session.sessionId(),
                                DialogueEvents.CAPTIVE_RESCUED));
            }
        }
        ActionSessionManager.finish(session, ActionResult.accepted("mcacrime.rescue.done",
                McaCompat.getVillagerDisplayName(target)));
    }

    /**
     * The freed villager's family thinks well of the rescuer too, at a reduced rate.
     *
     * <p>This mirrors the {@code familyHeartLoss} that kidnapping already applies in the other
     * direction. Taking somebody's spouse costs you with the spouse; giving them back should be worth
     * something to the same person, or the relationship graph only ever moves one way.
     */
    private static void grantFamilyGratitude(MinecraftServer server, ServerPlayer rescuer,
                                             LivingEntity rescued, int hearts) {
        if (!McaCompat.isRelationshipApiAvailable()) {
            return;
        }
        int familyHearts = Math.max(1, hearts / 2);
        for (UUID relative : McaCompat.getCloseRelativeUuids(rescued, 1)) {
            for (ServerLevel level : server.getAllLevels()) {
                var entity = level.getEntity(relative);
                if (entity instanceof LivingEntity family && McaCompat.isMcaVillager(family)) {
                    McaCompat.addHearts(rescuer, family, familyHearts);
                    break;
                }
            }
        }
    }

    /**
     * How long this rescue takes, from the restraint and what the rescuer brought.
     *
     * <p>Locked cuffs never reach here without a key — {@link #evaluate} blocks that — so the key
     * discount is the only branch they have.
     */
    private static int channelTicks(ServerPlayer rescuer, CustodyRecord record) {
        int base = McaCrimeConfig.COMMON.rescueChannelTicks.get();
        double multiplier = switch (record.getRestraint()) {
            case ROPE -> CrimeItems.hasCuttingTool(rescuer) ? 0.5D : 1.0D;
            case CUFFS -> CrimeItems.hasKey(rescuer) ? WITH_KEY : CUFFS_WITHOUT_KEY;
            case LOCKED_CUFFS -> WITH_KEY;
            case NONE -> 0.5D;
        };
        return Math.max(1, (int) Math.round(base * multiplier));
    }
}
