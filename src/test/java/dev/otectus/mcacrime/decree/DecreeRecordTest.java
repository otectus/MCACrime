package dev.otectus.mcacrime.decree;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One royal decree: its NBT, its lifecycle helpers, and its table in the world store (0.7.8). */
class DecreeRecordTest {

    private static final CrimeCommunityKey VILLAGE = new CrimeCommunityKey(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 5);
    private static final CrimeCommunityKey NETHER_VILLAGE =
            new CrimeCommunityKey(ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether"), 5);
    private static final UUID CAPITAL = UUID.randomUUID();
    private static final UUID RULER = UUID.randomUUID();

    private static Decree decree(UUID offender, CrimeCommunityKey community, long now) {
        return Decree.issue(UUID.randomUUID(), offender, "Target", community, CAPITAL, RULER, "Ruler",
                DecreeReason.DISORDER, now, 72000L, DecreeProvenance.COMMAND);
    }

    @Test
    void aDecreeRoundTripsThroughNbtUnchanged() {
        Decree issued = decree(UUID.randomUUID(), VILLAGE, 100L).bound(UUID.randomUUID());
        assertEquals(issued, Decree.load(issued.save()));
        Decree ended = issued.ended(DecreeState.VOID, 500L, null, "target_ennobled");
        assertEquals(ended, Decree.load(ended.save()));
        assertNull(ended.sentenceId(), "an ended decree is released from its sentence");
    }

    @Test
    void malformedRowsLoadAsNullForQuarantine() {
        assertNull(Decree.load(new CompoundTag()));
        CompoundTag badReason = decree(UUID.randomUUID(), VILLAGE, 0L).save();
        badReason.putString("reason", "witchcraft");
        assertNull(Decree.load(badReason));
        CompoundTag noCommunity = decree(UUID.randomUUID(), VILLAGE, 0L).save();
        noCommunity.remove("community");
        assertNull(Decree.load(noCommunity));
    }

    @Test
    void namesAndNotesAreBoundedAndControlCharactersDropped() {
        Decree odd = new Decree(UUID.randomUUID(), UUID.randomUUID(), "a\u0007b" + "x".repeat(80), VILLAGE, CAPITAL,
                RULER, "r", DecreeReason.OTHER, 0L, 10L, DecreeState.OPEN, 0L, null, "n".repeat(500),
                DecreeProvenance.MENU, null, 1L);
        assertEquals(Decree.MAX_NAME_LENGTH, odd.offenderName().length());
        assertTrue(odd.offenderName().startsWith("ab"));
        assertEquals(Decree.MAX_NOTE_LENGTH, odd.note().length());
    }

    @Test
    void theClockStopsWhileASentenceAnswersIt() {
        Decree open = decree(UUID.randomUUID(), VILLAGE, 0L);
        assertFalse(open.expired(71_999L));
        assertTrue(open.expired(72_000L));
        assertFalse(open.bound(UUID.randomUUID()).expired(900_000L));
        assertTrue(open.bound(UUID.randomUUID()).unbound().expired(900_000L));
        assertEquals(0L, open.remaining(80_000L));
    }

    @Test
    void everyChangeBumpsTheRevision() {
        Decree open = decree(UUID.randomUUID(), VILLAGE, 0L);
        assertEquals(1L, open.revision());
        assertEquals(2L, open.bound(UUID.randomUUID()).revision());
        assertEquals(2L, open.ended(DecreeState.WITHDRAWN, 1L, RULER, "withdrawn").revision());
    }

    // ------------------------------------------------------------------ the world table

    @Test
    void theStoreAnswersPerOffenderAndPerVillageAndKeepsDimensionsApart() {
        CrimeWorldData data = new CrimeWorldData();
        UUID target = UUID.randomUUID();
        Decree here = decree(target, VILLAGE, 0L);
        assertTrue(data.putDecree(here));
        assertTrue(data.hasOpenDecree(target));
        assertEquals(here, data.openDecree(target, VILLAGE).orElseThrow());
        assertTrue(data.openDecree(target, NETHER_VILLAGE).isEmpty(), "village 5 of the Nether is another place");
        assertEquals(1, data.openDecreeCount(CAPITAL));
        assertEquals(Long.MIN_VALUE, data.lastDecreeEndedAt(target, VILLAGE));

        data.putDecree(here.ended(DecreeState.FINED, 1_000L, target, "paid"));
        assertFalse(data.hasOpenDecree(target));
        assertTrue(data.openDecree(target, VILLAGE).isEmpty());
        assertEquals(0, data.openDecreeCount(CAPITAL));
        assertEquals(1_000L, data.lastDecreeEndedAt(target, VILLAGE));
        assertEquals(1, data.decreesOf(target).size(), "replacing an id keeps one row");
    }

    @Test
    void endedDecreesArePrunedAfterRetentionAndOpenOnesNever() {
        CrimeWorldData data = new CrimeWorldData();
        Decree open = decree(UUID.randomUUID(), VILLAGE, 0L);
        Decree ended = decree(UUID.randomUUID(), VILLAGE, 0L).ended(DecreeState.EXPIRED, 0L, null, "expired");
        data.putDecree(open);
        data.putDecree(ended);
        assertEquals(0, data.pruneDecrees(30L * 24000L));
        assertEquals(1, data.pruneDecrees(30L * 24000L + 1L));
        assertEquals(1, data.decrees().size());
        assertTrue(data.decrees().get(0).open());
        assertEquals(0, data.pruneDecrees(Long.MAX_VALUE / 2));
    }

    @Test
    void theTableRoundTripsThroughTheWorldSaveAndQuarantinesABadRow() {
        CrimeWorldData data = new CrimeWorldData();
        Decree a = decree(UUID.randomUUID(), VILLAGE, 0L);
        Decree b = decree(UUID.randomUUID(), NETHER_VILLAGE, 10L).ended(DecreeState.SERVED, 20L, null, "served:sentence_served");
        data.putDecree(a);
        data.putDecree(b);
        CompoundTag saved = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        ListTag rows = saved.getList("decrees", 10);
        CompoundTag broken = new CompoundTag();
        broken.putString("state", "open");
        rows.add(broken);
        CrimeWorldData loaded = CrimeWorldData.load(saved, RegistryAccess.EMPTY);
        assertEquals(2, loaded.decrees().size());
        assertEquals(a, loaded.decree(a.id()));
        assertEquals(b, loaded.decree(b.id()));
        assertTrue(loaded.quarantined().stream().anyMatch(row -> row.toString().contains("decree")),
                "the unreadable row was dropped instead of quarantined");
    }
}
