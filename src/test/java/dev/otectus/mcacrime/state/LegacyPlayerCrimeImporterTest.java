package dev.otectus.mcacrime.state;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.enforcement.ArrestState;
import dev.otectus.mcacrime.jail.JailContainmentMode;
import dev.otectus.mcacrime.jail.JailState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 1.20.1 to 1.21.1 player-data lift (spec §7.3, §7.4 rows 1-7 and 10).
 *
 * <p>An upgraded world still carries its crime state under {@code ForgeCaps}, which NeoForge ignores.
 * These are the cases that decide whether a Forge player keeps their Karma and their sentence or
 * silently starts over, and the precedence case is the one that decides whether a returning player's
 * current state can be overwritten by a stale one.
 *
 * <p>Pure NBT: no server, no attachment holder. The importer's file reading is one thin layer above
 * {@link LegacyPlayerCrimeImporter#importFromForgeCaps}, which is where every decision actually is.
 */
class LegacyPlayerCrimeImporterTest {

    private static final UUID CAPTIVE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID CAPTOR = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID SENTENCE = UUID.fromString("00000000-0000-0000-0000-0000000000e5");
    private static final UUID GUARD = UUID.fromString("00000000-0000-0000-0000-0000000000a7");

    private static final String ATTACHMENTS_KEY = "neoforge:attachments";

    // INTERIM until Phase 0 fixture: built from PlayerCrimeData.save(), not from a real Forge world.
    // The keys are identical either way -- save() is what the Forge capability wrote -- but only a
    // captured 1.20.1 player file proves the surrounding root shape.
    private static PlayerCrimeData populated() {
        PlayerCrimeData data = new PlayerCrimeData();
        data.setKarma(-4200L);
        data.setHeat(913L);
        data.setCachedBand(Band.RED);
        data.setWantedCached(true);
        data.setOnlineTicksLived(123_456L);
        data.setLastKarmaDecayTick(120_000L);
        data.setLastHeatDecayTick(122_000L);
        data.setLastSurrenderTick(123_000L);
        data.setResistingArrestUntilTick(124_000L);
        data.dailyKarmaCounters().rollTo(19_000L);
        data.dailyKarmaCounters().add("mcacrime:theft", 7L);
        data.setHeldCaptiveRef(CAPTIVE);
        data.setHeldByRef(CAPTOR);

        JailState jail = new JailState(6_000L, new BlockPos(12, 64, -30),
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 9,
                JailContainmentMode.CONTAINMENT);
        jail.setRealOnlineTicksServed(1_500L);
        jail.setSentenceId(SENTENCE);
        jail.setEscaped(true);
        data.setJail(jail);

        ArrestState arrest = new ArrestState();
        arrest.setGuard(GUARD);
        arrest.setSentenceTicks(4_800L);
        arrest.setDeadlineOnlineTick(130_000L);
        arrest.setStuckStrikes(2);
        arrest.setLastSeenPos(new BlockPos(1, 2, 3));
        data.setArrest(arrest);
        return data;
    }

    /** A Forge 1.20.1 player-file root: the capability blob under {@code ForgeCaps}. */
    private static CompoundTag legacyRoot(CompoundTag crimeBlob) {
        CompoundTag forgeCaps = new CompoundTag();
        forgeCaps.put(LegacyPlayerCrimeImporter.CRIME_KEY, crimeBlob);
        CompoundTag root = new CompoundTag();
        root.putString("Dimension", "minecraft:overworld"); // vanilla neighbours, so the root is realistic
        root.put(LegacyPlayerCrimeImporter.FORGE_CAPS_KEY, forgeCaps);
        return root;
    }

    /** A NeoForge 1.21.1 player-file root: the same blob under the attachment key. */
    private static CompoundTag nativeRoot(CompoundTag crimeBlob) {
        CompoundTag attachments = new CompoundTag();
        attachments.put(LegacyPlayerCrimeImporter.CRIME_KEY, crimeBlob);
        CompoundTag root = new CompoundTag();
        root.put(ATTACHMENTS_KEY, attachments);
        return root;
    }

    @Test
    void importsEveryFieldFromALegacyRoot() {
        CompoundTag root = legacyRoot(populated().save());

        PlayerCrimeData imported = new PlayerCrimeData();
        assertTrue(LegacyPlayerCrimeImporter.importFromForgeCaps(root, imported));

        assertEquals(-4200L, imported.getKarma());
        assertEquals(913L, imported.getHeat());
        assertEquals(Band.RED, imported.getCachedBand());
        assertTrue(imported.isWantedCached());
        assertEquals(123_456L, imported.getOnlineTicksLived());
        assertEquals(120_000L, imported.getLastKarmaDecayTick());
        assertEquals(122_000L, imported.getLastHeatDecayTick());
        assertEquals(123_000L, imported.getLastSurrenderTick());
        assertEquals(124_000L, imported.getResistingArrestUntilTick());
        assertEquals(19_000L, imported.dailyKarmaCounters().dayEpoch());
        assertEquals(7L, imported.dailyKarmaCounters().get("mcacrime:theft"));
        assertEquals(CAPTIVE, imported.getHeldCaptiveRef());
        assertEquals(CAPTOR, imported.getHeldByRef());

        JailState jail = imported.getJail();
        assertNotNull(jail);
        assertEquals(6_000L, jail.getRemainingOnlineTicks());
        assertEquals(1_500L, jail.getRealOnlineTicksServed());
        assertEquals(new BlockPos(12, 64, -30), jail.getJailAnchor());
        assertEquals(9, jail.getJailRadius());
        assertEquals(JailContainmentMode.CONTAINMENT, jail.getModeSnapshot());
        assertEquals(SENTENCE, jail.getSentenceId());
        assertTrue(jail.isEscaped());

        ArrestState arrest = imported.getArrest();
        assertNotNull(arrest);
        assertEquals(GUARD, arrest.getGuard());
        assertEquals(4_800L, arrest.getSentenceTicks());
        assertEquals(130_000L, arrest.getDeadlineOnlineTick());
        assertEquals(2, arrest.getStuckStrikes());
        assertEquals(new BlockPos(1, 2, 3), arrest.getLastSeenPos());
    }

    @Test
    void aRootWithoutForgeCapsImportsNothing() {
        CompoundTag root = new CompoundTag();
        root.putString("Dimension", "minecraft:overworld");

        PlayerCrimeData data = new PlayerCrimeData();
        assertFalse(LegacyPlayerCrimeImporter.importFromForgeCaps(root, data));
        assertEquals(0L, data.getKarma());
    }

    @Test
    void anotherModsForgeCapsAreLeftAlone() {
        CompoundTag forgeCaps = new CompoundTag();
        forgeCaps.put("someothermod:stuff", new CompoundTag());
        CompoundTag root = new CompoundTag();
        root.put(LegacyPlayerCrimeImporter.FORGE_CAPS_KEY, forgeCaps);

        PlayerCrimeData data = new PlayerCrimeData();
        assertFalse(LegacyPlayerCrimeImporter.importFromForgeCaps(root, data));
    }

    @Test
    void wrongTypesAtEitherLevelAreASafeNoOp() {
        CompoundTag stringForgeCaps = new CompoundTag();
        stringForgeCaps.putString(LegacyPlayerCrimeImporter.FORGE_CAPS_KEY, "not a compound");
        PlayerCrimeData a = new PlayerCrimeData();
        assertFalse(LegacyPlayerCrimeImporter.importFromForgeCaps(stringForgeCaps, a));

        CompoundTag forgeCaps = new CompoundTag();
        forgeCaps.putLong(LegacyPlayerCrimeImporter.CRIME_KEY, 42L);
        CompoundTag scalarBlob = new CompoundTag();
        scalarBlob.put(LegacyPlayerCrimeImporter.FORGE_CAPS_KEY, forgeCaps);
        PlayerCrimeData b = new PlayerCrimeData();
        assertFalse(LegacyPlayerCrimeImporter.importFromForgeCaps(scalarBlob, b));
        assertEquals(0L, b.getKarma());
        assertNull(b.getJail());
    }

    @Test
    void aMangledBlobDegradesToDefaultsInsteadOfThrowing() {
        CompoundTag blob = new CompoundTag();
        blob.putString("karma", "not a number"); // wrong type reads as 0, never throws
        blob.putString("band", "PUCE");          // unknown band falls back to GREY
        CompoundTag root = legacyRoot(blob);

        PlayerCrimeData data = new PlayerCrimeData();
        assertDoesNotThrow(() -> LegacyPlayerCrimeImporter.importFromForgeCaps(root, data));
        assertEquals(0L, data.getKarma());
        assertEquals(Band.GREY, data.getCachedBand());
        assertNull(data.getJail());
        assertNull(data.getArrest());
        assertNull(data.getHeldCaptiveRef());
    }

    @Test
    void aBlobWithoutTheOptionalSubCompoundsLoadsDefaults() {
        CompoundTag blob = new CompoundTag();
        blob.putLong("karma", -50L);
        blob.putLong("heat", 10L);
        CompoundTag root = legacyRoot(blob);

        PlayerCrimeData data = new PlayerCrimeData();
        assertTrue(LegacyPlayerCrimeImporter.importFromForgeCaps(root, data));
        assertEquals(-50L, data.getKarma());
        assertEquals(10L, data.getHeat());
        // No "band" key: derived from karma rather than left at the enum default.
        assertEquals(Band.fromKarma(-50L), data.getCachedBand());
        assertNull(data.getJail());
        assertNull(data.getArrest());
        assertEquals(0L, data.getResistingArrestUntilTick());
    }

    @Test
    void aNativeAttachmentWinsOverALegacyBlob() {
        // Both roots present: the file has already been through one NeoForge save, so ForgeCaps is
        // stale by definition and re-importing it would roll the player back.
        CompoundTag root = legacyRoot(populated().save());
        CompoundTag attachments = new CompoundTag();
        attachments.put(LegacyPlayerCrimeImporter.CRIME_KEY, new PlayerCrimeData().save());
        root.put(ATTACHMENTS_KEY, attachments);

        assertTrue(LegacyPlayerCrimeImporter.hasNativeAttachment(root));
    }

    @Test
    void hasNativeAttachmentIsFalseForALegacyOnlyRoot() {
        assertFalse(LegacyPlayerCrimeImporter.hasNativeAttachment(legacyRoot(populated().save())));
        assertFalse(LegacyPlayerCrimeImporter.hasNativeAttachment(new CompoundTag()));
        assertTrue(LegacyPlayerCrimeImporter.hasNativeAttachment(nativeRoot(new PlayerCrimeData().save())));
    }

    @Test
    void importingTwiceProducesTheSameStateAsImportingOnce() {
        CompoundTag root = legacyRoot(populated().save());

        PlayerCrimeData once = new PlayerCrimeData();
        LegacyPlayerCrimeImporter.importFromForgeCaps(root, once);
        PlayerCrimeData twice = new PlayerCrimeData();
        LegacyPlayerCrimeImporter.importFromForgeCaps(root, twice);
        LegacyPlayerCrimeImporter.importFromForgeCaps(root, twice);

        assertEquals(once.save(), twice.save());
        assertEquals(populated().save(), twice.save());
    }

    @Test
    void theAttachmentSerialiserWritesTheSameBlobAsTheCapabilityDid() {
        PlayerCrimeData source = populated();
        // The attachment persists through save()/load(); nothing here is registry-aware, so a null
        // provider is exactly what the delegation contract promises to tolerate.
        CompoundTag written = source.serializeNBT(null);
        assertEquals(source.save(), written);

        PlayerCrimeData restored = new PlayerCrimeData();
        restored.deserializeNBT(null, written);
        assertEquals(source.save(), restored.save());
    }
}
