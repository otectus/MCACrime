package dev.otectus.mcacrime;

import dev.otectus.mcacrime.relationship.FamilyGraph;
import dev.otectus.mcacrime.relationship.FamilyLookupSeam;
import dev.otectus.mcacrime.relationship.FamilyTier;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Family composition through the lookup seam: which tiers the scope admits, how the in-law tier is
 * derived from the spouse, and who wins when somebody qualifies twice.
 */
class FamilyGraphTest {

    private static final UUID SUBJECT = uuid(1);
    private static final UUID SPOUSE = uuid(2);
    private static final UUID PARENT = uuid(3);
    private static final UUID CHILD = uuid(4);
    private static final UUID SIBLING = uuid(5);
    private static final UUID COUSIN = uuid(6);
    private static final UUID SPOUSE_PARENT = uuid(7);
    private static final UUID SPOUSE_SIBLING = uuid(8);

    private static UUID uuid(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }

    /** One household: the subject, their immediate family, a cousin, and their spouse's family. */
    private static FamilyLookupSeam household() {
        Map<String, List<UUID>> links = new HashMap<>();
        links.put(key(SUBJECT, FamilyTier.SPOUSE), List.of(SPOUSE));
        links.put(key(SUBJECT, FamilyTier.PARENT), List.of(PARENT));
        links.put(key(SUBJECT, FamilyTier.CHILD), List.of(CHILD));
        links.put(key(SUBJECT, FamilyTier.SIBLING), List.of(SIBLING));
        links.put(key(SUBJECT, FamilyTier.EXTENDED), List.of(PARENT, CHILD, COUSIN));
        links.put(key(SPOUSE, FamilyTier.PARENT), List.of(SPOUSE_PARENT));
        links.put(key(SPOUSE, FamilyTier.SIBLING), List.of(SPOUSE_SIBLING));
        return (subject, tier) -> links.getOrDefault(key(subject, tier), List.of());
    }

    private static String key(UUID subject, FamilyTier tier) {
        return subject + "|" + tier;
    }

    @Test
    void onlyTheScopedTiersAreListed() {
        Map<UUID, FamilyTier> relatives = FamilyGraph.compose(SUBJECT,
                Set.of(FamilyTier.SPOUSE, FamilyTier.SIBLING), household());

        assertEquals(Map.of(SPOUSE, FamilyTier.SPOUSE, SIBLING, FamilyTier.SIBLING), relatives);
    }

    @Test
    void inLawsAreDerivedFromTheSpousesOwnFamily() {
        Map<UUID, FamilyTier> relatives = FamilyGraph.compose(SUBJECT, Set.of(FamilyTier.IN_LAW), household());

        assertEquals(FamilyTier.IN_LAW, relatives.get(SPOUSE_PARENT));
        assertEquals(FamilyTier.IN_LAW, relatives.get(SPOUSE_SIBLING));
        // The spouse themselves is not an in-law, and was not in scope.
        assertNull(relatives.get(SPOUSE));
        assertEquals(2, relatives.size());
    }

    @Test
    void theClosestTierWinsWhenSomebodyQualifiesTwice() {
        Map<UUID, FamilyTier> relatives = FamilyGraph.compose(SUBJECT,
                EnumSet.allOf(FamilyTier.class), household());

        // The parent and the child are both reachable through EXTENDED as well.
        assertEquals(FamilyTier.PARENT, relatives.get(PARENT));
        assertEquals(FamilyTier.CHILD, relatives.get(CHILD));
        assertEquals(FamilyTier.EXTENDED, relatives.get(COUSIN));
    }

    @Test
    void nobodyIsTheirOwnRelative() {
        FamilyLookupSeam narcissist = (subject, tier) ->
                tier == FamilyTier.SIBLING ? List.of(SUBJECT, SIBLING) : List.of();

        Map<UUID, FamilyTier> relatives = FamilyGraph.compose(SUBJECT, Set.of(FamilyTier.SIBLING), narcissist);

        assertFalse(relatives.containsKey(SUBJECT));
        assertEquals(Set.of(SIBLING), relatives.keySet());
    }

    @Test
    void anEmptyScopeOrMissingSeamMeansNoFamily() {
        assertTrue(FamilyGraph.compose(SUBJECT, Set.of(), household()).isEmpty());
        assertTrue(FamilyGraph.compose(SUBJECT, Set.of(FamilyTier.SPOUSE), null).isEmpty());
        assertTrue(FamilyGraph.compose(null, Set.of(FamilyTier.SPOUSE), household()).isEmpty());
    }

    @Test
    void anUnknownTierNameIsNeverParsedIntoAScope() {
        assertTrue(FamilyTier.parse("cousin-in-law").isEmpty());
        assertTrue(FamilyTier.parse(null).isEmpty());
        assertEquals(FamilyTier.IN_LAW, FamilyTier.parse(" in_law ").orElseThrow());
        assertEquals(FamilyTier.SPOUSE, FamilyTier.parse("spouse").orElseThrow());
    }
}
