package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * The one place a restraint's starting durability is decided (0.7.5 §M2.2).
 *
 * <p>Durability is a property of the <em>definition</em>, configured per definition, and stamped
 * onto the {@link AppliedRestraint} instance at the moment it goes on. It is deliberately not the
 * item's vanilla damage value: two prisoners in two pairs of handcuffs must have two counters, and
 * an item that carried the number would put it back on the stack the applier is holding. The source
 * registers all three restraint items with a placeholder durability of 999 and then never uses it,
 * which is the shape of number this class exists to replace.
 *
 * <p>{@link Settings} is a plain record so the whole mapping can be asserted without a running
 * config: the defect worth a test is the source reading its arm-tape setting for leg tape, and that
 * is a pure function of seven integers.
 */
public final class RestraintDurability {

    private RestraintDurability() {
    }

    /**
     * The six configured durabilities, as values.
     *
     * <p>Six independent fields rather than a tape pair, because "arm tape and leg tape are the
     * same number" is a default, not a law: a server may reasonably make leg tape flimsier than arm
     * tape, and the source's version of this cannot.
     */
    public record Settings(int handcuffs, int shackles, int duckTapeArms,
                           int duckTapeLegs, int duckTapeHead, int bundleHood) {

        public Settings {
            handcuffs = clamp(handcuffs);
            shackles = clamp(shackles);
            duckTapeArms = clamp(duckTapeArms);
            duckTapeLegs = clamp(duckTapeLegs);
            duckTapeHead = clamp(duckTapeHead);
            bundleHood = clamp(bundleHood);
        }

        private static int clamp(int value) {
            return Math.max(1, value);
        }

        /** The shipped defaults, and what an unloaded config resolves to. */
        public static Settings defaults() {
            return new Settings(40, 15, 5, 5, 5, 5);
        }
    }

    /**
     * The configured durability of {@code definitionId} under {@code settings}.
     *
     * <p>Falls back to the definition's own declared durability for anything the settings do not
     * name — the pillory, and any definition a later milestone adds — rather than to a constant, so
     * a new definition is never silently worth one struggle input.
     */
    public static int resolve(@Nullable ResourceLocation definitionId, @Nullable Settings settings) {
        Settings s = settings == null ? Settings.defaults() : settings;
        if (definitionId == null) {
            return 0;
        }
        // A datapack profile is the more specific statement: it names this definition, where the
        // config keys name a family-wide default. Absent one, the config still decides (§3.13).
        java.util.OptionalInt overridden = RestraintProfileOverrides.durability(definitionId);
        if (overridden.isPresent()) {
            return overridden.getAsInt();
        }
        if (RestraintDefinitions.HANDCUFFS_ARMS.equals(definitionId)
                || RestraintDefinitions.HANDCUFFS_LEGS.equals(definitionId)) {
            return s.handcuffs();
        }
        if (RestraintDefinitions.SHACKLES_ARMS.equals(definitionId)
                || RestraintDefinitions.SHACKLES_LEGS.equals(definitionId)) {
            return s.shackles();
        }
        if (RestraintDefinitions.DUCK_TAPE_ARMS.equals(definitionId)) {
            return s.duckTapeArms();
        }
        if (RestraintDefinitions.DUCK_TAPE_LEGS.equals(definitionId)) {
            return s.duckTapeLegs();
        }
        if (RestraintDefinitions.DUCK_TAPE_HEAD.equals(definitionId)) {
            return s.duckTapeHead();
        }
        if (RestraintDefinitions.BUNDLE.equals(definitionId)) {
            return s.bundleHood();
        }
        return RestraintDefinitions.get(definitionId)
                .map(definition -> definition.escape().durability())
                .orElse(0);
    }

    /** The live configured settings, or the shipped defaults when the config is not loaded yet. */
    public static Settings settings() {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        try {
            return new Settings(
                    c.durabilityHandcuffs.get(),
                    c.durabilityShackles.get(),
                    c.durabilityDuckTapeArms.get(),
                    c.durabilityDuckTapeLegs.get(),
                    c.durabilityDuckTapeHead.get(),
                    c.durabilityBundleHood.get());
        } catch (IllegalStateException e) {
            return Settings.defaults(); // config not loaded yet (startup, or a unit test)
        }
    }

    /** The configured starting durability of one definition. */
    public static int startingDurability(@Nullable ResourceLocation definitionId) {
        return resolve(definitionId, settings());
    }

    /** The configured starting durability of one definition. */
    public static int startingDurability(@Nullable RestraintDefinition definition) {
        return definition == null ? 0 : startingDurability(definition.id());
    }
}
