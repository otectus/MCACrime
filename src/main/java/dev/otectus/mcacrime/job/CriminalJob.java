package dev.otectus.mcacrime.job;

import org.jetbrains.annotations.Nullable;
import java.util.Locale;

/**
 * A villager's criminal occupation (0.5.1), persisted by this mod independently of MCA's profession.
 *
 * <p>Independent on purpose: MCA owns professions and reassigns them for its own reasons, so a job
 * stored only there would be lost the moment a villager changed workstation. The visible profession
 * is presentation; {@code CrimeWorldData.criminalVillagers} is the record of record.
 */
public enum CriminalJob {

    NONE,
    THIEF,
    FENCE;

    /** The lowercase form used in commands, config and NBT. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses {@link #id()}. Anything unrecognised, blank or null reads as {@link #NONE}. */
    public static CriminalJob parse(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
