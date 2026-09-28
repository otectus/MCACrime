package dev.otectus.mcacrime.restraint;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How several restraints compose into one answer (0.7.5 M2.8), replacing {@code RestraintPolicyTest}.
 *
 * <p>The test this replaces asked a single question — "is this player restrained" — because the
 * engine it covered could only answer that. The §7.1 matrix is per action type, and the properties
 * worth asserting are the ones a blanket flag could not have:
 *
 * <ul>
 *   <li>three slots compose, and removing one leaves the other two's restrictions intact;</li>
 *   <li>two sources imposing the same restriction do not compound it, and order does not matter;</li>
 *   <li>leg shackles allow walking and block only jump and sprint — the deliberate departure recorded
 *       in §3.4, and also what the source's own code does however its documentation reads.</li>
 * </ul>
 */
class RestrictionCompositionTest {

    private static PhysicalRestraintState wearing(Map<RestraintSlot, ResourceLocation> definitions) {
        PhysicalRestraintState state = PhysicalRestraintState.empty(UUID.randomUUID(), true, null);
        for (Map.Entry<RestraintSlot, ResourceLocation> entry : definitions.entrySet()) {
            RestraintDefinition definition = RestraintDefinitions.get(entry.getValue()).orElseThrow();
            state = state.with(entry.getKey(), AppliedRestraint.of(definition, null,
                    RestraintApplier.system(), AppliedRestraint.ApplicationContext.LAWFUL,
                    AppliedRestraint.Provenance.SYSTEM_ISSUED,
                    AppliedRestraint.ReturnPolicy.NONE, null, 0L, 10));
        }
        return state;
    }

    @Test
    void headArmsAndLegsCompose() {
        PhysicalRestraintState state = wearing(Map.of(
                RestraintSlot.HEAD, RestraintDefinitions.BUNDLE,
                RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS,
                RestraintSlot.LEGS, RestraintDefinitions.HANDCUFFS_LEGS));
        RestrictionPolicy policy = RestrictionResolver.resolve(state);

        assertTrue(policy.obscureVision(), "the hood");
        assertFalse(policy.useItem(), "the arm cuffs");
        assertFalse(policy.voluntaryMovement(), "the leg cuffs");
    }

    @Test
    void removingOneLeavesTheOtherTwoIntact() {
        PhysicalRestraintState state = wearing(Map.of(
                RestraintSlot.HEAD, RestraintDefinitions.BUNDLE,
                RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS,
                RestraintSlot.LEGS, RestraintDefinitions.HANDCUFFS_LEGS));

        RestrictionPolicy withoutArms = RestrictionResolver.resolve(state.without(RestraintSlot.ARMS));
        assertTrue(withoutArms.useItem(), "the hands are free once the cuffs are off");
        assertFalse(withoutArms.voluntaryMovement(), "the leg cuffs are still on");
        assertTrue(withoutArms.obscureVision(), "the hood is still on");

        RestrictionPolicy withoutLegs = RestrictionResolver.resolve(state.without(RestraintSlot.LEGS));
        assertFalse(withoutLegs.useItem());
        assertTrue(withoutLegs.voluntaryMovement());
    }

    @Test
    void identicalEffectsDoNotMultiply() {
        RestrictionPolicy one = RestrictionResolver.of(RestraintDefinitions.HANDCUFFS_ARMS);
        assertEquals(one, one.and(one));
        assertEquals(one, one.and(one).and(one));
    }

    @Test
    void compositionOrderDoesNotMatter() {
        List<ResourceLocation> forwards = List.of(RestraintDefinitions.BUNDLE,
                RestraintDefinitions.HANDCUFFS_ARMS, RestraintDefinitions.SHACKLES_LEGS);
        List<ResourceLocation> backwards = List.of(RestraintDefinitions.SHACKLES_LEGS,
                RestraintDefinitions.HANDCUFFS_ARMS, RestraintDefinitions.BUNDLE);
        assertEquals(RestrictionResolver.compose(forwards), RestrictionResolver.compose(backwards));
    }

    @Test
    void legShacklesAllowWalkingAndBlockJumpAndSprint() {
        RestrictionPolicy policy = RestrictionResolver.of(RestraintDefinitions.SHACKLES_LEGS);
        assertTrue(policy.voluntaryMovement(), "§3.4: leg shackles allow walking");
        assertFalse(policy.jump());
        assertFalse(policy.sprint());
        // And a GUI toggle cannot bypass them, because the answer is recomputed from the worn set
        // rather than cached anywhere a client could reach.
        assertEquals(policy, RestrictionResolver.resolve(
                wearing(Map.of(RestraintSlot.LEGS, RestraintDefinitions.SHACKLES_LEGS))));
    }

    @Test
    void theHeadSlotDoesNotGagTypedChat() {
        // §3.4: a deliberate departure from upstream. The hood obscures vision and gags voice; typing
        // is a ProtectedAction, and taking it away is a moderation problem rather than a mechanic.
        RestrictionPolicy hood = RestrictionResolver.of(RestraintDefinitions.BUNDLE);
        assertTrue(RestrictionResolver.allows(hood, ProtectedAction.CHAT));
        RestrictionPolicy tape = RestrictionResolver.of(RestraintDefinitions.DUCK_TAPE_HEAD);
        assertTrue(RestrictionResolver.allows(tape, ProtectedAction.CHAT));
        assertTrue(tape.voiceGag());
    }

    @Test
    void aDeviceComposesOnTopOfWornGear() {
        PhysicalRestraintState state =
                wearing(Map.of(RestraintSlot.HEAD, RestraintDefinitions.BUNDLE));
        RestrictionPolicy policy =
                RestrictionResolver.resolve(state, RestraintDefinitions.PILLORY);
        assertTrue(policy.obscureVision(), "the hood is not replaced by the device");
        assertFalse(policy.voluntaryMovement(), "the pillory holds them");
    }

    @Test
    void theArrestPhaseFoldsIn() {
        // The fold that used to live in enforcement/RestraintPolicy: an arrest says "restrained"
        // without saying with what, and resolves to the arm-handcuff restrictions.
        RestrictionPolicy arrested = RestrictionResolver.resolve(null, null, true);
        assertFalse(arrested.useItem());
        assertFalse(arrested.mineBlocks());
        assertTrue(arrested.voluntaryMovement(), "an arrested player still walks to the cell");
        assertTrue(RestrictionResolver.resolve(null, null, false).unrestrictedPolicy());
    }

    @Test
    void nothingWornIsNothingForbidden() {
        assertTrue(RestrictionResolver.resolve(null).unrestrictedPolicy());
        assertTrue(RestrictionResolver.resolve(
                PhysicalRestraintState.empty(UUID.randomUUID(), true, null)).unrestrictedPolicy());
    }
}
