package dev.otectus.mcacrime.decree;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.compat.CapitalStanding;
import dev.otectus.mcacrime.compat.CapitalView;
import dev.otectus.mcacrime.compat.CapitalsQueryResult;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every royal-decree decision, without a server or a Capitals jar (0.7.8): who may issue, who may be
 * named, the order refusals are reported in, and when an open decree stops being enforced.
 */
class DecreeRulesTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final CrimeCommunityKey VILLAGE = new CrimeCommunityKey(OVERWORLD, 3);
    private static final CrimeCommunityKey OTHER_VILLAGE = new CrimeCommunityKey(OVERWORLD, 4);
    private static final UUID CAPITAL = UUID.randomUUID();
    private static final UUID RULER = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final DecreeSettings ON = new DecreeSettings(true, true, 72000L, 24000L, 2, 6000L, 32L,
            true, false, false, true);

    private static CapitalsQueryResult<CapitalView> capital(String state, UUID sovereign) {
        return CapitalsQueryResult.available(new CapitalView(CAPITAL, VILLAGE.villageId(), "minecraft:overworld",
                state, sovereign));
    }

    private static CapitalsQueryResult<CapitalStanding> standing(String rank, String granted, String marriage,
                                                                 String dowager) {
        return CapitalsQueryResult.available(new CapitalStanding(rank, granted, marriage, dowager));
    }

    private static CapitalsQueryResult<CapitalStanding> commoner() {
        return standing("COMMONER", null, null, null);
    }

    /** An in-memory store the decisions read. */
    private static final class Store implements DecreeRules.Ledger {
        final Map<UUID, Decree> open = new HashMap<>();
        long lastEnded = Long.MIN_VALUE;
        int openInCapital;
        boolean full;

        @Override
        public Optional<Decree> openFor(UUID offender, CrimeCommunityKey community) {
            return open.values().stream().filter(d -> d.offender().equals(offender)
                    && d.community().equals(community)).findFirst();
        }

        @Override
        public long lastEndedAt(UUID offender, CrimeCommunityKey community) {
            return lastEnded;
        }

        @Override
        public int openIn(UUID capitalId) {
            return openInCapital;
        }

        @Override
        public boolean full() {
            return full;
        }
    }

    private static Optional<DecreeRefusal> issue(DecreeSettings settings, CapitalsQueryResult<CapitalView> capital,
                                                 CapitalsQueryResult<CapitalStanding> target, Store store) {
        return DecreeRules.decideIssue(new DecreeRules.IssueRequest(RULER, TARGET, VILLAGE, 100_000L), settings,
                false, true, capital, target, store);
    }

    // ------------------------------------------------------------------ authority

    @Test
    void thePlayerSovereignOfThisActiveCapitalMayIssue() {
        assertEquals(Optional.empty(), issue(ON, capital("ACTIVE", RULER), commoner(), new Store()));
    }

    @Test
    void anyoneElseIsNotTheSovereign() {
        assertEquals(Optional.of(DecreeRefusal.NOT_SOVEREIGN),
                issue(ON, capital("ACTIVE", UUID.randomUUID()), commoner(), new Store()));
        // A villager on the throne, or a vacancy, leaves no player sovereign at all.
        assertEquals(Optional.of(DecreeRefusal.NOT_SOVEREIGN),
                issue(ON, capital("ACTIVE", null), commoner(), new Store()));
    }

    @Test
    void anInactiveCapitalHasNoCourtToDecree() {
        assertEquals(Optional.of(DecreeRefusal.CAPITAL_INACTIVE),
                issue(ON, capital("FOUNDED", RULER), commoner(), new Store()));
        assertEquals(Optional.of(DecreeRefusal.CAPITAL_INACTIVE),
                issue(ON, capital("PENDING", RULER), commoner(), new Store()));
    }

    @Test
    void aVillageThatIsNotACapitalIsSaidSo() {
        assertEquals(Optional.of(DecreeRefusal.NOT_A_CAPITAL),
                issue(ON, CapitalsQueryResult.absent(), commoner(), new Store()));
    }

    @Test
    void anUnreadableThroneIsNeverAssumed() {
        assertEquals(Optional.of(DecreeRefusal.NOT_VERIFIABLE),
                issue(ON, CapitalsQueryResult.unavailable("x"), commoner(), new Store()));
        assertEquals(Optional.of(DecreeRefusal.NOT_VERIFIABLE),
                issue(ON, CapitalsQueryResult.failed("boom"), commoner(), new Store()));
    }

    @Test
    void aRecordForAnotherPlaceIsNotThisVillagesThrone() {
        CapitalsQueryResult<CapitalView> elsewhere = CapitalsQueryResult.available(new CapitalView(CAPITAL, 9,
                "minecraft:overworld", "ACTIVE", RULER));
        assertEquals(Optional.of(DecreeRefusal.NOT_VERIFIABLE), issue(ON, elsewhere, commoner(), new Store()));
        CapitalsQueryResult<CapitalView> nether = CapitalsQueryResult.available(new CapitalView(CAPITAL,
                VILLAGE.villageId(), "minecraft:the_nether", "ACTIVE", RULER));
        assertEquals(Optional.of(DecreeRefusal.NOT_VERIFIABLE), issue(ON, nether, commoner(), new Store()));
    }

    @Test
    void anIssuerStandingInNoVillageIsNotPresent() {
        assertEquals(Optional.of(DecreeRefusal.NOT_IN_VILLAGE), DecreeRules.decideIssue(
                new DecreeRules.IssueRequest(RULER, TARGET, null, 0L), ON, false, true,
                capital("ACTIVE", RULER), commoner(), new Store()));
    }

    // ------------------------------------------------------------------ eligibility

    /** rank, granted, marriage, dowager -> protected; "-" is no title. */
    private static final String[][] NOBILITY = {
            {"STRANGER", "-", "-", "-", "false"},
            {"COMMONER", "-", "-", "-", "false"},
            {"COMMONER", "COMMONER", "COMMONER", "COMMONER", "false"},
            {"COMMONER", "KNIGHT", "-", "-", "true"},
            {"COMMONER", "DAME", "-", "-", "true"},
            {"LORD", "LORD", "-", "-", "true"},
            {"COMMONER", "-", "LADY", "-", "true"},
            {"DUKE", "-", "DUCHESS", "-", "true"},
            {"COMMONER", "-", "-", "DUCHESS", "true"},
            {"PRINCE", "-", "-", "-", "true"},
            {"PRINCE_CONSORT", "-", "-", "-", "true"},
            {"HAND", "-", "-", "-", "true"},
            {"LORD_COMMANDER", "-", "-", "-", "true"},
            {"SOVEREIGN", "-", "-", "-", "true"},
            {"SOVEREIGN_CONSORT", "-", "-", "-", "true"},
            {"HIGH_SOVEREIGN", "-", "-", "-", "true"},
            {"SOMETHING_NEW", "-", "-", "-", "true"}};

    private static String title(String raw) {
        return "-".equals(raw) ? null : raw;
    }

    @Test
    void nobleOrAboveInThisCapitalIsProtected() {
        for (String[] row : NOBILITY) {
            boolean protectedTarget = Boolean.parseBoolean(row[4]);
            Optional<DecreeRefusal> refusal = DecreeRules.eligibility(standing(row[0], title(row[1]), title(row[2]),
                    title(row[3])));
            assertEquals(protectedTarget ? Optional.of(DecreeRefusal.TARGET_NOBLE) : Optional.empty(), refusal,
                    String.join(",", row));
        }
    }

    @Test
    void aStandingThatCannotBeReadDeniesRatherThanAssumingCommoner() {
        assertEquals(Optional.of(DecreeRefusal.NOT_VERIFIABLE),
                DecreeRules.eligibility(CapitalsQueryResult.unavailable("titles unbound")));
        assertEquals(Optional.of(DecreeRefusal.NOT_VERIFIABLE), DecreeRules.eligibility(CapitalsQueryResult.failed("x")));
        // Absent means the capital changed between the two reads; still no evidence of being a commoner.
        assertEquals(Optional.of(DecreeRefusal.NOT_VERIFIABLE), DecreeRules.eligibility(CapitalsQueryResult.absent()));
    }

    @Test
    void aNobleTargetIsRefusedEvenForTheRuler() {
        assertEquals(Optional.of(DecreeRefusal.TARGET_NOBLE),
                issue(ON, capital("ACTIVE", RULER), standing("COMMONER", "KNIGHT", null, null), new Store()));
    }

    // ------------------------------------------------------------------ the order of refusals

    @Test
    void switchedOffComesFirst() {
        DecreeSettings off = new DecreeSettings(true, false, 72000L, 0L, 2, 6000L, 32L, true, false, false, true);
        assertEquals(Optional.of(DecreeRefusal.DISABLED), issue(off, CapitalsQueryResult.unavailable("x"),
                CapitalsQueryResult.unavailable("x"), new Store()));
        DecreeSettings integrationOff = new DecreeSettings(false, true, 72000L, 0L, 2, 6000L, 32L, true, false,
                false, true);
        assertEquals(Optional.of(DecreeRefusal.DISABLED), issue(integrationOff, capital("ACTIVE", RULER),
                commoner(), new Store()));
    }

    @Test
    void aReadOnlyWorldAndAnUnboundBridgeComeBeforeAnyQuestionOfThrone() {
        assertEquals(Optional.of(DecreeRefusal.READ_ONLY), DecreeRules.decideIssue(
                new DecreeRules.IssueRequest(RULER, TARGET, VILLAGE, 0L), ON, true, true,
                capital("ACTIVE", RULER), commoner(), new Store()));
        assertEquals(Optional.of(DecreeRefusal.NOT_VERIFIABLE), DecreeRules.decideIssue(
                new DecreeRules.IssueRequest(RULER, TARGET, VILLAGE, 0L), ON, false, false,
                capital("ACTIVE", RULER), commoner(), new Store()));
    }

    @Test
    void aRulerCannotDecreeThemselves() {
        assertEquals(Optional.of(DecreeRefusal.TARGET_SELF), DecreeRules.decideIssue(
                new DecreeRules.IssueRequest(RULER, RULER, VILLAGE, 0L), ON, false, true,
                capital("ACTIVE", RULER), commoner(), new Store()));
    }

    @Test
    void repeatingARequestNeverStacksADecree() {
        Store store = new Store();
        Decree existing = Decree.issue(UUID.randomUUID(), TARGET, "t", VILLAGE, CAPITAL, RULER, "r",
                DecreeReason.OTHER, 0L, 72000L, DecreeProvenance.MENU);
        store.open.put(existing.id(), existing);
        assertEquals(Optional.of(DecreeRefusal.DUPLICATE), issue(ON, capital("ACTIVE", RULER), commoner(), store));
    }

    @Test
    void aDecreeInAnotherVillageIsNotADuplicate() {
        Store store = new Store();
        Decree elsewhere = Decree.issue(UUID.randomUUID(), TARGET, "t", OTHER_VILLAGE, CAPITAL, RULER, "r",
                DecreeReason.OTHER, 0L, 72000L, DecreeProvenance.MENU);
        store.open.put(elsewhere.id(), elsewhere);
        assertEquals(Optional.empty(), issue(ON, capital("ACTIVE", RULER), commoner(), store));
    }

    @Test
    void theCooldownRunsFromTheEndOfTheLastDecree() {
        Store store = new Store();
        store.lastEnded = 100_000L - 23_999L;
        assertEquals(Optional.of(DecreeRefusal.COOLDOWN), issue(ON, capital("ACTIVE", RULER), commoner(), store));
        store.lastEnded = 100_000L - 24_000L;
        assertEquals(Optional.empty(), issue(ON, capital("ACTIVE", RULER), commoner(), store));
        DecreeSettings noCooldown = new DecreeSettings(true, true, 72000L, 0L, 2, 6000L, 32L, true, false, false, true);
        store.lastEnded = 100_000L;
        assertEquals(Optional.empty(), issue(noCooldown, capital("ACTIVE", RULER), commoner(), store));
    }

    @Test
    void aCapitalAtItsCeilingAndAFullTableRefuse() {
        Store store = new Store();
        store.openInCapital = 2;
        assertEquals(Optional.of(DecreeRefusal.CAPITAL_FULL), issue(ON, capital("ACTIVE", RULER), commoner(), store));
        store.openInCapital = 0;
        store.full = true;
        assertEquals(Optional.of(DecreeRefusal.STORE_FULL), issue(ON, capital("ACTIVE", RULER), commoner(), store));
    }

    // ------------------------------------------------------------------ withdrawal

    private static Decree openDecree() {
        return Decree.issue(UUID.randomUUID(), TARGET, "t", VILLAGE, CAPITAL, RULER, "r", DecreeReason.TRESPASS, 0L,
                72000L, DecreeProvenance.MENU);
    }

    @Test
    void onlyTheCurrentRulerOfTheDecreesOwnCapitalMayWithdraw() {
        UUID successor = UUID.randomUUID();
        Decree decree = openDecree();
        assertEquals(Optional.empty(), DecreeRules.decideWithdraw(decree, ON, false, true, VILLAGE,
                capital("ACTIVE", successor), successor));
        // The ruler who issued it, after abdicating, has no throne here any more.
        assertEquals(Optional.of(DecreeRefusal.NOT_SOVEREIGN), DecreeRules.decideWithdraw(decree, ON, false, true,
                VILLAGE, capital("ACTIVE", successor), RULER));
        // A capital founded anew in the village does not inherit the old capital's decrees.
        CapitalsQueryResult<CapitalView> refounded = CapitalsQueryResult.available(new CapitalView(UUID.randomUUID(),
                VILLAGE.villageId(), "minecraft:overworld", "ACTIVE", successor));
        assertEquals(Optional.of(DecreeRefusal.NOT_SOVEREIGN), DecreeRules.decideWithdraw(decree, ON, false, true,
                VILLAGE, refounded, successor));
    }

    @Test
    void withdrawalNeedsAnOpenDecreeAndPresence() {
        Decree ended = openDecree().ended(DecreeState.FINED, 10L, TARGET, "paid");
        assertEquals(Optional.of(DecreeRefusal.NOT_FOUND), DecreeRules.decideWithdraw(ended, ON, false, true, VILLAGE,
                capital("ACTIVE", RULER), RULER));
        assertEquals(Optional.of(DecreeRefusal.NOT_FOUND), DecreeRules.decideWithdraw(null, ON, false, true, VILLAGE,
                capital("ACTIVE", RULER), RULER));
        assertEquals(Optional.of(DecreeRefusal.NOT_IN_VILLAGE), DecreeRules.decideWithdraw(openDecree(), ON, false,
                true, OTHER_VILLAGE, capital("ACTIVE", RULER), RULER));
    }

    // ------------------------------------------------------------------ standing

    private static DecreeRules.Standing standingOf(Decree decree, long now, CapitalsQueryResult<CapitalView> capital,
                                                   CapitalsQueryResult<Boolean> any,
                                                   CapitalsQueryResult<CapitalStanding> target) {
        return DecreeRules.standing(decree, now, true, true, capital, any, target);
    }

    @Test
    void anOpenDecreeIsInForceWhileItsCapitalAndTargetAllow() {
        assertEquals(DecreeRules.Standing.IN_FORCE, standingOf(openDecree(), 10L, capital("ACTIVE", RULER),
                CapitalsQueryResult.available(true), commoner()));
    }

    @Test
    void successionAndAVacantThroneLeaveTheDecreeStanding() {
        assertEquals(DecreeRules.Standing.IN_FORCE, standingOf(openDecree(), 10L, capital("ACTIVE", UUID.randomUUID()),
                CapitalsQueryResult.available(true), commoner()));
        assertEquals(DecreeRules.Standing.IN_FORCE, standingOf(openDecree(), 10L, capital("ACTIVE", null),
                CapitalsQueryResult.available(true), commoner()));
    }

    @Test
    void itsClockEndsIt() {
        assertEquals(DecreeRules.Standing.EXPIRED, standingOf(openDecree(), 72_000L, capital("ACTIVE", RULER),
                CapitalsQueryResult.available(true), commoner()));
    }

    @Test
    void aSentenceAnsweringItHoldsTheClock() {
        Decree held = openDecree().bound(UUID.randomUUID());
        assertEquals(DecreeRules.Standing.IN_FORCE, standingOf(held, 900_000L, capital("ACTIVE", RULER),
                CapitalsQueryResult.available(true), commoner()));
    }

    @Test
    void ennoblementVoidsIt() {
        assertEquals(DecreeRules.Standing.VOID_TARGET_ENNOBLED, standingOf(openDecree(), 10L, capital("ACTIVE", RULER),
                CapitalsQueryResult.available(true), standing("COMMONER", "KNIGHT", null, null)));
    }

    @Test
    void aCapitalGoneOrReplacedVoidsIt() {
        assertEquals(DecreeRules.Standing.VOID_CAPITAL_GONE, standingOf(openDecree(), 10L, CapitalsQueryResult.absent(),
                CapitalsQueryResult.available(true), commoner()));
        CapitalsQueryResult<CapitalView> refounded = CapitalsQueryResult.available(new CapitalView(UUID.randomUUID(),
                VILLAGE.villageId(), "minecraft:overworld", "ACTIVE", RULER));
        assertEquals(DecreeRules.Standing.VOID_CAPITAL_GONE, standingOf(openDecree(), 10L, refounded,
                CapitalsQueryResult.available(true), commoner()));
    }

    @Test
    void anEmptyRegistryIsCapitalsNotYetLoadedNotEveryCapitalGone() {
        assertEquals(DecreeRules.Standing.SUSPENDED, standingOf(openDecree(), 10L, CapitalsQueryResult.absent(),
                CapitalsQueryResult.available(false), commoner()));
        assertEquals(DecreeRules.Standing.SUSPENDED, standingOf(openDecree(), 10L, CapitalsQueryResult.absent(),
                CapitalsQueryResult.unavailable("x"), commoner()));
    }

    @Test
    void anythingUnreadableSuspendsRatherThanEnds() {
        assertEquals(DecreeRules.Standing.SUSPENDED, standingOf(openDecree(), 10L, CapitalsQueryResult.unavailable("x"),
                CapitalsQueryResult.available(true), commoner()));
        assertEquals(DecreeRules.Standing.SUSPENDED, standingOf(openDecree(), 10L, capital("FOUNDED", RULER),
                CapitalsQueryResult.available(true), commoner()));
        assertEquals(DecreeRules.Standing.SUSPENDED, standingOf(openDecree(), 10L, capital("ACTIVE", RULER),
                CapitalsQueryResult.available(true), CapitalsQueryResult.failed("x")));
        assertEquals(DecreeRules.Standing.SUSPENDED, DecreeRules.standing(openDecree(), 10L, false, true,
                capital("ACTIVE", RULER), CapitalsQueryResult.available(true), commoner()));
        assertEquals(DecreeRules.Standing.SUSPENDED, DecreeRules.standing(openDecree(), 10L, true, false,
                capital("ACTIVE", RULER), CapitalsQueryResult.available(true), commoner()));
    }

    @Test
    void anEndedDecreeIsClosedWhateverCapitalsSays() {
        Decree withdrawn = openDecree().ended(DecreeState.WITHDRAWN, 5L, RULER, "withdrawn");
        assertEquals(DecreeRules.Standing.CLOSED, standingOf(withdrawn, 10L, capital("ACTIVE", RULER),
                CapitalsQueryResult.available(true), commoner()));
        assertTrue(!DecreeRules.Standing.CLOSED.enforced() && !DecreeRules.Standing.SUSPENDED.ends());
    }
}
