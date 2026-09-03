package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Everything MCA: Crime asks of MCA: Reputation, expressed entirely in Crime, Java, and Minecraft
 * types.
 *
 * <p>This interface is the boundary. It is always loadable, because nothing in its signatures names a
 * {@code mcareputation} class — statuses cross as bounded lowercase strings rather than as
 * {@code IncidentStatus}, and communities cross as our own {@link CrimeCommunityKey}. The
 * implementation lives in {@code compat.reputation}, is reached only by name after a presence check,
 * and is the single file in this mod permitted to import the companion.
 *
 * <p>Every method is expected to contain its own failures and answer with an empty/false result
 * rather than throwing. The caller is the outbox pump, driving work that has already been committed
 * on our side; an exception escaping here would be a delivery failure dressed up as a crash.
 */
public interface ReputationOps {

    /** The companion's API generation, or a negative number if it could not be read. */
    int apiVersion();

    /** Whether the companion currently accepts writes attributed to us. */
    boolean acceptsWrites();

    /**
     * Records the civic incident for a committed crime case.
     *
     * @return the incident's id, or empty if it was refused. A refusal is not necessarily a failure —
     *         an unwitnessed deed the definition does not retain is legitimately dropped.
     */
    Optional<UUID> recordIncident(MinecraftServer server, CrimeRecordView view,
                                  net.minecraft.resources.ResourceLocation incidentType,
                                  String dedupeKey, OptionalInt deltaOverride);

    /**
     * Finds an incident already produced under {@code dedupeKey}, for repairing a link lost to a
     * crash between the companion's commit and ours.
     */
    Optional<UUID> findIncident(MinecraftServer server, UUID playerId,
                                CrimeCommunityKey community, String dedupeKey);

    /**
     * Moves a linked incident to a resolved status.
     *
     * @param status one of {@code atoned}, {@code apologized}, {@code forgiven}, {@code disproven}
     * @return true when the incident now holds that status or a stronger one — a same-or-stronger
     *         result counts as success, which is what makes a replayed resolution harmless
     */
    boolean resolveIncident(MinecraftServer server, UUID playerId, CrimeCommunityKey community,
                            UUID incidentId, String status);

    /** The companion's canonical standing for a player in a community, for diagnostics only. */
    OptionalInt score(MinecraftServer server, UUID playerId, CrimeCommunityKey community);

    /**
     * Claims ownership of the deeds the companion would otherwise detect itself.
     *
     * @return true when the claim was accepted. A false answer means we must not record the
     *         overlapping deeds, or the player pays twice for one swing.
     */
    boolean claimAuthority();

    /** Withdraws the claim, handing native detection back. */
    void releaseAuthority();

    /** Whether the claim is currently held and healthy. */
    boolean holdsAuthority();
}
