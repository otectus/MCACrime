package dev.otectus.mcacrime.api;

import dev.otectus.mcacrime.api.model.CustodyView;
import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.network.ActionValidation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stable custody identity (0.7.5 §6.2) and what it is for.
 *
 * <p>Before this release {@code CustodyView.custodyId} was hard-coded empty, because the record had
 * no id to project. Neither substitute works: an unlawful capture has no sentence, and two successive
 * captures of one person share a captive UUID — so a delayed packet from the first would be accepted
 * against the second. This is that hole being closed, asserted from the outside in.
 */
class CustodyIdentityTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private static CustodyRecord capture(UUID captive, boolean lawful) {
        return new CustodyRecord(captive, true, lawful,
                lawful ? CustodyOwner.guard(UUID.randomUUID()) : CustodyOwner.kidnapper(UUID.randomUUID()),
                0L, null, OVERWORLD);
    }

    @Test
    void twoSuccessiveCapturesOfOneSubjectHaveDifferentIdentities() {
        UUID captive = UUID.randomUUID();

        CustodyRecord first = capture(captive, false);
        CustodyRecord second = capture(captive, true);

        assertNotEquals(first.getCustodyId(), second.getCustodyId(),
                "the captive UUID is shared; the custody identity may not be");
        assertEquals(1L, first.getGeneration());
        assertEquals(1L, second.getGeneration());
    }

    @Test
    void theOlderCapturesPacketsAreRefusedAgainstTheNewOne() {
        UUID captive = UUID.randomUUID();
        CustodyRecord first = capture(captive, false);
        UUID issuedAgainst = first.getCustodyId();

        CustodyRecord second = capture(captive, true);

        assertFalse(ActionValidation.matchesCustody(second, issuedAgainst, 1L),
                "a packet from the kidnapping must not act on the arrest that followed it");
        assertTrue(ActionValidation.matchesCustody(second, second.getCustodyId(), 1L));
    }

    @Test
    void aHandoverKeepsTheIdentityAndAdvancesTheGeneration() {
        CustodyRecord record = capture(UUID.randomUUID(), true);
        UUID id = record.getCustodyId();

        record.setOwner(CustodyOwner.jail(4, null, OVERWORLD));
        long after = record.bumpGeneration();

        assertEquals(id, record.getCustodyId(), "custody changing hands is the same custody");
        assertEquals(2L, after);
        assertFalse(ActionValidation.matchesCustody(record, id, 1L));
    }

    @Test
    void theIdentityAndGenerationSurviveTheSaveFile() {
        CustodyRecord record = capture(UUID.randomUUID(), true);
        record.bumpGeneration();

        CustodyRecord loaded = CustodyRecord.load(record.save());

        assertEquals(record.getCustodyId(), loaded.getCustodyId());
        assertEquals(record.getGeneration(), loaded.getGeneration());
        assertEquals(record.getCustodyId(), record.copy().getCustodyId());
        assertEquals(record.getGeneration(), record.copy().getGeneration());
    }

    /** A row written before 0.7.5 gets a derived id, so two loads of one file agree. */
    @Test
    void aLegacyRowGetsTheSameDerivedIdentityEveryTime() {
        UUID captive = UUID.randomUUID();
        CompoundTag legacy = new CompoundTag();
        legacy.putUUID("captive", captive);
        legacy.putBoolean("player", true);
        legacy.putString("restraint", "CUFFS");

        CustodyRecord once = CustodyRecord.load(legacy);
        CustodyRecord twice = CustodyRecord.load(legacy);

        assertEquals(CustodyRecord.legacyCustodyId(captive), once.getCustodyId());
        assertEquals(once.getCustodyId(), twice.getCustodyId(),
                "a random id per load would make every reconnect a different captivity");
        assertEquals(1L, once.getGeneration(), "an un-migrated row starts at the first generation");
    }

    @Test
    void aRowNamingNoCaptiveHasNoIdentityToDerive() {
        CustodyRecord empty = CustodyRecord.load(new CompoundTag());
        assertEquals(null, empty.getCustodyId());
        assertEquals(1L, empty.getGeneration());
    }

    /** The projection companions read. It was {@code Optional.empty()} until this release. */
    @Test
    void theApiProjectionCarriesTheRealIdentity() {
        CustodyRecord record = capture(UUID.randomUUID(), true);

        CustodyView view = new CustodyView(record.getCaptive(), record.isCaptivePlayer(),
                record.isLawful(), record.getOwner().ownerUuid(), RestraintType.NONE,
                record.getRealTicksHeld(), record.getRemainingJailTicks(),
                Optional.ofNullable(record.getHoldDim()),
                Optional.ofNullable(record.getCustodyId()), Optional.empty());

        assertEquals(record.getCustodyId(), view.custodyId().orElseThrow());
        assertTrue(view.linkedCaseId().isEmpty(),
                "a custody names a sentence, and a sentence is not a case");
    }
}
