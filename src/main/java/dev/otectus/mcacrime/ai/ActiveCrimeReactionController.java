package dev.otectus.mcacrime.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * One villager's live reaction (spec §11.2/§11.3). Memory-only and deliberately so: a reaction lasts
 * seconds, and §23.4 is explicit that short sessions must not persist. What survives a restart is the
 * villager's <em>memory</em> of the offender and any pending observation, both of which are in world
 * data; the panic itself does not, and a villager who was mid-flee when the server stopped comes back
 * calm but still afraid of you.
 *
 * <p>This class holds state and transition bookkeeping only. It never touches an entity — the service
 * that owns it resolves the villager, applies navigation through {@code McaCompat}, and feeds results
 * back in. Keeping it entity-free is what lets the transition rules be unit-tested with no server.
 */
public final class ActiveCrimeReactionController {

    private final UUID villagerId;
    /** Where the villager was when the reaction started, so the service can find it again. */
    private final ResourceLocation dimension;
    @Nullable
    private UUID offenderId;
    @Nullable
    private final UUID observationId;

    private VictimReactionState state = VictimReactionState.CALM;
    private long stateEnteredAt;
    private long stateExpiresAt;
    private long nextThinkAt;
    private long nextNavigationAt;
    @Nullable
    private BlockPos destination;
    private int pathFailures;
    private boolean reported;

    public ActiveCrimeReactionController(UUID villagerId, ResourceLocation dimension,
                                         @Nullable UUID offenderId, @Nullable UUID observationId, long now) {
        this.villagerId = villagerId;
        this.dimension = dimension;
        this.offenderId = offenderId;
        this.observationId = observationId;
        this.stateEnteredAt = now;
    }

    public UUID villagerId() {
        return villagerId;
    }

    public ResourceLocation dimension() {
        return dimension;
    }

    @Nullable
    public UUID offenderId() {
        return offenderId;
    }

    @Nullable
    public UUID observationId() {
        return observationId;
    }

    public VictimReactionState state() {
        return state;
    }

    public long stateEnteredAt() {
        return stateEnteredAt;
    }

    @Nullable
    public BlockPos destination() {
        return destination;
    }

    public void setDestination(@Nullable BlockPos destination) {
        this.destination = destination;
    }

    public boolean reported() {
        return reported;
    }

    public void markReported() {
        this.reported = true;
    }

    /**
     * How many times navigation has refused to produce a path. Three is the exit threshold: a villager
     * who cannot path anywhere is walled in, and continuing to retry forever would keep the controller
     * alive against a wall for the rest of the session.
     */
    public int pathFailures() {
        return pathFailures;
    }

    public void notePathFailure() {
        pathFailures++;
    }

    public void clearPathFailures() {
        pathFailures = 0;
    }

    /** Retargets an existing reaction — a second offender takes over an already-panicking villager. */
    public void retarget(@Nullable UUID newOffender) {
        this.offenderId = newOffender;
    }

    /**
     * Enters a new state, resetting the timers and the destination.
     *
     * @param duration ticks this state may last before it times out; 0 means no timeout
     * @return true when the state actually changed, so the caller knows whether to fire the event
     */
    public boolean enter(VictimReactionState next, long now, long duration) {
        if (next == state) {
            return false;
        }
        this.state = next;
        this.stateEnteredAt = now;
        this.stateExpiresAt = duration > 0L ? now + duration : 0L;
        this.destination = null;
        this.pathFailures = 0;
        // Think immediately on entry: a state that waits a full interval before its first decision
        // makes every transition feel a fifth of a second late, which is visible at close range.
        this.nextThinkAt = now;
        this.nextNavigationAt = now;
        return true;
    }

    public boolean timedOut(long now) {
        return stateExpiresAt > 0L && now >= stateExpiresAt;
    }

    public boolean shouldThink(long now) {
        return now >= nextThinkAt;
    }

    public void scheduleThink(long now, int interval) {
        this.nextThinkAt = now + Math.max(1, interval);
    }

    public boolean shouldRepath(long now) {
        return now >= nextNavigationAt;
    }

    public void scheduleRepath(long now, int interval) {
        this.nextNavigationAt = now + Math.max(1, interval);
    }

    /** Ticks the state has been held for. Used by dialogue and by the reporting hand-off. */
    public long ticksInState(long now) {
        return Math.max(0L, now - stateEnteredAt);
    }
}
