package dev.otectus.mcacrime.action;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;
import java.util.UUID;

/** Short-lived server-issued menu binding. It authorizes a target context, never an outcome. */
public record ActionMenuSession(UUID id, UUID actor, UUID target, ResourceLocation dimension,
                                int revision, long expiresAt, Set<ResourceLocation> offered) {

    public ActionMenuSession {
        offered = offered == null ? Set.of() : Set.copyOf(offered);
    }

    public boolean valid(UUID actorId, UUID targetId, ResourceLocation targetDimension,
                         UUID menuId, int menuRevision, long now) {
        return id.equals(menuId) && actor.equals(actorId) && target.equals(targetId)
                && dimension.equals(targetDimension) && revision == menuRevision && now <= expiresAt;
    }

    /**
     * Whether this action was actually on the menu that was sent.
     *
     * <p>{@link #valid} only says the menu is the one the server issued; it says nothing about which
     * rows were on it. Without this a client holding a legitimate menu id can start any registered
     * action against that target — including one the menu builder deliberately hid, which is the whole
     * of the {@code HIDDEN} verdict undone by a single forged field.
     */
    public boolean offers(ResourceLocation actionId) {
        return actionId != null && offered.contains(actionId);
    }
}
