package dev.otectus.mcacrime.bounty;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.BountyResolvedEvent;
import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyOwnerType;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.crime.KarmaSource;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.enforcement.ArrestService;
import dev.otectus.mcacrime.enforcement.OutlawResolver;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Warrant;
import dev.otectus.mcacrime.ledger.WarrantService;
import dev.otectus.mcacrime.state.CrimeCapabilities;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reserves bounty entitlements and delivers confirmed payments with retained remainders.
 *
 * <p>The spec opens this section by naming the implementation it does not want:
 *
 * <pre>if (victim.isWanted()) giveEmeralds(killer);</pre>
 *
 * <p>Everything here is the distance between that line and something a server can actually run. A
 * payout needs a claim identity ({@link BountyClaimKey}), a price derived from what the target
 * actually did ({@link BountyCalculator}), an attribution that survives arrows, wolves, and automation
 * ({@link #attribute}), and a mark-before-pay that no reconnect can undo ({@link BountyClaimLedger}).
 * A player who dies to lava is worth nothing, a player who kills themselves is worth nothing, and a
 * player killed twice on one warrant revision is worth nothing the second time.
 *
 * <p>The alive route is the one the release is really for. {@code aliveCaptureMultiplier} defaults
 * above {@code killMultiplier} so restraints, escorts and cells are the profitable way to hunt, and
 * the delivery in {@link #tick} is what turns a held outlaw into money: bring them to a guard.
 */
public final class BountyService {

    /** How often the delivery scan runs. Delivery is a walk, not a reflex. */
    private static final int TICK_INTERVAL = 20;

    private static int counter;
    private static long lastExpiryDay = -1L;

    private BountyService() {
    }

    // ------------------------------------------------------------------ kill attribution

    /**
     * One attacker, flattened to the three facts attribution needs.
     *
     * <p>A record rather than an {@code Entity} so {@link #attribute} is a pure function: the rules
     * for who gets paid are worth testing, and testing them must not require a running server.
     */
    public record Attacker(UUID id, boolean player, boolean fakePlayer) {

        /** A real, payable player — not a machine wearing a player's shape. */
        public boolean payable() {
            return player && !fakePlayer;
        }
    }

    /**
     * A damage source reduced to what a bounty cares about.
     *
     * @param responsible      whoever the game blames: the shooter for an arrow, the wolf for a bite
     * @param ownerOfResponsible the player behind that entity, when there reliably is one
     */
    public record DamageSourceView(@Nullable UUID victim, @Nullable Attacker responsible,
                                   @Nullable Attacker ownerOfResponsible) {
    }

    /**
     * Who, if anybody, is owed the bounty for this death.
     *
     * <p>Pure, and every empty answer is one of the spec's anti-farm rules: no responsible entity is
     * an environmental death, a fake player is automation, and a claimant equal to the victim is a
     * player arranging their own death. Ownership is consulted only when the responsible entity is not
     * itself a player, so a hunter who shoots an outlaw is paid rather than their arrow's owner being
     * looked up a second time.
     */
    public static Optional<UUID> attribute(@Nullable DamageSourceView view) {
        if (view == null) {
            return Optional.empty();
        }
        Attacker candidate = null;
        if (view.responsible() != null && view.responsible().player()) {
            candidate = view.responsible();
        } else if (view.ownerOfResponsible() != null) {
            candidate = view.ownerOfResponsible();
        }
        if (candidate == null || !candidate.payable()) {
            return Optional.empty();
        }
        if (candidate.id() == null || candidate.id().equals(view.victim())) {
            return Optional.empty(); // suicide, however indirect, pays nothing
        }
        return Optional.of(candidate.id());
    }

    /** Reads a live damage source into the pure view {@link #attribute} works on. */
    public static DamageSourceView view(DamageSource source, LivingEntity victim) {
        Entity responsible = source == null ? null : source.getEntity();
        Entity direct = source == null ? null : source.getDirectEntity();
        Entity owner = null;
        if (direct instanceof Projectile projectile) {
            owner = projectile.getOwner();
        } else if (direct instanceof OwnableEntity ownable) {
            owner = ownable.getOwner();
        } else if (responsible instanceof OwnableEntity ownable) {
            owner = ownable.getOwner();
        }
        return new DamageSourceView(victim == null ? null : victim.getUUID(),
                attacker(responsible), attacker(owner));
    }

    @Nullable
    private static Attacker attacker(@Nullable Entity entity) {
        if (entity == null) {
            return null;
        }
        return new Attacker(entity.getUUID(), entity instanceof ServerPlayer,
                entity instanceof FakePlayer);
    }

    // ------------------------------------------------------------------ pricing

    /**
     * What {@code target} is currently worth, or empty when nothing is owed on them.
     *
     * <p>Empty rather than zero, because "no bounty" and "a bounty of nothing" are different answers
     * to a tooltip and only one of them should draw a number.
     */
    public static Optional<Long> quote(ServerPlayer target) {
        MinecraftServer server = target == null ? null : target.getServer();
        if (server == null || !OutlawResolver.resolve(target).bountyEligible()) {
            return Optional.empty();
        }
        return WarrantService.open(server, target.getUUID())
                .map(warrant -> price(server, target.getUUID()));
    }

    /** The principal price on one offender's head, before the resolution multiplier. */
    public static long price(MinecraftServer server, UUID offender) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        List<CrimeRecord> unresolved = CrimeWorldData.get(server).actionableFor(offender);
        long heatSum = 0L;
        long fines = 0L;
        for (CrimeRecord record : unresolved) {
            heatSum = dev.otectus.mcacrime.util.SafeMath.addSat(heatSum, Math.max(0L, record.heatGenerated()));
            fines = dev.otectus.mcacrime.util.SafeMath.addSat(fines, Math.max(0L, record.fineAmount()));
        }
        ServerPlayer online = server.getPlayerList().getPlayer(offender);
        int priorWarrants = online == null ? 0
                : CrimeCapabilities.get(online).map(PlayerCrimeData::getPriorWarrants).orElse(0);
        return BountyCalculator.compute(c.baseBounty.get(), unresolved.size(), heatSum,
                c.severityRewardScale.get(), fines, c.fineRewardShare.get(), priorWarrants,
                c.repeatOffenderBonus.get(), c.minBounty.get(), c.maxBounty.get());
    }

    // ------------------------------------------------------------------ the kill route

    /** Immutable offer sampled at the death hook. It never adopts a later warrant or higher price. */
    public record KillOffer(BountyClaimKey key, UUID claimant, long asking, String currencyId,
                            boolean bountyEligible, boolean lethalForceLawful, String targetName) {}

    @Nullable
    public static KillOffer prepareKill(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof ServerPlayer victim) || victim instanceof FakePlayer || source == null) return null;
        var c = McaCrimeConfig.COMMON;
        var server = victim.getServer();
        if (!c.bountyEnabled.get() || !c.payForKills.get() || !ServerMutationGate.allows(server)) return null;
        var status = OutlawResolver.resolve(victim);
        var claimant = attribute(view(source, victim)).orElse(null);
        var warrant = WarrantService.open(server, victim.getUUID()).orElse(null);
        if (claimant == null || warrant == null || !status.bountyEligible() || !status.lethalForceLawful()) return null;
        long asking = dev.otectus.mcacrime.util.SafeMath.mulSat(price(server, victim.getUUID()), c.killMultiplier.get());
        return new KillOffer(BountyClaimKey.of(warrant), claimant, Math.max(0, asking),
                Currencies.active().id().toString(), true, true, victim.getDisplayName().getString());
    }

    /** Testable claim boundary used only after the shared finality check has confirmed death. */
    public static Payout claimKill(CrimeWorldData data, KillOffer offer, boolean confirmedDeath,
                                  boolean enabled, String currencyId, long now) {
        if (!confirmedDeath || !enabled || !ServerMutationGate.allows(data) || offer == null
                || offer.key() == null || !offer.bountyEligible() || !offer.lethalForceLawful()
                || !java.util.Objects.equals(offer.currencyId(), currencyId)) return new Payout(false, 0);
        Warrant live = data.warrant(offer.key().target());
        if (live == null || !live.open() || !BountyClaimKey.of(live).equals(offer.key())) return new Payout(false, 0);
        return pay(data, offer.key(), offer.claimant(), offer.asking(), now, BountyResolutionType.KILLED, currencyId);
    }

    public static void confirmKill(MinecraftServer server, @Nullable KillOffer offer) {
        if (server == null || offer == null) return;
        ServerPlayer claimant = server.getPlayerList().getPlayer(offer.claimant());
        if (claimant == null || claimant instanceof FakePlayer) return;
        var currency = Currencies.active();
        var c = McaCrimeConfig.COMMON;
        Payout payout = claimKill(CrimeWorldData.get(server), offer, true, c.bountyEnabled.get() && c.payForKills.get(),
                currency.id().toString(), server.overworld().getGameTime());
        if (payout.claimed()) collect(claimant);
        else claimant.sendSystemMessage(Component.translatable("mcacrime.bounty.not_reserved"));
    }
    // ------------------------------------------------------------------ the alive route

    /**
     * Pays the hunter who brought this outlaw in alive, once the arrest has actually happened.
     *
     * <p>Called from {@code ArrestService} rather than from the delivery scan, because the arrest is
     * what makes the delivery real: a hunter who drags an outlaw past a guard and keeps walking has
     * delivered nothing. The qualifier is the open warrant rather than a fresh eligibility check —
     * the warrant is the persisted fact the claim key is built on, and an arrest is not the moment to
     * discover that the price evaporated between the capture and the handover.
     */
    public static boolean resolveCapture(UUID hunterUuid, ServerPlayer captive) {
        return resolveCapture(captive == null ? null : captive.getServer(), hunterUuid, captive);
    }

    /** {@link #resolveCapture(UUID, ServerPlayer)} with the server already in hand. */
    public static boolean resolveCapture(MinecraftServer server, UUID hunterUuid, ServerPlayer captive) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        if (server == null || hunterUuid == null || captive == null
                || !c.bountyEnabled.get() || !c.payForAliveCapture.get()) {
            return false;
        }
        ServerPlayer hunter = server.getPlayerList().getPlayer(hunterUuid);
        if (hunter == null || hunter instanceof FakePlayer) {
            return false;
        }
        return WarrantService.open(server, captive.getUUID())
                .map(warrant -> pay(server, hunter, captive, warrant, BountyResolutionType.CAPTURED_ALIVE,
                        c.aliveCaptureMultiplier.get(), "mcacrime.bounty.paid_alive"))
                .orElse(false);
    }

    // ------------------------------------------------------------------ payment

    /** Whether the entitlement was reserved, and its principal; this does not assert delivery. */
    public record Payout(boolean claimed, long amount) {
    }

    /**
     * Claims one warrant revision and says what it pays, against a ledger rather than a server.
     *
     * <p>This legacy overload does not know the provider, so its reserved payment cannot automatically
     * collect. Live callers use the provider-bearing overload. Completion rewards/events follow
     * confirmed delivery, which can happen later on login or explicit collection.
     *
     * @param asking what the warrant is worth to this claimant before the ledger has had its say;
     *               what is returned is that less whatever the same warrant has already paid out
     */
    public static Payout pay(CrimeWorldData data, BountyClaimKey key, UUID claimant, long asking, long now,
                             BountyResolutionType type) {
        return pay(data, key, claimant, asking, now, type, "");
    }

    public static Payout pay(CrimeWorldData data, BountyClaimKey key, UUID claimant, long asking, long now,
                             BountyResolutionType type, String provider) {
        if (!ServerMutationGate.allows(data) || key == null) {
            // The claim row is the anti-double-pay mechanism. Paying without being able to record it
            // would pay the same head again on the next kill, and again after that.
            return new Payout(false, 0L);
        }
        long price = Math.max(0L, asking);
        // What this warrant has already cost, across every revision of it. A revision re-opens the
        // claim key at the new price, so without this a hunter could be paid the whole price again for
        // a head that has only become slightly more expensive (0.6.0, audit finding B09).
        long consumed = BountyClaimLedger.alreadyPaid(data, key.target(), key.warrantId(), price);
        long principal = Math.max(0L, price - consumed);
        if (!BountyPayments.reserve(data, key, claimant, principal, now, type, provider)) {
            return new Payout(false, 0L);
        }
        return new Payout(true, principal);
    }

    private static boolean pay(MinecraftServer server, ServerPlayer claimant, ServerPlayer target,
                               Warrant warrant, BountyResolutionType type, double multiplier,
                               String messageKey) {
        BountyClaimKey key = BountyClaimKey.of(warrant);
        long asking = Math.max(0L, Math.round(price(server, target.getUUID()) * Math.max(0.0D, multiplier)));
        long now = server.overworld().getGameTime();
        var currency = Currencies.active();
        Payout payout = pay(CrimeWorldData.get(server), key, claimant.getUUID(), asking, now, type, currency.id().toString());
        if (!payout.claimed()) {
            return false; // already paid for this warrant revision, or a self-claim
        }
        collect(claimant);
        return true; // Entitlement accepted; inventory/provider delivery can finish later.
    }

    private static boolean finishPayment(ServerPlayer claimant, UUID targetId, Component targetName, BountyClaimKey key,
            long principal, BountyResolutionType type, String messageKey, dev.otectus.mcacrime.economy.Currency currency) {

        int karma = McaCrimeConfig.COMMON.bountyKarmaReward.get();
        if (karma > 0) {
            CrimeState.addKarma(claimant, karma, KarmaSource.BOUNTY);
        }
        claimant.sendSystemMessage(Component.translatable(messageKey,
                currency.format(principal), targetName));
        CrimeDebug.crime("bounty {}/{} claimed by {}", key.target(), key.revision(), claimant.getUUID());
        MinecraftForge.EVENT_BUS.post(new BountyResolvedEvent(new BountyResolution(key, targetId,
                claimant.getUUID(), principal, type)));
        return true;
    }

    /** Login and explicit collection retry only payments with a known undelivered remainder. */
    public static int collect(ServerPlayer player) {
        var server = player.getServer();
        if (!ServerMutationGate.allows(server) || !player.isAlive() || player instanceof FakePlayer) return 0;
        var data = CrimeWorldData.get(server);
        int delivered = 0;
        boolean pending = false;
        for (var claim : data.bountyClaims().values()) {
            if (!player.getUUID().equals(claim.claimant())) continue;
            var key = BountyPayments.key(claim);
            var queued = data.transaction(BountyPayments.id(key));
            if (queued == null || queued.state().terminal()) continue;
            var currency = Currencies.byId(net.minecraft.resources.ResourceLocation.tryParse(queued.providerId())).orElse(null);
            if (currency == null) { pending = true; continue; }
            if (BountyPayments.deliver(data, key, player.getUUID(), currency.id().toString(),
                    amount -> creditReward(player, currency, amount), server.overworld().getGameTime())) {
                var target = server.getPlayerList().getPlayer(claim.target());
                finishPayment(player, claim.target(), target == null ? Component.literal(claim.target().toString())
                        : target.getDisplayName(), key, claim.reward(), claim.type(),
                        claim.type() == BountyResolutionType.CAPTURED_ALIVE ? "mcacrime.bounty.paid_alive" : "mcacrime.bounty.paid", currency);
                delivered++;
            }
            var receipt = data.transaction(BountyPayments.id(key));
            pending |= receipt != null && !receipt.state().terminal();
        }
        if (pending) player.sendSystemMessage(Component.translatable("mcacrime.bounty.pending"));
        return delivered;
    }

    private static long creditReward(ServerPlayer player, dev.otectus.mcacrime.economy.Currency currency, long amount) {
        if (currency instanceof dev.otectus.mcacrime.economy.EmeraldCurrency) {
            // Bounded by main-inventory capacity, with no fallible ground-drop handoff for overflow.
            long left = amount;
            for (int i = 0; i < player.getInventory().items.size() && left > 0; i++) {
                var stack = player.getInventory().items.get(i);
                if (stack.isEmpty()) {
                    int give = (int) Math.min(net.minecraft.world.item.Items.EMERALD.getMaxStackSize(), left);
                    player.getInventory().items.set(i, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, give));
                    left -= give;
                } else if (stack.is(net.minecraft.world.item.Items.EMERALD) && !stack.hasTag()) {
                    int give = (int) Math.min(Math.max(0, stack.getMaxStackSize() - stack.getCount()), left);
                    stack.grow(give); left -= give;
                }
            }
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            return left;
        }
        return currency.tryCredit(player, amount, TransactionReason.BOUNTY) ? 0 : -1;
    }

    // ------------------------------------------------------------------ delivery

    /**
     * Turns a held outlaw into a delivered one, and forgets ancient claims.
     *
     * <p>Ridden off the guard scan rather than given its own ticker, and throttled again on top of it:
     * a hunter walking a prisoner to town is a journey measured in minutes, so checking once a second
     * is already generous. A world with no bounty hunters in it pays one empty map iteration.
     */
    public static void tick(MinecraftServer server) {
        if (server == null || ++counter < TICK_INTERVAL) {
            return;
        }
        counter = 0;
        long day = server.overworld().getGameTime() / 24000L;
        if (day != lastExpiryDay) {
            lastExpiryDay = day;
            BountyClaimLedger.expire(server, day, McaCrimeConfig.COMMON.claimRetentionDays.get());
        }
        if (!McaCrimeConfig.COMMON.bountyEnabled.get() || !McaCrimeConfig.COMMON.payForAliveCapture.get()) {
            return;
        }
        double radius = McaCrimeConfig.COMMON.bountyDeliveryRadius.get();
        for (CustodyRecord record : heldByHunters(server)) {
            ServerPlayer captive = server.getPlayerList().getPlayer(record.getCaptive());
            if (captive == null || !captive.isAlive() || !(captive.level() instanceof ServerLevel level)) {
                continue;
            }
            LivingEntity responder = responderNear(level, captive, radius);
            if (responder != null) {
                ArrestService.arrest(captive, responder, ArrestService.Cause.DELIVERED);
            }
        }
    }

    /** Every lawful record whose holder is a bounty hunter. A copy: the arrest below rewrites them. */
    private static List<CustodyRecord> heldByHunters(MinecraftServer server) {
        List<CustodyRecord> out = new ArrayList<>();
        for (CustodyRecord record : CrimeWorldData.get(server).custodyRecords()) {
            CustodyOwner owner = record.getOwner();
            if (record.isCaptivePlayer() && owner != null
                    && owner.type() == CustodyOwnerType.BOUNTY_HUNTER) {
                out.add(record);
            }
        }
        return out;
    }

    @Nullable
    private static LivingEntity responderNear(ServerLevel level, ServerPlayer captive, double radius) {
        AABB box = captive.getBoundingBox().inflate(radius);
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box,
                entity -> entity.isAlive() && EntitySelectors.isAvailableResponder(entity))) {
            return candidate;
        }
        return null;
    }

    /** Drops the throttle and the expiry marker so a second world in one session starts clean. */
    public static void clearAll() {
        counter = 0;
        lastExpiryDay = -1L;
    }
}
