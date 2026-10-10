package dev.otectus.mcacrime.decree;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.compat.CapitalStanding;
import dev.otectus.mcacrime.compat.CapitalView;
import dev.otectus.mcacrime.compat.CapitalsQueryResult;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * Every decision about a royal decree, as pure functions of facts already gathered (0.7.8).
 *
 * <p>Pure so each rule can be pinned without a server, a Capitals jar or a player. {@link DecreeService}
 * gathers the facts -- the capital, the target's standing, the store -- and writes what these answer.
 *
 * <h2>The rules</h2>
 *
 * <ul>
 *   <li><b>Authority</b> is the throne of <em>this</em> capital, read now: the player sovereign of the
 *       capital seated in the village, while that capital is active. A consort, a Hand, a granted title,
 *       a displayed title or a throne elsewhere grants nothing.</li>
 *   <li><b>Eligibility</b> is the target's standing in the same capital: below Noble -- Capitals' rank
 *       is stranger or commoner and no noble title is held there by grant, marriage or dowry. A title
 *       held in another capital does not protect; any title held in this one does, knights included.
 *       Anything other than a confirmed reading refuses.</li>
 *   <li><b>One decree</b> per target and village at a time, a cooldown after one ends, and a ceiling per
 *       capital, so repeating a request never stacks consequences.</li>
 * </ul>
 */
public final class DecreeRules {

    private DecreeRules() {
    }

    /** What the store can tell a decision, without a server. */
    public interface Ledger {

        /** The open decree against {@code offender} in {@code community}, if any. */
        Optional<Decree> openFor(UUID offender, CrimeCommunityKey community);

        /** When the last decree against {@code offender} in {@code community} ended; MIN_VALUE when none has. */
        long lastEndedAt(UUID offender, CrimeCommunityKey community);

        /** How many decrees {@code capitalId} holds open. */
        int openIn(UUID capitalId);

        /** Whether the world's table can take no new decree. */
        boolean full();
    }

    /**
     * One request to issue a decree, as the service gathered it.
     *
     * @param community the village the issuer is standing in, which is the village the decree would be
     *                  for; null when they stand in none
     */
    public record IssueRequest(UUID issuer, UUID target, @Nullable CrimeCommunityKey community, long now) {
    }

    /** Whether {@code actor} rules {@code community} right now, or why not. */
    public static Optional<DecreeRefusal> authority(CapitalsQueryResult<CapitalView> capital,
                                                    CrimeCommunityKey community, @Nullable UUID actor) {
        if (!capital.answered()) {
            return Optional.of(DecreeRefusal.NOT_VERIFIABLE);
        }
        if (capital.isAbsent()) {
            return Optional.of(DecreeRefusal.NOT_A_CAPITAL);
        }
        CapitalView view = capital.orThrow();
        if (view.villageId() != community.villageId() || !view.inDimension(community.dimension().toString())) {
            return Optional.of(DecreeRefusal.NOT_VERIFIABLE); // Capitals answered for some other place
        }
        if (!view.active()) {
            return Optional.of(DecreeRefusal.CAPITAL_INACTIVE);
        }
        if (!view.ruledBy(actor)) {
            return Optional.of(DecreeRefusal.NOT_SOVEREIGN);
        }
        return Optional.empty();
    }

    /** Whether the target may be named, on a confirmed reading of their standing in this capital. */
    public static Optional<DecreeRefusal> eligibility(CapitalsQueryResult<CapitalStanding> standing) {
        if (standing instanceof CapitalsQueryResult.Available<CapitalStanding> available) {
            return available.value().noble() ? Optional.of(DecreeRefusal.TARGET_NOBLE) : Optional.empty();
        }
        // Absent means the capital changed between two reads; unavailable and failed mean nothing was
        // read. None of them is evidence the target holds no title, so none of them may issue.
        return Optional.of(DecreeRefusal.NOT_VERIFIABLE);
    }

    /**
     * Whether a decree may be issued. The checks run in the order a ruler would want to be told: what is
     * switched off, who they are, where they stand, who the target is, and only then the bookkeeping.
     *
     * @param bridgeReady whether every capability a decree is judged by is bound
     */
    public static Optional<DecreeRefusal> decideIssue(IssueRequest request, DecreeSettings settings,
                                                      boolean readOnly, boolean bridgeReady,
                                                      CapitalsQueryResult<CapitalView> capital,
                                                      CapitalsQueryResult<CapitalStanding> targetStanding,
                                                      Ledger ledger) {
        if (!settings.on()) {
            return Optional.of(DecreeRefusal.DISABLED);
        }
        if (readOnly) {
            return Optional.of(DecreeRefusal.READ_ONLY);
        }
        if (!bridgeReady) {
            return Optional.of(DecreeRefusal.NOT_VERIFIABLE);
        }
        if (request.issuer().equals(request.target())) {
            return Optional.of(DecreeRefusal.TARGET_SELF);
        }
        if (request.community() == null) {
            return Optional.of(DecreeRefusal.NOT_IN_VILLAGE);
        }
        Optional<DecreeRefusal> authority = authority(capital, request.community(), request.issuer());
        if (authority.isPresent()) {
            return authority;
        }
        Optional<DecreeRefusal> eligible = eligibility(targetStanding);
        if (eligible.isPresent()) {
            return eligible;
        }
        if (ledger.openFor(request.target(), request.community()).isPresent()) {
            return Optional.of(DecreeRefusal.DUPLICATE);
        }
        long last = ledger.lastEndedAt(request.target(), request.community());
        if (settings.cooldownTicks() > 0L && last != Long.MIN_VALUE
                && request.now() - last < settings.cooldownTicks()) {
            return Optional.of(DecreeRefusal.COOLDOWN);
        }
        if (ledger.openIn(capital.orThrow().capitalId()) >= settings.maxOpenPerCapital()) {
            return Optional.of(DecreeRefusal.CAPITAL_FULL);
        }
        if (ledger.full()) {
            return Optional.of(DecreeRefusal.STORE_FULL);
        }
        return Optional.empty();
    }

    /** Whether {@code actor} may withdraw this open decree: only the current ruler of its own capital. */
    public static Optional<DecreeRefusal> decideWithdraw(@Nullable Decree decree, DecreeSettings settings,
                                                         boolean readOnly, boolean bridgeReady,
                                                         @Nullable CrimeCommunityKey actorCommunity,
                                                         CapitalsQueryResult<CapitalView> capital, UUID actor) {
        if (!settings.integrationEnabled()) {
            return Optional.of(DecreeRefusal.DISABLED);
        }
        if (readOnly) {
            return Optional.of(DecreeRefusal.READ_ONLY);
        }
        if (decree == null || !decree.open()) {
            return Optional.of(DecreeRefusal.NOT_FOUND);
        }
        if (!bridgeReady) {
            return Optional.of(DecreeRefusal.NOT_VERIFIABLE);
        }
        if (actorCommunity == null || !actorCommunity.equals(decree.community())) {
            return Optional.of(DecreeRefusal.NOT_IN_VILLAGE);
        }
        Optional<DecreeRefusal> authority = authority(capital, decree.community(), actor);
        if (authority.isPresent()) {
            return authority;
        }
        if (!capital.orThrow().capitalId().equals(decree.capitalId())) {
            return Optional.of(DecreeRefusal.NOT_SOVEREIGN); // a later capital rules here, not this decree's
        }
        return Optional.empty();
    }

    /** Whether an open decree is enforced right now, and if not, why. */
    public enum Standing {
        /** Enforced. */
        IN_FORCE,
        /** Kept but not enforced: Capitals cannot be asked, or the capital has no reigning court. */
        SUSPENDED,
        /** Its time is up. */
        EXPIRED,
        /** The capital that issued it no longer rules the village. */
        VOID_CAPITAL_GONE,
        /** The target now holds Noble status in that capital. */
        VOID_TARGET_ENNOBLED,
        /** Not open. */
        CLOSED;

        public boolean enforced() {
            return this == IN_FORCE;
        }

        /** Whether this standing ends the decree. */
        public boolean ends() {
            return this == EXPIRED || this == VOID_CAPITAL_GONE || this == VOID_TARGET_ENNOBLED;
        }

        /** The note an ending is recorded with. */
        public String note() {
            return switch (this) {
                case EXPIRED -> "expired";
                case VOID_CAPITAL_GONE -> "capital_gone";
                case VOID_TARGET_ENNOBLED -> "target_ennobled";
                default -> "";
            };
        }
    }

    /**
     * The standing of an open decree. A decree belongs to its capital rather than to the ruler who issued
     * it, so succession, abdication and a vacant throne leave it standing; what ends it is its own clock,
     * the capital leaving the village, or the target rising to Noble.
     *
     * @param anyCapitals whether Capitals holds any capital at all; an empty registry is how Capitals reads
     *                    before its data has loaded, so it never counts as "this capital is gone"
     */
    public static Standing standing(Decree decree, long now, boolean decreesOn, boolean bridgeReady,
                                    CapitalsQueryResult<CapitalView> capital,
                                    CapitalsQueryResult<Boolean> anyCapitals,
                                    CapitalsQueryResult<CapitalStanding> target) {
        if (decree == null || !decree.open()) {
            return Standing.CLOSED;
        }
        if (decree.expired(now)) {
            return Standing.EXPIRED;
        }
        if (!decreesOn || !bridgeReady) {
            return Standing.SUSPENDED;
        }
        if (capital.isAbsent()) {
            return anyCapitals instanceof CapitalsQueryResult.Available<Boolean> any && any.value()
                    ? Standing.VOID_CAPITAL_GONE : Standing.SUSPENDED;
        }
        if (!(capital instanceof CapitalsQueryResult.Available<CapitalView> available)) {
            return Standing.SUSPENDED;
        }
        CapitalView view = available.value();
        if (!view.capitalId().equals(decree.capitalId())) {
            return Standing.VOID_CAPITAL_GONE;
        }
        if (!view.active()) {
            return Standing.SUSPENDED;
        }
        if (target instanceof CapitalsQueryResult.Available<CapitalStanding> standing) {
            return standing.value().noble() ? Standing.VOID_TARGET_ENNOBLED : Standing.IN_FORCE;
        }
        return Standing.SUSPENDED;
    }
}
