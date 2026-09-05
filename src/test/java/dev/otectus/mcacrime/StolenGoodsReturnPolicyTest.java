package dev.otectus.mcacrime;

import dev.otectus.mcacrime.mug.npc.StolenGoodsReturn;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who gets their property back at the moment of an arrest, and who keeps their claim.
 *
 * <p>The distinction is the whole safety property of the feature. An entry that is returned leaves
 * the ledger; an entry that is kept must not, or a victim who happened to be logged out when their
 * mugger was arrested would have their sword deleted by a guard's good intentions.
 */
class StolenGoodsReturnPolicyTest {

    private final UUID near = UUID.randomUUID();
    private final UUID far = UUID.randomUUID();
    private final UUID offline = UUID.randomUUID();

    @Test
    void ownersWithinTheRadiusAreReturnedAndTheRestAreKept() {
        Map<UUID, Double> distances = new LinkedHashMap<>();
        distances.put(near, 4.0D);
        distances.put(far, 40.0D);

        StolenGoodsReturn.Partition partition =
                StolenGoodsReturn.partition(List.of(near, far, offline), distances, 16.0D);

        assertEquals(List.of(near), partition.returned());
        assertEquals(List.of(far, offline), partition.kept());
    }

    @Test
    void theRadiusIsInclusive() {
        StolenGoodsReturn.Partition partition =
                StolenGoodsReturn.partition(List.of(near), Map.of(near, 16.0D), 16.0D);
        assertEquals(List.of(near), partition.returned());
    }

    @Test
    void anOfflineOwnerKeepsTheirClaim() {
        StolenGoodsReturn.Partition partition =
                StolenGoodsReturn.partition(List.of(offline), Map.of(), 64.0D);
        assertTrue(partition.returned().isEmpty());
        assertEquals(List.of(offline), partition.kept());
    }

    @Test
    void oneOwnerBehindSeveralEntriesIsCountedOnce() {
        StolenGoodsReturn.Partition partition =
                StolenGoodsReturn.partition(List.of(near, near, near), Map.of(near, 1.0D), 16.0D);
        assertEquals(List.of(near), partition.returned());
        assertTrue(partition.kept().isEmpty());
    }

    @Test
    void aZeroRadiusReturnsNothing() {
        StolenGoodsReturn.Partition partition =
                StolenGoodsReturn.partition(List.of(near), Map.of(near, 0.5D), 0.0D);
        assertTrue(partition.returned().isEmpty());
        assertEquals(List.of(near), partition.kept());
    }

    @Test
    void noOwnersIsNotAnError() {
        StolenGoodsReturn.Partition partition = StolenGoodsReturn.partition(null, null, 16.0D);
        assertTrue(partition.returned().isEmpty());
        assertTrue(partition.kept().isEmpty());
    }
}
