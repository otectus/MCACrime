package dev.otectus.mcacrime.block.entity;

/**
 * How many slots a safe has, and what happens when that number changes (M3.5, spec §10.3).
 *
 * <p>Pure arithmetic, separated from the block entity so the one rule that matters can be asserted
 * without a world: <b>a smaller configured size never destroys stored items</b>. The container keeps
 * whatever it is already holding and the menu simply shows fewer rows, which is the difference between
 * "the operator lowered a setting" and "the operator deleted a server's valuables".
 *
 * <p>36 is the value the specification asks for and the one this mod uses. Upstream declares 36 in its
 * config, never reads it, and hard-codes 27 into {@code getContainerSize}
 * ({@code blocks/entity/SafeBlockEntity.java:173-175}); there is one authoritative number here.
 */
public final class SafeInventoryPolicy {

    /** The source-derived default (spec §10.3). */
    public static final int DEFAULT_SLOTS = 36;
    public static final int MIN_SLOTS = 9;
    public static final int MAX_SLOTS = 54;
    private static final int ROW = 9;

    private SafeInventoryPolicy() {
    }

    /** A configured value rounded down to whole rows and clamped into what a chest menu can show. */
    public static int clampConfigured(int configured) {
        int clamped = Math.max(MIN_SLOTS, Math.min(MAX_SLOTS, configured));
        return (clamped / ROW) * ROW;
    }

    /**
     * The real container size: the configured size, or what is already stored when that is larger.
     *
     * <p>The overflow is carried, not truncated. Nothing can read those slots through the menu — the
     * menu is built from {@link #menuSlots} — but they are saved, they are dropped when the block
     * breaks, and they come back into view the moment the setting goes up again.
     */
    public static int resolvedSize(int configured, int storedCount) {
        return Math.max(clampConfigured(configured), Math.max(0, storedCount));
    }

    /** How many rows the menu shows for a container of {@code size}. Never more than a chest can. */
    public static int rows(int size) {
        return Math.max(1, Math.min(6, size / ROW));
    }

    /** How many slots the menu shows: whole rows, and never more than the container holds. */
    public static int menuSlots(int size) {
        return Math.min(Math.max(ROW, size), rows(size) * ROW);
    }

    /** The configured size, or the default when no config is loaded. */
    public static int configuredSlots() {
        try {
            return clampConfigured(dev.otectus.mcacrime.McaCrimeConfig.COMMON.safeSlots.get());
        } catch (IllegalStateException notLoaded) {
            return DEFAULT_SLOTS;
        }
    }
}
