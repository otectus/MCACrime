package dev.otectus.mcacrime.api.event;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fired once per recorded crime (spec §16), after Karma/Heat have been applied and the ledger entry
 * written. Carries the offender (via {@link #getPlayer()}), the crime type, the victim, whether it was
 * witnessed, the Karma/Heat actually applied, and the ledger record id.
 */
public final class CrimeCommittedEvent extends CrimeEvent {

    private final ResourceLocation crimeType;
    @Nullable
    private final UUID victim;
    private final boolean witnessed;
    private final long karmaApplied;
    private final long heatApplied;
    private final UUID recordId;
    @Nullable
    private final CrimeRecordView view;

    /**
     * @deprecated prefer the overload carrying the full record view. Kept so existing callers keep
     *         working; listeners on it simply see an empty {@link #getRecordView()}.
     */
    @Deprecated
    public CrimeCommittedEvent(ServerPlayer offender, ResourceLocation crimeType, @Nullable UUID victim,
                               boolean witnessed, long karmaApplied, long heatApplied, UUID recordId) {
        this(offender, crimeType, victim, witnessed, karmaApplied, heatApplied, recordId, null);
    }

    public CrimeCommittedEvent(ServerPlayer offender, ResourceLocation crimeType, @Nullable UUID victim,
                               boolean witnessed, long karmaApplied, long heatApplied, UUID recordId,
                               @Nullable CrimeRecordView view) {
        super(offender);
        this.crimeType = crimeType;
        this.victim = victim;
        this.witnessed = witnessed;
        this.karmaApplied = karmaApplied;
        this.heatApplied = heatApplied;
        this.recordId = recordId;
        this.view = view;
    }

    public ResourceLocation getCrimeType() {
        return crimeType;
    }

    @Nullable
    public UUID getVictim() {
        return victim;
    }

    public boolean isWitnessed() {
        return witnessed;
    }

    public long getKarmaApplied() {
        return karmaApplied;
    }

    public long getHeatApplied() {
        return heatApplied;
    }

    public UUID getRecordId() {
        return recordId;
    }

    /**
     * The whole committed case, if the caller supplied it — community, witness identities, context,
     * and disposition in one immutable object.
     *
     * <p>Empty only when the event came from the deprecated constructor. A listener that needs the
     * detail should handle the empty case by ignoring the event rather than by re-querying the ledger:
     * a record fetched afterwards is a different read at a different moment.
     */
    public Optional<CrimeRecordView> getRecordView() {
        return Optional.ofNullable(view);
    }

    /** The dimension-aware community this crime was committed against, if any. */
    public Optional<CrimeCommunityKey> getCommunity() {
        return view == null ? Optional.empty() : view.community();
    }

    /** Which villagers saw it. Empty for an unwitnessed, official, or pre-identity record. */
    public Set<UUID> getWitnessIds() {
        return view == null ? Set.of() : view.witnessIds();
    }
}
