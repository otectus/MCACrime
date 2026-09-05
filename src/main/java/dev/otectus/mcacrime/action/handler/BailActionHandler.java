package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.jail.JailState;
import dev.otectus.mcacrime.jail.ReleaseReason;
import dev.otectus.mcacrime.ledger.SentenceResolutionService;
import dev.otectus.mcacrime.state.CrimeAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * Buying out the remainder of a jail sentence.
 *
 * <p>{@code enableBail} shipped in 0.1.0 as a bare boolean that no code read, which the plan (§22.3)
 * calls out by name: a bail option that is offered in the config and does nothing is worse than no
 * bail at all, because a server owner turns it on and their players find no way to use it. This is
 * that switch given a price and a path.
 *
 * <p>It is off by default and stays off. A sentence you can pay your way out of the moment it starts
 * is not a sentence, so the default configuration keeps jail as time served; the two settings that
 * make bail bearable when it is enabled — a minimum served fraction, and a cost that scales with the
 * time being bought — are both here rather than hard-coded.
 */
public final class BailActionHandler implements CrimeActionHandler {

    /** Ticks in one real minute of sentence, matching how the jail clock is described to players. */
    private static final long TICKS_PER_MINUTE = 1200L;

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.self(CrimeActionIds.BAIL,
            ActionCategory.RESOLVE, ActionLegality.LAWFUL, ActionDuration.INSTANT, false,
            ActionRequirement.FUNDS);

    @Override
    public ActionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        if (!McaCrimeConfig.COMMON.enableBail.get()) {
            return ActionAvailability.hidden("mcacrime.bail.disabled");
        }
        ServerPlayer player = actor.asPlayer();
        if (player == null || !actor.id().equals(target.getUUID())) {
            return ActionAvailability.hidden("mcacrime.bail.invalid");
        }
        JailState jail = CrimeAttachments.get(player).getJail();
        if (jail == null) {
            return ActionAvailability.hidden("mcacrime.bail.not_jailed");
        }
        if (servedFraction(jail) < McaCrimeConfig.COMMON.bailMinServedFraction.get()) {
            return ActionAvailability.blocked("mcacrime.bail.too_soon");
        }
        long cost = cost(jail);
        if (Currencies.active().balance(player) < cost) {
            return ActionAvailability.blocked("mcacrime.bail.cannot_afford");
        }
        return ActionAvailability.available();
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        ActionAvailability availability = evaluate(actor, target, level, level.getGameTime());
        if (!availability.isAvailable()) {
            return ActionResult.rejected(availability.reason());
        }
        ServerPlayer player = actor.asPlayer();
        MinecraftServer server = player.getServer();
        JailState jail = CrimeAttachments.get(player).getJail();
        if (server == null || jail == null) {
            return ActionResult.rejected("mcacrime.bail.not_jailed");
        }
        long cost = cost(jail);
        // Charge before releasing. The other order would free the player and then discover they could
        // not pay, and there is no way back into a sentence that has already ended.
        if (!Currencies.active().tryCharge(player, cost, TransactionReason.BAIL)) {
            return ActionResult.rejected("mcacrime.bail.cannot_afford");
        }
        UUID sentenceId = jail.getSentenceId();
        JailService.release(player, ReleaseReason.BAILED);
        SentenceResolutionService.markBailed(server, player.getUUID(), sentenceId);
        CrimeSounds.paid(player);
        player.sendSystemMessage(Component.translatable("mcacrime.bail.paid", cost));
        return ActionResult.accepted("mcacrime.bail.paid", cost);
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {
    }

    /**
     * What the rest of the sentence costs, rounded up so a sliver of a minute is never free.
     *
     * <p>Priced on time remaining rather than on the original sentence: bail buys the future, and a
     * player who has nearly finished should not be quoted what they would have paid on day one.
     */
    public static long cost(JailState jail) {
        return costFor(jail.getRemainingOnlineTicks(), McaCrimeConfig.COMMON.bailCostPerMinute.get());
    }

    /** The pure form, so the price can be asserted without a loaded config. */
    public static long costFor(long remainingTicks, int perMinute) {
        long remaining = Math.max(0L, remainingTicks);
        long minutes = (remaining + TICKS_PER_MINUTE - 1) / TICKS_PER_MINUTE;
        return minutes * Math.max(0, perMinute);
    }

    /**
     * How much of the sentence is already done, 0..1.
     *
     * <p>A sentence with no recorded length at all reads as fully served rather than not started. That
     * is the safe direction: the failure mode is a player being allowed to pay, not a player being
     * trapped by a division that could not be performed.
     */
    public static double servedFraction(JailState jail) {
        long served = Math.max(0L, jail.getRealOnlineTicksServed());
        long total = served + Math.max(0L, jail.getRemainingOnlineTicks());
        return total <= 0L ? 1.0D : (double) served / total;
    }
}
