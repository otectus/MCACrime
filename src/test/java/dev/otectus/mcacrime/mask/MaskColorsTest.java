package dev.otectus.mcacrime.mask;

import dev.otectus.mcacrime.item.MaskFamily;
import dev.otectus.mcacrime.item.MaskVariant;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
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
 * the {@code minecraft:dyed_color} component and dyes through {@code DyedItemColor.applyDyes}, which a
 * leather helmet takes exactly as a mask does once both are in {@code minecraft:dyeable}. The Forge build
 * runs the same cases over {@code display.color}.
 */
class MaskColorsTest {

    private static boolean gameAvailable;

    @BeforeAll
    static void bootstrapGame() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            // No datapack is loaded here, so no tag is bound, and 1.21.1's applyDyes refuses anything
            // outside minecraft:dyeable. The leather helmet stands in for a mask, so it is put in the tag
            // the way the shipped data/minecraft/tags/item/dyeable.json puts every mask there.
            if (!new ItemStack(Items.LEATHER_HELMET).is(net.minecraft.tags.ItemTags.DYEABLE)) {
                BuiltInRegistries.ITEM.bindTags(java.util.Map.of(net.minecraft.tags.ItemTags.DYEABLE,
                        List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.LEATHER_HELMET))));
            }
            ItemStack probe = DyedItemColor.applyDyes(new ItemStack(Items.LEATHER_HELMET), List.of(DyeItem.byColor(DyeColor.RED)));
            gameAvailable = MaskColors.hasColor(probe);
        } catch (Throwable unavailable) {
            gameAvailable = false;
        }
    }

    private static ItemStack withColor(int rgb) {
        ItemStack stack = new ItemStack(Items.LEATHER_HELMET);
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb, true));
        return stack;
    }

    @Test
    void everyStandardColourIsExactlyWhatOneDyeDoesToAnUndyedItem() {
        assumeTrue(gameAvailable, "no bootstrapped Minecraft in this environment");
        Set<Integer> seen = new HashSet<>();
        for (DyeColor dye : MaskColors.STANDARD) {
            ItemStack crafted = DyedItemColor.applyDyes(new ItemStack(Items.LEATHER_HELMET), List.of(DyeItem.byColor(dye)));
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
        assumeTrue(gameAvailable, "no bootstrapped Minecraft in this environment");
        int blends = 0;
        for (DyeColor first : MaskColors.STANDARD) {
            for (DyeColor second : MaskColors.STANDARD) {
                if (first == second) {
                    continue;
                }
                ItemStack blend = DyedItemColor.applyDyes(new ItemStack(Items.LEATHER_HELMET),
                        List.of(DyeItem.byColor(first), DyeItem.byColor(second)));
                int stored = MaskColors.storedColor(blend).orElseThrow();
                // A name is only ever given for a value that really is that colour's sample.
                MaskColors.standardOf(blend).ifPresent(dye -> assertEquals(MaskColors.standardColor(dye), stored));
                blends++;
            }
        }
        assertEquals(240, blends);
        assertEquals(Optional.empty(), MaskColors.standardOf(0x123456));
        assertEquals(Optional.empty(), MaskColors.standardOf(0xFFFFFF), "pure white is not the white dye's colour");
    }

    @Test
    void undyedAndAStoredWhiteAreDifferentStates() {
        assumeTrue(gameAvailable, "no bootstrapped Minecraft in this environment");
        ItemStack undyed = new ItemStack(Items.LEATHER_HELMET);
        assertFalse(MaskColors.hasColor(undyed));
        assertEquals(Optional.empty(), MaskColors.storedColor(undyed), "never white by default");
        assertEquals(Optional.empty(), MaskColors.standardOf(undyed));

        ItemStack customWhite = withColor(0xFFFFFF);
        assertTrue(MaskColors.hasColor(customWhite));
        assertEquals(Optional.of(0xFFFFFF), MaskColors.storedColor(customWhite));
        assertEquals(Optional.empty(), MaskColors.standardOf(customWhite), "custom white is a custom colour");

        ItemStack dyedWhite = MaskColors.sample(Items.LEATHER_HELMET, DyeColor.WHITE);
        assertTrue(MaskColors.hasColor(dyedWhite));
        assertEquals(Optional.of(DyeColor.WHITE), MaskColors.standardOf(dyedWhite));

        // A component's alpha is not part of the colour.
        assertEquals(Optional.of(0x3C44AA), MaskColors.storedColor(withColor(0xFF3C44AA)));

        // A name beside the colour is not a colour, and does not hide one.
        ItemStack named = new ItemStack(Items.LEATHER_HELMET);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Mine"));
        assertFalse(MaskColors.hasColor(named));
        named.set(DataComponents.DYED_COLOR, new DyedItemColor(MaskColors.standardColor(DyeColor.BLACK), true));
        assertEquals(Optional.of(DyeColor.BLACK), MaskColors.standardOf(named));
    }

    @Test
    void theCatalogueIsEveryStyleInFamilyOrderEachFollowedByItsSixteenColours() {
        assumeTrue(gameAvailable, "no bootstrapped Minecraft in this environment");
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

        HolderLookup.Provider registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        for (int style = 0; style < 16; style++) {
            ItemStack original = catalogue.get(style * 17);
            assertFalse(MaskColors.hasColor(original), "each style starts with its original");
            for (int index = 0; index < 16; index++) {
                DyeColor dye = MaskColors.STANDARD.get(index);
                ItemStack sample = catalogue.get(style * 17 + 1 + index);
                assertEquals(Optional.of(MaskColors.standardColor(dye)), MaskColors.storedColor(sample),
                        expected.get(style) + " " + dye.getName());
                assertEquals(1, sample.getCount());
                // Survives the trip through the item codec, which is what a creative pick and a save both do.
                ItemStack copy = ItemStack.parseOptional(registries, (net.minecraft.nbt.CompoundTag) sample.save(registries));
                assertTrue(ItemStack.isSameItemSameComponents(sample, copy), expected.get(style) + " " + dye.getName());
            }
        }
    }
}
