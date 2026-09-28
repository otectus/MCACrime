package dev.otectus.mcacrime.restraint;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ten definitions: their ids, their items, their keys, their durability and their statistics.
 *
 * <p>The item mapping is the part worth pinning down hardest, because it is counter-intuitive on
 * purpose (§3.1): {@code mcacrime:restraint_locked_cuffs} — MCA: Crime's protected locked-cuff icon —
 * is the <em>handcuff</em> family, and {@code mcacrime:restraint_cuffs} is the <em>shackle</em>
 * family. Getting that backwards would silently swap every existing stack's behaviour on upgrade.
 */
class RestraintDefinitionsTest {

    private static RestraintDefinition definition(ResourceLocation id) {
        return RestraintDefinitions.get(id).orElseThrow(() -> new AssertionError("no definition " + id));
    }

    @Test
    void thereAreExactlyNineDefinitionsAndTheirIdsAreTheAppendixOnes() {
        List<RestraintDefinition> all = RestraintDefinitions.all();
        assertEquals(9, all.size());

        Set<ResourceLocation> ids = new HashSet<>();
        all.forEach(definition -> assertTrue(ids.add(definition.id()),
                "two definitions share the id " + definition.id()));
        assertEquals(Set.of(
                RestraintDefinitions.HANDCUFFS_ARMS,
                RestraintDefinitions.HANDCUFFS_LEGS,
                RestraintDefinitions.SHACKLES_ARMS,
                RestraintDefinitions.SHACKLES_LEGS,
                RestraintDefinitions.DUCK_TAPE_ARMS,
                RestraintDefinitions.DUCK_TAPE_LEGS,
                RestraintDefinitions.DUCK_TAPE_HEAD,
                RestraintDefinitions.BUNDLE,
                RestraintDefinitions.PILLORY), ids);
    }

    @Test
    void theTwoProtectedItemIdsKeepTheirPathsAndSwapFamilies() {
        assertEquals("mcacrime:restraint_locked_cuffs", RestraintDefinitions.ITEM_LOCKED_CUFFS.toString());
        assertEquals("mcacrime:restraint_cuffs", RestraintDefinitions.ITEM_CUFFS.toString());

        assertEquals(RestraintFamily.HANDCUFFS,
                definition(RestraintDefinitions.HANDCUFFS_ARMS).family().orElseThrow());
        assertEquals(RestraintDefinitions.ITEM_LOCKED_CUFFS,
                definition(RestraintDefinitions.HANDCUFFS_ARMS).item().orElseThrow());
        assertEquals(RestraintFamily.SHACKLES,
                definition(RestraintDefinitions.SHACKLES_ARMS).family().orElseThrow());
        assertEquals(RestraintDefinitions.ITEM_CUFFS,
                definition(RestraintDefinitions.SHACKLES_ARMS).item().orElseThrow());
    }

    @Test
    void oneItemServesBothSlotsOfItsFamily() {
        assertEquals(definition(RestraintDefinitions.HANDCUFFS_ARMS).item(),
                definition(RestraintDefinitions.HANDCUFFS_LEGS).item(),
                "the same cuffs on the legs must not need a second item");
        assertEquals(definition(RestraintDefinitions.SHACKLES_ARMS).item(),
                definition(RestraintDefinitions.SHACKLES_LEGS).item());
        assertEquals(RestraintDefinitions.ITEM_DUCK_TAPE,
                definition(RestraintDefinitions.DUCK_TAPE_HEAD).item().orElseThrow());
    }

    @Test
    void everyWearableDefinitionSitsOnTheSlotItsNameClaims() {
        assertEquals(RestraintSlot.ARMS, definition(RestraintDefinitions.HANDCUFFS_ARMS).slot().orElseThrow());
        assertEquals(RestraintSlot.ARMS, definition(RestraintDefinitions.SHACKLES_ARMS).slot().orElseThrow());
        assertEquals(RestraintSlot.ARMS, definition(RestraintDefinitions.DUCK_TAPE_ARMS).slot().orElseThrow());
        assertEquals(RestraintSlot.LEGS, definition(RestraintDefinitions.HANDCUFFS_LEGS).slot().orElseThrow());
        assertEquals(RestraintSlot.LEGS, definition(RestraintDefinitions.SHACKLES_LEGS).slot().orElseThrow());
        assertEquals(RestraintSlot.LEGS, definition(RestraintDefinitions.DUCK_TAPE_LEGS).slot().orElseThrow());
        assertEquals(RestraintSlot.HEAD, definition(RestraintDefinitions.DUCK_TAPE_HEAD).slot().orElseThrow());
        assertEquals(RestraintSlot.HEAD, definition(RestraintDefinitions.BUNDLE).slot().orElseThrow());

        assertTrue(definition(RestraintDefinitions.PILLORY).device(),
                "the pillory detains; it does not occupy a body slot (§7.1)");
        assertEquals(8, RestraintDefinitions.wearable().size());
        assertEquals(3, RestraintDefinitions.forSlot(RestraintSlot.ARMS).size());
        assertEquals(3, RestraintDefinitions.forSlot(RestraintSlot.LEGS).size());
        assertEquals(2, RestraintDefinitions.forSlot(RestraintSlot.HEAD).size());
    }

    @Test
    void durabilityIsTheConfiguredSourceValueAndNeverThePlaceholder() {
        assertEquals(40, definition(RestraintDefinitions.HANDCUFFS_ARMS).escape().durability());
        assertEquals(40, definition(RestraintDefinitions.HANDCUFFS_LEGS).escape().durability());
        assertEquals(15, definition(RestraintDefinitions.SHACKLES_ARMS).escape().durability());
        assertEquals(15, definition(RestraintDefinitions.SHACKLES_LEGS).escape().durability());
        assertEquals(5, definition(RestraintDefinitions.DUCK_TAPE_ARMS).escape().durability());
        assertEquals(5, definition(RestraintDefinitions.DUCK_TAPE_LEGS).escape().durability(),
                "leg tape has its own setting; reading the arm value is the upstream defect");
        RestraintDefinitions.all().forEach(definition ->
                assertFalse(definition.escape().durability() == 999,
                        definition.id() + " kept the placeholder durability from item registration"));
    }

    @Test
    void aKeyOpensAFamilyRatherThanADefinition() {
        List<RestraintDefinition> handcuffKey =
                RestraintDefinitions.openedBy(RestraintDefinitions.ITEM_HANDCUFFS_KEY);
        assertEquals(Set.of(RestraintDefinitions.HANDCUFFS_ARMS, RestraintDefinitions.HANDCUFFS_LEGS),
                handcuffKey.stream().map(RestraintDefinition::id).collect(java.util.stream.Collectors.toSet()),
                "one handcuffs key frees arm cuffs and leg cuffs (§3.7)");

        List<RestraintDefinition> shackleKey =
                RestraintDefinitions.openedBy(RestraintDefinitions.ITEM_SHACKLES_KEY);
        assertEquals(Set.of(RestraintDefinitions.SHACKLES_ARMS, RestraintDefinitions.SHACKLES_LEGS),
                shackleKey.stream().map(RestraintDefinition::id).collect(java.util.stream.Collectors.toSet()));

        assertFalse(definition(RestraintDefinitions.HANDCUFFS_ARMS)
                        .openedBy(RestraintDefinitions.ITEM_SHACKLES_KEY),
                "the wrong family's key must not open handcuffs");
        assertTrue(definition(RestraintDefinitions.DUCK_TAPE_ARMS).keyItem().isEmpty(),
                "tape has no metal key");
        assertTrue(definition(RestraintDefinitions.DUCK_TAPE_ARMS).escape().removableWithCuttingTool());
        assertFalse(definition(RestraintDefinitions.HANDCUFFS_ARMS).escape().removableWithCuttingTool());
    }

    @Test
    void pickProfilesCarryTheSourceParityNumbers() {
        assertEquals(6, definition(RestraintDefinitions.HANDCUFFS_ARMS).pick().progressIncrease());
        assertEquals(12, definition(RestraintDefinitions.HANDCUFFS_ARMS).pick().speedIncrease());
        assertEquals(6, definition(RestraintDefinitions.HANDCUFFS_LEGS).pick().progressIncrease());
        assertEquals(12, definition(RestraintDefinitions.HANDCUFFS_LEGS).pick().speedIncrease());
        assertEquals(8, definition(RestraintDefinitions.SHACKLES_ARMS).pick().progressIncrease());
        assertEquals(10, definition(RestraintDefinitions.SHACKLES_ARMS).pick().speedIncrease());

        assertFalse(definition(RestraintDefinitions.DUCK_TAPE_ARMS).pick().pickable(),
                "there is no lock on a strip of tape");
        assertFalse(definition(RestraintDefinitions.BUNDLE).pick().pickable());
    }

    @Test
    void theTwoNamedApplicationSoundsGoToTheirOwnFamilies() {
        assertEquals("mcacrime:restraint.apply_handcuffs",
                definition(RestraintDefinitions.HANDCUFFS_ARMS).applySound().orElseThrow().toString());
        assertEquals("mcacrime:restraint.apply_shackles",
                definition(RestraintDefinitions.SHACKLES_LEGS).applySound().orElseThrow().toString());
        assertTrue(definition(RestraintDefinitions.DUCK_TAPE_ARMS).applySound().isEmpty(),
                "the source names no tape sound, and inventing one would be a claim nobody made");
    }

    @Test
    void theFiveStatisticFamiliesKeepTheirSourceNames() {
        assertEquals("mcacrime:handcuffs_times_restrained",
                definition(RestraintDefinitions.HANDCUFFS_ARMS).statistics().timesRestrained().orElseThrow().toString());
        assertEquals("mcacrime:legcuffs_broken",
                definition(RestraintDefinitions.HANDCUFFS_LEGS).statistics().broken().orElseThrow().toString());
        assertEquals("mcacrime:shackles_time_spent_restrained",
                definition(RestraintDefinitions.SHACKLES_ARMS).statistics().timeSpentRestrained().orElseThrow().toString());
        assertEquals("mcacrime:leg_shackles_times_restrained",
                definition(RestraintDefinitions.SHACKLES_LEGS).statistics().timesRestrained().orElseThrow().toString());
        assertTrue(definition(RestraintDefinitions.BUNDLE).statistics().timesRestrained().isEmpty(),
                "the source counts no bundle statistic");
    }

    @Test
    void rigSupportIsAskedOfTheRigRatherThanAssumed() {
        RigProfile humanoid = RigProfile.vanillaHumanoid();
        RestraintDefinitions.wearable().forEach(definition ->
                assertTrue(definition.fits(humanoid), definition.id() + " does not fit a vanilla biped"));

        RigProfile armless = new RigProfile("armless", true, true, false, true, 1.0F);
        assertFalse(definition(RestraintDefinitions.HANDCUFFS_ARMS).fits(armless));
        assertTrue(definition(RestraintDefinitions.HANDCUFFS_LEGS).fits(armless));
        assertFalse(definition(RestraintDefinitions.HANDCUFFS_ARMS).fits(null));
    }

    @Test
    void lookupIsByIdAndUnknownIdsAreEmptyRatherThanSubstituted() {
        assertTrue(RestraintDefinitions.get(new ResourceLocation("mcacrime", "nope")).isEmpty());
        assertTrue(RestraintDefinitions.get(null).isEmpty());
        assertFalse(RestraintDefinitions.exists(null));
        assertTrue(RestraintDefinitions.exists(RestraintDefinitions.BUNDLE));
    }

    /** The upstream defect §6.2 names: a registry that hands out the object players then mutate. */
    @Test
    void theRegistryHandsOutTheSameImmutableDefinitionEveryTime() {
        assertSame(definition(RestraintDefinitions.HANDCUFFS_ARMS),
                definition(RestraintDefinitions.HANDCUFFS_ARMS));
        assertTrue(RestraintDefinition.class.isRecord(),
                "a definition has to be immutable, or two subjects share one durability counter");
    }
}
