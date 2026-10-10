package dev.otectus.mcacrime.justice;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Immutable decision for one invocation, shared by native consequences and deferred reconciliation. */
public record ThiefCombatDecision(UUID actor, UUID target, ResourceLocation dimension, String action,
                                  int policyRevision, ThiefCombatPolicy.Reason reason, UUID evidence) {
    /** Whether a non-lethal act -- a blow, a shove, cuffs -- is exempt. */
    public boolean exempt() { return ThiefCombatPolicy.exempt(reason); }
    /** Whether a killing is exempt; narrower than {@link #exempt()} only for self-defence under normal law. */
    public boolean exemptsKill() { return ThiefCombatPolicy.fromRule(policyRevision).exemptsKill(reason); }
    /** Whether this is the victim of a mugging answering it (0.7.7), which also decides the encounter's aggressor. */
    public boolean answersThreat() { return reason == ThiefCombatPolicy.Reason.ACTIVE_THREAT; }
}
