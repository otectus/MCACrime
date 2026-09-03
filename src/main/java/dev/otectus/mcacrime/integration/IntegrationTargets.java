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
            ResourceLocation.fromNamespaceAndPath("mcareputation", "record_incident");

    /** Ask MCA: Reputation to move a linked incident to a resolved status. */
    public static final ResourceLocation REPUTATION_RESOLVE_INCIDENT =
            ResourceLocation.fromNamespaceAndPath("mcareputation", "resolve_incident");

    /** Create the companion-side record of a deed. */
    public static final ResourceLocation ACTION_CREATE = McaCrime.id("create");

    /** Move an existing companion-side record to a new state. */
    public static final ResourceLocation ACTION_RESOLVE = McaCrime.id("resolve");

    // --- payload keys, shared by the enqueue site and the delivering adapter ---

    public static final String PAYLOAD_INCIDENT_TYPE = "incident";
    public static final String PAYLOAD_DEDUPE_KEY = "dedupe";
    /** The game time the deed actually happened. Never "now" on a replay. */
    public static final String PAYLOAD_GAME_TIME = "gameTime";
    public static final String PAYLOAD_COMMUNITY = "community";
    public static final String PAYLOAD_STATUS = "status";
    public static final String PAYLOAD_INCIDENT_ID = "incidentId";

    private IntegrationTargets() {
    }
}
