package dev.otectus.mcacrime;

import dev.otectus.mcacrime.item.contraband.ContrabandProbe;
import dev.otectus.mcacrime.item.contraband.ContrabandRules;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contraband list: id matching, tag matching, and what a typo in the config does.
 *
 * <p>No Minecraft bootstrap — probes are built by hand, so the rules answer real questions about items
 * that do not exist, exactly as {@code WeaponRulesTest} does.
 */
class ContrabandRulesTest {

    private static ContrabandProbe probe(String id, String... tags) {
        Set<ResourceLocation> owned = Set.copyOf(List.of(tags).stream().map(ResourceLocation::new).toList());
        return new ContrabandProbe(new ResourceLocation(id), owned::contains, 1, 0, id,
                ContrabandProbe.ContrabandSlot.MAIN);
    }

    @Test
    void matchesPlainId() {
        ContrabandRules rules = ContrabandRules.compile(List.of("minecraft:tnt"));
        assertTrue(rules.matches(probe("minecraft:tnt")));
        assertFalse(rules.matches(probe("minecraft:stone")));
        assertEquals(Set.of(new ResourceLocation("minecraft:tnt")), rules.ids());
        assertTrue(rules.tags().isEmpty());
    }

    @Test
    void matchesTagAndStripsTheHash() {
        ContrabandRules rules = ContrabandRules.compile(List.of("#mcacrime:illicit_goods"));
        assertEquals(Set.of(new ResourceLocation("mcacrime:illicit_goods")), rules.tags());
        assertTrue(rules.ids().isEmpty());
        assertTrue(rules.matches(probe("modded:powder", "mcacrime:illicit_goods")));
        assertFalse(rules.matches(probe("modded:powder")));
    }

    @Test
    void emptyListMatchesNothing() {
        assertTrue(ContrabandRules.compile(List.of()).isEmpty());
        assertTrue(ContrabandRules.empty().isEmpty());
        assertFalse(ContrabandRules.empty().matches(probe("minecraft:tnt")));
    }

    @Test
    void malformedEntryIsSkippedAndReported() {
        ContrabandRules rules = ContrabandRules.compile(List.of("NOT AN ID", "", "minecraft:tnt"));
        assertTrue(rules.matches(probe("minecraft:tnt")), "one bad line must not take the list with it");
        assertEquals(1, rules.problems().size());
        assertTrue(rules.problems().get(0).contains("NOT AN ID"));
    }

    @Test
    void withTagsFilteredDropsUnknownTagsAndKeepsIds() {
        ContrabandRules rules = ContrabandRules.compile(
                List.of("minecraft:tnt", "#mcacrime:illicit_goods", "#modded:never_bound"));
        ContrabandRules filtered = rules.withTagsFiltered(
                tag -> tag.equals(new ResourceLocation("mcacrime:illicit_goods")));
        assertEquals(Set.of(new ResourceLocation("mcacrime:illicit_goods")), filtered.tags());
        assertTrue(filtered.matches(probe("minecraft:tnt")));
        assertFalse(filtered.matches(probe("modded:thing", "modded:never_bound")));
        assertEquals(1, filtered.problems().size());
        assertTrue(rules.tags().size() == 2, "the original rule set is untouched");
    }
}
