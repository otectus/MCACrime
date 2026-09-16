package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.captivity.CustodyOwnerType;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.enforcement.AccompliceService;
import dev.otectus.mcacrime.enforcement.BailQuote;
import dev.otectus.mcacrime.network.BailQuoteS2CPacket;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.relationship.FamilyGraph;
import dev.otectus.mcacrime.relationship.FamilyTier;
import dev.otectus.mcacrime.state.world.AccompliceRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buying a relative out of the rest of an arrest.
 *
 * <p>Two steps, and the first one is not a formality. Bail is priced from the sentence still to serve
 * and from how many times that relative has been arrested before, both of which the client has no way
 * of knowing; so the first click produces a {@link BailQuoteS2CPacket} and a screen, and the second —
 * an ordinary {@code StartActionC2SPacket} back through the same validated menu session — is the one
 * that charges. The existing {@code hostile} confirmation could not do this: it is a client-side
 * "are you sure" that sends the same packet once, with nowhere for a server-computed price to come
 * from.
 *
 * <p>The two clicks carry different nonces, which is what makes them two: the action engine's replay
 * cache answers a repeated nonce from cache without re-running anything, so a confirmation that
 * reused the first nonce would return the quote again forever. The pending quote is therefore held
 * here, per player, for as long as the menu that produced it is valid.
 *
 * <p>Self-{@code bail} (a player buying their own sentence out, {@code BailActionHandler}) and
 * {@code pay_ransom} are untouched. This shares only their money sink.
 */
public final class PayBailActionHandler implements CrimeActionHandler {

    /** How long a quote stays payable. Matches the action menu's own time-to-live. */
    private static final long QUOTE_TTL_TICKS = 200L;

    /** A price this player has been shown and has not yet paid or let lapse. */
    private record Pending(UUID target, long cost, long expiresAt) {
    }

    private static final Map<UUID, Pending> QUOTES = new ConcurrentHashMap<>();

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.of(CrimeActionIds.PAY_BAIL,
            ActionCategory.RESOLVE, ActionLegality.LAWFUL, ActionDuration.INSTANT, false,
            ActionRequirement.FAMILY, ActionRequirement.FUNDS);

    @Override
    public ActionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        if (!McaCrimeConfig.COMMON.enableFamilyBail.get()) {
            return ActionAvailability.hidden("mcacrime.bail.disabled");
        }
        ServerPlayer player = actor.asPlayer();
        if (player == null || target == null || actor.id().equals(target.getUUID())) {
            return ActionAvailability.hidden("mcacrime.bail.invalid");
        }
        if (!ServerMutationGate.allows(level.getServer())) {
            return ActionAvailability.blocked("mcacrime.readonly");
        }
        CustodyRecord record = lawfulArrest(level, target.getUUID());
        if (record == null) {
            // Not under arrest: an option to bail somebody who is walking around free names a
            // situation that does not exist, so it is not shown at all.
            return ActionAvailability.hidden("mcacrime.bail.not_held");
        }
        if (!isFamily(player, target)) {
            return ActionAvailability.hidden("mcacrime.bail.not_family");
        }
        long cost = cost(level, target.getUUID(), record);
        if (Currencies.active().balance(player) < cost) {
            return ActionAvailability.blocked("mcacrime.bail.insufficient");
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
        MinecraftServer server = level.getServer();
        long now = level.getGameTime();
        UUID captive = target.getUUID();

        Pending pending = QUOTES.get(player.getUUID());
        boolean confirmed = pending != null && pending.target().equals(captive) && pending.expiresAt() > now;
        long cost = confirmed ? pending.cost() : cost(level, captive, lawfulArrest(level, captive));

        BailQuote.Outcome outcome = BailQuote.pay(new BailQuote.Payment() {
            @Override
            public boolean inCustody() {
                return lawfulArrest(level, captive) != null;
            }

            @Override
            public long balance() {
                return Currencies.active().balance(player);
            }

            @Override
            public boolean charge(long amount) {
                return Currencies.active().tryCharge(player, amount, TransactionReason.BAIL);
            }

            @Override
            public void release() {
                CustodyService.release(server, captive, CustodyReleaseReason.RANSOM_PAID);
            }
        }, cost, confirmed);

        return switch (outcome) {
            case ALREADY_RELEASED -> {
                QUOTES.remove(player.getUUID());
                yield ActionResult.rejected("mcacrime.bail.already_released");
            }
            case QUOTED -> quote(player, target, level, cost, now)
                    ? ActionResult.accepted("mcacrime.action.feedback_sent")
                    : ActionResult.rejected("mcacrime.bail.unavailable");
            case INSUFFICIENT -> {
                QUOTES.remove(player.getUUID());
                yield ActionResult.rejected("mcacrime.bail.insufficient");
            }
            case PAID -> {
                QUOTES.remove(player.getUUID());
                CrimeSounds.paid(player);
                AccompliceService.notifyFamily(server, captive, "mcacrime.msg.family.released");
                player.sendSystemMessage(Component.translatable("mcacrime.bail.family_paid",
                        McaCompat.getVillagerDisplayName(target), Currencies.active().format(cost)));
                yield ActionResult.accepted("mcacrime.action.feedback_sent");
            }
        };
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {
    }

    /** Sends the price and remembers it, so the confirming click charges what the player was shown. */
    private static boolean quote(ServerPlayer player, LivingEntity target, ServerLevel level, long cost, long now) {
        ActionMenuSession menu = CrimeActionService.openMenuFor(player.getUUID()).orElse(null);
        if (menu == null) {
            // No open menu means no session for the screen's reply to come back through, and a price
            // nobody can act on is worse than a refusal.
            return false;
        }
        CustodyRecord record = lawfulArrest(level, target.getUUID());
        long remaining = record == null ? 0L : Math.max(0L, record.getRemainingJailTicks());
        QUOTES.put(player.getUUID(), new Pending(target.getUUID(), cost, now + QUOTE_TTL_TICKS));
        CrimeNetwork.sendBailQuote(player, new BailQuoteS2CPacket(menu.id(), menu.revision(),
                target.getUUID(), cost, McaCompat.getVillagerDisplayName(target).getString(),
                "crime." + CrimeIds.AIDING_A_CRIMINAL.getNamespace() + "."
                        + CrimeIds.AIDING_A_CRIMINAL.getPath(),
                remaining, BailQuote.releaseConditionKey(remaining)));
        player.sendSystemMessage(Component.translatable("mcacrime.bail.quote",
                McaCompat.getVillagerDisplayName(target), Currencies.active().format(cost)));
        return true;
    }

    /**
     * The custody record, when it is an arrest rather than a kidnapping.
     *
     * <p>Every lawful holder counts, not only the arresting guard: NPC custody is handed from the
     * guard to the jail as soon as a cell takes them ({@code NpcCustodyService}), and a bail option
     * that only worked during the walk there would be unusable for all but a few seconds of a
     * sentence. A bounty hunter's hold is excluded — that is a delivery, and it has its own payout.
     */
    private static CustodyRecord lawfulArrest(ServerLevel level, UUID captive) {
        CustodyRecord record = CrimeWorldData.get(level.getServer()).getCustody(captive);
        if (record == null || !record.isLawful() || record.isCaptivePlayer()) {
            return null;
        }
        CustodyOwnerType type = record.getOwner().type();
        return type == CustodyOwnerType.GUARD || type == CustodyOwnerType.JAIL
                || type == CustodyOwnerType.AUTHORITY ? record : null;
    }

    /** Whether this villager is one of the player's own relatives, inside the accomplice scope. */
    private static boolean isFamily(ServerPlayer player, LivingEntity target) {
        if (!McaCompat.isRelationshipApiAvailable()) {
            return false;
        }
        Set<FamilyTier> scope = AccompliceGate.scope(McaCrimeConfig.COMMON.accompliceScope.get());
        return FamilyGraph.relativesOf(player, scope,
                McaCrimeConfig.COMMON.familyLoyaltyGenerations.get()).containsKey(target.getUUID());
    }

    /** The price right now. Prior arrests come from the accomplice record, or zero when there is none. */
    private static long cost(ServerLevel level, UUID captive, CustodyRecord record) {
        AccompliceRecord accomplice = CrimeWorldData.get(level.getServer()).accomplice(captive);
        long remaining = record == null ? 0L : Math.max(0L, record.getRemainingJailTicks());
        return BailQuote.cost(remaining, accomplice == null ? 0 : accomplice.priorArrests(), settings());
    }

    /** The pricing keys, snapshotted at the call site so {@link BailQuote} never reads config. */
    public static BailQuote.Settings settings() {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        return new BailQuote.Settings(c.bailBase.get(), c.bailPerThousandTicks.get(), c.bailMin.get(),
                c.bailMax.get(), c.bailRepeatMultiplier.get());
    }

    /** Drops every outstanding quote. Called when a player logs out, alongside their menu. */
    public static void forget(UUID player) {
        QUOTES.remove(player);
    }
}
