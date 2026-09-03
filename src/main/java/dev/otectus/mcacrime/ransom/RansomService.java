package dev.otectus.mcacrime.ransom;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.economy.EmeraldCurrency;
import dev.otectus.mcacrime.economy.account.EconomicTransactionService;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.ledger.CrimeLedger;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Server-authoritative, idempotent ransom lifecycle (spec §8.5): a captor demands a ransom for an unlawful
 * captive; a payer (resolved by relationship priority) pays it to free the captive. A demand can never be
 * paid after the victim died, escaped, was rescued, or was jailed — those flip it to the matching failure.
 * Family payers must be reachable (online) players; when none exist it downgrades to a lower-value
 * village-authority settlement (or is refused if that fallback is disabled). The only writer of {@link
 * RansomState}; amounts charge atomically via {@link EmeraldCurrency}.
 */
public final class RansomService {

    private RansomService() {
    }

    // ------------------------------------------------------------------ demand

    /** The captor demands a ransom for the captive they hold. Returns 1 on success, 0 on a (messaged) refusal. */
    public static int demand(ServerPlayer captor) {
        MinecraftServer server = captor.getServer();
        if (server == null) {
            return 0;
        }
        for (CustodyRecord record : dev.otectus.mcacrime.captivity.CustodyRegistry.byOwner(
                server, captor.getUUID())) {
            if (!record.isLawful() && record.getOwner().isKidnapper(captor.getUUID())) {
                return demandFor(captor, record.getCaptive());
            }
        }
        return refuse(captor, "mcacrime.ransom.notholding");
    }

    /** Exact-target form used by the interaction menu; the custody table, not a cached capability, is authoritative. */
    public static int demandFor(ServerPlayer captor, UUID victimId) {
        MinecraftServer server = captor.getServer();
        if (server == null) return 0;
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        CrimeWorldData world = CrimeWorldData.get(server);
        CustodyRecord record = world.getCustody(victimId);
        if (record == null || record.isLawful() || !record.getOwner().isKidnapper(captor.getUUID())) {
            return refuse(captor, "mcacrime.ransom.notholding");
        }
        if (world.getRansomForVictim(victimId) != null) {
            return refuse(captor, "mcacrime.ransom.open");
        }
        LivingEntity victim = resolveVictim(server, record);
        if (victim == null || !victim.isAlive()) {
            return refuse(captor, "mcacrime.ransom.victimgone");
        }
        long now = victim.level().getGameTime();
        OptionalInt villageId = McaCompat.getHomeVillageId(victim);
        Optional<PayerResolver.Candidate> payerOpt = PayerResolver.resolve(
                gatherCandidates(server, victim, captor), c.enableVillageRansomFallback.get());
        if (payerOpt.isEmpty()) {
            return refuse(captor, "mcacrime.ransom.nopayer");
        }
        PayerResolver.Candidate payer = payerOpt.get();
        long amount = RansomCalculator.amount(c.ransomBaseAmount.get(), tierMultiplier(payer.tier()));
        if (!cooldownsReady(world, victimId, payer.uuid(), villageId, now)) {
            return refuse(captor, "mcacrime.ransom.cooldown");
        }
        UUID demandId = UUID.randomUUID();

        if (payer.tier() == PayerTier.VILLAGE_AUTHORITY) {
            String treasury = treasuryKey(record, villageId);
            if (!EconomicTransactionService.transferTreasuryToPlayer(world, demandId, treasury,
                    c.villageTreasuryInitialBalance.get(), captor, amount)) {
                return refuse(captor, "mcacrime.ransom.treasury_empty");
            }
            settle(server, record, captor, amount, villageId, now);
            stampCooldowns(world, victimId, null, villageId, now);
            captor.sendSystemMessage(Component.translatable("mcacrime.ransom.village", amount));
            return 1;
        }

        long ttl = c.ransomDemandTtlTicks.get();
        RansomState state = new RansomState(demandId, victimId, captor.getUUID(), payer.uuid(),
                payer.tier(), amount, now, now + ttl);
        world.putRansom(state);
        stampCooldowns(world, victimId, payer.uuid(), villageId, now);

        captor.sendSystemMessage(Component.translatable("mcacrime.ransom.demanded", amount));
        ServerPlayer payerPlayer = server.getPlayerList().getPlayer(payer.uuid());
        if (payerPlayer != null) {
            payerPlayer.sendSystemMessage(Component.translatable("mcacrime.ransom.notice", amount));
        }
        return 1;
    }

    // ------------------------------------------------------------------ pay (idempotent, atomic)

    /** The resolved payer pays their open demand, freeing the captive. Returns 1 on success, 0 on a refusal. */
    public static int pay(ServerPlayer payer) {
        MinecraftServer server = payer.getServer();
        if (server == null) {
            return 0;
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        RansomState state = findOpenDemandForPayer(world, payer.getUUID());
        if (state == null) {
            return refuse(payer, "mcacrime.ransom.none");
        }
        CustodyRecord record = world.getCustody(state.getVictim());
        if (record == null) {
            return failDemand(world, state, RansomStatus.FAILED_RESCUED, payer);
        }
        if (record.isLawful()) {
            return failDemand(world, state, RansomStatus.FAILED_JAILED, payer);
        }
        LivingEntity victim = resolveVictim(server, record);
        if (victim == null || !victim.isAlive()) {
            return failDemand(world, state, RansomStatus.FAILED_VICTIM_GONE, payer);
        }
        ServerPlayer captor = server.getPlayerList().getPlayer(state.getCaptor());
        if (captor == null) {
            return refuse(payer, "mcacrime.ransom.captoraway"); // hold the demand until the captor returns
        }
        if (EmeraldCurrency.INSTANCE.balance(payer) < state.getAmount()) {
            return refuse(payer, "mcacrime.ransom.need");
        }
        if (!EconomicTransactionService.transferPlayerToPlayer(world, state.getDemandId(), payer, captor,
                state.getAmount())) {
            return refuse(payer, "mcacrime.ransom.need");
        }
        state.setStatus(RansomStatus.PAID);
        settle(server, record, captor, state.getAmount(), McaCompat.getHomeVillageId(victim),
                victim.level().getGameTime());
        world.removeRansom(state.getVictim());
        payer.sendSystemMessage(Component.translatable("mcacrime.ransom.paid", state.getAmount()));
        captor.sendSystemMessage(Component.translatable("mcacrime.ransom.received", state.getAmount()));
        return 1;
    }

    // ------------------------------------------------------------------ tick re-validation (from RansomTickHandler)

    /** Expires stale demands and fails any whose victim died / escaped / was rescued / jailed (spec §8.5). */
    public static void validateOpenDemands(MinecraftServer server) {
        CrimeWorldData world = CrimeWorldData.get(server);
        long now = server.overworld().getGameTime();
        for (RansomState state : world.ransoms()) {
            if (state.getStatus().isTerminal()) {
                world.removeRansom(state.getVictim());
                continue;
            }
            CustodyRecord record = world.getCustody(state.getVictim());
            RansomStatus fail = null;
            if (record == null) {
                fail = RansomStatus.FAILED_RESCUED;
            } else if (record.isLawful()) {
                fail = RansomStatus.FAILED_JAILED;
            } else if (now >= state.getExpiresAtGameTime()) {
                fail = RansomStatus.EXPIRED;
            }
            if (fail != null) {
                world.removeRansom(state.getVictim());
                notifyFail(server, state, fail);
            }
        }
    }

    // ------------------------------------------------------------------ internals

    /** Frees the victim and writes the ransom audit record. Karma/Heat were already applied at capture time. */
    private static void settle(MinecraftServer server, CustodyRecord record, ServerPlayer captor, long amount,
                               OptionalInt villageId, long gameTime) {
        CrimeLedger.record(server, new CrimeRecord(UUID.randomUUID(), captor.getUUID(), record.getCaptive(),
                CrimeIds.EXTORTION, villageId, false, gameTime, 0L, 0L, amount, 0L, Resolution.UNRESOLVED));
        CustodyService.release(server, record.getCaptive(), CustodyReleaseReason.RANSOM_PAID);
    }

    private static int failDemand(CrimeWorldData world, RansomState state, RansomStatus status, ServerPlayer payer) {
        state.setStatus(status);
        world.removeRansom(state.getVictim());
        payer.sendSystemMessage(Component.translatable(failKey(status)));
        return 0;
    }

    private static void notifyFail(MinecraftServer server, RansomState state, RansomStatus status) {
        ServerPlayer captor = server.getPlayerList().getPlayer(state.getCaptor());
        if (captor != null) {
            captor.sendSystemMessage(Component.translatable(failKey(status)));
        }
        if (state.getPayer() != null) {
            ServerPlayer payer = server.getPlayerList().getPlayer(state.getPayer());
            if (payer != null) {
                payer.sendSystemMessage(Component.translatable(failKey(status)));
            }
        }
    }

    private static String failKey(RansomStatus status) {
        return switch (status) {
            case FAILED_VICTIM_GONE -> "mcacrime.ransom.fail.victim";
            case FAILED_JAILED -> "mcacrime.ransom.fail.jailed";
            case FAILED_RESCUED -> "mcacrime.ransom.fail.rescued";
            case EXPIRED -> "mcacrime.ransom.fail.expired";
            default -> "mcacrime.ransom.fail.cancelled";
        };
    }

    /**
     * Hearts at which a villager counts a player as a friend. Shared with the dialogue system's
     * {@code relationship=friend} fact on purpose — one number, one meaning.
     */
    private static final int FRIEND_HEART_THRESHOLD = 50;

    private static List<PayerResolver.Candidate> gatherCandidates(MinecraftServer server, LivingEntity victim,
                                                                   ServerPlayer captor) {
        List<PayerResolver.Candidate> out = new ArrayList<>();
        if (!McaCompat.isRelationshipApiAvailable()) {
            return out; // degrade to the village fallback (spec §8.5)
        }
        McaCompat.getSpouseUuid(victim).ifPresent(u -> addCandidate(out, server, u, PayerTier.SPOUSE, victim, captor));
        for (UUID u : McaCompat.getParentUuids(victim)) {
            addCandidate(out, server, u, PayerTier.PARENT, victim, captor);
        }
        for (UUID u : McaCompat.getChildUuids(victim)) {
            addCandidate(out, server, u, PayerTier.ADULT_CHILD, victim, captor);
        }
        for (UUID u : McaCompat.getSiblingUuids(victim)) {
            addCandidate(out, server, u, PayerTier.SIBLING, victim, captor);
        }
        for (UUID u : McaCompat.getCloseRelativeUuids(victim, 2)) {
            addCandidate(out, server, u, PayerTier.CLOSE_RELATIVE, victim, captor);
        }
        addCloseFriends(out, server, victim, captor);
        return out;
    }

    /**
     * Adds the close-friend tier, when it is enabled.
     *
     * <p>{@code enableCloseFriendTier} shipped saying it had "no MCA edge" and folded into the village
     * fallback, which meant turning it on changed nothing at all. It does have a verified source: MCA
     * hearts between a player and a villager are exactly a measure of who cares about that villager,
     * and they are already read everywhere else in this mod.
     *
     * <p>So a close friend is an online player, not related to the captive and not the captor, whose
     * hearts with them are at or above the same threshold the dialogue system calls a friend. Sharing
     * that number matters: a villager who calls you a friend when they speak should be a villager
     * whose ransom you are asked to pay.
     */
    private static void addCloseFriends(List<PayerResolver.Candidate> out, MinecraftServer server,
                                        LivingEntity victim, ServerPlayer captor) {
        if (!McaCrimeConfig.COMMON.enableCloseFriendTier.get()) {
            return;
        }
        java.util.Set<UUID> alreadyListed = new java.util.HashSet<>();
        for (PayerResolver.Candidate candidate : out) {
            if (candidate.uuid() != null) {
                alreadyListed.add(candidate.uuid());
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            if (id.equals(captor.getUUID()) || alreadyListed.contains(id)) {
                continue;
            }
            if (McaCompat.getHearts(player, victim) >= FRIEND_HEART_THRESHOLD) {
                addCandidate(out, server, id, PayerTier.CLOSE_FRIEND, victim, captor);
            }
        }
    }

    /** A candidate can pay only if it resolves to an online player (villager relations fold into the village fallback). */
    private static void addCandidate(List<PayerResolver.Candidate> out, MinecraftServer server, UUID uuid,
                                     PayerTier tier, LivingEntity victim, ServerPlayer captor) {
        if (uuid == null || uuid.equals(victim.getUUID()) || uuid.equals(captor.getUUID())) return;
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        long required = RansomCalculator.amount(McaCrimeConfig.COMMON.ransomBaseAmount.get(), tierMultiplier(tier));
        out.add(new PayerResolver.Candidate(uuid, tier, true,
                player != null && EmeraldCurrency.INSTANCE.balance(player) >= required));
    }

    private static boolean cooldownsReady(CrimeWorldData world, UUID victim, @Nullable UUID payer,
                                          OptionalInt villageId, long now) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        long lastVictim = world.ransomCooldown(victimKey(victim));
        long lastFamily = payer != null ? world.ransomCooldown(payerKey(payer)) : 0L;
        long lastVillage = villageId.isPresent() ? world.ransomCooldown(villageKey(villageId.getAsInt())) : 0L;
        return RansomCooldowns.ready(now,
                lastVictim, c.ransomCooldownPerVictimTicks.get(),
                lastFamily, c.ransomCooldownPerFamilyTicks.get(),
                lastVillage, c.ransomCooldownPerVillageTicks.get());
    }

    private static void stampCooldowns(CrimeWorldData world, UUID victim, @Nullable UUID payer,
                                       OptionalInt villageId, long now) {
        world.stampRansomCooldown(victimKey(victim), now);
        if (payer != null) {
            world.stampRansomCooldown(payerKey(payer), now);
        }
        villageId.ifPresent(id -> world.stampRansomCooldown(villageKey(id), now));
    }

    private static String victimKey(UUID victim) {
        return "victim:" + victim;
    }

    private static String payerKey(UUID payer) {
        return "payer:" + payer;
    }

    private static String villageKey(int villageId) {
        return "village:" + villageId;
    }

    private static String treasuryKey(CustodyRecord record, OptionalInt villageId) {
        return (record.getHoldDim() == null ? "minecraft:overworld" : record.getHoldDim().toString())
                + ":" + (villageId.isPresent() ? villageId.getAsInt() : "unincorporated");
    }

    @Nullable
    private static RansomState findOpenDemandForPayer(CrimeWorldData world, UUID payer) {
        for (RansomState state : world.ransoms()) {
            if (state.getStatus() == RansomStatus.OPEN && payer.equals(state.getPayer())) {
                return state;
            }
        }
        return null;
    }

    @Nullable
    private static LivingEntity resolveVictim(MinecraftServer server, CustodyRecord record) {
        if (record.isCaptivePlayer()) {
            return server.getPlayerList().getPlayer(record.getCaptive());
        }
        ServerLevel level = JailService.resolveLevel(server, record.getHoldDim());
        Entity e = level == null ? null : level.getEntity(record.getCaptive());
        return e instanceof LivingEntity living ? living : null;
    }

    private static double tierMultiplier(PayerTier tier) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        return switch (tier) {
            case SPOUSE -> c.ransomSpouseMultiplier.get();
            case PARENT -> c.ransomParentMultiplier.get();
            case ADULT_CHILD -> c.ransomChildMultiplier.get();
            case SIBLING -> c.ransomSiblingMultiplier.get();
            case CLOSE_RELATIVE, CLOSE_FRIEND -> c.ransomRelativeMultiplier.get();
            case VILLAGE_AUTHORITY -> c.ransomVillageMultiplier.get();
        };
    }

    private static int refuse(ServerPlayer player, String key) {
        player.sendSystemMessage(Component.translatable(key));
        return 0;
    }
}
