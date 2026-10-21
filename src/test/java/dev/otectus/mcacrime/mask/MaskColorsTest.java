package dev.otectus.mcacrime.mask;

import dev.otectus.mcacrime.item.MaskFamily;
import dev.otectus.mcacrime.item.MaskVariant;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The standard colours of 0.8.0 (D1-D3), checked against vanilla's own dye blend rather than against
 * a table typed out a second time. A leather helmet stands in for a mask: {@link MaskColors} reads only
 * {@code display.color} and dyes through {@code DyeableLeatherItem}, and a registered mask item cannot be
 * built once the registries are frozen.
 *
 * <p>Runs in its own JVM ({@code bootstrapTest} in {@code build.gradle}), because in the shared {@code test}
 * JVM an earlier class can leave the registries unable to initialise and every case here would skip.
 */
class MaskColorsTest {

    private static boolean gameAvailable;
    private static String unavailableReason = "";

    @BeforeAll
    static void bootstrapGame() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            try {
                net.minecraft.server.Bootstrap.bootStrap();
            } catch (Throwable forgeNetworking) {
                // Off the mod launcher, Forge's network hooks cannot build their event listeners and
                // throw from the end of bootStrap -- after vanilla's registries and dyes are in place.
                // Which test class bootstraps first is up to the runner, so this one does not depend
                // on it: the probe below proves what these checks need actually works.
            }
            ItemStack probe = DyeableLeatherItem.dyeArmor(new ItemStack(Items.LEATHER_HELMET),
                    List.of((DyeItem) DyeItem.byColor(DyeColor.RED)));
            gameAvailable = MaskColors.hasColor(probe);
        } catch (Throwable unavailable) {
            gameAvailable = false;
            unavailableReason = unavailable.toString();
        }
    }

    private static ItemStack withColorTag(CompoundTag display) {
        ItemStack stack = new ItemStack(Items.LEATHER_HELMET);
        stack.getOrCreateTag().put("display", display);
        return stack;
    }

    @Test
    void everyStandardColourIsExactlyWhatOneDyeDoesToAnUndyedItem() {
        assumeTrue(gameAvailable, () -> "no bootstrapped Minecraft in this environment: " + unavailableReason);
        Set<Integer> seen = new HashSet<>();
        for (DyeColor dye : MaskColors.STANDARD) {
            ItemStack crafted = DyeableLeatherItem.dyeArmor(new ItemStack(Items.LEATHER_HELMET),
                    List.of((DyeItem) DyeItem.byColor(dye)));
            assertEquals(Optional.of(MaskColors.standardColor(dye)), MaskColors.storedColor(crafted), dye.getName());
            assertEquals(MaskColors.storedColor(crafted), MaskColors.storedColor(MaskColors.sample(Items.LEATHER_HELMET, dye)),
                    "a creative sample is the crafted colour: " + dye.getName());
            assertEquals(Optional.of(dye), MaskColors.standardOf(crafted), dye.getName());
            assertTrue(seen.add(MaskColors.standardColor(dye)), "two dyes share a colour: " + dye.getName());
        }
        assertEquals(16, MaskColors.STANDARD.size());
        assertEquals(List.of(DyeColor.values()), MaskColors.STANDARD, "the game's own order");
    }

    @Test
    void aBlendOrACustomColourIsNeverNamedAsAStandardOne() {
        assumeTrue(gameAvailable, () -> "no bootstrapped Minecraft in this environment: " + unavailableReason);
        int blends = 0;
        for (DyeColor first : MaskColors.STANDARD) {
            for (DyeColor second : MaskColors.STANDARD) {
                if (first == second) {
                    continue;
                }
                ItemStack blend = DyeableLeatherItem.dyeArmor(new ItemStack(Items.LEATHER_HELMET),
                        List.of((DyeItem) DyeItem.byColor(first), (DyeItem) DyeItem.byColor(second)));
                int stored = MaskColors.storedColor(blend).orElseThrow();
                Optional<DyeColor> named = MaskColors.standardOf(blend);
                // A name is only ever given for a value that really is that colour's sample.
                named.ifPresent(dye -> assertEquals(MaskColors.standardColor(dye), stored));
                blends++;
            }
        }
        assertEquals(240, blends);
        assertEquals(Optional.empty(), MaskColors.standardOf(0x123456));
        assertEquals(Optional.empty(), MaskColors.standardOf(0xFFFFFF), "pure white is not the white dye's colour");
    }

    @Test
    void undyedAndAStoredWhiteAreDifferentStates() {
        assumeTrue(gameAvailable, () -> "no bootstrapped Minecraft in this environment: " + unavailableReason);
        ItemStack undyed = new ItemStack(Items.LEATHER_HELMET);
        assertFalse(MaskColors.hasColor(undyed));
        assertEquals(Optional.empty(), MaskColors.storedColor(undyed), "never white by default");
        assertEquals(Optional.empty(), MaskColors.standardOf(undyed));

        CompoundTag white = new CompoundTag();
        white.putInt("color", 0xFFFFFF);
        ItemStack customWhite = withColorTag(white);
        assertTrue(MaskColors.hasColor(customWhite));
        assertEquals(Optional.of(0xFFFFFF), MaskColors.storedColor(customWhite));
        assertEquals(Optional.empty(), MaskColors.standardOf(customWhite), "custom white is a custom colour");

        ItemStack dyedWhite = MaskColors.sample(Items.LEATHER_HELMET, DyeColor.WHITE);
        assertTrue(MaskColors.hasColor(dyedWhite));
        assertEquals(Optional.of(DyeColor.WHITE), MaskColors.standardOf(dyedWhite));

        // Any numeric tag is a colour, as vanilla reads it; a string is not.
        CompoundTag asLong = new CompoundTag();
        asLong.putLong("color", 0x3C44AAL);
        assertEquals(Optional.of(0x3C44AA), MaskColors.storedColor(withColorTag(asLong)));
        CompoundTag asString = new CompoundTag();
        asString.putString("color", "blue");
        assertFalse(MaskColors.hasColor(withColorTag(asString)));

        // A name and lore beside the colour are not a colour, and do not hide one.
        CompoundTag named = new CompoundTag();
        named.putString("Name", "{\"text\":\"Mine\"}");
        assertFalse(MaskColors.hasColor(withColorTag(named)));
        named.putInt("color", MaskColors.standardColor(DyeColor.BLACK));
        assertEquals(Optional.of(DyeColor.BLACK), MaskColors.standardOf(withColorTag(named)));
    }

    @Test
    void theCatalogueIsEveryStyleInFamilyOrderEachFollowedByItsSixteenColours() {
        assumeTrue(gameAvailable, () -> "no bootstrapped Minecraft in this environment: " + unavailableReason);
        List<MaskVariant> asked = new ArrayList<>();
        List<ItemStack> catalogue = MaskColors.catalogue(variant -> {
            asked.add(variant);
            return Items.LEATHER_HELMET;
        });
        assertEquals(272, catalogue.size(), "16 styles x (original + 16 colours)");

        List<MaskVariant> expected = new ArrayList<>();
        for (MaskFamily family : MaskFamily.values()) {
            List<MaskVariant> styles = new ArrayList<>(MaskVariant.of(family));
            styles.sort(java.util.Comparator.comparingInt(MaskVariant::sortOrder));
            expected.addAll(styles);
        }
        assertEquals(expected, asked, "family, then position in the family");

        for (int style = 0; style < 16; style++) {
            ItemStack original = catalogue.get(style * 17);
            assertFalse(MaskColors.hasColor(original), "each style starts with its original");
            for (int index = 0; index < 16; index++) {
                DyeColor dye = MaskColors.STANDARD.get(index);
                ItemStack sample = catalogue.get(style * 17 + 1 + index);
                assertEquals(Optional.of(MaskColors.standardColor(dye)), MaskColors.storedColor(sample),
                        expected.get(style) + " " + dye.getName());
                assertEquals(1, sample.getCount());
                // Survives the trip through NBT, which is what a creative pick and a save both do.
                ItemStack copy = ItemStack.of(sample.save(new CompoundTag()));
                assertTrue(ItemStack.matches(sample, copy), expected.get(style) + " " + dye.getName());
            }
        }
    }
}
