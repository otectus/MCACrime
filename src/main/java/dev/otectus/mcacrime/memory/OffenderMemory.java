package dev.otectus.mcacrime.memory;

import net.minecraft.nbt.CompoundTag;

/** Persistent, target-specific memory of one offender. */
public final class OffenderMemory {
    private long lastAttempt;
    private long lastSuccess;
    private long fearUntil;
    private long panicUntil;
    private int attempts;
    private long stolenValue;
    private boolean pendingReport;

    public long lastAttempt() { return lastAttempt; }
    public long lastSuccess() { return lastSuccess; }
    public long fearUntil() { return fearUntil; }
    public long panicUntil() { return panicUntil; }
    public int attempts() { return attempts; }
    public long stolenValue() { return stolenValue; }
    public boolean pendingReport() { return pendingReport; }

    public void recordAttempt(long now, long fearDuration, long panicDuration) {
        lastAttempt = now;
        fearUntil = Math.max(fearUntil, now + Math.max(0L, fearDuration));
        panicUntil = Math.max(panicUntil, now + Math.max(0L, panicDuration));
        if (attempts < Integer.MAX_VALUE) attempts++;
        pendingReport = true;
    }

    public void recordSuccess(long now, long amount) {
        lastSuccess = now;
        stolenValue = dev.otectus.mcacrime.util.SafeMath.addSat(stolenValue, Math.max(0L, amount));
    }

    public void clearPendingReport() {
        pendingReport = false;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("attempt", lastAttempt);
        tag.putLong("success", lastSuccess);
        tag.putLong("fearUntil", fearUntil);
        tag.putLong("panicUntil", panicUntil);
        tag.putInt("attempts", attempts);
        tag.putLong("stolen", stolenValue);
        tag.putBoolean("pendingReport", pendingReport);
        return tag;
    }

    public static OffenderMemory load(CompoundTag tag) {
        OffenderMemory memory = new OffenderMemory();
        memory.lastAttempt = Math.max(0L, tag.getLong("attempt"));
        memory.lastSuccess = Math.max(0L, tag.getLong("success"));
        memory.fearUntil = Math.max(0L, tag.getLong("fearUntil"));
        memory.panicUntil = Math.max(0L, tag.getLong("panicUntil"));
        memory.attempts = Math.max(0, tag.getInt("attempts"));
        memory.stolenValue = Math.max(0L, tag.getLong("stolen"));
        memory.pendingReport = tag.getBoolean("pendingReport");
        return memory;
    }
}
