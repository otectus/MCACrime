package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.item.CrimeItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The hood is vanilla's empty bundle (§3.1, R07).
 *
 * <p>The hood definition, its worn model, its recipe and its dispenser registration all shipped, and
 * nothing could put it on: every application route asks {@link CrimeItems#familyFor}, which recognised
 * only this mod's own restraint items. A filled bundle must still be refused, or putting it over
 * somebody's head would consume what was inside.
 */
class BundleHoodFamilyTest {

    @Test
    void anEmptyBundleIsTheHood() {
        assertEquals(Optional.of(RestraintFamily.HOOD), CrimeItems.familyFor(new ItemStack(Items.BUNDLE)));
    }

    @Test
    void aFilledBundleIsNotARestraint() {
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(new ItemStack(Items.DIRT, 8))));
        assertEquals(Optional.empty(), CrimeItems.familyFor(bundle));
    }

    @Test
    void anOrdinaryItemIsStillNothing() {
        assertEquals(Optional.empty(), CrimeItems.familyFor(new ItemStack(Items.DIRT)));
    }
}
