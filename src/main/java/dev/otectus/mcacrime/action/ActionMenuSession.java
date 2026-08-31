package dev.otectus.mcacrime.action;

import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Short-lived server-issued menu binding. It authorizes a target context, never an outcome. */
public record ActionMenuSession(UUID id, UUID actor, UUID target, ResourceLocation dimension,
                                int revision, long expiresAt) {
    public boolean valid(UUID actorId, UUID targetId, ResourceLocation targetDimension,
                         UUID menuId, int menuRevision, long now) {
        return id.equals(menuId) && actor.equals(actorId) && target.equals(targetId)
                && dimension.equals(targetDimension) && revision == menuRevision && now <= expiresAt;
    }
}
