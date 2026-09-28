package dev.otectus.mcacrime.justice;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Immutable decision for one invocation, shared by native consequences and deferred reconciliation. */
public record ThiefCombatDecision(UUID actor, UUID target, ResourceLocation dimension, String action,
                                  int policyRevision, ThiefCombatPolicy.Reason reason, UUID evidence) {
    public boolean exempt() { return ThiefCombatPolicy.exempt(reason); }
}
