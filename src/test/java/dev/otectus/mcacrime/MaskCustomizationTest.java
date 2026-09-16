package dev.otectus.mcacrime;

import dev.otectus.mcacrime.item.MaskVariant;
import dev.otectus.mcacrime.mask.MaskCustomization;
import dev.otectus.mcacrime.mask.MaskNbtTransfer;
import dev.otectus.mcacrime.mask.MaskRestyleRejection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant 8, as a matrix: a restyle keeps everything it understands, carries everything it merely
 * recognises as somebody else's, refuses what it cannot move, and never repairs anything (0.7.2 §5.4,
 * MASK-06, MASK-07, MASK-08).
 *
 * <p>Registry-free by construction. The rule lives in {@link MaskCustomization#decide} and the tag
 * arithmetic in {@link MaskNbtTransfer}, both of which take values rather than a running game, which
 * is exactly why this matrix can exist at all.
 */
class MaskCustomizationTest {

    /** A well-used, named, cursed, flagged clay mask with a foreign mod's flag on it too. */
    private static CompoundTag lovedMask() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", 41);
        tag.putInt("RepairCost", 7);
        CompoundTag display = new CompoundTag();
        display.putString("Name", "{\"text\":\"The Old Face\"}");
        display.putInt("color", 0x7F0033);
        ListTag lore = new ListTag();
        lore.add(StringTag.valueOf("{\"text\":\"Taken from a guard\"}"));
        display.put("Lore", lore);
        tag.put("display", display);
        ListTag enchantments = new ListTag();
        CompoundTag curse = new CompoundTag();
        curse.putString("id", "minecraft:binding_curse");
        curse.putShort("lvl", (short) 1);
        enchantments.add(curse);
        tag.put("Enchantments", enchantments);
        CompoundTag mine = new CompoundTag();
        mine.putString("provenance", "incident-91");
        mine.putBoolean("flagged", true);
        tag.put("mcacrime", mine);
        tag.putString("somemod:soulbound", "player-a");
        return tag;
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

    /** §7.4: a tag makes an item conceal a face; it does not volunteer that item's NBT. */
    @Test
    void anUnregisteredStyleIsNeverConverted() {
        assertSame(MaskRestyleRejection.NOT_A_REGISTERED_STYLE,
                MaskCustomization.decide(null, MaskVariant.HOCKEY, null, true));
        assertSame(MaskRestyleRejection.NOT_A_REGISTERED_STYLE,
                MaskCustomization.decide(MaskVariant.HOCKEY, null, null, true));
    }

    // ---- what survives ----------------------------------------------------------------------

    @Test
    void nameColourLoreEnchantmentsCursesAndProvenanceAllSurvive() {
        CompoundTag source = lovedMask();
        CompoundTag merged = MaskNbtTransfer.merge(source, new CompoundTag()).orElseThrow();

        assertEquals("{\"text\":\"The Old Face\"}", merged.getCompound("display").getString("Name"));
        assertEquals(0x7F0033, merged.getCompound("display").getInt("color"));
        assertEquals(1, merged.getCompound("display").getList("Lore", CompoundTag.TAG_STRING).size());
        assertEquals(7, merged.getInt("RepairCost"));
        assertEquals("minecraft:binding_curse",
                merged.getList("Enchantments", CompoundTag.TAG_COMPOUND).getCompound(0).getString("id"),
                "a curse is item history, not a blemish to launder off");
        assertEquals("incident-91", merged.getCompound("mcacrime").getString("provenance"));
        assertTrue(merged.getCompound("mcacrime").getBoolean("flagged"),
                "§14.4: a flagged mask stays flagged through the station");
    }

    /** Unknown but recoverable: a foreign mod's plain data follows the item rather than evaporating. */
    @Test
    void aForeignModsOwnFlagIsCarriedVerbatim() {
        CompoundTag merged = MaskNbtTransfer.merge(lovedMask(), null).orElseThrow();
        assertEquals("player-a", merged.getString("somemod:soulbound"));
    }

    /** The merged tag is a copy; editing it must not reach back into the mask it came from. */
    @Test
    void theTransferIsACopyAndNotAnAlias() {
        CompoundTag source = lovedMask();
        CompoundTag merged = MaskNbtTransfer.merge(source, null).orElseThrow();
        merged.getCompound("mcacrime").putString("provenance", "tampered");
        merged.getCompound("display").putInt("color", 0x00FF00);
        assertEquals("incident-91", source.getCompound("mcacrime").getString("provenance"));
        assertEquals(0x7F0033, source.getCompound("display").getInt("color"));
    }

    /**
     * Damage is never carried through the tag.
     *
     * <p>It is set through the stack instead, where the "same maximum durability or no conversion"
     * check lives — so a merge cannot smuggle a 41 from a 64-use mask onto a 256-use one, and a
     * restyle cannot become either a repair or a quiet loss (§5.4).
     */
    @Test
    void damageIsNotCarriedByTheTagMerge() {
        CompoundTag merged = MaskNbtTransfer.merge(lovedMask(), null).orElseThrow();
        assertFalse(merged.contains("Damage"), "damage belongs to the stack, not to this merge");
    }

    /** A fresh target's own presentation fields are kept where the source has no opinion. */
    @Test
    void theTargetKeepsItsOwnUncontestedDisplayFields() {
        CompoundTag target = new CompoundTag();
        CompoundTag display = new CompoundTag();
        display.putInt("CustomModelData", 3);
        target.put("display", display);
        CompoundTag merged = MaskNbtTransfer.merge(lovedMask(), target).orElseThrow();
        assertEquals(3, merged.getCompound("display").getInt("CustomModelData"));
        assertEquals(0x7F0033, merged.getCompound("display").getInt("color"));
    }

    // ---- what refuses -----------------------------------------------------------------------

    @Test
    void capabilityDataRefusesTheConversionRatherThanDisappearing() {
        CompoundTag source = lovedMask();
        source.put("ForgeCaps", new CompoundTag());
        assertEquals(java.util.List.of("ForgeCaps"), MaskNbtTransfer.unsupportedKeys(source));
        assertTrue(MaskNbtTransfer.merge(source, null).isEmpty());
        assertSame(MaskRestyleRejection.UNSUPPORTED_DATA,
                MaskCustomization.decide(MaskVariant.CLAY, MaskVariant.HOCKEY, source, true));
    }

    @Test
    void everyUnsupportedKeyIsReportedNotJustTheFirst() {
        CompoundTag source = new CompoundTag();
        source.put("ForgeCaps", new CompoundTag());
        source.put("BlockEntityTag", new CompoundTag());
        source.put("EntityTag", new CompoundTag());
        assertEquals(java.util.List.of("BlockEntityTag", "EntityTag", "ForgeCaps"),
                MaskNbtTransfer.unsupportedKeys(source));
    }

    @Test
    void anUnknownKeyIsRecoverableAndDoesNotRefuse() {
        CompoundTag source = new CompoundTag();
        source.putInt("someothermod:charges", 4);
        assertTrue(MaskNbtTransfer.unsupportedKeys(source).isEmpty());
        assertEquals(4, MaskNbtTransfer.merge(source, null).orElseThrow().getInt("someothermod:charges"));
    }

    @Test
    void aMaskWithNoTagAtAllConvertsCleanly() {
        assertTrue(MaskNbtTransfer.merge(null, null).orElseThrow().isEmpty());
        assertSame(MaskRestyleRejection.NONE,
                MaskCustomization.decide(MaskVariant.BANDANA, MaskVariant.HALF_VEIL, null, false));
    }

    @Test
    void theKnownKeyListStillCoversWhatThisModWrites() {
        assertTrue(MaskNbtTransfer.KNOWN_KEYS.contains("mcacrime"),
                "this mod's own mask data must stay a known-and-carried key");
        assertTrue(MaskNbtTransfer.KNOWN_KEYS.contains("Enchantments"));
        assertTrue(MaskNbtTransfer.unsupported("ForgeCaps"));
        assertFalse(MaskNbtTransfer.unsupported("mcacrime"));
    }
}
