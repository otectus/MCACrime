package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.compat.TownsteadEquipmentProvenance.Origin;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The duplicate-hoe fix, as the rules it actually consists of.
 *
 * <h2>The bug</h2>
 *
 * <p>Townstead dresses a villager for their shift: it stashes a copy of whatever they were holding in
 * a map of its own and puts a <em>copy</em> of the matching inventory tool in their hand. MCA: Crime's
 * death loot decides what to drop by reference identity — a hand item that is not the same object as
 * an inventory stack is gear the villager owns and would otherwise be lost. That rule is right for MCA
 * and misreads both of Townstead's copies: the display tool became a second hoe that never existed,
 * and the stashed original became an item nobody dropped at all.
 *
 * <h2>Why this file has no {@code ItemStack} in it</h2>
 *
 * <p>Every case below is a decision, and not one of them depends on what the item is. That is not an
 * accident of the test — it is the fix. The moment any of this consulted item <em>equality</em>, a
 * villager who genuinely owned two iron hoes would lose one, which is a worse bug than the one being
 * fixed. So the rules are stated over provenance alone and the stack arithmetic stays where it already
 * was: {@code DeathLoot.missingEquipment}, which reconciles MCA: Crime's additions against what MCA
 * already dropped and which the stash deliberately does not go through.
 *
 * <p>Naming no Minecraft type also keeps this file independent of the unit-test runner: it asserts
 * the rules of the fix and nothing about the game, so it answers the same way whether or not anything
 * else in the suite has bootstrapped a registry.
 */
class EquipmentProvenanceTest {

    /**
     * The drop count, assembled exactly as {@code VillagerDeathLoot.onDrops} assembles it.
     *
     * <p>Three contributions and no subtraction anywhere: whatever MCA dropped out of the inventory,
     * one per equipped stack this mod is responsible for, and the stashed original if Townstead is
     * holding one. The absence of a fourth term is the assertion — there is no deduplication step, and
     * nothing here can remove an item from an inventory.
     *
     * @param mcaInventoryDrops items MCA dropped from the villager's own inventory
     * @param equipped          the provenance verdict for each occupied equipment slot
     * @param stashHeld         whether Townstead is holding an original nothing else will drop
     */
    private static int drops(int mcaInventoryDrops, List<Origin> equipped, boolean stashHeld) {
        int total = mcaInventoryDrops;
        for (Origin origin : equipped) {
            if (origin.dropsAsEquipment()) {
                total++;
            }
        }
        return stashHeld ? total + 1 : total;
    }

    // ---------------------------------------------------------------------------------------------
    // Classification
    // ---------------------------------------------------------------------------------------------

    /**
     * Inventory ownership is settled before Townstead is asked, and settled the same way whether or not
     * a settlement mod is installed.
     */
    @Test
    void anInventoryStackIsAMirrorWhateverElseIsKnown() {
        assertSame(Origin.INVENTORY_MIRROR, TownsteadEquipmentProvenance.classify(true, false, false));
        assertSame(Origin.INVENTORY_MIRROR, TownsteadEquipmentProvenance.classify(true, true, false));
        assertSame(Origin.INVENTORY_MIRROR, TownsteadEquipmentProvenance.classify(true, true, true));
    }

    /** No record, no opinion — and no opinion still drops, which is what this mod always did. */
    @Test
    void anUntrackedVillagerFallsBackToTheOldRule() {
        assertSame(Origin.UNKNOWN, TownsteadEquipmentProvenance.classify(false, false, false));
        assertSame(Origin.UNKNOWN, TownsteadEquipmentProvenance.classify(false, false, true),
                "a display flag means nothing for a villager nothing is recorded about");
    }

    /** A tracked villager holding something other than the display copy is holding their own gear. */
    @Test
    void aTrackedVillagersOtherGearIsStillPhysical() {
        assertSame(Origin.PHYSICAL, TownsteadEquipmentProvenance.classify(false, true, false));
    }

    /** The display copy, and the only thing in the hand that is not an item. */
    @Test
    void theDisplayCopyIsTheOneThingThatIsNotAnItem() {
        assertSame(Origin.TEMPORARY_DISPLAY, TownsteadEquipmentProvenance.classify(false, true, true));
    }

    /**
     * Exactly two origins are somebody else's responsibility, and the other two are this mod's.
     *
     * <p>Stated as a set rather than four separate assertions because the risk is a <em>new</em> origin
     * defaulting the wrong way: the safe default is to drop, and the only two exceptions are the case
     * where the inventory will drop it and the case where no item exists.
     */
    @Test
    void onlyAMirrorAndAPropAreSomebodyElsesProblem() {
        Set<Origin> mine = EnumSet.noneOf(Origin.class);
        for (Origin origin : Origin.values()) {
            if (origin.dropsAsEquipment()) {
                mine.add(origin);
            }
        }
        assertEquals(EnumSet.of(Origin.PHYSICAL, Origin.UNKNOWN), mine);
        assertTrue(Origin.UNKNOWN.dropsAsEquipment(),
                "not knowing must never delete an item; it must behave as this mod did before");
    }

    // ---------------------------------------------------------------------------------------------
    // What reaches the ground
    // ---------------------------------------------------------------------------------------------

    /**
     * The ordinary villager, with no settlement mod anywhere near them.
     *
     * <p>MCA drops the hoe out of the inventory; the hoe in the hand is the very same object, so this
     * mod adds nothing. One hoe.
     */
    @Test
    void oneInventoryHoeDropsExactlyOnce() {
        assertEquals(1, drops(1, List.of(Origin.INVENTORY_MIRROR), false));
    }

    /**
     * The bug itself: a villager who died on shift.
     *
     * <p>The hand holds Townstead's copy of the inventory hoe and Townstead's map holds the sword they
     * were carrying before the shift. The real hoe drops once, the sword drops once, and the copy never
     * touches the ground.
     */
    @Test
    void displayCopyAndStashDropOnlyTheOriginals() {
        assertEquals(2, drops(1, List.of(Origin.TEMPORARY_DISPLAY), true),
                "one hoe from the inventory and one stashed sword: the display copy is not an item");
        assertEquals(1, drops(1, List.of(Origin.TEMPORARY_DISPLAY), false),
                "with nothing stashed, only the inventory hoe drops");
    }

    /**
     * Two iron hoes, and both of them are real.
     *
     * <p>The villager owned a spare and was carrying it when their shift started, so Townstead stashed
     * it and put a copy of the inventory one in their hand. This is arithmetically identical to the
     * case above, and that identity <em>is</em> the property: nothing in the path looks at what the item
     * is, so a second hoe cannot be mistaken for the first one seen twice. A fix that deduplicated by
     * equality would have made this case produce one hoe.
     */
    @Test
    void twoIdenticalToolsBothDrop() {
        assertEquals(2, drops(1, List.of(Origin.TEMPORARY_DISPLAY), true));
    }

    /**
     * Unknown provenance adds, and only adds.
     *
     * <p>There is no path in this mod that removes an item from a villager's inventory, and the whole
     * fallback rests on that: the worst case of a wrong guess has to be a duplicate rather than a loss.
     * A villager with a hoe in the inventory and an unreadable sword in hand ends with both.
     */
    @Test
    void unknownNeverRemovesFromTheInventory() {
        assertEquals(2, drops(1, List.of(Origin.UNKNOWN), false));
        assertEquals(1, drops(1, List.of(), false), "an empty hand adds nothing either");
    }

    // ---------------------------------------------------------------------------------------------
    // The seam with no Townstead behind it
    // ---------------------------------------------------------------------------------------------

    /**
     * Reading the switch answers rather than throwing, whatever state the config is in.
     *
     * <p>This is read from inside a death handler, so the guarded read is the point: an operator who
     * turned the switch off, a dedicated CLI with no config at all, and a running server all have to
     * get an answer rather than an exception.
     *
     * <p>Deliberately not asserted as "off". The Forge 1.20.1 baseline can assert that, because a
     * plain JUnit run there has no config spec loaded and the guarded read falls through to false.
     * These tests run under ModDevGradle's NeoForge runner, which boots the mod loader and therefore
     * does load the common config, so the same call legitimately answers the configured default. The
     * property being pinned is the same one in both: this read is total.
     */
    @Test
    void readingTheSwitchNeverThrows() {
        assertDoesNotThrow(TownsteadEquipmentProvenance::active);
    }

    /** Nothing is recorded for a villager nobody mentioned, and asking is not an error. */
    @Test
    void anUnmentionedVillagerIsUntracked() {
        UUID villager = UUID.randomUUID();
        assertFalse(TownsteadEquipmentProvenance.tracked(villager));
        assertFalse(TownsteadEquipmentProvenance.tracked(null));
        TownsteadEquipmentProvenance.forget(villager);
        TownsteadEquipmentProvenance.forget(null);
        assertFalse(TownsteadEquipmentProvenance.tracked(villager));
    }

    /**
     * A null or empty recording is not a recording.
     *
     * <p>Both reach here from inside Townstead's own method: {@code displayTool} is handed whatever the
     * redirect produced, and Townstead stashes an <em>empty</em> stack when the villager was already
     * holding the right tool. Storing that would claim there is an original to drop when there is not.
     */
    @Test
    void nothingIsRecordedForNothing() {
        UUID villager = UUID.randomUUID();
        TownsteadEquipmentProvenance.displayTool(villager, null);
        TownsteadEquipmentProvenance.stashedOriginal(villager, null);
        TownsteadEquipmentProvenance.displayTool(null, null);
        assertFalse(TownsteadEquipmentProvenance.tracked(villager));
        assertEquals(0, TownsteadEquipmentProvenance.size());
    }

    /** The classification of nothing is UNKNOWN, which drops — the safe direction, again. */
    @Test
    void classifyingNothingIsUnknown() {
        assertSame(Origin.UNKNOWN, TownsteadEquipmentProvenance.classify(null, null, null));
    }
}
