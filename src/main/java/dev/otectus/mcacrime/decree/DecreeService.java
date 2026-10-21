package dev.otectus.mcacrime.decree;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.api.event.DecreeChangedEvent;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.DecreeView;
import dev.otectus.mcacrime.compat.CapitalStanding;
import dev.otectus.mcacrime.compat.CapitalView;
import dev.otectus.mcacrime.compat.CapitalsBridge;
import dev.otectus.mcacrime.compat.CapitalsCapability;
import dev.otectus.mcacrime.compat.CapitalsQueryResult;
import dev.otectus.mcacrime.detect.CrimeCommunityResolver;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.economy.FineService;
import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.economy.account.EconomicTransactionService;
import dev.otectus.mcacrime.economy.account.PurseAccess;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.jail.JailState;
import dev.otectus.mcacrime.jail.ReleaseReason;
import dev.otectus.mcacrime.jail.SentenceDisposition;
import dev.otectus.mcacrime.state.CrimeAttachments;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Royal decrees, on the server (0.7.8): issuing, withdrawing, paying, answering by custody, lapsing,
 * and the one question enforcement asks -- is there a decree in force against this player for this
 * village's guards.
 *
 * <p>Every rule lives in {@link DecreeRules}; this class gathers the facts those rules need from the
 * live server and MCA Capitals, writes what they answer, and tells the people involved. Every door --
 * the decree screen, {@code /crime decree}, the guard's challenge -- comes through the same methods, so a
 * command cannot skip a check a click applies.
 *
 * <h2>What a decree never touches</h2>
 *
 * <p>Heat, Karma, the band, the global Wanted flag, the bounty {@code Warrant}, the case ledger, MCA
 * hearts and MCA: Reputation. Paying a decree's fine settles the decree and nothing else; serving its
 * term closes it and whatever cases the same sentence was for, exactly as a sentence always did.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class DecreeService {

    /** How long one standing reading is reused. Expiry is always checked fresh on top of it. */
    private static final long STANDING_CACHE_TICKS = 100L;

    private record Cached(long revision, DecreeRules.Standing standing, long until) {
    }

    private static final Map<UUID, Cached> STANDINGS = new ConcurrentHashMap<>();
    /** Per player, the decrees they have been told about while standing in the village. Memory only. */
    private static final Map<UUID, Set<UUID>> NOTICED = new ConcurrentHashMap<>();

    private DecreeService() {
    }

    /** What an attempt came to. */
    public record Outcome(@Nullable Decree decree, @Nullable DecreeRefusal refusal) {
        public boolean ok() {
            return refusal == null;
        }

        static Outcome done(Decree decree) {
            return new Outcome(decree, null);
        }

        static Outcome refused(DecreeRefusal refusal) {
            return new Outcome(null, refusal);
        }
    }

    // ------------------------------------------------------------------ facts

    /** The village a player is standing in, by the same rule a crime against a homeless victim uses. */
    public static Optional<CrimeCommunityKey> communityOf(@Nullable ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        return CrimeCommunityResolver.resolve(player, level);
    }

    @Nullable
    static ServerLevel levelOf(@Nullable MinecraftServer server, @Nullable CrimeCommunityKey community) {
        if (server == null || community == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, community.dimension()));
    }

    static long now(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    static DecreeRules.Ledger ledger(CrimeWorldData data) {
        return new DecreeRules.Ledger() {
            @Override
            public Optional<Decree> openFor(UUID offender, CrimeCommunityKey community) {
                return data.openDecree(offender, community);
            }

            @Override
            public long lastEndedAt(UUID offender, CrimeCommunityKey community) {
                return data.lastDecreeEndedAt(offender, community);
            }

            @Override
            public int openIn(UUID capitalId) {
                return data.openDecreeCount(capitalId);
            }

            @Override
            public boolean full() {
                return data.decreeTableFull();
            }
        };
    }

    /** The capital seated where a decree applies, or why it cannot be read. */
    static CapitalsQueryResult<CapitalView> capitalFor(@Nullable ServerLevel level, @Nullable CrimeCommunityKey community) {
        if (level == null || community == null) {
            return CapitalsQueryResult.unavailable("no village");
        }
        return CapitalsBridge.capitalAt(level, community.villageId());
    }

    /** Whether {@code player} sits on the throne of the capital seated in {@code community}, read now. */
    public static boolean rules(@Nullable ServerPlayer player, @Nullable CrimeCommunityKey community) {
        if (player == null || community == null || !CapitalsBridge.has(CapitalsCapability.REGISTRY)
                || !CapitalsBridge.has(CapitalsCapability.AUTHORITY)) {
            return false;
        }
        ServerLevel level = levelOf(player.getServer(), community);
        return DecreeRules.authority(capitalFor(level, community), community, player.getUUID()).isEmpty();
    }

    // ------------------------------------------------------------------ issuing

    /**
     * Issues a decree against {@code target} in the village {@code issuer} is standing in. Every fact is
     * read now: the throne, the target's standing in that capital, the store.
     */
    public static Outcome issue(ServerPlayer issuer, ServerPlayer target, DecreeReason reason, DecreeProvenance via) {
        MinecraftServer server = issuer == null ? null : issuer.getServer();
        if (server == null || !server.isSameThread() || target == null || reason == null
                || server.getPlayerList().getPlayer(target.getUUID()) != target) {
            return Outcome.refused(DecreeRefusal.TARGET_UNKNOWN);
        }
        DecreeSettings settings = DecreeSettings.fromConfig();
        CrimeWorldData data = CrimeWorldData.get(server);
        CrimeCommunityKey community = communityOf(issuer).orElse(null);
        ServerLevel level = levelOf(server, community);
        long now = now(server);
        CapitalsQueryResult<CapitalView> capital = capitalFor(level, community);
        CapitalsQueryResult<CapitalStanding> standing = capital instanceof CapitalsQueryResult.Available<CapitalView> found
                ? CapitalsBridge.standing(level, community.villageId(), found.value().capitalId(), target.getUUID())
                : CapitalsQueryResult.unavailable("no capital");
        Optional<DecreeRefusal> refusal = DecreeRules.decideIssue(
                new DecreeRules.IssueRequest(issuer.getUUID(), target.getUUID(), community, now), settings,
                !ServerMutationGate.allows(data), CapitalsBridge.decreesReady(), capital, standing, ledger(data));
        if (refusal.isPresent()) {
            return Outcome.refused(refusal.get());
        }
        CapitalView view = capital.orThrow();
        Decree decree = Decree.issue(UUID.randomUUID(), target.getUUID(), target.getGameProfile().getName(),
                community, view.capitalId(), issuer.getUUID(), issuer.getGameProfile().getName(), reason, now,
                settings.durationTicks(), via);
        if (!data.putDecree(decree)) {
            return Outcome.refused(DecreeRefusal.STORE_FULL);
        }
        forget(decree);
        CrimeDebug.crime("decree {} issued against {} in {} by {}", decree.id(), decree.offender(),
                community.asString(), decree.issuedBy());
        Component village = DecreeText.village(server, community);
        issuer.sendSystemMessage(Component.translatable("mcacrime.decree.issued", DecreeText.name(decree.offenderName()),
                village, DecreeText.reason(reason), DecreeText.time(settings.durationTicks())));
        tellIssued(target, decree, village, settings, now);
        NOTICED.computeIfAbsent(target.getUUID(), id -> ConcurrentHashMap.newKeySet()).add(decree.id());
        if (settings.chronicle()) {
            chronicle(level, decree, DecreeText.chronicleIssued(server, decree), settings.chronicleHerald());
        }
        post(decree, DecreeChangedEvent.Change.ISSUED);
        return Outcome.done(decree);
    }

    private static void tellIssued(ServerPlayer target, Decree decree, Component village, DecreeSettings settings,
                                   long now) {
        Component ruler = DecreeText.name(decree.issuedByName());
        target.sendSystemMessage(settings.payable()
                ? Component.translatable("mcacrime.decree.notice.issued", ruler, village,
                        DecreeText.reason(decree.reason()), DecreeText.fine(settings.fineAmount()),
                        DecreeText.time(decree.remaining(now)))
                : Component.translatable("mcacrime.decree.notice.issued_unpayable", ruler, village,
                        DecreeText.reason(decree.reason()), DecreeText.time(decree.remaining(now))));
    }

    // ------------------------------------------------------------------ withdrawing and voiding

    /** Withdraws the open decree against {@code offender} in the village {@code actor} stands in. */
    public static Outcome withdraw(ServerPlayer actor, UUID offender, DecreeProvenance via) {
        MinecraftServer server = actor == null ? null : actor.getServer();
        if (server == null || !server.isSameThread() || offender == null) {
            return Outcome.refused(DecreeRefusal.NOT_FOUND);
        }
        DecreeSettings settings = DecreeSettings.fromConfig();
        CrimeWorldData data = CrimeWorldData.get(server);
        CrimeCommunityKey community = communityOf(actor).orElse(null);
        Decree decree = data.openDecree(offender, community).orElse(null);
        ServerLevel level = levelOf(server, community);
        CapitalsQueryResult<CapitalView> capital = capitalFor(level, community);
        Optional<DecreeRefusal> refusal = DecreeRules.decideWithdraw(decree, settings, !ServerMutationGate.allows(data),
                CapitalsBridge.has(CapitalsCapability.REGISTRY) && CapitalsBridge.has(CapitalsCapability.AUTHORITY),
                community, capital, actor.getUUID());
        if (refusal.isPresent()) {
            return Outcome.refused(refusal.get());
        }
        Decree ended = decree.ended(DecreeState.WITHDRAWN, now(server), actor.getUUID(), "withdrawn:" + via.id());
        if (!data.putDecree(ended)) {
            return Outcome.refused(DecreeRefusal.READ_ONLY);
        }
        forget(ended);
        Component village = DecreeText.village(server, ended.community());
        actor.sendSystemMessage(Component.translatable("mcacrime.decree.withdrawn",
                DecreeText.name(ended.offenderName()), village));
        tell(server, ended.offender(), Component.translatable("mcacrime.decree.notice.withdrawn", village));
        liftIfSoleReason(server, decree);
        if (settings.chronicle()) {
            chronicle(level, ended, DecreeText.chronicleWithdrawn(server, ended, actor.getGameProfile().getName()),
                    settings.chronicleHerald());
        }
        post(ended, DecreeChangedEvent.Change.WITHDRAWN);
        return Outcome.done(ended);
    }

    /**
     * An operator's void: ends every open decree against {@code offender}, or only the one in
     * {@code community} when given. Needs no Capitals at all, so a stuck decree can always be cleared.
     *
     * @return how many were voided
     */
    public static int voidDecrees(MinecraftServer server, UUID offender, @Nullable CrimeCommunityKey community,
                                  @Nullable UUID actor) {
        if (server == null || offender == null || !server.isSameThread()) {
            return 0;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        if (!ServerMutationGate.allows(data)) {
            return 0;
        }
        int voided = 0;
        long now = now(server);
        for (Decree decree : data.decreesOf(offender)) {
            if (!decree.open() || community != null && !community.equals(decree.community())) {
                continue;
            }
            Decree ended = decree.ended(DecreeState.VOID, now, actor, "operator");
            if (data.putDecree(ended)) {
                voided++;
                forget(ended);
                tell(server, offender, Component.translatable("mcacrime.decree.notice.voided",
                        DecreeText.village(server, ended.community())));
                liftIfSoleReason(server, decree);
                post(ended, DecreeChangedEvent.Change.VOIDED);
            }
        }
        return voided;
    }

    /**
     * Ends a decree's custody term with it when nothing else holds the prisoner: the sentence binds no
     * case and no other open decree. Released through the forgive-nothing {@link ReleaseReason#ADMIN}
     * disposition, so their Heat and any case stay exactly as they were. Offline, the term simply runs
     * out; it is short by design.
     */
    private static void liftIfSoleReason(MinecraftServer server, Decree before) {
        if (before.sentenceId() == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(before.offender());
        if (player == null) {
            return;
        }
        JailState jail = dev.otectus.mcacrime.state.CrimeAttachments.get(player).getJail();
        CrimeWorldData data = CrimeWorldData.get(server);
        if (jail == null || !before.sentenceId().equals(jail.getSentenceId())
                || !data.casesForSentence(player.getUUID(), jail.getSentenceId()).isEmpty()) {
            return;
        }
        for (Decree other : data.decreesOf(player.getUUID())) {
            if (other.open() && before.sentenceId().equals(other.sentenceId())) {
                return;
            }
        }
        JailService.release(player, ReleaseReason.ADMIN, "mcacrime.decree.released");
    }

    // ------------------------------------------------------------------ paying

    /** Pays the decree in force against {@code player} in the village they stand in. */
    public static FineService.Payment payHere(ServerPlayer player) {
        CrimeCommunityKey community = communityOf(player).orElse(null);
        MinecraftServer server = player == null ? null : player.getServer();
        Decree decree = server == null ? null
                : CrimeWorldData.get(server).openDecree(player.getUUID(), community).orElse(null);
        if (decree == null) {
            return refusedPayment(player, DecreeRefusal.NOT_FOUND);
        }
        return pay(player, decree.id());
    }

    /**
     * Pays one decree's fine. The decree must be open, in force and the payer's own; the fine is the
     * configured amount, taken through the same receipted transfer a case fine uses.
     */
    public static FineService.Payment pay(ServerPlayer player, @Nullable UUID decreeId) {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null || !server.isSameThread()) {
            return refusedPayment(player, DecreeRefusal.NOT_FOUND);
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        if (!ServerMutationGate.allows(data)) {
            return refusedPayment(player, DecreeRefusal.READ_ONLY);
        }
        Decree decree = data.decree(decreeId);
        if (decree == null || !decree.open() || !decree.offender().equals(player.getUUID())) {
            return refusedPayment(player, DecreeRefusal.NOT_FOUND);
        }
        DecreeSettings settings = DecreeSettings.fromConfig();
        long now = now(server);
        if (!evaluate(server, decree, now).enforced()) {
            return refusedPayment(player, DecreeRefusal.NOT_VERIFIABLE);
        }
        if (!settings.payable()) {
            return refusedPayment(player, DecreeRefusal.NOT_PAYABLE);
        }
        long amount = settings.fineAmount();
        UUID transactionId = UUID.randomUUID();
        boolean charged = EconomicTransactionService.transfer(data, transactionId, TransactionReason.FINE,
                Currencies.active().id().toString(), purse(player), amount, false, player.getUUID(), null, now)
                .delivered();
        long heat = CrimeState.getHeat(player);
        if (!charged) {
            player.sendSystemMessage(Component.translatable(DecreeRefusal.CANNOT_PAY.messageKey(), DecreeText.fine(amount)));
            return new FineService.Payment(false, null, List.of(), amount, heat, heat,
                    DecreeRefusal.CANNOT_PAY.messageKey());
        }
        Decree ended = decree.ended(DecreeState.FINED, now, player.getUUID(), "paid");
        data.putDecree(ended);
        forget(ended);
        player.sendSystemMessage(Component.translatable("mcacrime.decree.paid", DecreeText.fine(amount),
                DecreeText.village(server, ended.community())));
        liftIfSoleReason(server, decree);
        post(ended, DecreeChangedEvent.Change.FINED);
        return new FineService.Payment(true, transactionId, List.of(), amount, heat, heat, "mcacrime.decree.paid");
    }

    private static PurseAccess purse(ServerPlayer player) {
        return new PurseAccess() {
            @Override
            public long available() {
                return Currencies.active().balance(player);
            }

            @Override
            public long debit(long requested, TransactionReason reason) {
                return Currencies.active().tryCharge(player, requested, reason) ? requested : 0L;
            }

            @Override
            public boolean credit(long credited, TransactionReason reason) {
                return true; // nobody is paid a decree's fine; the debit is the whole transfer
            }
        };
    }

    private static FineService.Payment refusedPayment(@Nullable ServerPlayer player, DecreeRefusal refusal) {
        long heat = player == null ? 0L : CrimeState.getHeat(player);
        if (player != null) {
            player.sendSystemMessage(Component.translatable(refusal.messageKey()));
        }
        return new FineService.Payment(false, null, List.of(), 0L, heat, heat, refusal.messageKey());
    }

    // ------------------------------------------------------------------ enforcement

    /** Whether this decree is enforced right now, reusing a recent reading. */
    public static DecreeRules.Standing standing(MinecraftServer server, Decree decree) {
        long now = now(server);
        Cached cached = STANDINGS.get(decree.id());
        if (cached != null && cached.revision() == decree.revision() && now < cached.until()) {
            return decree.expired(now) ? DecreeRules.Standing.EXPIRED : cached.standing();
        }
        DecreeRules.Standing fresh = evaluate(server, decree, now);
        STANDINGS.put(decree.id(), new Cached(decree.revision(), fresh, now + STANDING_CACHE_TICKS));
        return fresh;
    }

    /** A fresh reading, used where a stale one could decide something: paying, the sweep. */
    static DecreeRules.Standing evaluate(MinecraftServer server, Decree decree, long now) {
        DecreeSettings settings = DecreeSettings.fromConfig();
        ServerLevel level = levelOf(server, decree.community());
        if (level == null) {
            return decree.expired(now) ? DecreeRules.Standing.EXPIRED
                    : decree.open() ? DecreeRules.Standing.SUSPENDED : DecreeRules.Standing.CLOSED;
        }
        CapitalsQueryResult<CapitalView> capital = CapitalsBridge.capitalAt(level, decree.community().villageId());
        CapitalsQueryResult<Boolean> any = capital.isAbsent() ? CapitalsBridge.anyCapitals()
                : CapitalsQueryResult.available(Boolean.TRUE);
        CapitalsQueryResult<CapitalStanding> target = capital instanceof CapitalsQueryResult.Available<CapitalView> found
                && found.value().capitalId().equals(decree.capitalId())
                ? CapitalsBridge.standing(level, decree.community().villageId(), decree.capitalId(), decree.offender())
                : CapitalsQueryResult.unavailable("no capital");
        return DecreeRules.standing(decree, now, settings.on(), CapitalsBridge.decreesReady(), capital, any, target);
    }

    /** A guard may act on a decree only while its target is physically in that jurisdiction. */
    public static Optional<Decree> enforceableHere(@Nullable ServerPlayer player,
                                                   @Nullable CrimeCommunityKey jurisdiction) {
        return jurisdiction == null ? Optional.empty()
                : inForceHere(player).filter(decree -> jurisdiction.equals(decree.community()));
    }

    /** The standing decree in the player's present village, never one from a village they left. */
    public static Optional<Decree> inForceHere(@Nullable ServerPlayer player) {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null || !CrimeWorldData.get(server).hasOpenDecree(player.getUUID())) return Optional.empty();
        // Location is deliberately outside the standing cache: leaving a village stands guards down now.
        return inForce(server, player.getUUID(), communityOf(player).orElse(null));
    }

    /** The decree standing in a community, independent of the offender's current location. */
    public static Optional<Decree> inForce(@Nullable MinecraftServer server, @Nullable UUID offender,
                                           @Nullable CrimeCommunityKey community) {
        if (server == null || offender == null || community == null) {
            return Optional.empty();
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        if (!data.hasOpenDecree(offender)) {
            return Optional.empty();
        }
        return data.openDecree(offender, community).filter(decree -> standing(server, decree).enforced());
    }

    /** Whether any decree stands against {@code offender} anywhere, independent of their location. */
    public static boolean anyInForce(@Nullable MinecraftServer server, @Nullable UUID offender) {
        if (server == null || offender == null) {
            return false;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        if (!data.hasOpenDecree(offender)) {
            return false;
        }
        for (Decree decree : data.decreesOf(offender)) {
            if (decree.open() && standing(server, decree).enforced()) {
                return true;
            }
        }
        return false;
    }

    /** Every decree in force against {@code offender}, for {@code /crime status}. */
    public static List<Decree> inForceAnywhere(@Nullable MinecraftServer server, @Nullable UUID offender) {
        List<Decree> out = new ArrayList<>();
        if (server == null || offender == null) {
            return out;
        }
        for (Decree decree : CrimeWorldData.get(server).decreesOf(offender)) {
            if (decree.open() && standing(server, decree).enforced()) {
                out.add(decree);
            }
        }
        return out;
    }

    /**
     * What a guard says to someone stopped on a decree: whose decree, in which village, why, and how it
     * ends. Falls back to the plain detention line when the decree is gone.
     */
    public static Component challengeLine(@Nullable MinecraftServer server, @Nullable UUID decreeId) {
        Decree decree = server == null ? null : CrimeWorldData.get(server).decree(decreeId);
        if (decree == null || !decree.open()) {
            return Component.translatableWithFallback("mcacrime.challenge.reason.decree",
                    "You are wanted here by royal decree. Surrender to answer it.");
        }
        DecreeSettings settings = DecreeSettings.fromConfig();
        return Component.translatable(settings.payable() ? "mcacrime.challenge.decree" : "mcacrime.challenge.decree_unpayable",
                DecreeText.name(decree.issuedByName()), DecreeText.village(server, decree.community()),
                DecreeText.reason(decree.reason()));
    }

    // ------------------------------------------------------------------ custody

    /** Records that {@code sentenceId} answers this decree, so serving it ends the decree. */
    public static void bindSentence(@Nullable MinecraftServer server, UUID offender, @Nullable UUID decreeId,
                                    @Nullable UUID sentenceId) {
        if (server == null || decreeId == null || sentenceId == null) {
            return;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        Decree decree = data.decree(decreeId);
        if (decree != null && decree.open() && decree.offender().equals(offender)
                && !sentenceId.equals(decree.sentenceId()) && data.putDecree(decree.bound(sentenceId))) {
            forget(decree);
        }
    }

    /**
     * A sentence ended. A decree it answered ends as served when the sentence was served, bailed,
     * pardoned or carried out; when it ended without answering anything -- an operator's release, a jail
     * that no longer exists -- the decree is released from it and stands again, exactly as cases do.
     */
    public static void sentenceEnded(MinecraftServer server, ServerPlayer player, @Nullable UUID sentenceId,
                                     ReleaseReason reason) {
        if (server == null || player == null || sentenceId == null) {
            return;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        if (!data.hasOpenDecree(player.getUUID())) {
            return;
        }
        SentenceDisposition.Effect effect = SentenceDisposition.effectOf(reason);
        long now = now(server);
        for (Decree decree : data.decreesOf(player.getUUID())) {
            if (!decree.open() || !sentenceId.equals(decree.sentenceId())) {
                continue;
            }
            if (effect == SentenceDisposition.Effect.UNBIND) {
                if (data.putDecree(decree.unbound())) {
                    forget(decree);
                }
                continue;
            }
            Decree ended = decree.ended(DecreeState.SERVED, now, null,
                    "served:" + reason.name().toLowerCase(java.util.Locale.ROOT));
            if (data.putDecree(ended)) {
                forget(ended);
                player.sendSystemMessage(Component.translatable("mcacrime.decree.notice.served",
                        DecreeText.village(server, ended.community())));
                post(ended, DecreeChangedEvent.Change.SERVED);
            }
        }
    }

    // ------------------------------------------------------------------ the sweep

    /**
     * Ends what can no longer stand -- lapsed decrees, a capital that left its village, a target raised
     * to Noble -- and forgets ended ones past retention. Called from the maintenance sweep; a decree that
     * is merely suspended is never ended here.
     *
     * @return how many decrees were ended
     */
    public static int sweep(@Nullable MinecraftServer server) {
        if (server == null) {
            return 0;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        if (!ServerMutationGate.allows(data)) {
            return 0;
        }
        long now = now(server);
        int ended = 0;
        for (Decree decree : data.decrees()) {
            if (!decree.open()) {
                continue;
            }
            DecreeRules.Standing standing = evaluate(server, decree, now);
            if (!standing.ends()) {
                continue;
            }
            DecreeState next = standing == DecreeRules.Standing.EXPIRED ? DecreeState.EXPIRED : DecreeState.VOID;
            Decree closed = decree.ended(next, now, null, standing.note());
            if (data.putDecree(closed)) {
                ended++;
                forget(closed);
                tell(server, closed.offender(), Component.translatable(next == DecreeState.EXPIRED
                                ? "mcacrime.decree.notice.expired" : "mcacrime.decree.notice.voided",
                        DecreeText.village(server, closed.community())));
                if (next == DecreeState.VOID) {
                    liftIfSoleReason(server, decree);
                }
                post(closed, next == DecreeState.EXPIRED ? DecreeChangedEvent.Change.EXPIRED
                        : DecreeChangedEvent.Change.VOIDED);
            }
        }
        data.pruneDecrees(now);
        return ended;
    }

    // ------------------------------------------------------------------ notices

    /**
     * Tells a player once, when they are in a village where a decree is in force against them, and again
     * the next time after they have left. Rides the guard scan; costs nothing for a player with no decree.
     */
    public static void tickNotices(@Nullable MinecraftServer server) {
        if (server == null) {
            return;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            if (!data.hasOpenDecree(id)) {
                NOTICED.remove(id);
                continue;
            }
            CrimeCommunityKey here = communityOf(player).orElse(null);
            Set<UUID> told = NOTICED.computeIfAbsent(id, key -> ConcurrentHashMap.newKeySet());
            Set<UUID> present = new HashSet<>();
            Decree current = here == null ? null : inForce(server, id, here).orElse(null);
            if (current != null) {
                present.add(current.id());
                if (!told.contains(current.id())) {
                    player.sendSystemMessage(Component.translatable("mcacrime.decree.notice.entered",
                            DecreeText.village(server, here), DecreeText.name(current.issuedByName()),
                            DecreeText.reason(current.reason())));
                }
            }
            told.retainAll(present);
            told.addAll(present);
        }
    }

    /**
     * What {@code /crime status} says about decrees: each one in force against the player, and whether
     * Capitals itself holds a warrant against them in the capital they are standing in.
     */
    public static List<Component> statusLines(@Nullable ServerPlayer player) {
        List<Component> lines = new ArrayList<>();
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null) {
            return lines;
        }
        DecreeSettings settings = DecreeSettings.fromConfig();
        long now = now(server);
        for (Decree decree : inForceAnywhere(server, player.getUUID())) {
            Component village = DecreeText.village(server, decree.community());
            lines.add(settings.payable()
                    ? Component.translatable("mcacrime.command.status.decree", village,
                            DecreeText.name(decree.issuedByName()), DecreeText.reason(decree.reason()),
                            DecreeText.time(decree.remaining(now)), DecreeText.fine(settings.fineAmount()))
                    : Component.translatable("mcacrime.command.status.decree_unpayable", village,
                            DecreeText.name(decree.issuedByName()), DecreeText.reason(decree.reason()),
                            DecreeText.time(decree.remaining(now))));
        }
        if (settings.integrationEnabled() && settings.readPlayerWarrants()
                && CapitalsBridge.has(CapitalsCapability.PLAYER_WARRANTS)) {
            CrimeCommunityKey here = communityOf(player).orElse(null);
            ServerLevel level = levelOf(server, here);
            if (capitalFor(level, here) instanceof CapitalsQueryResult.Available<CapitalView> capital
                    && Boolean.TRUE.equals(CapitalsBridge.playerWarrant(level, here.villageId(),
                            capital.value().capitalId(), player.getUUID()).orElse(Boolean.FALSE))) {
                lines.add(Component.translatable("mcacrime.command.status.capitals_warrant",
                        DecreeText.village(server, here)));
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------ plumbing

    /** A player's view of their decrees. */
    public static List<DecreeView> views(@Nullable MinecraftServer server, @Nullable UUID offender) {
        List<DecreeView> out = new ArrayList<>();
        if (server == null || offender == null) {
            return out;
        }
        for (Decree decree : CrimeWorldData.get(server).decreesOf(offender)) {
            out.add(view(decree));
        }
        return out;
    }

    public static DecreeView view(Decree decree) {
        return new DecreeView(decree.id(), decree.offender(), decree.community(), decree.capitalId(),
                decree.issuedBy(), decree.reason().id(), decree.issuedAt(), decree.expiresAt(), decree.state().id(),
                decree.resolvedAt(), Optional.ofNullable(decree.sentenceId()));
    }

    private static void post(Decree decree, DecreeChangedEvent.Change change) {
        try {
            NeoForge.EVENT_BUS.post(new DecreeChangedEvent(decree.offender(), view(decree), change));
        } catch (RuntimeException failure) {
            McaCrime.LOGGER.error("A listener failed on royal decree {} ({}); the decree itself is stored",
                    decree.id(), change, failure);
        }
    }

    private static void tell(MinecraftServer server, UUID player, Component message) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            online.sendSystemMessage(message);
        }
    }

    private static void chronicle(@Nullable ServerLevel level, Decree decree, String line, boolean herald) {
        if (level == null) {
            return;
        }
        CapitalsQueryResult<Boolean> written = CapitalsBridge.chronicle(level, decree.community().villageId(),
                decree.capitalId(), line, herald);
        if (!written.isAvailable()) {
            CrimeDebug.crime("decree chronicle line not written: {}", written.describe());
        }
    }

    private static void forget(Decree decree) {
        STANDINGS.remove(decree.id());
    }

    /** Drops every memory-only cache; at server stop and in tests. */
    public static void clearRuntime() {
        STANDINGS.clear();
        NOTICED.clear();
        DecreeMenuService.clearAll();
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        NOTICED.remove(event.getEntity().getUUID());
    }
}
