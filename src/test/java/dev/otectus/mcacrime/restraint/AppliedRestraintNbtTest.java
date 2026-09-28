package dev.otectus.mcacrime.restraint;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One applied restraint, through NBT and back, and the isolation that makes instances instances.
 *
 * <p>The item is asserted as its saved {@link CompoundTag} rather than as an {@code ItemStack}, both
 * because an item registry does not exist in a unit test and because the tag <em>is</em> the
 * persisted form: a byte-identical compound cannot have quietly dropped an enchantment on the way
 * through, which is the failure a returned stack would hide.
 */
class AppliedRestraintNbtTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    /** An enchanted, damaged pair of cuffs in the shape {@code ItemStack.save} writes. */
    private static CompoundTag cuffStack() {
        CompoundTag enchantment = new CompoundTag();
        enchantment.putString("id", "minecraft:unbreaking");
        enchantment.putShort("lvl", (short) 2);
        net.minecraft.nbt.ListTag enchantments = new net.minecraft.nbt.ListTag();
        enchantments.add(enchantment);
        CompoundTag extra = new CompoundTag();
        extra.put("Enchantments", enchantments);
        extra.putInt("Damage", 3);
        CompoundTag stack = new CompoundTag();
        stack.putString("id", "mcacrime:restraint_locked_cuffs");
        stack.putByte("Count", (byte) 1);
        stack.put("tag", extra);
        return stack;
    }

    @Test
    void everyFieldRoundTrips() {
        UUID applier = UUID.randomUUID();
        UUID custody = UUID.randomUUID();
        AppliedRestraint restraint = new AppliedRestraint(UUID.randomUUID(),
                RestraintDefinitions.HANDCUFFS_ARMS, cuffStack(), 37, 1,
                RestraintApplier.player(applier), AppliedRestraint.ApplicationContext.LAWFUL,
                AppliedRestraint.Provenance.PLAYER_OWNED, AppliedRestraint.ReturnPolicy.RETURN_TO_APPLIER,
                custody, 4242L, 9L);

        AppliedRestraint loaded = AppliedRestraint.load(restraint.save()).orElseThrow();

        assertEquals(restraint, loaded, "the whole instance round-trips, not only its item");
        assertEquals(cuffStack(), loaded.itemSnapshot(), "the persisted item tag is not byte-identical");
        assertEquals(37, loaded.remainingDurability());
        assertEquals(applier, loaded.applier().entityId().orElseThrow());
        assertEquals(RestraintApplier.Kind.PLAYER, loaded.applier().kind());
        assertEquals(custody, loaded.custodyId());
        assertEquals(AppliedRestraint.ReturnPolicy.RETURN_TO_APPLIER, loaded.returnPolicy());
    }

    @Test
    void aDeviceApplierKeepsItsPosition() {
        AppliedRestraint restraint = AppliedRestraint.of(
                RestraintDefinitions.get(RestraintDefinitions.BUNDLE).orElseThrow(), null,
                RestraintApplier.device(OVERWORLD, new BlockPos(3, 64, -7)),
                AppliedRestraint.ApplicationContext.DEVICE, AppliedRestraint.Provenance.SYSTEM_ISSUED,
                AppliedRestraint.ReturnPolicy.NONE, null, 10L);

        AppliedRestraint loaded = AppliedRestraint.load(restraint.save()).orElseThrow();

        assertEquals(RestraintApplier.Kind.DEVICE, loaded.applier().kind());
        assertEquals(new BlockPos(3, 64, -7), loaded.applier().devicePos().orElseThrow());
        assertEquals(OVERWORLD, loaded.applier().deviceDimension().orElseThrow());
    }

    @Test
    void aSystemIssuedInstanceCarriesNoItemAndWritesNone() {
        AppliedRestraint restraint = AppliedRestraint.of(
                RestraintDefinitions.get(RestraintDefinitions.HANDCUFFS_ARMS).orElseThrow(), null,
                RestraintApplier.system(), AppliedRestraint.ApplicationContext.LAWFUL,
                AppliedRestraint.Provenance.SYSTEM_ISSUED, AppliedRestraint.ReturnPolicy.NONE, null, 0L);

        CompoundTag saved = restraint.save();

        assertFalse(saved.contains("item"), "gear nobody supplied must not persist an item to hand back");
        assertFalse(restraint.hasItem());
        assertEquals(restraint, AppliedRestraint.load(saved).orElseThrow());
    }

    @Test
    void theItemSnapshotIsCopiedInAndOut() {
        CompoundTag mutable = cuffStack();
        AppliedRestraint restraint = AppliedRestraint.of(
                RestraintDefinitions.get(RestraintDefinitions.HANDCUFFS_ARMS).orElseThrow(), mutable,
                RestraintApplier.system(), AppliedRestraint.ApplicationContext.LAWFUL,
                AppliedRestraint.Provenance.PLAYER_OWNED, AppliedRestraint.ReturnPolicy.NONE, null, 0L);

        mutable.putString("id", "minecraft:dirt");
        assertEquals("mcacrime:restraint_locked_cuffs", restraint.itemSnapshot().getString("id"),
                "a record everybody can edit in place is not evidence");

        restraint.itemSnapshot().putString("id", "minecraft:dirt");
        assertEquals("mcacrime:restraint_locked_cuffs", restraint.itemSnapshot().getString("id"));
        assertNotSame(restraint.itemSnapshot(), restraint.itemSnapshot());
    }

    /** The upstream defect: two subjects sharing one registry object, and therefore one durability. */
    @Test
    void twoInstancesOfOneDefinitionAreIndependent() {
        RestraintDefinition definition =
                RestraintDefinitions.get(RestraintDefinitions.SHACKLES_ARMS).orElseThrow();
        AppliedRestraint first = AppliedRestraint.of(definition, null, RestraintApplier.system(),
                AppliedRestraint.ApplicationContext.LAWFUL, AppliedRestraint.Provenance.SYSTEM_ISSUED,
                AppliedRestraint.ReturnPolicy.NONE, null, 0L);
        AppliedRestraint second = AppliedRestraint.of(definition, null, RestraintApplier.system(),
                AppliedRestraint.ApplicationContext.LAWFUL, AppliedRestraint.Provenance.SYSTEM_ISSUED,
                AppliedRestraint.ReturnPolicy.NONE, null, 0L);

        assertNotEquals(first.instanceId(), second.instanceId(), "every application is its own instance");

        AppliedRestraint firstDamaged = first.damaged(5);

        assertEquals(definition.escape().durability() - 5, firstDamaged.remainingDurability());
        assertEquals(definition.escape().durability(), second.remainingDurability(),
                "damaging one subject's gear damaged another's");
        assertEquals(definition.escape().durability(), first.remainingDurability(),
                "the original instance is immutable");
    }

    @Test
    void durabilityStopsAtZeroAndReadsAsBroken() {
        RestraintDefinition definition =
                RestraintDefinitions.get(RestraintDefinitions.DUCK_TAPE_ARMS).orElseThrow();
        AppliedRestraint tape = AppliedRestraint.of(definition, null, RestraintApplier.system(),
                AppliedRestraint.ApplicationContext.UNLAWFUL, AppliedRestraint.Provenance.PLAYER_OWNED,
                AppliedRestraint.ReturnPolicy.NONE, null, 0L);

        AppliedRestraint spent = tape.damaged(definition.escape().durability() + 10);

        assertEquals(0, spent.remainingDurability(), "durability may not go negative");
        assertTrue(spent.broken());
        assertFalse(tape.broken());
        assertEquals(0.0F, spent.durabilityFraction());
        assertEquals(1.0F, tape.durabilityFraction());
    }

    @Test
    void aRowWithNoInstanceIdOrNoDefinitionIsEmptyRatherThanGuessed() {
        assertEquals(Optional.empty(), AppliedRestraint.load(null));
        assertEquals(Optional.empty(), AppliedRestraint.load(new CompoundTag()));

        CompoundTag noDefinition = new CompoundTag();
        noDefinition.putUUID("instance", UUID.randomUUID());
        noDefinition.putString("definition", "not a resource location");
        assertEquals(Optional.empty(), AppliedRestraint.load(noDefinition),
                "guessing a definition would put gear on somebody that nobody applied");
    }

    @Test
    void unknownEnumNamesFallBackWithoutThrowing() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("instance", UUID.randomUUID());
        tag.putString("definition", RestraintDefinitions.BUNDLE.toString());
        tag.putString("context", "sideways");
        tag.putString("provenance", "borrowed");
        tag.putString("returnPolicy", "maybe");

        AppliedRestraint loaded = AppliedRestraint.load(tag).orElseThrow();

        assertEquals(AppliedRestraint.ApplicationContext.UNLAWFUL, loaded.context());
        assertEquals(AppliedRestraint.Provenance.SYSTEM_ISSUED, loaded.provenance());
        assertEquals(AppliedRestraint.ReturnPolicy.NONE, loaded.returnPolicy(),
                "an unreadable return policy must never mint an item");
    }
}
