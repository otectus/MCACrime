package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.item.CrimeItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The hood is vanilla's empty bundle (§3.1, R07).
 *
 * <p>The hood definition, its worn model, its recipe and its dispenser registration all shipped, and
 * nothing could put it on: every application route asks {@link CrimeItems#familyFor}, which recognised
 * only this mod's own restraint items. A filled bundle must still be refused, or putting it over
 * somebody's head would consume what was inside.
 */
class BundleHoodFamilyTest {

    private static boolean gameAvailable;

    @BeforeAll
    static void bootstrapGame() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            CrimeItems.familyFor(ItemStack.EMPTY);
            gameAvailable = true;
        } catch (Throwable unavailable) {
            gameAvailable = false;
        }
    }

    @Test
    void anEmptyBundleIsTheHood() {
        assumeTrue(gameAvailable, "no bootstrapped Minecraft in this environment");
        assertEquals(Optional.of(RestraintFamily.HOOD), CrimeItems.familyFor(new ItemStack(Items.BUNDLE)));
    }

    @Test
    void aFilledBundleIsNotARestraint() {
        assumeTrue(gameAvailable, "no bootstrapped Minecraft in this environment");
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        ListTag items = new ListTag();
        items.add(new ItemStack(Items.DIRT, 8).save(new CompoundTag()));
        bundle.getOrCreateTag().put("Items", items);
        assertEquals(Optional.empty(), CrimeItems.familyFor(bundle));
    }

    @Test
    void anOrdinaryItemIsStillNothing() {
        assumeTrue(gameAvailable, "no bootstrapped Minecraft in this environment");
        assertEquals(Optional.empty(), CrimeItems.familyFor(new ItemStack(Items.DIRT)));
    }
}
