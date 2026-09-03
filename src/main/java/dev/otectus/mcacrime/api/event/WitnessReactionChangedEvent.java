package dev.otectus.mcacrime.api.event;

import dev.otectus.mcacrime.ai.VictimReactionState;
import net.neoforged.bus.api.Event;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * One villager's reaction state changing (spec §12.5). Fired server-side on
 * {@code NeoForge.EVENT_BUS} after the transition has been applied.
 *
 * <p>Not cancellable, and deliberately so. A reaction transition is not a policy decision another mod
 * should be able to veto halfway through — a villager stopped mid-flee by a cancelled event would be
 * left with this mod's navigation applied and no controller to clear it. Mods that want to prevent a
 * reaction cancel {@link CrimeObservationEvent.Pre} instead, which is upstream of it existing at all.
 *
 * <p>The villager is identified by UUID rather than handed over as an entity, so a listener cannot
 * mutate AI state from inside the transition that is still being applied.
 */
public final class WitnessReactionChangedEvent extends Event {

    private final UUID villagerId;
    @Nullable
    private final UUID offenderId;
    private final VictimReactionState from;
    private final VictimReactionState to;

    public WitnessReactionChangedEvent(UUID villagerId, @Nullable UUID offenderId,
                                       VictimReactionState from, VictimReactionState to) {
        this.villagerId = villagerId;
        this.offenderId = offenderId;
        this.from = from == null ? VictimReactionState.CALM : from;
        this.to = to == null ? VictimReactionState.CALM : to;
    }

    public UUID getVillagerId() {
        return villagerId;
    }

    /** Who the reaction is about, or null when the reaction has outlived a known offender. */
    @Nullable
    public UUID getOffenderId() {
        return offenderId;
    }

    public VictimReactionState getFrom() {
        return from;
    }

    public VictimReactionState getTo() {
        return to;
    }

    /** True when the villager has just returned to MCA's own AI. */
    public boolean isCalmed() {
        return to == VictimReactionState.CALM;
    }
}
