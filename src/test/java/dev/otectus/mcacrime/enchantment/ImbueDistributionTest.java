package dev.otectus.mcacrime.enchantment;

import dev.otectus.mcacrime.restraint.AppliedRestraint;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import dev.otectus.mcacrime.restraint.RestraintApplier;
import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three confirmed Imbue defects, asserted as arithmetic and as set membership (0.7.5 M6.1).
 *
 * <p>Split exactly where the production code is split: {@link ImbueMath} owns the budget and
 * {@link ImbueIndex} owns who shares it, so "a prisoner restrained twice by the same captor is hurt
 * twice" and "the n-th recipient absorbs everybody's share" are separate, provable questions rather
 * than one untestable event handler. The re-entrancy guard is the third defect and lives in
 * {@link ImbueHandler}; what is assertable here is the property that makes it terminate — a transfer
 * never exceeds the damage that caused it, at any level, with any number of recipients.
 */
class ImbueDistributionTest {

    private static final double PER_LEVEL = ImbueMath.DEFAULT_PER_LEVEL;
    private static final double CAP = ImbueMath.DEFAULT_MAX_FRACTION;
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    @BeforeEach
    @AfterEach
    void clearIndex() {
        ImbueIndex.clear();
    }

    // ------------------------------------------------------------------ the budget

    @Test
    void noRecipientsMovesNothing() {
        ImbueMath.Distribution split = ImbueMath.distribute(10.0F, 1, PER_LEVEL, CAP, 0, 8);
        assertFalse(split.moves());
        assertEquals(10.0F, split.retained(), 1.0E-6F, "the captor keeps everything");
        assertEquals(0, split.recipients());
    }

    @Test
    void oneRecipientTakesTheWholeBudget() {
        ImbueMath.Distribution split = ImbueMath.distribute(10.0F, 1, PER_LEVEL, CAP, 1, 8);
        assertEquals(2.666F, split.transferred(), 1.0E-3F);
        assertEquals(split.transferred(), split.perRecipient(), 1.0E-6F);
        assertEquals(10.0F - split.transferred(), split.retained(), 1.0E-5F);
    }

    /** The in-loop accumulator defect: with k recipients upstream moves roughly k times the budget. */
    @Test
    void manyRecipientsSplitOneBudgetRatherThanMultiplyingIt() {
        for (int recipients = 1; recipients <= 8; recipients++) {
            ImbueMath.Distribution split =
                    ImbueMath.distribute(12.0F, 1, PER_LEVEL, CAP, recipients, 8);
            float total = split.perRecipient() * split.recipients();
            assertEquals(split.transferred(), total, 1.0E-4F,
                    "the shares must add up to one budget with " + recipients + " recipients");
            assertEquals(12.0F * PER_LEVEL, split.transferred(), 1.0E-3F,
                    "the budget must not grow with the number of recipients");
            assertEquals(12.0F, split.retained() + split.transferred(), 1.0E-4F,
                    "nothing is created or destroyed");
        }
    }

    @Test
    void theBudgetNeverExceedsTheDamageThatCausedIt() {
        for (int level = 1; level <= 5; level++) {
            for (int recipients = 1; recipients <= 16; recipients++) {
                ImbueMath.Distribution split =
                        ImbueMath.distribute(20.0F, level, PER_LEVEL, CAP, recipients, 32);
                assertTrue(split.transferred() <= 20.0F, "level " + level + " transferred more than dealt");
                assertTrue(split.retained() >= 0.0F, "the captor's share went negative");
                assertTrue(split.retained() > 0.0F,
                        "the cap must leave the captor some of the damage at level " + level);
            }
        }
    }

    /** The unbounded-level defect: an unclamped level 40 would transfer more than ten times the hit. */
    @Test
    void aLevelAboveTheCapIsStillCapped() {
        ImbueMath.Distribution huge = ImbueMath.distribute(10.0F, 40, PER_LEVEL, CAP, 1, 8);
        assertEquals(10.0F * CAP, huge.transferred(), 1.0E-4F);
        assertEquals(10.0F * (1.0D - CAP), huge.retained(), 1.0E-4F);
    }

    @Test
    void moreRecipientsThanTheConfiguredMaximumSimplyDoNotShare() {
        ImbueMath.Distribution split = ImbueMath.distribute(10.0F, 1, PER_LEVEL, CAP, 20, 4);
        assertEquals(4, split.recipients());
        assertEquals(split.transferred() / 4.0F, split.perRecipient(), 1.0E-5F);
    }

    @Test
    void cancelledOrZeroDamageMovesNothing() {
        assertFalse(ImbueMath.distribute(0.0F, 1, PER_LEVEL, CAP, 3, 8).moves());
        assertFalse(ImbueMath.distribute(-4.0F, 1, PER_LEVEL, CAP, 3, 8).moves());
        assertFalse(ImbueMath.distribute(10.0F, 0, PER_LEVEL, CAP, 3, 8).moves());
    }

    @Test
    void nonFiniteInputIsRejectedRatherThanPropagated() {
        for (float bad : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            ImbueMath.Distribution split = ImbueMath.distribute(bad, 1, PER_LEVEL, CAP, 2, 8);
            assertFalse(split.moves());
            assertTrue(Float.isFinite(split.retained()), "a NaN amount must not become a NaN health bar");
            assertTrue(Float.isFinite(split.perRecipient()));
        }
        assertFalse(ImbueMath.distribute(10.0F, 1, Double.NaN, CAP, 2, 8).moves());
        assertFalse(ImbueMath.distribute(10.0F, 1, PER_LEVEL, Double.NaN, 2, 8).moves());
    }

    // ------------------------------------------------------------------ the recipients

    /** The duplicate-recipient defect: arms and legs by the same captor is one prisoner, not two. */
    @Test
    void oneSubjectRestrainedInTwoSlotsByTheSameCaptorIsOneRecipient() {
        UUID captor = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        ImbueIndex.rebuild(List.of(state(subject, Map.of(
                RestraintSlot.ARMS, imbued(captor),
                RestraintSlot.LEGS, imbued(captor)))));

        assertEquals(List.of(subject), ImbueIndex.recipients(captor));
    }

    @Test
    void twoSubjectsHeldByOneCaptorBothShare() {
        UUID captor = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ImbueIndex.rebuild(List.of(
                state(first, Map.of(RestraintSlot.ARMS, imbued(captor))),
                state(second, Map.of(RestraintSlot.LEGS, imbued(captor)))));

        assertEquals(2, ImbueIndex.recipients(captor).size());
        assertTrue(ImbueIndex.recipients(captor).containsAll(List.of(first, second)));
    }

    @Test
    void anUnenchantedRestraintPutsNobodyInTheIndex() {
        UUID captor = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        ImbueIndex.rebuild(List.of(state(subject, Map.of(RestraintSlot.ARMS, plain(captor)))));

        assertTrue(ImbueIndex.empty());
        assertEquals(List.of(), ImbueIndex.recipients(captor));
    }

    @Test
    void aSelfAppliedImbueRestraintTransfersToNobody() {
        UUID subject = UUID.randomUUID();
        ImbueIndex.rebuild(List.of(state(subject, Map.of(RestraintSlot.ARMS, imbued(subject)))));

        assertTrue(ImbueIndex.empty());
    }

    @Test
    void systemIssuedAndDeviceAppliedRestraintsHaveNoCaptorToRelieve() {
        UUID subject = UUID.randomUUID();
        AppliedRestraint issued = new AppliedRestraint(UUID.randomUUID(),
                RestraintDefinitions.HANDCUFFS_ARMS, imbuedStack(), 40, 1, RestraintApplier.none(),
                AppliedRestraint.ApplicationContext.LAWFUL, AppliedRestraint.Provenance.SYSTEM_ISSUED,
                AppliedRestraint.ReturnPolicy.NONE, null, 1L, 1L);
        ImbueIndex.rebuild(List.of(state(subject, Map.of(RestraintSlot.ARMS, issued))));

        assertTrue(ImbueIndex.empty());
    }

    /** Two captors holding each other: both are recipients, and neither is their own. */
    @Test
    void mutuallyBoundSubjectsEachAppearUnderTheOther() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ImbueIndex.rebuild(List.of(
                state(first, Map.of(RestraintSlot.ARMS, imbued(second))),
                state(second, Map.of(RestraintSlot.ARMS, imbued(first)))));

        assertEquals(List.of(first), ImbueIndex.recipients(second));
        assertEquals(List.of(second), ImbueIndex.recipients(first));
        assertFalse(ImbueIndex.recipients(first).contains(first));
    }

    @Test
    void rebuildingReplacesTheIndexRatherThanAccumulating() {
        UUID captor = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        ImbueIndex.rebuild(List.of(state(subject, Map.of(RestraintSlot.ARMS, imbued(captor)))));
        assertFalse(ImbueIndex.empty());

        ImbueIndex.rebuild(List.of());
        assertTrue(ImbueIndex.empty(), "a released prisoner stops sharing on the next pass");
    }

    @Test
    void theLevelReadFromAWornInstanceIsClampedByConfiguration() {
        AppliedRestraint overEnchanted = new AppliedRestraint(UUID.randomUUID(),
                RestraintDefinitions.HANDCUFFS_ARMS, imbuedStack(40), 40, 1,
                RestraintApplier.player(UUID.randomUUID()), AppliedRestraint.ApplicationContext.UNLAWFUL,
                AppliedRestraint.Provenance.PLAYER_OWNED, AppliedRestraint.ReturnPolicy.NONE, null, 1L, 1L);

        assertEquals(40, RestraintEnchantments.rawLevel(overEnchanted.itemSnapshot(),
                CrimeEnchantKind.IMBUE), "the NBT says what it says");
        assertEquals(EnchantmentApplicability.DEFAULT_MAX_LEVEL,
                RestraintEnchantments.levelOn(overEnchanted, CrimeEnchantKind.IMBUE),
                "what the game reads is clamped to the configured maximum");
    
        // 1.21.1 only: ItemEnchantments.CODEC accepts the bare level map as an alternative to the
        // {levels: ...} record, so a snapshot written in either shape has to read the same.
        assertEquals(40, RestraintEnchantments.rawLevel(imbuedStackBareLevels(40),
                CrimeEnchantKind.IMBUE), "the alternative component shape reads the same");
    }

    // ------------------------------------------------------------------ fixtures

    private static PhysicalRestraintState state(UUID subject, Map<RestraintSlot, AppliedRestraint> worn) {
        return new PhysicalRestraintState(subject, true, OVERWORLD, 1L, 1L,
                new EnumMap<>(worn), null, null, null);
    }

    private static AppliedRestraint imbued(UUID captor) {
        return new AppliedRestraint(UUID.randomUUID(), RestraintDefinitions.HANDCUFFS_ARMS, imbuedStack(),
                40, 1, RestraintApplier.player(captor), AppliedRestraint.ApplicationContext.UNLAWFUL,
                AppliedRestraint.Provenance.PLAYER_OWNED, AppliedRestraint.ReturnPolicy.NONE, null, 1L, 1L);
    }

    private static AppliedRestraint plain(UUID captor) {
        return new AppliedRestraint(UUID.randomUUID(), RestraintDefinitions.HANDCUFFS_ARMS, null,
                40, 1, RestraintApplier.player(captor), AppliedRestraint.ApplicationContext.UNLAWFUL,
                AppliedRestraint.Provenance.PLAYER_OWNED, AppliedRestraint.ReturnPolicy.NONE, null, 1L, 1L);
    }

    private static CompoundTag imbuedStack() {
        return imbuedStack(1);
    }

    /**
     * The shape {@code ItemStack.save} writes on 1.21.1.
     *
     * <p>There is no item NBT and no {@code "tag"} compound here: a saved stack is
     * {@code {id, count, components}} and the enchantments are the {@code minecraft:enchantments}
     * component, whose codec writes {@code {levels: {"<id>": n}}}. Same fact, different shelf.
     */
    private static CompoundTag imbuedStack(int level) {
        CompoundTag levels = new CompoundTag();
        levels.putInt("mcacrime:imbue", level);
        CompoundTag enchantments = new CompoundTag();
        enchantments.put("levels", levels);
        CompoundTag components = new CompoundTag();
        components.put("minecraft:enchantments", enchantments);
        CompoundTag stack = new CompoundTag();
        stack.putString("id", "mcacrime:restraint_cuffs");
        stack.putInt("count", 1);
        stack.put("components", components);
        return stack;
    }

    /** The codec's alternative shape - a bare level map - is read the same way. */
    private static CompoundTag imbuedStackBareLevels(int level) {
        CompoundTag enchantments = new CompoundTag();
        enchantments.putInt("mcacrime:imbue", level);
        CompoundTag components = new CompoundTag();
        components.put("minecraft:enchantments", enchantments);
        CompoundTag stack = new CompoundTag();
        stack.putString("id", "mcacrime:restraint_cuffs");
        stack.putInt("count", 1);
        stack.put("components", components);
        return stack;
    }
}
