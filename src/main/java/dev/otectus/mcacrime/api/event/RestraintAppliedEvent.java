package dev.otectus.mcacrime.api.event;

import dev.otectus.mcacrime.api.model.RestraintSlotView;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Fired once after a restraint has been committed onto a subject (0.7.5 M6.4).
 *
 * <p>Post-commit and not cancellable, like every event in this package: the application either stood
 * or was refused before this point, and a listener that could veto here would leave the item spent
 * and the state written. A companion that wants to <em>prevent</em> a restraint configures the
 * eligibility rules instead.
 *
 * <p>Both parties are UUIDs because either may be a villager. The applier is absent for a device or
 * for gear the server issued at an arrest.
 */
public final class RestraintAppliedEvent extends Event {

    private final UUID subject;
    private final boolean subjectIsPlayer;
    @Nullable
    private final UUID applier;
    private final ResourceLocation definitionId;
    private final String slot;
    private final String context;
    private final RestraintSlotView view;

    public RestraintAppliedEvent(UUID subject, boolean subjectIsPlayer, @Nullable UUID applier,
                                 ResourceLocation definitionId, String slot, String context,
                                 RestraintSlotView view) {
        this.subject = subject;
        this.subjectIsPlayer = subjectIsPlayer;
        this.applier = applier;
        this.definitionId = definitionId;
        this.slot = slot == null ? "" : slot;
        this.context = context == null ? "" : context;
        this.view = view;
    }

    public UUID getSubject() {
        return subject;
    }

    public boolean isSubjectPlayer() {
        return subjectIsPlayer;
    }

    /** Who applied it, or null for a device or for system-issued gear. */
    @Nullable
    public UUID getApplier() {
        return applier;
    }

    public ResourceLocation getDefinitionId() {
        return definitionId;
    }

    /** {@code head}, {@code arms} or {@code legs}. */
    public String getSlot() {
        return slot;
    }

    /** Under what authority: {@code voluntary}, {@code unlawful}, {@code lawful}, and so on. */
    public String getContext() {
        return context;
    }

    public RestraintSlotView getView() {
        return view;
    }

    /** Whether this was an arrest or an operator act rather than a private one. */
    public boolean isLawful() {
        return "lawful".equalsIgnoreCase(context) || "administrative".equalsIgnoreCase(context);
    }
}
