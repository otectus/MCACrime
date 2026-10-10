package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.captivity.CaptureCommitResult;
import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A kidnapping owns the gear that holds its captive, so its end takes that gear off (0.7.7).
 *
 * <p>The bug this pins: the restraint that begins a kidnapping goes on a moment before the custody
 * record exists, so it was stamped with no custody at all, and a custody's end takes off only the gear
 * carrying its id. A villager whose ransom had been paid walked home in the kidnapper's cuffs. These run
 * against a bare world store: the rules are about which slots name which custody, and none of them
 * needs a server to be true.
 */
class CustodyGearAttributionTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final BlockPos HOLD = new BlockPos(0, 64, 0);

    private CrimeWorldData data;
    private UUID captive;
    private UUID kidnapper;

    @BeforeEach
    void freshWorld() {
        data = new CrimeWorldData();
        captive = UUID.randomUUID();
        kidnapper = UUID.randomUUID();
    }

    private static AppliedRestraint worn(ResourceLocation definition, AppliedRestraint.ApplicationContext context,
                                         @Nullable UUID custody) {
        return new AppliedRestraint(UUID.randomUUID(), definition, null, 50, 1,
                RestraintApplier.player(UUID.randomUUID()), context,
                AppliedRestraint.Provenance.PLAYER_OWNED, AppliedRestraint.ReturnPolicy.DROP_AT_SUBJECT,
                custody, 10L, 1L);
    }

    private void wear(RestraintSlot slot, AppliedRestraint restraint) {
        PhysicalRestraintState state = data.physicalRestraint(captive);
        PhysicalRestraintState base = state != null ? state
                : PhysicalRestraintState.empty(captive, false, OVERWORLD);
        assertTrue(data.putPhysicalRestraint(base.with(slot, restraint)));
    }

    @Nullable
    private UUID custodyOf(RestraintSlot slot) {
        PhysicalRestraintState state = data.physicalRestraint(captive);
        return state == null ? null : state.slot(slot).map(AppliedRestraint::custodyId).orElse(null);
    }

    private boolean wears(RestraintSlot slot) {
        PhysicalRestraintState state = data.physicalRestraint(captive);
        return state != null && state.occupied(slot);
    }

    private UUID kidnap() {
        assertEquals(CaptureCommitResult.CAPTURED,
                CustodyService.capture(data, kidnapper, captive, false, 0L, HOLD, OVERWORLD, 4));
        CustodyRecord record = data.getCustody(captive);
        assertNotNull(record);
        return record.getCustodyId();
    }

    // --- adoption at capture ------------------------------------------------------------------------------

    @Test
    void aKidnappingAdoptsTheRestraintThatBeganIt() {
        wear(RestraintSlot.ARMS, worn(RestraintDefinitions.HANDCUFFS_ARMS,
                AppliedRestraint.ApplicationContext.UNLAWFUL, null));

        UUID custody = kidnap();

        assertEquals(custody, custodyOf(RestraintSlot.ARMS),
                "the cuffs that began the kidnapping belong to it, so its end takes them off");
    }

    @Test
    void theSubjectsOwnVoluntaryGearIsNeverAdopted() {
        wear(RestraintSlot.HEAD, worn(RestraintDefinitions.BUNDLE,
                AppliedRestraint.ApplicationContext.VOLUNTARY, null));
        wear(RestraintSlot.ARMS, worn(RestraintDefinitions.DUCK_TAPE_ARMS,
                AppliedRestraint.ApplicationContext.UNLAWFUL, null));

        UUID custody = kidnap();

        assertNull(custodyOf(RestraintSlot.HEAD), "a hood they put on themselves is still theirs");
        assertEquals(custody, custodyOf(RestraintSlot.ARMS));
    }

    @Test
    void gearAnotherCustodyAlreadyOwnsKeepsItsOwner() {
        UUID earlier = UUID.randomUUID();
        wear(RestraintSlot.LEGS, worn(RestraintDefinitions.SHACKLES_LEGS,
                AppliedRestraint.ApplicationContext.LAWFUL, earlier));

        kidnap();

        assertEquals(earlier, custodyOf(RestraintSlot.LEGS), "adoption only ever takes unattributed gear");
    }

    @Test
    void deviceAndAdministrativeGearIsPartOfTheHoldItWasFoundIn() {
        wear(RestraintSlot.ARMS, worn(RestraintDefinitions.HANDCUFFS_ARMS,
                AppliedRestraint.ApplicationContext.DEVICE, null));
        wear(RestraintSlot.LEGS, worn(RestraintDefinitions.HANDCUFFS_LEGS,
                AppliedRestraint.ApplicationContext.ADMINISTRATIVE, null));

        UUID custody = kidnap();

        assertEquals(custody, custodyOf(RestraintSlot.ARMS));
        assertEquals(custody, custodyOf(RestraintSlot.LEGS));
    }

    @Test
    void adoptionHappensOnceAndNeverMovesGearBetweenCustodies() {
        wear(RestraintSlot.ARMS, worn(RestraintDefinitions.HANDCUFFS_ARMS,
                AppliedRestraint.ApplicationContext.UNLAWFUL, null));
        UUID custody = kidnap();

        assertEquals(0, CustodyTransitionService.adoptGear(data, captive, custody),
                "a second adoption finds nothing unattributed");
        assertEquals(0, CustodyTransitionService.adoptGear(data, captive, UUID.randomUUID()),
                "and a later custody cannot take gear the first one owns");
        assertEquals(custody, custodyOf(RestraintSlot.ARMS));
    }

    @Test
    void aRefusedCaptureAdoptsNothing() {
        kidnap();
        // Written straight to the store, so it names no custody; a refused second kidnapper must not
        // take it.
        wear(RestraintSlot.HEAD, worn(RestraintDefinitions.DUCK_TAPE_HEAD,
                AppliedRestraint.ApplicationContext.UNLAWFUL, null));

        assertEquals(CaptureCommitResult.ALREADY_HELD,
                CustodyService.capture(data, UUID.randomUUID(), captive, false, 0L, HOLD, OVERWORLD, 4));
        assertNull(custodyOf(RestraintSlot.HEAD), "a capture that did not stand changes nobody's gear");
    }

    @Test
    void adoptableIsUnattributedAndInvoluntary() {
        assertTrue(CustodyTransitionService.adoptable(worn(RestraintDefinitions.HANDCUFFS_ARMS,
                AppliedRestraint.ApplicationContext.UNLAWFUL, null)));
        assertFalse(CustodyTransitionService.adoptable(worn(RestraintDefinitions.BUNDLE,
                AppliedRestraint.ApplicationContext.VOLUNTARY, null)));
        assertFalse(CustodyTransitionService.adoptable(worn(RestraintDefinitions.HANDCUFFS_ARMS,
                AppliedRestraint.ApplicationContext.UNLAWFUL, UUID.randomUUID())));
        assertFalse(CustodyTransitionService.adoptable(null));
    }

    // --- stamping during a custody ------------------------------------------------------------------------

    @Test
    void gearPutOnDuringAKidnappingBelongsToIt() {
        UUID custody = kidnap();

        assertEquals(custody, CustodyTransitionService.custodyFor(data, captive, false),
                "the gag added after the cuffs comes off with them");
        assertNull(CustodyTransitionService.custodyFor(data, captive, true),
                "a voluntary application is never a custody's");
    }

    @Test
    void aLawfulCustodyStampsNothingNew() {
        data.putCustody(new CustodyRecord(captive, true, true, CustodyOwner.bountyHunter(kidnapper), 0L,
                HOLD, OVERWORLD));

        assertNull(CustodyTransitionService.custodyFor(data, captive, false),
                "when a prisoner's cuffs come off is the arrest's business, unchanged");
    }

    @Test
    void nobodyHeldMeansNobodysGear() {
        assertNull(CustodyTransitionService.custodyFor(data, captive, false));
        assertNull(CustodyTransitionService.custodyFor(null, captive, false));
        assertNull(CustodyTransitionService.custodyFor(data, null, false));
    }

    // --- the custody's end -------------------------------------------------------------------------------

    @Test
    void releaseTakesOffExactlyWhatTheCustodyOwns() {
        UUID other = UUID.randomUUID();
        wear(RestraintSlot.ARMS, worn(RestraintDefinitions.HANDCUFFS_ARMS,
                AppliedRestraint.ApplicationContext.UNLAWFUL, null));
        wear(RestraintSlot.HEAD, worn(RestraintDefinitions.BUNDLE,
                AppliedRestraint.ApplicationContext.VOLUNTARY, null));
        wear(RestraintSlot.LEGS, worn(RestraintDefinitions.SHACKLES_LEGS,
                AppliedRestraint.ApplicationContext.LAWFUL, other));
        UUID custody = kidnap();

        assertEquals(1, RestraintService.releaseFor(data, captive, custody, null));

        assertFalse(wears(RestraintSlot.ARMS), "the kidnapper's cuffs come off with the kidnapping");
        assertTrue(wears(RestraintSlot.HEAD), "the subject's own hood stays on");
        assertTrue(wears(RestraintSlot.LEGS), "another custody's gear is that custody's to take off");
    }

    @Test
    void aSecondReleaseEmptiesNothing() {
        wear(RestraintSlot.ARMS, worn(RestraintDefinitions.HANDCUFFS_ARMS,
                AppliedRestraint.ApplicationContext.UNLAWFUL, null));
        wear(RestraintSlot.HEAD, worn(RestraintDefinitions.DUCK_TAPE_HEAD,
                AppliedRestraint.ApplicationContext.UNLAWFUL, null));
        UUID custody = kidnap();

        assertEquals(2, RestraintService.releaseFor(data, captive, custody, null));
        assertEquals(0, RestraintService.releaseFor(data, captive, custody, null),
                "a repeated settlement finds nothing left that names the custody");
    }

    @Test
    void aLoadedCaptiveGoesThroughTheRemovalPathOncePerOwnedSlot() {
        wear(RestraintSlot.ARMS, worn(RestraintDefinitions.HANDCUFFS_ARMS,
                AppliedRestraint.ApplicationContext.UNLAWFUL, null));
        wear(RestraintSlot.LEGS, worn(RestraintDefinitions.DUCK_TAPE_LEGS,
                AppliedRestraint.ApplicationContext.UNLAWFUL, null));
        wear(RestraintSlot.HEAD, worn(RestraintDefinitions.BUNDLE,
                AppliedRestraint.ApplicationContext.VOLUNTARY, null));
        UUID custody = kidnap();

        List<RestraintSlot> handed = new ArrayList<>();
        // The removal path owns the item; the stand-in records the slot and empties it, as it would.
        int removed = RestraintService.releaseFor(data, captive, custody, slot -> {
            handed.add(slot);
            PhysicalRestraintState state = data.physicalRestraint(captive);
            return data.putPhysicalRestraint(state.without(slot));
        });

        assertEquals(2, removed);
        assertEquals(List.of(RestraintSlot.ARMS, RestraintSlot.LEGS).stream().sorted().toList(),
                handed.stream().sorted().toList(), "every owned slot once, and never the voluntary one");
        assertEquals(0, RestraintService.releaseFor(data, captive, custody, slot -> {
            handed.add(slot);
            return true;
        }));
        assertEquals(2, handed.size(), "nothing is handed to the removal path a second time");
    }

    @Test
    void aReleaseWithoutACustodyIdReleasesNothing() {
        wear(RestraintSlot.ARMS, worn(RestraintDefinitions.HANDCUFFS_ARMS,
                AppliedRestraint.ApplicationContext.UNLAWFUL, null));

        assertEquals(0, RestraintService.releaseFor(data, captive, null, null));
        assertTrue(wears(RestraintSlot.ARMS), "unattributed gear is nobody's custody's to take off");
    }
}
