package dev.otectus.mcacrime.integration;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.resources.ResourceLocation;

/**
 * The identifiers an outbox operation may name.
 *
 * <p>Kept as plain {@link ResourceLocation} constants in an always-loadable class, so a pending
 * operation can be read back from disk, listed by a debug command, and retried, all without the
 * target mod being installed. Naming a companion's type here instead would mean an uninstalled mod
 * turns saved data into an unreadable blob.
 */
public final class IntegrationTargets {

    /** Ask MCA: Reputation to record the civic incident for a crime case. */
    public static final ResourceLocation REPUTATION_RECORD_INCIDENT =
            new ResourceLocation("mcareputation", "record_incident");

    /** Ask MCA: Reputation to move a linked incident to a resolved status. */
    public static final ResourceLocation REPUTATION_RESOLVE_INCIDENT =
            new ResourceLocation("mcareputation", "resolve_incident");

    /**
     * Ask Townstead to play a public reaction for something that has become common knowledge.
     *
     * <p>A second target namespace on the same queue, and the reason the queue needed a routing layer
     * at all. It shares the outbox's durability and its budget and shares nothing else: a Townstead
     * delivery that fails must never reach MCA: Reputation's dead-letter path, where a failed delivery
     * applies a local village-standing penalty on the grounds that the civic record was lost. Nothing
     * about a missed animation is a civic record, and charging a player standing for one would be a
     * penalty with no deed behind it.
     */
    public static final ResourceLocation TOWNSTEAD_REACTION =
            new ResourceLocation("townstead", "reaction");

    /** Create the companion-side record of a deed. */
    public static final ResourceLocation ACTION_CREATE = McaCrime.id("create");

    /** Move an existing companion-side record to a new state. */
    public static final ResourceLocation ACTION_RESOLVE = McaCrime.id("resolve");

    /** Play a bounded, one-shot social reaction. Carries no record and settles nothing. */
    public static final ResourceLocation ACTION_REACT = McaCrime.id("react");

    /**
     * Whether an operation is bound for the settlement companion rather than the reputation one.
     *
     * <p>By namespace rather than by an exact target, so a second Townstead target added later is
     * routed correctly by construction instead of by somebody remembering to extend a list. Namespace
     * is the right granularity: it is the mod the delivery is addressed to, which is exactly the unit
     * whose failures must not be attributed to a different mod.
     */
    public static boolean isTownstead(ResourceLocation target) {
        return target != null && "townstead".equals(target.getNamespace());
    }

    /** Whether an operation is bound for MCA: Reputation. */
    public static boolean isReputation(ResourceLocation target) {
        return target != null && "mcareputation".equals(target.getNamespace());
    }

    // --- payload keys, shared by the enqueue site and the delivering adapter ---

    public static final String PAYLOAD_INCIDENT_TYPE = "incident";
    public static final String PAYLOAD_DEDUPE_KEY = "dedupe";
    /** The game time the deed actually happened. Never "now" on a replay. */
    public static final String PAYLOAD_GAME_TIME = "gameTime";
    public static final String PAYLOAD_COMMUNITY = "community";
    public static final String PAYLOAD_STATUS = "status";
    public static final String PAYLOAD_INCIDENT_ID = "incidentId";

    // --- Townstead reaction payload ---

    /** MCA: Crime's own event name, from the vocabulary in {@code TownsteadReactions.Event}. */
    public static final String PAYLOAD_EVENT = "event";
    /** The reaction id a datapack binding chose for that event. */
    public static final String PAYLOAD_REACTION_ID = "reaction";
    /** Where it happened, so the audience is the people who could plausibly know. */
    public static final String PAYLOAD_DIMENSION = "dim";
    public static final String PAYLOAD_X = "x";
    public static final String PAYLOAD_Y = "y";
    public static final String PAYLOAD_Z = "z";
    /** How far the reaction carries, in blocks. Bounded at the enqueue site. */
    public static final String PAYLOAD_RADIUS = "radius";

    private IntegrationTargets() {
    }
}
