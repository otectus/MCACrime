package dev.otectus.mcacrime.job;

import java.util.Locale;

/**
 * Why an NPC may or may not act as this mod's criminal (0.7.2, spec §3 "Guard safety").
 *
 * <p>A named reason rather than a boolean because the two rejections that matter are not the same
 * fact and must never collapse into one another: {@link #RESPONDER} says "this villager is law", and
 * {@link #CLASSIFICATION_UNAVAILABLE} says "MCA could not be asked". The second one used to read as
 * the first one's opposite — an unbound profession handle answered "not a guard" — which is how a
 * guard ended up carrying a Thief record in the first place.
 */
public enum NpcMuggerEligibilityReason {

    /** No objection. The only value for which {@link NpcMuggerEligibility.Result#eligible()} is true. */
    ELIGIBLE,

    /**
     * The villager is a law responder: an MCA guard, an MCA archer, or an entity the server owner put
     * in {@code responderEntities}. Identity, not availability — a sleeping, unarmed or off-duty guard
     * is still law and is still excluded.
     */
    RESPONDER,

    /**
     * MCA's classification could not be read at all, so no role claim can honestly be made. Fails
     * closed: no new assignment and no execution pass proceeds on a villager nobody can classify.
     */
    CLASSIFICATION_UNAVAILABLE,

    /** The entity is not loaded, not alive, or has been removed, so there is nothing to classify. */
    NOT_LOADED,

    /** Not an MCA villager. This mod's criminals are MCA's people and nobody else's. */
    NOT_A_VILLAGER,

    /** Children are never recruited, and an unreadable age counts as a child here. */
    NOT_ADULT,

    /** Asked as an execution actor, but nothing in world data says this villager is a thief. */
    NOT_A_THIEF;

    /** {@code mcacrime.job.rejected.<reason>}, shown to an operator when a job mutation is refused. */
    public String messageKey() {
        return "mcacrime.job.rejected." + name().toLowerCase(Locale.ROOT);
    }
}
