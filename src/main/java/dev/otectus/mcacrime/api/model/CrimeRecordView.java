package dev.otectus.mcacrime.api.model;

import dev.otectus.mcacrime.ledger.Resolution;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An immutable public projection of one crime case. This is what {@code McaCrimeApi} hands out; the
 * internal {@code CrimeRecord} never crosses the API boundary.
 *
 * <p>Karma, heat, fines, and sentences are {@code long} here because they are {@code long} in
 * {@code CrimeState} and {@code CrimeRecord}. Narrowing them at the boundary would silently truncate
 * on a server that raised {@code heatMax} or {@code karmaMax}.
 *
 * <p>{@code witnessed} is stored alongside {@code witnessIds} rather than derived from it, because
 * the two genuinely differ. A jailbreak is witnessed by the authority itself and has no villager
 * witness UUIDs at all; a migrated pre-0.2 record has {@code witnessed=true} and an empty set because
 * the identities were never recorded. Deriving the flag would force one of those cases to lie, and
 * the honest answer is what the privacy rules downstream depend on.
 */
public record CrimeRecordView(
        UUID id,
        UUID offenderId,
        Optional<UUID> victimId,
        ResourceLocation crimeType,
        Optional<CrimeCommunityKey> community,
        Set<UUID> witnessIds,
        boolean witnessed,
        long committedGameTime,
        long heatGenerated,
        long karmaDelta,
        long fineAmount,
        long jailTicks,
        Resolution resolution,
        long resolutionRevision,
        Optional<UUID> linkedReputationIncidentId,
        Map<String, String> context) {

    public CrimeRecordView {
        victimId = victimId == null ? Optional.empty() : victimId;
        community = community == null ? Optional.empty() : community;
        linkedReputationIncidentId =
                linkedReputationIncidentId == null ? Optional.empty() : linkedReputationIncidentId;
        // Defensive copies: a caller must not be able to reach back into the ledger through a view.
        witnessIds = witnessIds == null ? Set.of() : Set.copyOf(witnessIds);
        context = context == null ? Map.of() : Map.copyOf(context);
        resolution = resolution == null ? Resolution.UNRESOLVED : resolution;
    }

    /** How many villagers are known to have seen this. Zero for an unwitnessed or official record. */
    public int witnessCount() {
        return witnessIds.size();
    }

    /**
     * Whether this case is still legally actionable. {@code ESCAPED} counts: breaking out of jail is
     * not forgiveness, and a guard should still act on it.
     */
    public boolean actionable() {
        return resolution == Resolution.UNRESOLVED || resolution == Resolution.ESCAPED;
    }

    /** A context value, or empty. Context is an allowlisted snapshot, never arbitrary entity data. */
    public Optional<String> context(String key) {
        return Optional.ofNullable(context.get(key));
    }
}
