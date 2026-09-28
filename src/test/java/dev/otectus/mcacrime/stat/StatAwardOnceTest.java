package dev.otectus.mcacrime.stat;

import dev.otectus.mcacrime.TestPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fifteen statistics, each awarded once per committed server event (M5.11, Appendix A.3).
 *
 * <p>Upstream declares nineteen ids and awards several of them nowhere at all: its award helpers
 * branch on restraint classes that the tape and bundle restraints are not
 * ({@code init/ModStatistics.java:88-123}). The mapping here is pure and total, and the call sites
 * are checked to be the commits rather than the ticks or the packet handlers.
 */
class StatAwardOnceTest {

    @Test
    void theFifteenShippedIdsAreAllRegistered() {
        Set<String> paths = new TreeSet<>(CrimeStatIds.paths());
        assertEquals(15, paths.size(), "four families of three, plus three singles");
        for (String prefix : List.of("handcuffs", "shackles", "legcuffs", "leg_shackles")) {
            for (String suffix : List.of("times_restrained", "broken", "time_spent_restrained")) {
                assertTrue(paths.contains(prefix + "_" + suffix), "missing " + prefix + "_" + suffix);
            }
        }
        for (String single : List.of("successful_lockpicks", "lockpicks_broken", "open_safe")) {
            assertTrue(paths.contains(single), "missing " + single);
        }
    }

    @Test
    void everyStatisticHasADisplayName() {
        JsonObject lang = JsonParser.parseString(read(TestPaths.resources("assets",
                "mcacrime", "lang", "en_us.json"))).getAsJsonObject();
        for (String path : CrimeStatIds.paths()) {
            assertTrue(lang.has("stat.mcacrime." + path),
                    "stat.mcacrime." + path + " would show as a raw key on the statistics screen");
        }
    }

    @Test
    void theDefinitionToStatisticMappingIsTotalAndHonest() {
        assertSame(CrimeStatIds.Tracked.HANDCUFFS, CrimeStatIds.trackedFor(RestraintDefinitions.HANDCUFFS_ARMS));
        assertSame(CrimeStatIds.Tracked.LEGCUFFS, CrimeStatIds.trackedFor(RestraintDefinitions.HANDCUFFS_LEGS));
        assertSame(CrimeStatIds.Tracked.SHACKLES, CrimeStatIds.trackedFor(RestraintDefinitions.SHACKLES_ARMS));
        assertSame(CrimeStatIds.Tracked.LEG_SHACKLES,
                CrimeStatIds.trackedFor(RestraintDefinitions.SHACKLES_LEGS));

        // The five definitions Appendix A gives no statistic to map to nothing, rather than being
        // quietly counted as something they are not.
        for (ResourceLocation definition : List.of(RestraintDefinitions.DUCK_TAPE_ARMS,
                RestraintDefinitions.DUCK_TAPE_LEGS, RestraintDefinitions.DUCK_TAPE_HEAD,
                RestraintDefinitions.BUNDLE, RestraintDefinitions.PILLORY)) {
            assertNull(CrimeStatIds.trackedFor(definition), definition + " has no statistic in Appendix A");
        }
        assertNull(CrimeStatIds.trackedFor(null));
    }

    @Test
    void theThreeKindsAreDistinctAndTimeIsFormattedAsTime() {
        assertEquals(3, CrimeStatIds.Kind.values().length);
        assertEquals("times_restrained", CrimeStatIds.Kind.TIMES_RESTRAINED.suffix());
        assertEquals("broken", CrimeStatIds.Kind.BROKEN.suffix());
        assertEquals("time_spent_restrained", CrimeStatIds.Kind.TIME_SPENT_RESTRAINED.suffix());
        assertSame(net.minecraft.stats.StatFormatter.TIME,
                CrimeStatIds.Kind.TIME_SPENT_RESTRAINED.formatter(),
                "a tick count shown as a plain number is unreadable");
    }

    @Test
    void awardingRefusesEverythingThatIsNotAPlayerOnTheServer() {
        // A villager has no statistics screen, a null subject has no screen at all, and a zero
        // amount counts nothing. None of the three may reach the registry, which is also why this
        // case can run without one.
        String source = read(TestPaths.sources("dev", "otectus", "mcacrime", "stat",
                "CrimeStats.java"));
        assertTrue(source.contains("if (!(subject instanceof ServerPlayer player) || amount <= 0) {"),
                "the guard comes before any registry lookup");
        assertTrue(source.contains("if (player == null || stat == null || amount <= 0) {"));
        assertTrue(source.contains("catch (RuntimeException notReady)"),
                "and a statistic that cannot be written never interrupts what the player was doing");
    }

    @Test
    void eachAwardSitsOnACommittedEventRatherThanATickOrAPacket() {
        String apply = read(TestPaths.sources("dev", "otectus", "mcacrime", "restraint",
                "RestraintService.java"));
        int committed = apply.indexOf("if (!result.applied()) {");
        int awarded = apply.indexOf("awardRestrained(subject, definitionId);");
        assertTrue(committed > 0 && awarded > committed,
                "times_restrained is awarded after the transaction committed, not when it was attempted");

        String escape = read(TestPaths.sources("dev", "otectus", "mcacrime", "restraint",
                "EscapeService.java"));
        assertTrue(escape.contains("if (removal.removed()) {")
                        && escape.contains("CrimeStatIds.Kind.BROKEN"),
                "broken is awarded on the one input that took the durability to zero");

        String safe = read(TestPaths.sources("dev", "otectus", "mcacrime", "block",
                "entity", "SafeBlockEntity.java"));
        assertTrue(safe.contains("CrimeStats.OPEN_SAFE"), "open_safe is awarded when a safe opens");
        assertTrue(safe.contains("if (!this.remove && !player.isSpectator())"),
                "and not for a spectator or a removed block");

        String events = read(TestPaths.sources("dev", "otectus", "mcacrime", "restraint",
                "RestraintServerEvents.java"));
        assertTrue(events.contains("NPC_CUSTODY_INTERVAL_TICKS)"),
                "time spent is credited in one-second batches rather than per tick");
        assertFalse(events.contains("creditTimeRestrained(server);\n        }\n        RestraintService"),
                "and inside the batched branch, not on every tick");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
