package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dispenser applies a restraint on its own authority, never on the victim's (0.7.5 M2.6).
 *
 * <p>The defect this exists for is upstream's, and it is a single argument: its dispenser path calls
 * the application with {@code (player, player)} — the victim as both actor and target. The world then
 * records that the subject restrained <em>herself</em>, and everything downstream reads wrong. A
 * self-application is supposed to be voluntary and file no case at all, so a trap becomes a crime the
 * victim committed against themselves; and the release path looks for an applier who is the person in
 * the cuffs.
 *
 * <p>Asserted over the transaction rather than through a live dispenser, because the thing that must
 * be true is a property of the recorded instance, and the block only supplies its position.
 */
class DispenserBehaviorTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final BlockPos DEVICE = new BlockPos(4, 70, -12);

    private CrimeWorldData data;
    private UUID victim;

    @BeforeEach
    void setUp() {
        ApplicationTransaction.clearDeliveries();
        data = new CrimeWorldData();
        victim = UUID.randomUUID();
    }

    private ApplicationTransaction.Request dispensed(RestraintSlot slot, ResourceLocation definitionId) {
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("id", "mcacrime:restraint_locked_cuffs");
        snapshot.putByte("Count", (byte) 1);
        return new ApplicationTransaction.Request(victim, true, OVERWORLD, slot, definitionId,
                RestraintApplier.device(OVERWORLD, DEVICE), null,
                AppliedRestraint.ApplicationContext.DEVICE,
                AppliedRestraint.Provenance.PLAYER_OWNED,
                AppliedRestraint.ReturnPolicy.DROP_AT_SUBJECT, null, snapshot);
    }

    @Test
    void theDeviceIsTheActor_andTheVictimIsNamedNowhere() {
        assertTrue(ApplicationTransaction.commit(data,
                dispensed(RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS),
                RigProfile.vanillaHumanoid(), 10L).applied());

        AppliedRestraint worn = data.physicalRestraint(victim).slot(RestraintSlot.ARMS).orElseThrow();
        assertEquals(RestraintApplier.Kind.DEVICE, worn.applier().kind());
        assertTrue(worn.applier().entityId().isEmpty(),
                "a device applier carries no entity id, so nobody can be forged into it");
        assertEquals(DEVICE, worn.applier().devicePos().orElseThrow());
        assertEquals(OVERWORLD, worn.applier().deviceDimension().orElseThrow());
    }

    @Test
    void aDispensedRestraintIsNotVoluntary() {
        // The consequence of the forged actor, stated directly: if the victim were the applier this
        // would read as a self-application, which files no case and is never a kidnapping.
        assertTrue(ApplicationTransaction.commit(data,
                dispensed(RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS),
                RigProfile.vanillaHumanoid(), 10L).applied());
        AppliedRestraint worn = data.physicalRestraint(victim).slot(RestraintSlot.ARMS).orElseThrow();
        assertEquals(AppliedRestraint.ApplicationContext.DEVICE, worn.context());
        assertFalse(worn.context() == AppliedRestraint.ApplicationContext.VOLUNTARY);
        assertTrue(worn.custodyId() == null, "a physical event creates no custody by itself");
    }

    @Test
    void aRefusedDispenseWritesNothing() {
        // The failed-dispense-consumes-nothing rule, at the level the transaction owns: no row is
        // written, so the behaviour above it never reaches its shrink.
        assertEquals(ApplicationTransaction.Refusal.NO_DEFINITION,
                ApplicationTransaction.commit(data,
                        dispensed(RestraintSlot.HEAD, RestraintDefinitions.HANDCUFFS_ARMS),
                        RigProfile.vanillaHumanoid(), 10L).refusal());
        assertTrue(data.physicalRestraints().isEmpty());
    }

    @Test
    void aDeviceWillNotReplaceWhatSomebodyElseApplied() {
        AppliedRestraint theirs = AppliedRestraint.of(
                RestraintDefinitions.get(RestraintDefinitions.SHACKLES_ARMS).orElseThrow(), null,
                RestraintApplier.player(UUID.randomUUID()),
                AppliedRestraint.ApplicationContext.UNLAWFUL,
                AppliedRestraint.Provenance.PLAYER_OWNED, AppliedRestraint.ReturnPolicy.NONE, null, 1L);
        data.putPhysicalRestraint(PhysicalRestraintState.empty(victim, true, OVERWORLD)
                .with(RestraintSlot.ARMS, theirs));

        assertEquals(ApplicationTransaction.Refusal.SLOT_OCCUPIED,
                ApplicationTransaction.commit(data,
                        dispensed(RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS),
                        RigProfile.vanillaHumanoid(), 10L).refusal());
        assertEquals(theirs, data.physicalRestraint(victim).slot(RestraintSlot.ARMS).orElseThrow());
    }
}
