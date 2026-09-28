package dev.otectus.mcacrime.config;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.McaCrimeConfig.RestraintPreset;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The two tunings {@code restraints.preset} names, and the one place that writes them (0.7.5 M7.2).
 *
 * <h2>What a preset is</h2>
 * A named set of values for keys an operator could already set by hand. It is <b>not</b> a second
 * source of truth read at runtime: every gameplay class still reads its own key, so a preset that has
 * been applied is indistinguishable from the same values typed in. That is deliberate — a preset that
 * overrode keys at read time would make the config file a lie about what the server is doing.
 *
 * <h2>Never imposed silently</h2>
 * Applying a preset is an explicit act. {@code restraints.appliedPreset} records which tuning the file
 * was last written from, so:
 *
 * <ul>
 *   <li>a fresh install applies nothing — the shipped defaults already <em>are</em> the parity preset,
 *       with the single documented exception below;</li>
 *   <li>changing {@code restraints.preset} rewrites exactly the keys in that preset's table, once, and
 *       every changed key is named in the log with its old and new value;</li>
 *   <li>editing one of those keys afterwards is respected: nothing re-applies until the preset value
 *       changes again.</li>
 * </ul>
 *
 * <h2>The one place the shipped defaults are not the parity preset</h2>
 * {@code lockpicking.destructiveOutcome} ships {@code false}. Upstream destroys a picked door or safe,
 * so {@code CUFFED_PARITY} sets it {@code true}; but decision D10 settled non-destructive unlocking as
 * this mod's default outcome, and a default that destroyed a player's safe the first time somebody
 * picked it is not a default anybody should arrive at by accident. An operator who explicitly asks for
 * upstream parity gets upstream's outcome; everybody else keeps their block.
 *
 * <h2>Pure</h2>
 * {@link #values(RestraintPreset)} and {@link #describe(RestraintPreset, Map)} touch no config at all,
 * which is what makes both tables assertable without a running game. {@link #apply} is the only method
 * here that writes.
 */
public final class RestraintPresets {

    private RestraintPresets() {
    }

    // The key paths, spelled exactly as they appear in the TOML, because these strings end up in the
    // log an operator reads next to their own file.
    public static final String CHANNEL_TICKS = "restraints.application.channelTicks";
    public static final String REQUIRE_LINE_OF_SIGHT = "restraints.application.requireLineOfSight";
    public static final String VULNERABILITY_GATES = "restraints.application.vulnerabilityGates";
    public static final String ENABLE_KIDNAPPING_PLAYER = "kidnapping.enableKidnappingPlayer";
    public static final String DESTRUCTIVE_OUTCOME = "lockpicking.destructiveOutcome";

    /**
     * The vulnerability gates {@code BALANCED_VILLAGE} asks for.
     *
     * <p>Alternatives, not conjunctions: any one of them is an opening. The list is the set of
     * conditions in which restraining somebody is an act against a person who is already out of the
     * fight, which is what "vulnerability requirement" means in a village.
     */
    public static final List<String> BALANCED_GATES =
            List.of("low_health", "sleeping", "unconscious", "surrendered", "detained");

    /**
     * Every key one preset owns, in the order they are written and reported.
     *
     * <p>Both presets own the <em>same</em> keys. A preset that set a key its counterpart did not
     * could not be undone by switching back, which would make the second preset a trapdoor.
     */
    public static Map<String, Object> values(@Nullable RestraintPreset preset) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (preset == RestraintPreset.BALANCED_VILLAGE) {
            values.put(CHANNEL_TICKS, 60);
            values.put(REQUIRE_LINE_OF_SIGHT, true);
            values.put(VULNERABILITY_GATES, BALANCED_GATES);
            values.put(ENABLE_KIDNAPPING_PLAYER, false);
            values.put(DESTRUCTIVE_OUTCOME, false);
            return values;
        }
        values.put(CHANNEL_TICKS, 0);
        values.put(REQUIRE_LINE_OF_SIGHT, true);
        values.put(VULNERABILITY_GATES, List.of());
        values.put(ENABLE_KIDNAPPING_PLAYER, true);
        values.put(DESTRUCTIVE_OUTCOME, true);
        return values;
    }

    /**
     * Whether {@code requested} still has to be written over what {@code applied} left behind.
     *
     * <p>A file with no marker is left alone when the requested preset is the shipped default. That
     * case is a fresh or hand-blanked file, and the shipped defaults already <em>are</em>
     * {@code CUFFED_PARITY} apart from {@code lockpicking.destructiveOutcome}, which D10 ships
     * {@code false} on purpose — so "apply the default preset because there is no marker" would move
     * exactly one key, and it would be the one key nobody chose. An explicitly selected non-default
     * preset still applies over a blank marker, because selecting it is the act.
     */
    public static boolean shouldApply(@Nullable RestraintPreset requested, @Nullable String applied) {
        if (requested == null) {
            return false;
        }
        String marker = applied == null ? "" : applied.trim();
        if (marker.isEmpty()) {
            return requested != shippedDefault();
        }
        return !requested.name().equalsIgnoreCase(marker);
    }

    /**
     * The log lines for applying {@code preset} over {@code current}, one per key that actually moves.
     *
     * <p>Pure, and separate from {@link #apply} so the exact sentence an operator reads can be
     * asserted. A preset that changed nothing says so in one line rather than none: silence would be
     * indistinguishable from a preset that failed to apply.
     */
    public static List<String> describe(@Nullable RestraintPreset preset,
                                        @Nullable Map<String, Object> current) {
        List<String> report = new ArrayList<>();
        if (preset == null) {
            return report;
        }
        Map<String, Object> wanted = values(preset);
        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, Object> entry : wanted.entrySet()) {
            Object was = current == null ? null : current.get(entry.getKey());
            if (!equal(was, entry.getValue())) {
                changes.add("  - " + entry.getKey() + ": " + text(was) + " -> " + text(entry.getValue()));
            }
        }
        if (changes.isEmpty()) {
            report.add("MCA: Crime applied the " + preset.name() + " restraint preset; every key it owns "
                    + "already held that value, so nothing was rewritten.");
            return report;
        }
        report.add("MCA: Crime applied the " + preset.name() + " restraint preset, rewriting "
                + changes.size() + " setting(s):");
        report.addAll(changes);
        return report;
    }

    /**
     * Writes {@code preset} into the live common config and returns the report.
     *
     * <p>The caller saves the file and records {@code appliedPreset}; doing it here would make this
     * method impossible to call from anything but a running game, and the caller is the only thing
     * that holds the {@code ModConfig} that can save.
     */
    public static List<String> apply(@Nullable McaCrimeConfig.Common config,
                                     @Nullable RestraintPreset preset) {
        if (config == null || preset == null) {
            return List.of();
        }
        Map<String, Object> current = new LinkedHashMap<>();
        current.put(CHANNEL_TICKS, config.applicationChannelTicks.get());
        current.put(REQUIRE_LINE_OF_SIGHT, config.applicationRequireLineOfSight.get());
        current.put(VULNERABILITY_GATES, List.copyOf(config.vulnerabilityGates.get()));
        current.put(ENABLE_KIDNAPPING_PLAYER, config.enableKidnappingPlayer.get());
        current.put(DESTRUCTIVE_OUTCOME, config.lockpickDestructiveOutcome.get());

        List<String> report = describe(preset, current);
        Map<String, Object> wanted = values(preset);
        config.applicationChannelTicks.set((Integer) wanted.get(CHANNEL_TICKS));
        config.applicationRequireLineOfSight.set((Boolean) wanted.get(REQUIRE_LINE_OF_SIGHT));
        @SuppressWarnings("unchecked")
        List<String> gates = List.copyOf((List<String>) wanted.get(VULNERABILITY_GATES));
        config.vulnerabilityGates.set(gates);
        config.enableKidnappingPlayer.set((Boolean) wanted.get(ENABLE_KIDNAPPING_PLAYER));
        config.lockpickDestructiveOutcome.set((Boolean) wanted.get(DESTRUCTIVE_OUTCOME));
        config.appliedPreset.set(preset.name());
        return report;
    }

    /** The preset {@code restraints.preset} ships selected: the parity tuning. */
    public static RestraintPreset shippedDefault() {
        return RestraintPreset.CUFFED_PARITY;
    }

    /** Parses a preset name, falling back to the shipped parity tuning. */
    public static RestraintPreset parse(@Nullable String name) {
        if (name != null) {
            for (RestraintPreset preset : RestraintPreset.values()) {
                if (preset.name().equalsIgnoreCase(name.trim())) {
                    return preset;
                }
            }
        }
        return RestraintPreset.CUFFED_PARITY;
    }

    private static boolean equal(@Nullable Object a, @Nullable Object b) {
        if (a instanceof List<?> first && b instanceof List<?> second) {
            return first.size() == second.size() && first.toString().equals(second.toString());
        }
        return a != null && a.equals(b);
    }

    private static String text(@Nullable Object value) {
        if (value == null) {
            return "(unset)";
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "[]" : "[" + String.join(", ", list.stream().map(String::valueOf)
                    .map(s -> s.toLowerCase(Locale.ROOT)).toList()) + "]";
        }
        return String.valueOf(value);
    }
}
