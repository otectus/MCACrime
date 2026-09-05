package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.captivity.RestraintType;

/**
 * What a restrained subject's wrists look like (spec §8.3 presentation).
 *
 * <p>Deliberately coarser than {@link RestraintType}: the escape-difficulty difference between cuffs
 * and locked cuffs is gameplay, and drawing two nearly identical metal bands would be a texture nobody
 * could tell apart at render distance. Rope is the one that has to read differently, so it is the one
 * that gets its own constant.
 *
 * <p>Lives in {@code enforcement} rather than {@code client} because the packets carry it, and a
 * dedicated server has to be able to name it.
 */
public enum RestraintVisualType {
    NONE,
    HANDCUFFS,
    ROPE;

    /** Total over {@link RestraintType} — a new restraint must choose a look here or fail to compile. */
    public static RestraintVisualType of(RestraintType type) {
        if (type == null) {
            return NONE;
        }
        return switch (type) {
            case NONE -> NONE;
            case ROPE -> ROPE;
            case CUFFS, LOCKED_CUFFS -> HANDCUFFS;
        };
    }
}
