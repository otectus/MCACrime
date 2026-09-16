package dev.otectus.mcacrime;

import dev.otectus.mcacrime.item.MaskVariant;
import dev.otectus.mcacrime.mask.MaskCustomization;
import dev.otectus.mcacrime.mask.MaskNbtTransfer;
import dev.otectus.mcacrime.mask.MaskRestyleRejection;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant 8, as a matrix: a restyle keeps everything it understands, carries everything it merely
 * recognises as somebody else's, refuses what it cannot move, and never repairs anything (0.7.2 §5.4,
 * MASK-06, MASK-07, MASK-08).
 *
 * <p>Registry-free by construction. The rule lives in {@link MaskCustomization#decide} and the
 * arithmetic in {@link MaskNbtTransfer}, both of which take values rather than a running game, which
 * is exactly why this matrix can exist at all.
 *
 * <p>The values are 1.21.1 data components rather than 1.20.1 root NBT keys. That changes the spelling
 * of every case and none of the claims — with one simplification worth naming: a
 * {@link DataComponentPatch} is immutable, so the 1.20.1 "the transfer is a copy and not an alias"
 * case has become structural and is not re-asserted here.
 */
class MaskCustomizationTest {

    private static final CompoundTag PROVENANCE = provenance();

    private static CompoundTag provenance() {
        CompoundTag tag = new CompoundTag();
        tag.putString("provenance", "incident-91");
        tag.putBoolean("flagged", true);
        return tag;
    }

    /** A well-used, named, dyed, flagged clay mask with a foreign mod's data on it too. */
    private static DataComponentPatch lovedMask() {
        return DataComponentPatch.builder()
                .set(DataComponents.DAMAGE, 41)
                .set(DataComponents.REPAIR_COST, 7)
                .set(DataComponents.CUSTOM_NAME, Component.literal("The Old Face"))
                .set(DataComponents.DYED_COLOR, new DyedItemColor(0x7F0033, true))
                .set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Taken from a guard"))))
                .set(DataComponents.CUSTOM_DATA, CustomData.of(provenance()))
                .build();
    }

    /**
     * The value of one component on a patch.
     *
     * <p>{@link DataComponentPatch#get} has three answers, not two: {@code null} for a component the
     * patch says nothing about, an empty {@link java.util.Optional} for one it explicitly removes, and
     * a present one for a value. This helper is only for the third, so the tests below can read as
     * assertions about values.
     */
    private static <T> T get(DataComponentPatch patch, net.minecraft.core.component.DataComponentType<T> type) {
        java.util.Optional<? extends T> value = patch.get(type);
        assertTrue(value != null && value.isPresent(), type + " is not set on this patch");
        return value.get();
    }

    // ---- the pairing rule -------------------------------------------------------------------

    @Test
    void sameFamilyDifferentStyleIsAllowed() {
        assertSame(MaskRestyleRejection.NONE,
                MaskCustomization.decide(MaskVariant.CLAY, MaskVariant.HOCKEY, lovedMask(), false));
    }

    @Test
    void crossFamilyIsRefused() {
        assertSame(MaskRestyleRejection.WRONG_FAMILY,
                MaskCustomization.decide(MaskVariant.CLAY, MaskVariant.IRON_SKULL, null, true));
        assertSame(MaskRestyleRejection.WRONG_FAMILY,
                MaskCustomization.decide(MaskVariant.BANDANA, MaskVariant.LEATHER, null, true));
    }

    /** §5.4: the already-current style with no colour change is a disabled no-op, not a free spin. */
    @Test
    void theSameStyleWithNoColourChangeIsRefused() {
        assertSame(MaskRestyleRejection.NO_CHANGE,
                MaskCustomization.decide(MaskVariant.HOCKEY, MaskVariant.HOCKEY, null, false));
        assertSame(MaskRestyleRejection.NONE,
                MaskCustomization.decide(MaskVariant.HOCKEY, MaskVariant.HOCKEY, null, true));
    }

    /** §7.4: a tag makes an item conceal a face; it does not volunteer that item's data. */
    @Test
    void anUnregisteredStyleIsNeverConverted() {
        assertSame(MaskRestyleRejection.NOT_A_REGISTERED_STYLE,
                MaskCustomization.decide(null, MaskVariant.HOCKEY, null, true));
        assertSame(MaskRestyleRejection.NOT_A_REGISTERED_STYLE,
                MaskCustomization.decide(MaskVariant.HOCKEY, null, null, true));
    }

    // ---- what survives ----------------------------------------------------------------------

    @Test
    void nameColourLoreRepairCostAndProvenanceAllSurvive() {
        DataComponentPatch merged =
                MaskNbtTransfer.merge(lovedMask(), DataComponentPatch.EMPTY).orElseThrow();

        assertEquals(Component.literal("The Old Face"), get(merged, DataComponents.CUSTOM_NAME));
        assertEquals(0x7F0033, get(merged, DataComponents.DYED_COLOR).rgb());
        assertEquals(1, get(merged, DataComponents.LORE).lines().size());
        assertEquals(7, get(merged, DataComponents.REPAIR_COST));
        assertEquals("incident-91",
                get(merged, DataComponents.CUSTOM_DATA).copyTag().getString("provenance"));
        assertTrue(get(merged, DataComponents.CUSTOM_DATA).copyTag().getBoolean("flagged"),
                "§14.4: a flagged mask stays flagged through the station");
    }

    /**
     * A curse is item history, not a blemish to launder off.
     *
     * <p>Asserted as membership rather than as a round trip: an enchantment holder needs a loaded
     * registry, the merge is type-agnostic, and what could actually go wrong is somebody moving
     * {@code minecraft:enchantments} onto the refusing list.
     */
    @Test
    void enchantmentsAndCursesAreACarriedComponent() {
        assertTrue(MaskNbtTransfer.KNOWN_TYPES.contains(DataComponents.ENCHANTMENTS));
        assertFalse(MaskNbtTransfer.unsupported(DataComponents.ENCHANTMENTS));
    }

    /** Unknown but recoverable: a foreign mod's plain data follows the item rather than evaporating. */
    @Test
    void aForeignModsOwnFlagIsCarriedVerbatim() {
        DataComponentPatch source = DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_DATA, CustomData.of(PROVENANCE))
                .set(DataComponents.ITEM_NAME, Component.literal("somemod flag"))
                .build();
        DataComponentPatch merged = MaskNbtTransfer.merge(source, null).orElseThrow();
        assertEquals(Component.literal("somemod flag"), get(merged, DataComponents.ITEM_NAME));
        assertEquals("incident-91", get(merged, DataComponents.CUSTOM_DATA).copyTag().getString("provenance"));
    }

    /**
     * Damage is never carried through the patch.
     *
     * <p>It is set through the stack instead, where the "same maximum durability or no conversion"
     * check lives — so a merge cannot smuggle a 41 from a 64-use mask onto a 256-use one, and a
     * restyle cannot become either a repair or a quiet loss (§5.4).
     */
    @Test
    void damageIsNotCarriedByThePatchMerge() {
        DataComponentPatch merged = MaskNbtTransfer.merge(lovedMask(), null).orElseThrow();
        assertNull(merged.get(DataComponents.DAMAGE),
                "damage belongs to the stack, not to this merge");
    }

    /** A fresh target's own presentation components are kept where the source has no opinion. */
    @Test
    void theTargetKeepsItsOwnUncontestedComponents() {
        DataComponentPatch target = DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(3))
                .set(DataComponents.DYED_COLOR, new DyedItemColor(0x00FF00, true))
                .build();
        DataComponentPatch merged = MaskNbtTransfer.merge(lovedMask(), target).orElseThrow();
        assertEquals(3, get(merged, DataComponents.CUSTOM_MODEL_DATA).value());
        assertEquals(0x7F0033, get(merged, DataComponents.DYED_COLOR).rgb(),
                "where both stacks have an opinion, the source's history wins");
    }

    /** A removal is history too: "this mask has no lore" must not be undone by the target's default. */
    @Test
    void anExplicitRemovalInTheSourceIsCarriedAsARemoval() {
        DataComponentPatch source = DataComponentPatch.builder().remove(DataComponents.LORE).build();
        DataComponentPatch target = DataComponentPatch.builder()
                .set(DataComponents.LORE, new ItemLore(List.of(Component.literal("shop stock"))))
                .build();
        DataComponentPatch merged = MaskNbtTransfer.merge(source, target).orElseThrow();
        java.util.Optional<? extends ItemLore> lore = merged.get(DataComponents.LORE);
        assertNotNull(lore, "the patch must still say something about lore");
        assertTrue(lore.isEmpty(), "and what it says is that there is none");
    }

    // ---- what refuses -----------------------------------------------------------------------

    @Test
    void placementDataRefusesTheConversionRatherThanDisappearing() {
        DataComponentPatch source = DataComponentPatch.builder()
                .set(DataComponents.REPAIR_COST, 7)
                .set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(new CompoundTag()))
                .build();
        assertEquals(List.of("minecraft:block_entity_data"), MaskNbtTransfer.unsupportedKeys(source));
        assertTrue(MaskNbtTransfer.merge(source, null).isEmpty());
        assertSame(MaskRestyleRejection.UNSUPPORTED_DATA,
                MaskCustomization.decide(MaskVariant.CLAY, MaskVariant.HOCKEY, source, true));
    }

    @Test
    void everyUnsupportedKeyIsReportedNotJustTheFirst() {
        DataComponentPatch source = DataComponentPatch.builder()
                .set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(new CompoundTag()))
                .set(DataComponents.ENTITY_DATA, CustomData.of(new CompoundTag()))
                .build();
        assertEquals(List.of("minecraft:block_entity_data", "minecraft:entity_data"),
                MaskNbtTransfer.unsupportedKeys(source));
    }

    @Test
    void anUnknownKeyIsRecoverableAndDoesNotRefuse() {
        DataComponentPatch source = DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(4))
                .build();
        assertTrue(MaskNbtTransfer.unsupportedKeys(source).isEmpty());
        assertEquals(4, get(MaskNbtTransfer.merge(source, null).orElseThrow(),
                DataComponents.CUSTOM_MODEL_DATA).value());
    }

    @Test
    void aMaskWithNoDataAtAllConvertsCleanly() {
        assertTrue(MaskNbtTransfer.merge(null, null).orElseThrow().isEmpty());
        assertSame(MaskRestyleRejection.NONE,
                MaskCustomization.decide(MaskVariant.BANDANA, MaskVariant.HALF_VEIL, null, false));
    }

    @Test
    void theKnownKeyListStillCoversWhatThisModWrites() {
        assertTrue(MaskNbtTransfer.KNOWN_TYPES.contains(DataComponents.DYED_COLOR),
                "the dye this mod applies must stay a known-and-carried component");
        assertTrue(MaskNbtTransfer.KNOWN_TYPES.contains(DataComponents.DAMAGE));
        assertTrue(MaskNbtTransfer.knownKeys().contains("minecraft:custom_name"));
        assertEquals(List.of("minecraft:block_entity_data", "minecraft:entity_data"),
                MaskNbtTransfer.unsupportedKeyNames());
        assertTrue(MaskNbtTransfer.unsupported("minecraft:entity_data"));
        assertFalse(MaskNbtTransfer.unsupported("minecraft:custom_data"));
    }

    /** Every refusal has a message key of its own, so a blocked restyle is never a silent one. */
    @Test
    void everyRejectionNamesItsOwnMessageKey() {
        for (MaskRestyleRejection rejection : MaskRestyleRejection.values()) {
            assertEquals("mcacrime.mask.restyle." + rejection.key(), rejection.labelKey());
            assertEquals(rejection == MaskRestyleRejection.NONE, rejection.allowed());
        }
    }
}
