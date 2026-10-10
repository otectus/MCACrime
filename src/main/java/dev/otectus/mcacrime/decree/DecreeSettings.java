package dev.otectus.mcacrime.decree;

import dev.otectus.mcacrime.McaCrimeConfig;

/**
 * The {@code [capitals]} and {@code [capitals.decrees]} settings a decree is decided by, read once per
 * decision so one decision never mixes two configurations (0.7.8).
 *
 * @param finesEnabled the server-wide {@code enableFines}: a server that will not let a case be bought off
 *                     will not let a decree be either
 */
public record DecreeSettings(boolean integrationEnabled, boolean decreesEnabled, long durationTicks,
                             long cooldownTicks, int maxOpenPerCapital, long sentenceTicks, long fineAmount,
                             boolean finesEnabled, boolean chronicle, boolean chronicleHerald,
                             boolean readPlayerWarrants) {

    /** Everything off; what a server without a loaded config reads. */
    public static final DecreeSettings OFF = new DecreeSettings(false, false, 72000L, 24000L, 8, 6000L, 0L,
            false, false, false, false);

    public static DecreeSettings fromConfig() {
        try {
            McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
            return new DecreeSettings(c.capitalsEnabled.get(), c.decreesEnabled.get(), c.decreesDurationTicks.get(),
                    c.decreesCooldownTicks.get(), c.decreesMaxOpenPerCapital.get(), c.decreesSentenceTicks.get(),
                    c.decreesFineAmount.get(), c.enableFines.get(), c.decreesChronicle.get(),
                    c.decreesChronicleHerald.get(), c.capitalsReadPlayerWarrants.get());
        } catch (Throwable notLoaded) {
            return OFF;
        }
    }

    /** Whether decrees may be issued and enforced at all. */
    public boolean on() {
        return integrationEnabled && decreesEnabled;
    }

    /** Whether a decree can be settled with money. */
    public boolean payable() {
        return finesEnabled && fineAmount > 0L;
    }

    /** The fine a guard's challenge offers for a decree, or 0 when none can be offered. */
    public long guardFine() {
        return on() && payable() ? fineAmount : 0L;
    }
}
