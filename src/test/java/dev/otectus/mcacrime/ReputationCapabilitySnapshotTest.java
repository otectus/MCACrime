package dev.otectus.mcacrime;

import dev.otectus.mcacrime.compat.ReputationCapabilitySnapshot;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the capability handshake decides, with MCA: Reputation absent from the classpath.
 *
 * <p>The rule under test is the conservative direction. A companion that cannot describe itself gets
 * the oldest contract this mod knows how to speak, not the newest: guessing upwards would mean
 * calling a method that is not there, and the failure would land in somebody's server log rather than
 * here. The adapter checks these strings against the companion's own constants at load time, which is
 * the only place both sides exist at once.
 */
class ReputationCapabilitySnapshotTest {

    @Test
    void anUndescribableCompanionSupportsNothingNew() {
        ReputationCapabilitySnapshot older =
                ReputationCapabilitySnapshot.unsupported(1, true, "capabilities() unavailable");

        assertFalse(older.supportsDelivery());
        assertFalse(older.supportsReceiptLookup());
        assertFalse(older.supportsSupersede());
        assertFalse(older.supportsProfiledDelivery());
        assertFalse(older.supportsBoundResolution());
        assertFalse(older.supportsProfiles());
        assertEquals("none", older.advertisedProfileFeatures());
        assertTrue(older.enabled(), "an old companion still accepts ordinary writes");
    }

    @Test
    void noBridgeAdvertisesNothingAndReportsNoVersion() {
        ReputationCapabilitySnapshot absent = ReputationCapabilitySnapshot.absent();

        assertEquals(-1, absent.apiVersion());
        assertFalse(absent.enabled());
        assertTrue(absent.features().isEmpty());
        assertFalse(absent.has(ReputationCapabilitySnapshot.FEATURE_DELIVERY));
        assertFalse(absent.has(null));
    }

    /**
     * Receipt lookup needs both strings: one says a receipt exists to find, the other says finding it
     * writes nothing. Without the second, the honest response is to not look — which is the whole
     * reason the synthetic write-probe was removed.
     */
    @Test
    void receiptLookupNeedsBothHalvesOfThePromise() {
        assertFalse(snapshot(ReputationCapabilitySnapshot.FEATURE_RECEIPTS).supportsReceiptLookup());
        assertFalse(snapshot(ReputationCapabilitySnapshot.FEATURE_READ_ONLY_LOOKUP)
                .supportsReceiptLookup());
        assertTrue(snapshot(ReputationCapabilitySnapshot.FEATURE_RECEIPTS,
                ReputationCapabilitySnapshot.FEATURE_READ_ONLY_LOOKUP).supportsReceiptLookup());
    }

    @Test
    void aFullyFeaturedCompanionReportsEveryProfileFeatureItAdvertises() {
        ReputationCapabilitySnapshot live = snapshot(
                ReputationCapabilitySnapshot.PROFILE_FEATURES.toArray(new String[0]));

        assertTrue(live.supportsProfiles());
        assertTrue(live.supportsProfiledDelivery());
        assertEquals(String.join(",", ReputationCapabilitySnapshot.PROFILE_FEATURES),
                live.advertisedProfileFeatures());
    }

    /** Profiles switched off mid-session: the features go dark, and the report says which. */
    @Test
    void profilesCanGoDarkWithoutTheRestOfTheSurfaceGoingWithThem() {
        ReputationCapabilitySnapshot noProfiles = snapshot(ReputationCapabilitySnapshot.FEATURE_DELIVERY,
                ReputationCapabilitySnapshot.FEATURE_SUPERSEDE);

        assertTrue(noProfiles.supportsDelivery());
        assertTrue(noProfiles.supportsSupersede());
        assertFalse(noProfiles.supportsProfiles());
        assertFalse(noProfiles.supportsProfiledDelivery());
        assertEquals("none", noProfiles.advertisedProfileFeatures());
        assertTrue(noProfiles.describe().contains("profiles=none"));
    }

    @Test
    void theSnapshotDefendsItselfAgainstNulls() {
        ReputationCapabilitySnapshot sparse =
                new ReputationCapabilitySnapshot(1, true, null, null, null);

        assertTrue(sparse.features().isEmpty());
        assertTrue(sparse.nativeKinds().isEmpty());
        assertEquals("", sparse.readinessReason());
        assertFalse(sparse.describe().isEmpty());
    }

    private static ReputationCapabilitySnapshot snapshot(String... features) {
        return new ReputationCapabilitySnapshot(1, true, Set.of(features), Set.of(), "");
    }
}
