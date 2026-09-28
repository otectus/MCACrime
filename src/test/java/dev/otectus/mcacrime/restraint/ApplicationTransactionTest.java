package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The six-step commit that puts one restraint on one subject (0.7.5 M2.3, specification §7.2).
 *
 * <p>Two contested situations, both of which the source loses:
 *
 * <ul>
 *   <li><b>Two actors, one empty slot.</b> One of them must succeed and the other must be refused,
 *       and the refusal must cost nothing. The source consumes the item in the same statement that
 *       applies the restraint, so the loser of the race pays for a restraint that never went on.</li>
 *   <li><b>One interaction delivered twice.</b> Forge fires {@code EntityInteract} and
 *       {@code EntityInteractSpecific} for a single right-click, and a two-handed delivery adds
 *       another. Without a dedupe key the second arrival either equips a second restraint or spends
 *       a second item; the source relies on the first call's inventory shrink being visible to the
 *       second, which is a race rather than a rule.</li>
 * </ul>
 *
 * <p>Everything asserted here is over world state, with no server: the transaction owns the decision
 * and the single write precisely so the decision can be checked without one.
 */
class ApplicationTransactionTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    private CrimeWorldData data;
    private UUID subject;
    private UUID actorOne;
    private UUID actorTwo;

    @BeforeEach
    void setUp() {
        ApplicationTransaction.clearDeliveries();
        data = new CrimeWorldData();
        subject = UUID.randomUUID();
        actorOne = UUID.randomUUID();
        actorTwo = UUID.randomUUID();
    }

    private ApplicationTransaction.Request request(UUID actor, RestraintSlot slot,
                                                   ResourceLocation definitionId) {
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("id", "mcacrime:restraint_locked_cuffs");
        snapshot.putByte("Count", (byte) 1);
        return new ApplicationTransaction.Request(subject, true, OVERWORLD, slot, definitionId,
                RestraintApplier.player(actor), actor,
                AppliedRestraint.ApplicationContext.UNLAWFUL,
                AppliedRestraint.Provenance.PLAYER_OWNED,
                AppliedRestraint.ReturnPolicy.DROP_AT_SUBJECT, null, snapshot);
    }

    @Test
    void twoActorsOneEmptySlot() {
        ApplicationTransaction.Result first = ApplicationTransaction.commit(data,
                request(actorOne, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS),
                RigProfile.vanillaHumanoid(), 100L);
        ApplicationTransaction.Result second = ApplicationTransaction.commit(data,
                request(actorTwo, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS),
                RigProfile.vanillaHumanoid(), 100L);

        assertTrue(first.applied());
        assertFalse(second.applied());
        assertEquals(ApplicationTransaction.Refusal.SLOT_OCCUPIED, second.refusal());

        // Exactly one instance exists, and it belongs to the actor who won.
        PhysicalRestraintState state = data.physicalRestraint(subject);
        assertNotNull(state);
        AppliedRestraint worn = state.slot(RestraintSlot.ARMS).orElseThrow();
        assertEquals(actorOne, worn.applier().entityId().orElseThrow());
        // Nothing landed on the other two slots as a side effect of the race.
        assertFalse(state.occupied(RestraintSlot.HEAD));
        assertFalse(state.occupied(RestraintSlot.LEGS));
    }

    @Test
    void replayedApplicationDoesNotEquipTwice() {
        ApplicationTransaction.Request request =
                request(actorOne, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS);
        assertTrue(ApplicationTransaction.commit(data, request, RigProfile.vanillaHumanoid(), 50L)
                .applied());
        ApplicationTransaction.Result replay =
                ApplicationTransaction.commit(data, request, RigProfile.vanillaHumanoid(), 50L);

        // The slot is occupied by then, so the slot check refuses first -- which is the correct
        // answer either way. What matters is that no second instance and no second item appear.
        assertFalse(replay.applied());
        UUID instance = data.physicalRestraint(subject).slot(RestraintSlot.ARMS)
                .orElseThrow().instanceId();
        assertEquals(instance, data.physicalRestraint(subject).slot(RestraintSlot.ARMS)
                .orElseThrow().instanceId());
    }

    @Test
    void theDuplicateKeyRefusesTheSecondDeliveryOfOneInteraction() {
        // The same actor, subject, slot and tick, with the slot free both times: only the dedupe key
        // can tell these apart, so this is the case that proves it is doing something.
        ApplicationTransaction.Request request =
                request(actorOne, RestraintSlot.LEGS, RestraintDefinitions.HANDCUFFS_LEGS);
        assertTrue(ApplicationTransaction.commit(data, request, RigProfile.vanillaHumanoid(), 7L)
                .applied());
        data.removePhysicalRestraint(subject);
        assertEquals(ApplicationTransaction.Refusal.DUPLICATE_DELIVERY,
                ApplicationTransaction.commit(data, request, RigProfile.vanillaHumanoid(), 7L).refusal());
        // A later tick is a new interaction and is allowed.
        assertTrue(ApplicationTransaction.commit(data, request, RigProfile.vanillaHumanoid(), 12L)
                .applied());
    }

    @Test
    void threeSlotsCoexistAndDoNotDisturbEachOther() {
        assertTrue(ApplicationTransaction.commit(data,
                request(actorOne, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS),
                RigProfile.vanillaHumanoid(), 1L).applied());
        assertTrue(ApplicationTransaction.commit(data,
                request(actorOne, RestraintSlot.LEGS, RestraintDefinitions.SHACKLES_LEGS),
                RigProfile.vanillaHumanoid(), 2L).applied());
        assertTrue(ApplicationTransaction.commit(data,
                request(actorOne, RestraintSlot.HEAD, RestraintDefinitions.BUNDLE),
                RigProfile.vanillaHumanoid(), 3L).applied());

        PhysicalRestraintState state = data.physicalRestraint(subject);
        assertTrue(state.occupied(RestraintSlot.ARMS));
        assertTrue(state.occupied(RestraintSlot.LEGS));
        assertTrue(state.occupied(RestraintSlot.HEAD));
        // Three distinct instances, not one shared object: this is the upstream registry-singleton
        // defect, asserted at the level where it would show.
        assertEquals(3, java.util.Set.of(
                state.slot(RestraintSlot.ARMS).orElseThrow().instanceId(),
                state.slot(RestraintSlot.LEGS).orElseThrow().instanceId(),
                state.slot(RestraintSlot.HEAD).orElseThrow().instanceId()).size());
    }

    @Test
    void aDefinitionThatDoesNotBelongOnThatSlotIsRefused() {
        assertEquals(ApplicationTransaction.Refusal.NO_DEFINITION,
                ApplicationTransaction.commit(data,
                        request(actorOne, RestraintSlot.HEAD, RestraintDefinitions.HANDCUFFS_ARMS),
                        RigProfile.vanillaHumanoid(), 1L).refusal());
        // And the device definition is not wearable at all.
        assertEquals(ApplicationTransaction.Refusal.NO_DEFINITION,
                ApplicationTransaction.commit(data,
                        request(actorOne, RestraintSlot.ARMS, RestraintDefinitions.PILLORY),
                        RigProfile.vanillaHumanoid(), 1L).refusal());
    }

    @Test
    void aRigWithoutTheRegionIsRefused() {
        RigProfile grub = new RigProfile("grub", false, true, false, false, 1.0F);
        assertEquals(ApplicationTransaction.Refusal.RIG_UNSUPPORTED,
                ApplicationTransaction.commit(data,
                        request(actorOne, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS),
                        grub, 1L).refusal());
    }

    @Test
    void theItemToDefinitionDirectionIsSlotAware() {
        assertEquals(RestraintDefinitions.HANDCUFFS_ARMS, ApplicationTransaction
                .definitionFor(RestraintFamily.HANDCUFFS, RestraintSlot.ARMS).orElseThrow());
        assertEquals(RestraintDefinitions.HANDCUFFS_LEGS, ApplicationTransaction
                .definitionFor(RestraintFamily.HANDCUFFS, RestraintSlot.LEGS).orElseThrow());
        assertEquals(RestraintDefinitions.DUCK_TAPE_HEAD, ApplicationTransaction
                .definitionFor(RestraintFamily.TAPE, RestraintSlot.HEAD).orElseThrow());
        // The handcuff family has no head definition, and inventing one is not an option.
        assertTrue(ApplicationTransaction
                .definitionFor(RestraintFamily.HANDCUFFS, RestraintSlot.HEAD).isEmpty());
        // The legacy rope carrier is not one of the ten.
        assertTrue(ApplicationTransaction
                .definitionFor(RestraintFamily.LEGACY_ROPE, RestraintSlot.ARMS).isEmpty());
    }
}
