package dev.otectus.mcacrime.memory;

import dev.otectus.mcacrime.api.model.VictimMemoryView;
import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

/** One bounded perpetrator/category memory; emotional decay is calculated lazily in game time. */
public record VictimCrimeMemory(UUID perpetrator, UUID incident, UUID victim, CrimeMemoryCategory category,
                               long timestamp, long updatedAt, long duration, double severity,
                               double fear, double anger, int repeatCount, boolean indirect,
                               boolean apologized, boolean restitutionPaid, boolean sentenceServed,
                               long apologyAt) {
    /**
     * Severity at and above which a memory is grave (0.7.7): it keeps a small residual, an apology
     * softens it only slightly, and an accepted apology does not by itself reopen conversation.
     * One constant because those three rules used to read two different numbers, 0.8 and 0.7, and a
     * single punch (severity 0.7) fell on the wrong side of the second one.
     */
    public static final double GRAVE_SEVERITY = 0.8;

    public VictimCrimeMemory {
        severity = clamp(severity); fear = clamp(fear); anger = clamp(anger);
        repeatCount = Math.max(1, Math.min(100, repeatCount));
        duration = Math.max(24000, Math.min(365L * 24000, duration));
        timestamp = Math.max(0, timestamp); updatedAt = Math.max(timestamp, updatedAt);
    }
    private static double clamp(double n) { return Double.isFinite(n) ? Math.max(0, Math.min(1, n)) : 0; }
    public String key() { return perpetrator + ":" + category; }
    private double decay(double value, long now, double multiplier) {
        double elapsed = Math.max(0, now - updatedAt) * Math.max(0, multiplier);
        // Severe memories retain a small, non-blocking residual; lesser memories can fade entirely.
        double floor = floor();
        return Math.min(value, floor + Math.max(0, value - floor) * Math.pow(0.05, elapsed / duration));
    }
    private double floor() { return severity >= GRAVE_SEVERITY ? 0.08 * severity : 0; }
    /**
     * Game ticks from {@code now} until both fear and anger have decayed below these thresholds, 0 when
     * they already have, and {@link Long#MAX_VALUE} when they never will (a decay multiplier of 0, or a
     * residual floor at or above a threshold).
     */
    public long ticksUntilCalm(long now, double multiplier, double fearThreshold, double angerThreshold) {
        return Math.max(ticksUntilBelow(fear, now, multiplier, fearThreshold),
                ticksUntilBelow(anger, now, multiplier, angerThreshold));
    }
    private long ticksUntilBelow(double value, long now, double multiplier, double threshold) {
        if (decay(value, now, multiplier) < threshold) return 0L;
        double floor = floor();
        if (!(multiplier > 0) || threshold <= floor || value <= floor) return Long.MAX_VALUE;
        // Solve floor + (value - floor) * 0.05^(e / duration) < threshold for the decay time e, then turn
        // decay time back into game ticks from now.
        double decayNeeded = duration * Math.log((threshold - floor) / (value - floor)) / Math.log(0.05);
        double ticks = decayNeeded / multiplier - Math.max(0, now - updatedAt);
        if (!Double.isFinite(ticks) || ticks >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(1L, (long) Math.ceil(ticks) + 1L);
    }
    public double fearAt(long now, double multiplier) { return decay(fear, now, multiplier); }
    public double angerAt(long now, double multiplier) { return decay(anger, now, multiplier); }
    public double importance(long now, double multiplier) { return severity + fearAt(now, multiplier) + angerAt(now, multiplier); }
    public VictimMemoryView view(long now, double multiplier) {
        return new VictimMemoryView(perpetrator, incident, victim, category.name(), timestamp, severity,
                fearAt(now, multiplier), angerAt(now, multiplier), repeatCount, indirect,
                apologized, restitutionPaid, sentenceServed);
    }
    public VictimCrimeMemory merge(VictimCrimeMemory next, long now, double multiplier) {
        if (incident.equals(next.incident)) return this;
        return new VictimCrimeMemory(perpetrator, next.incident, next.victim, category, now, now,
                Math.max(duration, next.duration), Math.max(severity, next.severity),
                Math.max(next.fear, fearAt(now, multiplier)) + next.fear * 0.18,
                Math.max(next.anger, angerAt(now, multiplier)) + next.anger * 0.18,
                repeatCount + 1, indirect, false, false, false, apologyAt);
    }
    public VictimCrimeMemory reconcile(long now, double multiplier, boolean apology, boolean restitution, boolean served) {
        double fearFactor = served && !sentenceServed ? 0.8 : 1;
        double angerFactor = restitution && !restitutionPaid ? 0.65 : served && !sentenceServed ? 0.8 : 1;
        if (apology && !apologized) angerFactor *= severity >= GRAVE_SEVERITY || repeatCount > 2 ? 0.97 : 0.85;
        return new VictimCrimeMemory(perpetrator, incident, victim, category, timestamp, now, duration, severity,
                fearAt(now, multiplier) * fearFactor, angerAt(now, multiplier) * angerFactor, repeatCount, indirect,
                apologized || apology, restitutionPaid || restitution, sentenceServed || served, apology ? now : apologyAt);
    }
    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("perpetrator", perpetrator); t.putUUID("incident", incident); t.putUUID("victim", victim);
        t.putString("category", category.name()); t.putLong("timestamp", timestamp); t.putLong("updated", updatedAt);
        t.putLong("duration", duration); t.putDouble("severity", severity); t.putDouble("fear", fear); t.putDouble("anger", anger);
        t.putInt("repeats", repeatCount); t.putBoolean("indirect", indirect); t.putBoolean("apologized", apologized);
        t.putBoolean("restitution", restitutionPaid); t.putBoolean("served", sentenceServed); t.putLong("apologyAt", apologyAt);
        return t;
    }
    public static VictimCrimeMemory load(CompoundTag t) {
        if (!t.hasUUID("perpetrator") || !t.hasUUID("incident") || !t.hasUUID("victim"))
            throw new IllegalArgumentException("missing memory identity");
        return new VictimCrimeMemory(t.getUUID("perpetrator"), t.getUUID("incident"), t.getUUID("victim"),
                CrimeMemoryCategory.valueOf(t.getString("category")), t.getLong("timestamp"), t.getLong("updated"),
                t.getLong("duration"), t.getDouble("severity"), t.getDouble("fear"), t.getDouble("anger"),
                t.getInt("repeats"), t.getBoolean("indirect"), t.getBoolean("apologized"), t.getBoolean("restitution"),
                t.getBoolean("served"), t.getLong("apologyAt"));
    }
}
