package dev.otectus.mcacrime.mug.npc;

import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * One thief-on-player mugging, from the threat to the moment it ends.
 *
 * <p>Mutable and memory-only, like {@code ActionSession} on the player-originated side: a session
 * lasts four seconds by default, and spec §23.4 is explicit that something that short must not
 * persist. A server that stops mid-mug comes back with the player unrobbed, which is the outcome
 * everybody would rather have anyway.
 *
 * <p>The session holds no entities. {@link NpcMuggingService} resolves both parties every tick from
 * their ids, which is also how "the victim logged out" stops being a special case.
 */
public final class NpcMugSession {

    /** Where the session is. Only {@code RUNNING} is still costing the victim anything. */
    public enum Phase {
        RUNNING,
        FINISHED,
        ABORTED
    }

    private final UUID transactionId;
    private final UUID thiefId;
    private final UUID victimId;
    private final ResourceLocation dimension;
    private final long startedAt;
    private final int requiredTicks;

    private int progress;
    private Phase phase = Phase.RUNNING;
    private long nextHudAt;
    private long nextWeaponCheckAt;

    public NpcMugSession(UUID transactionId, UUID thiefId, UUID victimId, ResourceLocation dimension,
                         long startedAt, int requiredTicks) {
        this.transactionId = transactionId;
        this.thiefId = thiefId;
        this.victimId = victimId;
        this.dimension = dimension;
        this.startedAt = startedAt;
        this.requiredTicks = Math.max(1, requiredTicks);
    }

    public UUID transactionId() {
        return transactionId;
    }

    public UUID thiefId() {
        return thiefId;
    }

    public UUID victimId() {
        return victimId;
    }

    public ResourceLocation dimension() {
        return dimension;
    }

    public long startedAt() {
        return startedAt;
    }

    public int requiredTicks() {
        return requiredTicks;
    }

    public int progress() {
        return progress;
    }

    /** Advances the timer by one tick, never past the requirement. */
    public void advance() {
        if (progress < requiredTicks) {
            progress++;
        }
    }

    public boolean complete() {
        return progress >= requiredTicks;
    }

    public Phase phase() {
        return phase;
    }

    public void markFinished() {
        this.phase = Phase.FINISHED;
    }

    public void markAborted() {
        this.phase = Phase.ABORTED;
    }

    public boolean running() {
        return phase == Phase.RUNNING;
    }

    public boolean shouldSendHud(long now) {
        return now >= nextHudAt;
    }

    public void scheduleHud(long now, int interval) {
        this.nextHudAt = now + Math.max(1, interval);
    }

    public boolean shouldCheckWeapon(long now) {
        return now >= nextWeaponCheckAt;
    }

    public void scheduleWeaponCheck(long now, int interval) {
        this.nextWeaponCheckAt = now + Math.max(1, interval);
    }
}
