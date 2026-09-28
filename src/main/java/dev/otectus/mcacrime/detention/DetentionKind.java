package dev.otectus.mcacrime.detention;

import java.util.Locale;

/** Which device is holding a subject. */
public enum DetentionKind {

    /** Fixed occupancy and pose; separate from any head-slot gear the subject is wearing. */
    PILLORY,
    /** Occupied while an execution is authorised; the device never takes a life on its own. */
    GUILLOTINE,
    /** A prison bunk: occupancy and a respawn point that must not overwrite a newer one. */
    BUNK;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Unknown names fall back to {@link #PILLORY}: the least dangerous device to be wrong about. */
    public static DetentionKind parse(String raw) {
        if (raw == null) {
            return PILLORY;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT);
        for (DetentionKind kind : values()) {
            if (kind.name().equals(name)) {
                return kind;
            }
        }
        return PILLORY;
    }
}
