package dev.otectus.mcacrime.ai.thief;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One thief's live behaviour, modelled on {@code ActiveCrimeReactionController}.
 *
 * <p>Memory-only, and for the same reason: a mugging lasts seconds. What survives a restart is the
 * criminal job in world data and the mug cooldown stamped on it, not the walk across the square.
 *
 * <p>The class holds state and schedules only. It never touches an entity — the service that owns it
 * resolves the villager, queries the world, and applies movement through {@code McaCompat}, which is
 * what lets the transition rules next door be unit-tested with no server.
 */
public final class ThiefBehaviorController {

    private final UUID thiefId;
    /** Where the thief was when it was tracked, so the service can find it again. */
    private final ResourceLocation dimension;

    private ThiefState state = ThiefState.IDLE;
    private long stateEnteredAt;
    private long nextThinkAt;
    private long nextScanAt;
    private long nextRiskAt;
    private long cooldownUntil;

    @Nullable
    private UUID victimId;
    /** Identity of the current attempt, shared with the mug session and the active incident. */
    @Nullable
    private UUID transactionId;
    @Nullable
    private Vec3 fleeTarget;
    /** Where the nearest guard was at the last risk evaluation — the ray the escape runs along. */
    @Nullable
    private Vec3 lastGuardPosition;
    private GuardRisk guardRisk = GuardRisk.none();
    /** Attempts that came to nothing, so a thief stops picking the same hopeless victim. */
    private int failures;
    /** Set when a guard reaches the thief; read and cleared by the next think. */
    private boolean guardIntervened;

    public ThiefBehaviorController(UUID thiefId, ResourceLocation dimension, long now) {
        this.thiefId = thiefId;
        this.dimension = dimension;
        this.stateEnteredAt = now;
    }

    public UUID thiefId() {
        return thiefId;
    }

    public ResourceLocation dimension() {
        return dimension;
    }

    public ThiefState state() {
        return state;
    }

    public long stateEnteredAt() {
        return stateEnteredAt;
    }

    public long ticksInState(long now) {
        return Math.max(0L, now - stateEnteredAt);
    }

    @Nullable
    public UUID victimId() {
        return victimId;
    }

    public void setVictim(@Nullable UUID victimId) {
        this.victimId = victimId;
    }

    @Nullable
    public UUID transactionId() {
        return transactionId;
    }

    public void setTransactionId(@Nullable UUID transactionId) {
        this.transactionId = transactionId;
    }

    @Nullable
    public Vec3 fleeTarget() {
        return fleeTarget;
    }

    public void setFleeTarget(@Nullable Vec3 fleeTarget) {
        this.fleeTarget = fleeTarget;
    }

    @Nullable
    public Vec3 lastGuardPosition() {
        return lastGuardPosition;
    }

    public GuardRisk guardRisk() {
        return guardRisk;
    }

    public void setGuardRisk(GuardRisk risk, @Nullable Vec3 nearestGuardPosition) {
        this.guardRisk = risk == null ? GuardRisk.none() : risk;
        if (nearestGuardPosition != null) {
            this.lastGuardPosition = nearestGuardPosition;
        }
    }

    public int failures() {
        return failures;
    }

    public void noteFailure() {
        failures++;
    }

    public void clearFailures() {
        failures = 0;
    }

    /**
     * Enters a new state, clearing the per-attempt scratch.
     *
     * @return true when the state actually changed, so the caller only runs entry work once
     */
    public boolean enter(ThiefState next, long now) {
        if (next == state) {
            return false;
        }
        this.state = next;
        this.stateEnteredAt = now;
        this.fleeTarget = null;
        // Think immediately on entry: a state that waits a full interval before its first decision
        // makes every transition read a beat late at close range.
        this.nextThinkAt = now;
        return true;
    }

    /** A guard has this thief. The state machine turns it into ARRESTED on the next think. */
    public void markGuardIntervention() {
        this.guardIntervened = true;
    }

    /** Reads the flag and clears it, so one intervention produces one transition. */
    public boolean consumeGuardIntervention() {
        boolean value = guardIntervened;
        guardIntervened = false;
        return value;
    }

    /** Brings the next think forward to the very next tick, for an outside event that cannot wait. */
    public void thinkAsSoonAsPossible() {
        this.nextThinkAt = Long.MIN_VALUE;
    }

    public boolean shouldThink(long now) {
        return now >= nextThinkAt;
    }

    public void scheduleThink(long now, int interval) {
        this.nextThinkAt = now + Math.max(1, interval);
    }

    public boolean shouldScan(long now) {
        return now >= nextScanAt;
    }

    public void scheduleScan(long now, int interval) {
        this.nextScanAt = now + Math.max(1, interval);
    }

    public boolean shouldEvaluateRisk(long now) {
        return now >= nextRiskAt;
    }

    public void scheduleRisk(long now, int interval) {
        this.nextRiskAt = now + Math.max(1, interval);
    }

    public long cooldownUntil() {
        return cooldownUntil;
    }

    public void setCooldownUntil(long until) {
        this.cooldownUntil = until;
    }
}
