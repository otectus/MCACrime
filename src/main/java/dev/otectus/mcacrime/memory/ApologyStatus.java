package dev.otectus.mcacrime.memory;

import java.util.Collection;
import java.util.UUID;

/** Shared eligibility for menu feedback and the memories an apology may actually change. */
public enum ApologyStatus {
    READY(""),
    NOT_NEEDED("mcacrime.apologize.not_needed"),
    GIVE_SPACE("mcacrime.apologize.give_space"),
    COOLDOWN("mcacrime.apologize.cooldown"),
    ALREADY_APOLOGIZED("mcacrime.apologize.already_apologized"),
    DISABLED("mcacrime.apologize.disabled");

    private static final long SETTLE_TICKS = 1200;
    private final String reason;

    ApologyStatus(String reason) { this.reason = reason; }

    public String reason() { return reason; }

    public static ApologyStatus forMemory(VictimCrimeMemory memory, long now, long cooldown) {
        if (memory.apologized()) return ALREADY_APOLOGIZED;
        if (now - memory.timestamp() < SETTLE_TICKS) return GIVE_SPACE;
        if (memory.apologyAt() > 0 && now - memory.apologyAt() < cooldown) return COOLDOWN;
        return READY;
    }

    public static ApologyStatus evaluate(Collection<VictimCrimeMemory> memories, UUID offender,
                                         long now, long cooldown) {
        ApologyStatus result = NOT_NEEDED;
        for (var memory : memories) {
            if (!memory.perpetrator().equals(offender)) continue;
            ApologyStatus status = forMemory(memory, now, cooldown);
            if (status == READY) return READY;
            // An outstanding offense takes precedence over one already acknowledged.
            if (result == NOT_NEEDED || result == ALREADY_APOLOGIZED || status == COOLDOWN) result = status;
        }
        return result;
    }
}
