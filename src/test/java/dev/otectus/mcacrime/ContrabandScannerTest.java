package dev.otectus.mcacrime;

import dev.otectus.mcacrime.item.contraband.ContrabandInventoryScanner;
import dev.otectus.mcacrime.item.contraband.ContrabandInventoryScanner.Options;
import dev.otectus.mcacrime.item.contraband.ContrabandProbe;
import dev.otectus.mcacrime.item.contraband.ContrabandProbe.ContrabandSlot;
import dev.otectus.mcacrime.item.contraband.ContrabandRules;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure half of a search: which probes count, and how far down it is allowed to look. */
class ContrabandScannerTest {

    private static final ContrabandRules RULES = ContrabandRules.compile(List.of("minecraft:tnt"));

    private static ContrabandProbe probe(ContrabandSlot slot, int depth) {
        return new ContrabandProbe(ResourceLocation.parse("minecraft:tnt"), tag -> false, 1, depth,
                "TNT", slot);
    }

    @Test
    void shortCircuitStopsAtTheFirstHit() {
        List<ContrabandProbe> probes = List.of(probe(ContrabandSlot.MAIN, 0), probe(ContrabandSlot.MAIN, 0));
        assertEquals(1, ContrabandInventoryScanner.listed(probes, RULES, true).size());
        assertEquals(2, ContrabandInventoryScanner.listed(probes, RULES, false).size());
    }

    @Test
    void unlistedItemsAndAnEmptyRuleSetFindNothing() {
        ContrabandProbe stone = new ContrabandProbe(ResourceLocation.parse("minecraft:stone"), tag -> false,
                1, 0, "Stone", ContrabandSlot.MAIN);
        assertTrue(ContrabandInventoryScanner.listed(List.of(stone), RULES, false).isEmpty());
        assertTrue(ContrabandInventoryScanner.listed(List.of(probe(ContrabandSlot.MAIN, 0)),
                ContrabandRules.empty(), false).isEmpty());
    }

    @Test
    void optionsAreHonouredPerSlot() {
        List<ContrabandProbe> probes = List.of(
                probe(ContrabandSlot.MAIN, 0),
                probe(ContrabandSlot.ARMOR, 0),
                probe(ContrabandSlot.OFFHAND, 0),
                probe(ContrabandSlot.NESTED, 1));
        assertEquals(4, ContrabandInventoryScanner.listed(probes, RULES,
                new Options(true, true, true), false).size());
        // The main inventory is never optional; everything else is.
        assertEquals(List.of(ContrabandSlot.MAIN),
                ContrabandInventoryScanner.listed(probes, RULES, new Options(false, false, false), false)
                        .stream().map(ContrabandProbe::slot).toList());
        assertEquals(List.of(ContrabandSlot.MAIN, ContrabandSlot.OFFHAND),
                ContrabandInventoryScanner.listed(probes, RULES, new Options(false, true, false), false)
                        .stream().map(ContrabandProbe::slot).toList());
    }

    @Test
    void depthIsCappedAtOneLevel() {
        assertEquals(1, ContrabandInventoryScanner.MAX_NESTED_DEPTH);
        List<ContrabandProbe> probes = List.of(probe(ContrabandSlot.NESTED, 1), probe(ContrabandSlot.NESTED, 2));
        List<ContrabandProbe> listed = ContrabandInventoryScanner.listed(probes, RULES, false);
        assertEquals(1, listed.size(), "a probe from a second level of nesting is never listed");
        assertEquals(1, listed.get(0).depth());
    }
}
