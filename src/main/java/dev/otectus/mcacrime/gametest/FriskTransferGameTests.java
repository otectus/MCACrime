package dev.otectus.mcacrime.gametest;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.frisk.FriskRefusal;
import dev.otectus.mcacrime.frisk.FriskRevision;
import dev.otectus.mcacrime.frisk.FriskSession;
import dev.otectus.mcacrime.frisk.FriskSlotKind;
import dev.otectus.mcacrime.frisk.FriskSlotRef;
import dev.otectus.mcacrime.frisk.SeizureKind;
import dev.otectus.mcacrime.frisk.SeizureLedger;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Confiscation on a real server (plan section 7.2).
 *
 * <p>What a unit test cannot reach here is the <em>registry</em>. In 1.21 a stack's enchantments,
 * its name and its damage are data components holding registry entries, so the revision a screen
 * quotes back is only meaningful with a world behind it — which is what these tests supply.
 *
 * <p>The two properties asserted are the ones §11.2 rests on: a transfer that has already been
 * applied, or that names the revision of a slot somebody else has since emptied, is refused rather
 * than replayed.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FriskTransferGameTests {

    private FriskTransferGameTests() {
    }

    /** A sword with a registry-backed enchantment, a custom name and damage on it. */
    private static ItemStack markedSword(ServerLevel level) {
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        HolderLookup.RegistryLookup<net.minecraft.world.item.enchantment.Enchantment> enchantments =
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        sword.enchant(enchantments.getOrThrow(Enchantments.SHARPNESS), 3);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Evidence"));
        sword.setDamageValue(17);
        return sword;
    }

    /**
     * The replay guard, keyed exactly as the packet is.
     *
     * <p>A transfer id is a pure function of the session, the slot, the revision and the count, so a
     * resent packet produces the same id and is recognised. The bound is the session's own memory,
     * which is why the id has to be derived rather than allocated.
     */
    @GameTest(template = "platform", timeoutTicks = 100)
    public static void aReplayedTransferIsRecognisedAndRefused(GameTestHelper helper) {
        FriskSession session = session(helper.getLevel());
        UUID first = SeizureLedger.transferId(session.id(), 3, 991, 1);
        helper.assertTrue(first.equals(SeizureLedger.transferId(session.id(), 3, 991, 1)),
                "the same packet produced two different transfer ids");
        helper.assertTrue(!session.alreadyApplied(first), "a fresh session already knows this transfer");
        session.markApplied(first);
        helper.assertTrue(session.alreadyApplied(first),
                "a replayed packet would be applied a second time");
        helper.assertTrue(!session.alreadyApplied(SeizureLedger.transferId(session.id(), 3, 991, 2)),
                "a different count is a different transfer and must not be swallowed by the guard");
        helper.succeed();
    }

    /**
     * Two searchers, one stack: the second sees a revision that no longer matches.
     *
     * <p>The revision is built from the live stack's item, count and components, so a stack somebody
     * else has just taken from cannot be taken from again on the strength of the old screen. Run in a
     * world because the component patch it hashes is only meaningful with a registry behind it.
     */
    @GameTest(template = "platform", timeoutTicks = 100)
    public static void aConcurrentSearcherSeesAStaleRevision(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack live = markedSword(level);
        live.setCount(1);
        int shown = FriskRevision.of(live);
        helper.assertTrue(shown != FriskRevision.EMPTY, "an occupied slot read as empty");
        helper.assertTrue(shown == FriskRevision.of(live.copy()),
                "two reads of the same stack disagreed, so no screen could ever quote one");

        ItemStack changed = live.copy();
        changed.setDamageValue(18);
        helper.assertTrue(FriskRevision.of(changed) != shown,
                "a component change left the revision unchanged; a stale screen could still take it");
        helper.assertTrue(FriskRevision.of(ItemStack.EMPTY) == FriskRevision.EMPTY,
                "an emptied slot must read as empty whatever used to be in it");

        // And the refusal the mismatch produces is the one the player is told about.
        helper.assertTrue(FriskRefusal.SLOT_CHANGED.messageKey()
                        .equals("mcacrime.msg.frisk.slot_changed"),
                "the refusal names a lang key the screen does not ship");
        helper.succeed();
    }

    private static FriskSession session(ServerLevel level) {
        return new FriskSession(1L, UUID.randomUUID(), UUID.randomUUID(), 42, 0L,
                level.dimension().location(),
                null, 0L, List.of(new FriskSlotRef("player", FriskSlotKind.MAIN, 3)),
                SeizureKind.LAWFUL_SEARCH, level.getGameTime() + 200L);
    }
}
