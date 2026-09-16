package dev.otectus.mcacrime.effect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who a sand burst actually blinds, as a pure function of plain facts (0.7.2 §13.3, §13.7).
 *
 * <p>Nothing here touches a level, an entity or the config spec: the caller reduces the world to
 * {@link Candidate} records and this class decides. That is what makes the bounded-crowd rule, the
 * falloff, the PvP/team/creative exclusions and the one-application-per-victim guarantee testable
 * without a running server, which is the only way any of them get tested at all.
 *
 * <p><b>Invariant 13 (blindness is temporary and bounded)</b> starts here: a burst can never select
 * more than {@link Settings#maxAffected()} victims, and it only ever examines the first
 * {@link Settings#maxCandidates()} entities the collector handed it. The bound is applied
 * <em>during</em> collection by the caller — {@link #bound(List, int)} exists so the same ceiling is
 * expressed once and can be asserted — not by sorting an unlimited list afterwards.
 */
public final class SandExposurePolicy {

    /** Player item cooldown between throws, in ticks. */
    public static final int DEFAULT_COOLDOWN_TICKS = 80;
    /** How long an unobstructed bottle stays in the world before it discards itself. */
    public static final int DEFAULT_LIFETIME_TICKS = 60;
    /** Burst radius, in blocks. */
    public static final double DEFAULT_RADIUS = 2.0D;
    /** What a directly struck target gets. */
    public static final int DEFAULT_DIRECT_DURATION_TICKS = 80;
    /** The most a splash victim can get, at the centre of the burst. */
    public static final int DEFAULT_SPLASH_DURATION_TICKS = 40;
    /** Below this, an application is not worth the status icon and is dropped entirely. */
    public static final int MIN_APPLICATION_TICKS = 10;
    /** How long after the effect ends sand cannot take hold again, from any thrower. */
    public static final int DEFAULT_RECOVERY_TICKS = 60;
    /** A blinded NPC still notices somebody this close (§13.5). */
    public static final double CLOSE_CONTACT_RANGE = 1.5D;
    /** Hard ceiling on victims per impact. */
    public static final int MAX_AFFECTED = 64;
    /** Hard ceiling on entities examined per impact, applied while collecting. */
    public static final int MAX_CANDIDATES = 256;

    private SandExposurePolicy() {
    }

    /**
     * The tuning one burst runs under.
     *
     * @param serverPvpAllowed the server's own PvP rule. It is separate from {@code affectsPlayers}
     *                         because a server that forbids PvP forbids it: the mod toggle can only
     *                         ever subtract (§13.7).
     */
    public record Settings(double radius, int directDurationTicks, int splashDurationTicks,
                           int minApplicationTicks, int maxAffected, int maxCandidates,
                           boolean affectsPlayers, boolean serverPvpAllowed) {

        public static Settings defaults() {
            return new Settings(DEFAULT_RADIUS, DEFAULT_DIRECT_DURATION_TICKS, DEFAULT_SPLASH_DURATION_TICKS,
                    MIN_APPLICATION_TICKS, MAX_AFFECTED, MAX_CANDIDATES, false, true);
        }
    }

    /**
     * One nearby entity, reduced to the facts the decision needs.
     *
     * @param direct            this is the entity the projectile actually struck
     * @param thrower           this is the entity that threw the bottle (deliberate self-risk, §13.7)
     * @param occluded          a wall stands between the impact point and this entity's eyes
     * @param alreadyBlinded    sand is already active on them, so its duration must not be refreshed
     * @param recovering        they are inside the post-effect recovery window, from any thrower
     */
    public record Candidate(UUID id, double distance, boolean direct, boolean player, boolean thrower,
                            boolean immune, boolean protectedByConfig, boolean creativeOrSpectator,
                            boolean occluded, boolean teamProtected, boolean alreadyBlinded,
                            boolean recovering) {
    }

    /**
     * One victim that will be blinded.
     *
     * @param selfExposure the thrower caught their own splash. They are blinded exactly like anybody
     *                     else and no incident is ever written for it (§13.7, §14.1).
     */
    public record Application(UUID target, int durationTicks, boolean direct, boolean selfExposure) {

        /** Nobody commits a crime against themselves; everything else is a hostile exposure. */
        public boolean hostile() {
            return !selfExposure;
        }
    }

    /**
     * The collection ceiling, expressed once.
     *
     * <p>Callers pass this limit to the bounded {@code Level.getEntities(..., List, int)} overload so
     * the list never grows past it in the first place. This method exists for the pathological case
     * where a caller assembled a list some other way, and so a test can assert the ceiling is a
     * ceiling rather than an intention.
     */
    public static <T> List<T> bound(List<T> collected, int maxCandidates) {
        int limit = Math.max(0, maxCandidates);
        if (collected.size() <= limit) {
            return List.copyOf(collected);
        }
        return List.copyOf(collected.subList(0, limit));
    }

    /**
     * The victims of one impact, in application order.
     *
     * <p>The direct target is selected first and receives the direct duration, so a target that is
     * both struck and inside the radius gets one application rather than direct plus splash
     * (SAND-04). Everybody else is ordered nearest-first, and ties break on UUID so a crowd produces
     * the same answer on the client's screen, in the log and in the next test run.
     */
    public static List<Application> select(List<Candidate> candidates, Settings settings) {
        List<Candidate> examined = bound(candidates, settings.maxCandidates());
        List<Application> applications = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Candidate candidate : examined) {
            if (!candidate.direct()) {
                continue;
            }
            eligible(candidate, settings).ifPresent(duration -> {
                seen.add(candidate.id());
                applications.add(new Application(candidate.id(), duration, true, candidate.thrower()));
            });
        }
        List<Candidate> splash = new ArrayList<>();
        for (Candidate candidate : examined) {
            if (!candidate.direct() && !seen.contains(candidate.id())) {
                splash.add(candidate);
            }
        }
        splash.sort(Comparator.comparingDouble(Candidate::distance).thenComparing(Candidate::id));
        for (Candidate candidate : splash) {
            if (applications.size() >= Math.max(0, settings.maxAffected())) {
                break;
            }
            if (!seen.add(candidate.id())) {
                continue;
            }
            eligible(candidate, settings).ifPresent(duration ->
                    applications.add(new Application(candidate.id(), duration, false, candidate.thrower())));
        }
        return List.copyOf(applications);
    }

    /**
     * The duration this candidate would receive, or empty when sand does not take hold at all.
     *
     * <p>Order is deliberate. Immunity and protection come before geometry because they are cheap and
     * absolute; the server's PvP rule comes before the mod's own toggle because a toggle may not
     * overrule it; and "already blinded" comes before any duration is computed, because the one thing
     * a second bottle must never do is extend the first (invariant 13).
     */
    public static java.util.OptionalInt eligible(Candidate candidate, Settings settings) {
        if (candidate.immune() || candidate.protectedByConfig() || candidate.creativeOrSpectator()
                || candidate.alreadyBlinded() || candidate.recovering()) {
            return java.util.OptionalInt.empty();
        }
        if (candidate.occluded() && !candidate.direct()) {
            return java.util.OptionalInt.empty();
        }
        // Self-effect is not PvP. Catching yourself in your own splash is the risk the item asks you
        // to take, and it stays available on a server that forbids players harming each other.
        if (candidate.player() && !candidate.thrower()
                && (!settings.serverPvpAllowed() || !settings.affectsPlayers() || candidate.teamProtected())) {
            return java.util.OptionalInt.empty();
        }
        if (candidate.direct()) {
            return java.util.OptionalInt.of(Math.max(settings.minApplicationTicks(),
                    settings.directDurationTicks()));
        }
        if (candidate.distance() > settings.radius() || settings.radius() <= 0.0D) {
            return java.util.OptionalInt.empty();
        }
        int duration = falloff(candidate.distance(), settings);
        return duration < settings.minApplicationTicks()
                ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(duration);
    }

    /** Linear falloff from the full splash duration at the impact point to nothing at the rim. */
    public static int falloff(double distance, Settings settings) {
        if (settings.radius() <= 0.0D) {
            return 0;
        }
        double scale = 1.0D - Math.max(0.0D, Math.min(1.0D, distance / settings.radius()));
        return (int) Math.round(settings.splashDurationTicks() * scale);
    }

    /**
     * Whether a sand-blinded observer can still form a fresh visual observation of something this far
     * away (§13.5). Close contact still works; anything beyond it does not.
     */
    public static boolean withinCloseContact(double distance) {
        return distance <= CLOSE_CONTACT_RANGE;
    }
}
