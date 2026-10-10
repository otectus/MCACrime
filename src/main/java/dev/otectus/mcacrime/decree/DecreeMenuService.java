package dev.otectus.mcacrime.decree;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.compat.CapitalCourtView;
import dev.otectus.mcacrime.compat.CapitalStanding;
import dev.otectus.mcacrime.compat.CapitalView;
import dev.otectus.mcacrime.compat.CapitalsBridge;
import dev.otectus.mcacrime.compat.CapitalsQueryResult;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.detect.CrimeCommunityResolver;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The decree screen, on the server (0.7.8): who may open it, what it offers, and the one place its
 * answers come back through.
 *
 * <p>A ruler opens it by speaking to a guard or a court officer of their own capital, in that village.
 * The screen is a way of asking, never a source of authority: a submit names a server-issued menu by id
 * and revision with a single-use nonce, and a target or decree it did not offer is refused before any
 * rule is consulted. Everything the service then decides is re-read live, exactly as a command is.
 */
public final class DecreeMenuService {

    /** Rows one menu may carry, each way. */
    public static final int MAX_CANDIDATES = 32;
    public static final int MAX_OPEN = 32;

    /** How long a menu may be answered: a minute, long enough to read names and choose a reason. */
    private static final long SESSION_TTL = 1200L;
    /** How close the host must still be when the ruler answers. */
    private static final double HOST_REACH_SQR = 64.0D;

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    /** What the ruler asks for. */
    public enum Op { ISSUE, WITHDRAW }

    /** A player the ruler may name, or may see but not name, and why. */
    public record Candidate(UUID player, Component name, boolean eligible, String reasonKey) {
        public Candidate {
            reasonKey = reasonKey == null ? "" : reasonKey;
        }
    }

    /** An open decree of this capital in this village, which the ruler may withdraw. */
    public record OpenRow(UUID decree, Component name, String reasonId, long remainingTicks) {
    }

    /** The screen's contents. */
    public record Menu(UUID id, int revision, UUID hostId, Component village, List<Candidate> candidates,
                       List<OpenRow> open, long durationTicks, Component fine, boolean payable) {
        public Menu {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            open = open == null ? List.of() : List.copyOf(open);
        }
    }

    /** Where a host stands and whose capital it serves. */
    public record Host(CrimeCommunityKey community, CapitalView capital) {
    }

    private static final class Session {
        final UUID id;
        final int revision = 1;
        final UUID ruler;
        final UUID host;
        final ResourceLocation dimension;
        final CrimeCommunityKey community;
        final long expiresAt;
        final Set<UUID> offeredTargets;
        final Set<UUID> offeredDecrees;
        final Set<UUID> nonces = new HashSet<>();

        Session(UUID id, UUID ruler, UUID host, ResourceLocation dimension, CrimeCommunityKey community,
                long expiresAt, Set<UUID> offeredTargets, Set<UUID> offeredDecrees) {
            this.id = id;
            this.ruler = ruler;
            this.host = host;
            this.dimension = dimension;
            this.community = community;
            this.expiresAt = expiresAt;
            this.offeredTargets = Set.copyOf(offeredTargets);
            this.offeredDecrees = Set.copyOf(offeredDecrees);
        }

        synchronized boolean claim(UUID nonce) {
            return nonce != null && nonces.add(nonce);
        }
    }

    private DecreeMenuService() {
    }

    /**
     * Whether {@code villager} may receive {@code ruler}'s decree, and for which village: a guard of that
     * village, or the Hand, Lord Commander, Master of Laws, Herald or a royal guard of the capital, while
     * the ruler stands in the same village and sits on its throne. Holding an office grants no power here;
     * it only makes the officer someone a decree can be handed to.
     */
    public static Optional<Host> host(@Nullable ServerPlayer ruler, @Nullable LivingEntity villager) {
        if (ruler == null || villager == null || !(ruler.level() instanceof ServerLevel level)
                || villager.level() != level || !villager.isAlive() || !CapitalsBridge.decreesReady()
                || !DecreeSettings.fromConfig().on()) {
            return Optional.empty();
        }
        CrimeCommunityKey community = DecreeService.communityOf(ruler).orElse(null);
        CrimeCommunityKey hostHome = CrimeCommunityResolver.resolve(villager, level).orElse(null);
        if (community == null || !community.equals(hostHome)) {
            return Optional.empty();
        }
        CapitalsQueryResult<CapitalView> capital = CapitalsBridge.capitalAt(level, community.villageId());
        if (DecreeRules.authority(capital, community, ruler.getUUID()).isPresent()) {
            return Optional.empty();
        }
        CapitalView view = capital.orThrow();
        if (EntitySelectors.isResponder(villager) || courtMember(level, community, view, villager)) {
            return Optional.of(new Host(community, view));
        }
        return Optional.empty();
    }

    private static boolean courtMember(ServerLevel level, CrimeCommunityKey community, CapitalView capital,
                                       LivingEntity villager) {
        if (!McaCompat.isMcaVillager(villager)) {
            return false;
        }
        CapitalCourtView court = CapitalsBridge.court(level, community.villageId(), capital.capitalId()).orElse(null);
        if (court != null && court.holdsOffice(villager.getUUID())) {
            return true;
        }
        return Boolean.TRUE.equals(CapitalsBridge.royalGuard(level, community.villageId(), capital.capitalId(),
                villager.getUUID()).orElse(Boolean.FALSE));
    }

    /** Builds and remembers a menu for {@code ruler} at {@code host}, or null when the host does not qualify. */
    @Nullable
    public static Menu open(ServerPlayer ruler, LivingEntity hostEntity) {
        Host host = host(ruler, hostEntity).orElse(null);
        if (host == null) {
            return null;
        }
        MinecraftServer server = ruler.getServer();
        ServerLevel level = ruler.serverLevel();
        CrimeWorldData data = CrimeWorldData.get(server);
        DecreeSettings settings = DecreeSettings.fromConfig();
        long now = DecreeService.now(server);
        CrimeCommunityKey community = host.community();

        List<ServerPlayer> present = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player != ruler && !player.isSpectator()
                    && community.equals(DecreeService.communityOf(player).orElse(null))) {
                present.add(player);
            }
        }
        present.sort(Comparator.comparing(player -> player.getGameProfile().getName()));
        List<Candidate> candidates = new ArrayList<>();
        Set<UUID> offeredTargets = new HashSet<>();
        for (ServerPlayer player : present) {
            if (candidates.size() >= MAX_CANDIDATES) {
                break;
            }
            String why = candidateRefusal(level, data, settings, host, player.getUUID(), now);
            candidates.add(new Candidate(player.getUUID(), player.getDisplayName(), why.isEmpty(), why));
            if (why.isEmpty()) {
                offeredTargets.add(player.getUUID());
            }
        }

        List<OpenRow> open = new ArrayList<>();
        Set<UUID> offeredDecrees = new HashSet<>();
        for (Decree decree : data.decrees()) {
            if (open.size() >= MAX_OPEN) {
                break;
            }
            if (decree.open() && decree.community().equals(community)
                    && decree.capitalId().equals(host.capital().capitalId())) {
                open.add(new OpenRow(decree.id(), DecreeText.name(decree.offenderName()), decree.reason().id(),
                        decree.remaining(now)));
                offeredDecrees.add(decree.id());
            }
        }

        Session session = new Session(UUID.randomUUID(), ruler.getUUID(), hostEntity.getUUID(),
                level.dimension().location(), community, level.getGameTime() + SESSION_TTL,
                offeredTargets, offeredDecrees);
        SESSIONS.put(ruler.getUUID(), session);
        return new Menu(session.id, session.revision, hostEntity.getUUID(), DecreeText.village(server, community),
                candidates, open, settings.durationTicks(), DecreeText.fine(settings.fineAmount()),
                settings.payable());
    }

    /** Why a present player cannot be named, as a message key, or empty when they can. */
    private static String candidateRefusal(ServerLevel level, CrimeWorldData data, DecreeSettings settings, Host host,
                                           UUID target, long now) {
        CapitalsQueryResult<CapitalStanding> standing = CapitalsBridge.standing(level, host.community().villageId(),
                host.capital().capitalId(), target);
        Optional<DecreeRefusal> eligible = DecreeRules.eligibility(standing);
        if (eligible.isPresent()) {
            return eligible.get().messageKey();
        }
        if (data.openDecree(target, host.community()).isPresent()) {
            return DecreeRefusal.DUPLICATE.messageKey();
        }
        long last = data.lastDecreeEndedAt(target, host.community());
        if (settings.cooldownTicks() > 0L && last != Long.MIN_VALUE && now - last < settings.cooldownTicks()) {
            return DecreeRefusal.COOLDOWN.messageKey();
        }
        return "";
    }

    /**
     * The ruler's answer. Refused as stale unless it names the live menu, at its revision, with a nonce
     * not used before, a host still standing within reach, and a target or decree the menu offered.
     */
    public static DecreeService.Outcome submit(ServerPlayer ruler, UUID menuId, int revision, UUID nonce, Op op,
                                               UUID subject, String reasonId) {
        if (ruler == null || !(ruler.level() instanceof ServerLevel level) || op == null || subject == null) {
            return DecreeService.Outcome.refused(DecreeRefusal.STALE);
        }
        Session session = SESSIONS.get(ruler.getUUID());
        if (session == null || !session.id.equals(menuId) || session.revision != revision
                || !session.ruler.equals(ruler.getUUID()) || !session.dimension.equals(level.dimension().location())
                || level.getGameTime() > session.expiresAt || !session.claim(nonce)) {
            return DecreeService.Outcome.refused(DecreeRefusal.STALE);
        }
        Entity host = level.getEntity(session.host);
        if (!(host instanceof LivingEntity living) || !living.isAlive()
                || ruler.distanceToSqr(living) > HOST_REACH_SQR || host(ruler, living).isEmpty()) {
            return DecreeService.Outcome.refused(DecreeRefusal.STALE);
        }
        DecreeService.Outcome outcome;
        if (op == Op.ISSUE) {
            DecreeReason reason = DecreeReason.parse(reasonId).orElse(null);
            ServerPlayer target = ruler.getServer().getPlayerList().getPlayer(subject);
            if (reason == null || !session.offeredTargets.contains(subject)) {
                return DecreeService.Outcome.refused(DecreeRefusal.STALE);
            }
            if (target == null) {
                return DecreeService.Outcome.refused(DecreeRefusal.TARGET_UNKNOWN);
            }
            outcome = DecreeService.issue(ruler, target, reason, DecreeProvenance.MENU);
        } else {
            Decree decree = CrimeWorldData.get(ruler.getServer()).decree(subject);
            if (decree == null || !session.offeredDecrees.contains(subject)) {
                return DecreeService.Outcome.refused(DecreeRefusal.STALE);
            }
            outcome = DecreeService.withdraw(ruler, decree.offender(), DecreeProvenance.MENU);
        }
        if (outcome.ok()) {
            SESSIONS.remove(ruler.getUUID(), session); // one decision per screen; the next needs a fresh one
        }
        return outcome;
    }

    /** Server stop and tests. */
    public static void clearAll() {
        SESSIONS.clear();
    }
}
