package dev.otectus.mcacrime.lockpick;

import dev.otectus.mcacrime.restraint.RestraintDefinition;

import org.jetbrains.annotations.Nullable;
import java.util.Locale;
import java.util.Optional;

/**
 * The seven difficulty profiles of specification §8, as one table (M3.3).
 *
 * <p>Each profile is two numbers: how much meter one successful alignment adds, and how fast the
 * meter drains while the phase runs. They are the source's own values, carried over exactly — see the
 * table in the specification — and they are here rather than in the config because they are parity
 * parameters, not tuning knobs. What <em>is</em> configurable is the reinterpretation around them:
 * {@code lockpicking.drainPerTickDivisor} turns a per-rendered-frame drain into a per-tick one, and
 * the two window settings widen or narrow the alignment window.
 *
 * <p>The restraint profiles duplicate the ones already carried on
 * {@link RestraintDefinition.PickProfile}. They are listed here as well so the six can be asserted
 * as a set, and {@link #of} is the bridge: a definition's own numbers win, and this table is the
 * fallback for anything that names no profile.
 */
public enum LockpickProfile {

    PADLOCK("padlock", 8, 10),
    REINFORCED_PADLOCK("reinforced_padlock", 6, 13),
    CELL_DOOR("cell_door", 6, 14),
    SAFE("safe", 3, 10),
    HANDCUFFS("handcuffs", 6, 12),
    SHACKLES("shackles", 8, 10);

    /** The meter a session opens on (source: {@code ticksLeftToPick = 30}). */
    public static final int START_METER = 30;
    /** The meter a session wins at. */
    public static final int WIN_METER = 40;
    /** The meter a session fails at. */
    public static final int FAIL_METER = 0;

    private final String id;
    private final int progressIncrease;
    private final int speedIncrease;

    LockpickProfile(String id, int progressIncrease, int speedIncrease) {
        this.id = id;
        this.progressIncrease = progressIncrease;
        this.speedIncrease = speedIncrease;
    }

    public String id() {
        return id;
    }

    /** Meter added by one successful alignment. */
    public int progressIncrease() {
        return progressIncrease;
    }

    /** The drain parameter: larger drains faster. */
    public int speedIncrease() {
        return speedIncrease;
    }

    public static Optional<LockpickProfile> byId(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        for (LockpickProfile profile : values()) {
            if (profile.id.equals(id.trim().toLowerCase(Locale.ROOT))) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    /** The profile for a padlock, harder when it has been reinforced. */
    public static LockpickProfile forPadlock(boolean reinforced) {
        return reinforced ? REINFORCED_PADLOCK : PADLOCK;
    }

    /**
     * A restraint definition's own pick numbers, as a profile-shaped pair.
     *
     * <p>Returns empty for a definition nothing can pick — tape, a hood, the pillory — which is how a
     * session refuses to open on them rather than opening and never being winnable.
     */
    public static Optional<Pick> of(@Nullable RestraintDefinition definition) {
        if (definition == null || !definition.pick().pickable()) {
            return Optional.empty();
        }
        return Optional.of(new Pick(definition.pick().progressIncrease(),
                definition.pick().speedIncrease()));
    }

    /** This profile's numbers, detached from the enum. */
    public Pick pick() {
        return new Pick(progressIncrease, speedIncrease);
    }

    /**
     * One target's numbers.
     *
     * @param progressIncrease meter added per successful alignment
     * @param speedIncrease    the drain parameter
     */
    public record Pick(int progressIncrease, int speedIncrease) {
        public Pick {
            progressIncrease = Math.max(1, progressIncrease);
            speedIncrease = Math.max(1, speedIncrease);
        }
    }
}
