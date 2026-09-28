package dev.otectus.mcacrime.gametest;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.enchantment.CrimeEnchantKind;
import dev.otectus.mcacrime.enchantment.CrimeEnchantments;
import dev.otectus.mcacrime.enchantment.EnchantmentApplicability;
import dev.otectus.mcacrime.item.CrimeItems;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The five enchantments as datapack entries, on a real server (0.7.5 P6.1, plan section 7.2).
 *
 * <p>Unique to this line, and it is the test the plan's caution paragraph asks for. The Forge 1.20.1
 * baseline registers five {@code Enchantment} objects and can assert their applicability with a plain
 * unit test; here they are {@code data/mcacrime/enchantment/*.json}, loaded by the datapack system
 * into a dynamic registry, and "the JSON parses, resolves, and supports the items it says it does" is
 * a claim only a booted server can settle. A missing or malformed file would otherwise show up as an
 * enchantment that simply never appears, with nothing in the log about it.
 *
 * <p>The applicability <em>table</em> is still asserted without a registry in
 * {@code T:enchantment/EnchantmentApplicabilityTest}, because it is pure. What is proved here is that
 * the data agrees with the table: the same yes and the same no, from the registry side.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EnchantmentApplicabilityGameTests {

    private EnchantmentApplicabilityGameTests() {
    }

    /**
     * All six resolve, and each carries the level ceiling the config half agrees with.
     *
     * <p>A {@code ResourceKey} exists whether or not the file does — that is the whole reason
     * {@code CrimeEnchantments.holder} answers with an {@code Optional} — so "the key is there" proves
     * nothing. Resolving it against the world's registry is what proves the datapack entry shipped.
     */
    @GameTest(template = "platform", timeoutTicks = 200, batch = "enchantments")
    public static void allSixEnchantmentDefinitionsResolveFromTheDatapack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (CrimeEnchantKind kind : CrimeEnchantKind.values()) {
            Holder.Reference<Enchantment> holder = CrimeEnchantments.holder(level, kind).orElse(null);
            helper.assertTrue(holder != null,
                    "mcacrime:" + kind.path() + " has no enchantment definition in the registry");
            helper.assertTrue(holder.value().getMaxLevel() >= 1,
                    "mcacrime:" + kind.path() + " declares a max_level below one");
            helper.assertTrue(EnchantmentApplicability.maxLevel(kind) >= 1,
                    "the configured maximum for " + kind.path() + " is below one");
        }
        helper.succeed();
    }

    /**
     * The data's {@code supported_items} agrees with the pure table, both ways.
     *
     * <p>The five restraint enchantments go on a restraint and not on a stick. A tag that named the
     * wrong item list would let a player put one on an ordinary tool — the exact defect the
     * applicability rules exist to rule out, and one that "it compiled" cannot catch when the rule
     * lives in JSON.
     */
    @GameTest(template = "platform", timeoutTicks = 200, batch = "enchantments")
    public static void theSupportedItemTagsMatchTheApplicabilityTable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack restraint = new ItemStack(CrimeItems.RESTRAINT_CUFFS.get());
        ItemStack unrelated = new ItemStack(Items.STICK);

        for (CrimeEnchantKind kind : CrimeEnchantKind.values()) {
            Holder.Reference<Enchantment> holder = CrimeEnchantments.holder(level, kind).orElseThrow();
            boolean onRestraint = restraint.supportsEnchantment(holder);

            helper.assertTrue(onRestraint == (kind.carrier() == CrimeEnchantKind.Carrier.RESTRAINT),
                    kind.path() + " on a restraint: data says " + onRestraint
                            + ", the table says " + (kind.carrier() == CrimeEnchantKind.Carrier.RESTRAINT));
            helper.assertTrue(!unrelated.supportsEnchantment(holder),
                    kind.path() + " is applicable to a stick");

            // And the same question asked the way the pure table asks it.
            helper.assertTrue(EnchantmentApplicability.appliesTo(kind,
                            CrimeEnchantments.carrierOf(restraint)) == onRestraint,
                    "the table and the data disagree about " + kind.path() + " on a restraint");
        }
        helper.succeed();
    }

    /**
     * A level written onto a real stack is read back clamped, through the registry.
     *
     * <p>The counterpart of the snapshot read the unit suite covers. A command or a datapack can put
     * level forty on an item; what the game acts on is the configured maximum, and that clamp has to
     * hold on the live-stack path as well as on the persisted one or Imbue's transfer fraction escapes
     * exactly as it does upstream.
     */
    @GameTest(template = "platform", timeoutTicks = 200, batch = "enchantments")
    public static void anOverEnchantedStackIsReadBackClamped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Holder.Reference<Enchantment> imbue =
                CrimeEnchantments.holder(level, CrimeEnchantKind.IMBUE).orElseThrow();

        ItemStack cuffs = new ItemStack(CrimeItems.RESTRAINT_CUFFS.get());
        cuffs.enchant(imbue, 40);

        helper.assertTrue(EnchantmentHelper.getItemEnchantmentLevel(imbue, cuffs) == 40,
                "the stack does not carry what was written to it");
        helper.assertTrue(CrimeEnchantments.levelOn(level, cuffs, CrimeEnchantKind.IMBUE)
                        == EnchantmentApplicability.DEFAULT_MAX_LEVEL,
                "the level the game acts on was not clamped to the configured maximum");
        helper.assertTrue(CrimeEnchantments.levelOn(level, new ItemStack(Items.STICK),
                CrimeEnchantKind.IMBUE) == 0, "an unenchanted item reported a level");
        helper.succeed();
    }
}
