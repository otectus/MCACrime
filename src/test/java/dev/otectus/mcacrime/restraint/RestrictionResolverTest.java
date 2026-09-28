package dev.otectus.mcacrime.restraint;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The §7.1 restriction matrix, one assertion per cell, plus the composition rules around it.
 *
 * <p>The matrix is the authority for what a restrained player may do, so it is asserted literally
 * rather than through the definitions that implement it: if somebody edits the table in
 * {@link RestraintDefinitions}, this is what notices.
 *
 * <p>Two rows are deliberate departures from upstream and are called out where they appear: leg
 * shackles allow walking, and the head slot does not gag text chat.
 */
class RestrictionResolverTest {

    private static RestrictionPolicy policy(ResourceLocation definitionId) {
        return RestrictionResolver.of(definitionId);
    }

    private static void assertPermits(ResourceLocation definitionId, RestraintAction action,
                                      boolean expected, String why) {
        assertEquals(expected, policy(definitionId).permits(action),
                definitionId + " / " + action + ": " + why);
    }

    /** The eight action types §7.1 collapses into its "hand actions / inventory" column. */
    private static final List<RestraintAction> HAND_ACTIONS = List.of(
            RestraintAction.USE_ITEM, RestraintAction.ATTACK, RestraintAction.INTERACT_ENTITY,
            RestraintAction.INTERACT_BLOCK, RestraintAction.DROP_ITEM, RestraintAction.MUTATE_INVENTORY,
            RestraintAction.SWAP_OFFHAND, RestraintAction.CHANGE_HOTBAR);

    private static void assertHandActions(ResourceLocation definitionId, boolean expected) {
        for (RestraintAction action : HAND_ACTIONS) {
            assertPermits(definitionId, action, expected, "the §7.1 hand-actions column");
        }
    }

    // ------------------------------------------------------------------ row by row

    @Test
    void armHandcuffs() {
        ResourceLocation id = RestraintDefinitions.HANDCUFFS_ARMS;
        assertPermits(id, RestraintAction.MINE_BLOCKS, false, "no mining");
        assertHandActions(id, false);
        assertPermits(id, RestraintAction.VOLUNTARY_MOVEMENT, true, "walking is unaffected");
        assertPermits(id, RestraintAction.JUMP, true, "jumping is unaffected");
        assertPermits(id, RestraintAction.SPRINT, true, "no legacy blanket slowdown for cuffed arms");
        assertFalse(policy(id).obscureVision());
        assertFalse(policy(id).voiceGag());
    }

    @Test
    void armShackles() {
        ResourceLocation id = RestraintDefinitions.SHACKLES_ARMS;
        assertPermits(id, RestraintAction.MINE_BLOCKS, false, "no mining");
        assertHandActions(id, true);
        assertPermits(id, RestraintAction.VOLUNTARY_MOVEMENT, true, "walking is unaffected");
        assertPermits(id, RestraintAction.JUMP, true, "jumping is unaffected");
        assertPermits(id, RestraintAction.SPRINT, true, "weaker restraints keep the hands useful");
    }

    @Test
    void armTape() {
        ResourceLocation id = RestraintDefinitions.DUCK_TAPE_ARMS;
        assertPermits(id, RestraintAction.MINE_BLOCKS, false, "no mining");
        assertHandActions(id, false);
        assertPermits(id, RestraintAction.VOLUNTARY_MOVEMENT, true, "walking is unaffected");
        assertPermits(id, RestraintAction.JUMP, true, "jumping is unaffected");
        assertEquals(policy(RestraintDefinitions.HANDCUFFS_ARMS), policy(id),
                "arm tape restricts as handcuffs do; it is only weaker to get out of");
    }

    @Test
    void legHandcuffs() {
        ResourceLocation id = RestraintDefinitions.HANDCUFFS_LEGS;
        assertPermits(id, RestraintAction.MINE_BLOCKS, true, "mining is unaffected");
        assertHandActions(id, true);
        assertPermits(id, RestraintAction.VOLUNTARY_MOVEMENT, false, "no voluntary movement");
        assertPermits(id, RestraintAction.JUMP, false, "no jumping");
        assertPermits(id, RestraintAction.SPRINT, false, "no sprinting");
        assertPermits(id, RestraintAction.STEER_VEHICLE, false,
                "external escort and transport still work; steering one's own is not external");
        assertPermits(id, RestraintAction.DISMOUNT, false, "and they cannot climb back out");
    }

    /** The departure §3.4 records: walking is allowed, which is also what the source's code does. */
    @Test
    void legShacklesAllowWalking() {
        ResourceLocation id = RestraintDefinitions.SHACKLES_LEGS;
        assertPermits(id, RestraintAction.MINE_BLOCKS, true, "mining is unaffected");
        assertHandActions(id, true);
        assertPermits(id, RestraintAction.VOLUNTARY_MOVEMENT, true,
                "the client/server disagreement is resolved in favour of walking");
        assertPermits(id, RestraintAction.JUMP, false, "jumping is blocked");
        assertPermits(id, RestraintAction.SPRINT, false, "sprinting is blocked");
    }

    @Test
    void legTape() {
        ResourceLocation id = RestraintDefinitions.DUCK_TAPE_LEGS;
        assertPermits(id, RestraintAction.MINE_BLOCKS, true, "mining is unaffected");
        assertHandActions(id, true);
        assertPermits(id, RestraintAction.VOLUNTARY_MOVEMENT, false, "a weak leg bind is still a leg bind");
        assertPermits(id, RestraintAction.JUMP, false, "no jumping");
    }

    @Test
    void bundleHood() {
        ResourceLocation id = RestraintDefinitions.BUNDLE;
        assertPermits(id, RestraintAction.MINE_BLOCKS, true, "mining is unaffected");
        assertHandActions(id, true);
        assertPermits(id, RestraintAction.VOLUNTARY_MOVEMENT, true, "walking is unaffected");
        assertPermits(id, RestraintAction.JUMP, true, "jumping is unaffected");
        assertTrue(policy(id).obscureVision(), "the hood's whole effect is that they cannot see");
        assertFalse(policy(id).voiceGag());
    }

    /** The second departure: a gag silences voice, never typing. */
    @Test
    void headTapeGagsVoiceAndNotText() {
        ResourceLocation id = RestraintDefinitions.DUCK_TAPE_HEAD;
        assertPermits(id, RestraintAction.MINE_BLOCKS, true, "mining is unaffected");
        assertHandActions(id, true);
        assertPermits(id, RestraintAction.VOLUNTARY_MOVEMENT, true, "walking is unaffected");
        assertPermits(id, RestraintAction.JUMP, true, "jumping is unaffected");
        assertTrue(policy(id).voiceGag(), "voice is gagged when a voice mod is present");
        assertFalse(policy(id).obscureVision(), "tape over the mouth is not a blindfold");
        assertTrue(RestrictionResolver.allows(policy(id), ProtectedAction.CHAT),
                "text chat is never blocked by a head restraint (§3.4)");
    }

    @Test
    void pilloryDetention() {
        ResourceLocation id = RestraintDefinitions.PILLORY;
        assertPermits(id, RestraintAction.MINE_BLOCKS, false, "no mining");
        assertHandActions(id, false);
        assertPermits(id, RestraintAction.VOLUNTARY_MOVEMENT, false, "the device holds them in place");
        assertPermits(id, RestraintAction.JUMP, false, "no jumping");
    }

    // ------------------------------------------------------------------ composition

    @Test
    void threeSlotsComposeAndTheMostRestrictiveWins() {
        PhysicalRestraintState state = state()
                .with(RestraintSlot.HEAD, applied(RestraintDefinitions.BUNDLE))
                .with(RestraintSlot.ARMS, applied(RestraintDefinitions.HANDCUFFS_ARMS))
                .with(RestraintSlot.LEGS, applied(RestraintDefinitions.SHACKLES_LEGS));

        RestrictionPolicy composed = RestrictionResolver.resolve(state);

        assertFalse(composed.permits(RestraintAction.USE_ITEM), "the arms forbid it");
        assertFalse(composed.permits(RestraintAction.JUMP), "the legs forbid it");
        assertTrue(composed.permits(RestraintAction.VOLUNTARY_MOVEMENT), "neither forbids walking");
        assertTrue(composed.obscureVision(), "the hood is still on");
    }

    @Test
    void removingOneSourceDoesNotRestoreWhatAnotherStillForbids() {
        PhysicalRestraintState both = state()
                .with(RestraintSlot.ARMS, applied(RestraintDefinitions.HANDCUFFS_ARMS))
                .with(RestraintSlot.LEGS, applied(RestraintDefinitions.HANDCUFFS_LEGS));

        RestrictionPolicy afterArms = RestrictionResolver.resolve(both.without(RestraintSlot.ARMS));
        assertTrue(afterArms.permits(RestraintAction.USE_ITEM), "the arms are free now");
        assertFalse(afterArms.permits(RestraintAction.VOLUNTARY_MOVEMENT), "the legs are not");

        RestrictionPolicy afterLegs = RestrictionResolver.resolve(both.without(RestraintSlot.LEGS));
        assertFalse(afterLegs.permits(RestraintAction.USE_ITEM));
        assertTrue(afterLegs.permits(RestraintAction.VOLUNTARY_MOVEMENT));
    }

    @Test
    void aDeviceComposesOnTopOfWornGearWithoutOccupyingASlot() {
        PhysicalRestraintState state = state().with(RestraintSlot.HEAD, applied(RestraintDefinitions.BUNDLE));

        RestrictionPolicy composed = RestrictionResolver.resolve(state, RestraintDefinitions.PILLORY);

        assertTrue(composed.obscureVision(), "the hood is worn");
        assertFalse(composed.permits(RestraintAction.VOLUNTARY_MOVEMENT), "the pillory holds them");
        assertTrue(state.slot(RestraintSlot.ARMS).isEmpty(),
                "a device must not consume a body slot (§7.1: separate from head-slot gear)");
    }

    @Test
    void identicalSourcesDoNotCompound() {
        RestrictionPolicy once = policy(RestraintDefinitions.HANDCUFFS_ARMS);
        assertEquals(once, once.and(once), "two identical effects must not multiply");
        assertEquals(once.and(policy(RestraintDefinitions.SHACKLES_LEGS)),
                policy(RestraintDefinitions.SHACKLES_LEGS).and(once),
                "composition order must not change what a subject may do");
    }

    @Test
    void nothingRestrainsAnUnrestrainedSubject() {
        assertTrue(RestrictionResolver.resolve(null).unrestrictedPolicy());
        assertTrue(RestrictionResolver.resolve(state()).unrestrictedPolicy());
        assertTrue(RestrictionResolver.compose(null).unrestrictedPolicy());
    }

    @Test
    void anUnknownDefinitionRestrictsNothingRatherThanEverything() {
        assertTrue(RestrictionResolver.of(new ResourceLocation("mcacrime", "nope")).unrestrictedPolicy(),
                "a definition this build does not have must not silently imprison somebody");
    }

    @Test
    void noPolicyCanCancelAProtectedAction() {
        RestrictionPolicy everything = RestrictionResolver.compose(
                RestraintDefinitions.all().stream().map(RestraintDefinition::id).toList());

        for (ProtectedAction action : ProtectedAction.values()) {
            assertTrue(RestrictionResolver.allows(everything, action),
                    action + " must survive every restraint at once, or a restraint is permanent");
        }
    }

    private static PhysicalRestraintState state() {
        return PhysicalRestraintState.empty(UUID.randomUUID(), true,
                new ResourceLocation("minecraft", "overworld"));
    }

    private static AppliedRestraint applied(ResourceLocation definitionId) {
        return AppliedRestraint.of(RestraintDefinitions.get(definitionId).orElseThrow(), null,
                RestraintApplier.system(), AppliedRestraint.ApplicationContext.LAWFUL,
                AppliedRestraint.Provenance.SYSTEM_ISSUED, AppliedRestraint.ReturnPolicy.NONE, null, 0L);
    }
}
