package dev.otectus.mcacrime.incident;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.ledger.CrimeContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which community an incident belongs to, and why that one.
 *
 * <p>The ordering is the whole design and it is not arbitrary. A settlement whose stores were emptied
 * is the party whose law was broken, whoever the container's registered owner is and wherever the
 * offender lives — so property outranks the victim's home, which until 0.7.4 was the only rule there
 * was. The victim's home still comes second, because every incident that has no property must keep
 * giving exactly the answer it gave before. And nothing is invented: a context with no candidate at
 * all selects nothing, which is the same answer the old rule gave a victimless crime.
 */
class IncidentContextTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation NETHER = new ResourceLocation("minecraft", "the_nether");
    private static final BlockPos WHERE = new BlockPos(10, 64, 10);

    private static CrimeCommunityKey community(ResourceLocation dimension, int id) {
        return CrimeCommunityKey.of(dimension, id).orElseThrow();
    }

    @Test
    void propertyJurisdictionOutranksEverything() {
        IncidentContext context = IncidentContext.at(OVERWORLD, WHERE)
                .withVictimHome(community(OVERWORLD, 1))
                .withEventCommunity(community(OVERWORLD, 2))
                .withProperty(community(OVERWORLD, 3), UUID.randomUUID(), 4);

        assertEquals(IncidentContext.Basis.PROPERTY_JURISDICTION, context.basis());
        assertEquals(Optional.of(community(OVERWORLD, 3)), context.selected());
    }

    @Test
    void withoutPropertyTheVictimsHomeWinsExactlyAsItAlwaysDid() {
        IncidentContext context = IncidentContext.at(OVERWORLD, WHERE)
                .withVictimHome(community(OVERWORLD, 1))
                .withEventCommunity(community(OVERWORLD, 2));

        assertEquals(IncidentContext.Basis.VICTIM_HOME, context.basis());
        assertEquals(Optional.of(community(OVERWORLD, 1)), context.selected());
    }

    @Test
    void theEventLocationIsTheLastResortAndOnlyBetterThanNothing() {
        IncidentContext context =
                IncidentContext.at(OVERWORLD, WHERE).withEventCommunity(community(OVERWORLD, 2));

        assertEquals(IncidentContext.Basis.EVENT_LOCATION, context.basis());
        assertEquals(Optional.of(community(OVERWORLD, 2)), context.selected());
    }

    @Test
    void noCandidateSelectsNothingRatherThanGuessing() {
        IncidentContext context = IncidentContext.at(OVERWORLD, WHERE);

        assertEquals(IncidentContext.Basis.NONE, context.basis());
        assertTrue(context.selected().isEmpty());
    }

    @Test
    void theSelectedCommunityKeepsItsOwnDimension() {
        IncidentContext context = IncidentContext.at(OVERWORLD, WHERE)
                .withProperty(community(NETHER, 3), UUID.randomUUID(), 1);

        assertEquals(NETHER, context.selected().orElseThrow().dimension(),
                "a community is dimension-aware; the place the act happened does not overwrite it");
    }

    @Test
    void theProvenanceCarriesThePlaceTheBasisAndTheProperty() {
        UUID property = UUID.randomUUID();
        Map<String, String> provenance = IncidentContext.at(OVERWORLD, WHERE)
                .withProperty(community(OVERWORLD, 3), property, 7)
                .provenance();

        assertEquals(OVERWORLD.toString(), provenance.get(CrimeContext.INCIDENT_DIMENSION));
        assertEquals("10,64,10", provenance.get(CrimeContext.INCIDENT_POSITION));
        assertEquals("property", provenance.get(CrimeContext.COMMUNITY_BASIS));
        assertEquals(property.toString(), provenance.get(CrimeContext.PROPERTY_ID));
        assertEquals("7", provenance.get(CrimeContext.PROPERTY_REVISION));
    }

    @Test
    void aContextWithNoPropertyNamesNoProperty() {
        Map<String, String> provenance = IncidentContext.at(OVERWORLD, WHERE)
                .withVictimHome(community(OVERWORLD, 1)).provenance();

        assertEquals("victim_home", provenance.get(CrimeContext.COMMUNITY_BASIS));
        assertFalse(provenance.containsKey(CrimeContext.PROPERTY_ID),
                "a record is not an audit log of a decision; the basis plus the place is enough");
    }

    @Test
    void aContextMustNameWhereItHappened() {
        assertThrows(IllegalArgumentException.class, () -> IncidentContext.at(null, WHERE));
        assertThrows(IllegalArgumentException.class, () -> IncidentContext.at(OVERWORLD, null));
    }

    @Test
    void everyBasisHasADistinctStableId() {
        for (IncidentContext.Basis basis : IncidentContext.Basis.values()) {
            assertFalse(basis.id().isBlank(), basis + " has no id");
        }
        assertEquals(IncidentContext.Basis.values().length,
                java.util.Arrays.stream(IncidentContext.Basis.values())
                        .map(IncidentContext.Basis::id).distinct().count());
    }
}
