package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Everything MCA: Crime asks of MCA: Reputation, expressed entirely in Crime, Java, and Minecraft
 * types.
 *
 * <p>This interface is the boundary. It is always loadable, because nothing in its signatures names a
 * {@code mcareputation} class — statuses cross as bounded lowercase strings rather than as
 * {@code IncidentStatus}, communities cross as our own {@link CrimeCommunityKey}, delivery results as
 * {@link ReputationDelivery}, and the companion's advertised capabilities as
 * {@link ReputationCapabilitySnapshot}. The implementation lives in {@code compat.reputation}, is
 * reached only by name after a presence check, and is the single file in this mod permitted to import
 * the companion.
 *
 * <p>Every method is expected to contain its own failures and answer with an empty/unknown result
 * rather than throwing. The caller is the outbox pump, driving work that has already been committed
 * on our side; an exception escaping here would be a delivery failure dressed up as a crash.
 */
public interface ReputationOps {

    /** The companion's API generation, or a negative number if it could not be read. */
    int apiVersion();

    /** Whether the companion currently accepts writes attributed to us. */
    boolean acceptsWrites();

    /**
     * What the installed companion can actually do.
     *
     * <p>Asked once per server and cached by {@link ReputationBridge}, because the answer is a
     * property of the installed jar and its config rather than of the deed being delivered. A build
     * too old to answer gets {@link ReputationCapabilitySnapshot#unsupported}, and every feature this
     * mod would otherwise use is then simply not used.
     */
    ReputationCapabilitySnapshot capabilities(@Nullable MinecraftServer server);

    /**
     * Delivers the civic incident for a committed crime case under an operation key.
     *
     * <p>The operation key is the identity that makes this exactly-once across a crash: replaying it
     * returns the first delivery's answer rather than recording a second incident, including for an
     * operation that produced nothing public at all.
     *
     * @param precursorIncidentId  a civic incident this deed absorbs rather than stacks on — the
     *                             assault a killing finished. Null for the ordinary case.
     * @param supersedeWindowTicks how far back the companion should accept that precursor
     * @return what the companion did, never null
     */
    ReputationDelivery deliverIncident(MinecraftServer server, CrimeRecordView view,
                                       net.minecraft.resources.ResourceLocation incidentType,
                                       String operationKey, OptionalInt deltaOverride,
                                       @Nullable UUID precursorIncidentId, long supersedeWindowTicks);

    /**
     * Reads back what became of an operation, <b>without writing anything</b>.
     *
     * <p>This is what repairs a link lost to a crash between the companion's commit and ours. The old
     * implementation discovered the incident by sending a synthetic assault through the record path
     * and reading the duplicate refusal — a write used as a query, which on a companion that had
     * forgotten the key recorded a villager assault that never happened. Discovering a deed by
     * committing one is never acceptable, whatever the odds of the race.
     *
     * @return the stored outcome, or {@link ReputationDelivery.Outcome#UNKNOWN} when this side has no
     *         memory of the operation and cannot honestly say it never happened
     */
    ReputationDelivery findDelivery(MinecraftServer server, UUID playerId, CrimeCommunityKey community,
                                    String operationKey);

    /**
     * Moves a linked incident to a resolved status.
     *
     * @param status       one of {@code atoned}, {@code apologized}, {@code forgiven}, {@code disproven}
     * @param operationKey the identity of this resolution step, used for exactly-once settlement where
     *                     the companion supports it
     */
    ReputationDelivery resolveIncident(MinecraftServer server, UUID playerId, CrimeCommunityKey community,
                                       UUID incidentId, String status, String operationKey);

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
