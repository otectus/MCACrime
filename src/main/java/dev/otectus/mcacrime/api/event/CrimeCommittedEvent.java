package dev.otectus.mcacrime.api.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
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

    public CrimeCommittedEvent(ServerPlayer offender, ResourceLocation crimeType, @Nullable UUID victim,
                               boolean witnessed, long karmaApplied, long heatApplied, UUID recordId) {
        super(offender);
        this.crimeType = crimeType;
        this.victim = victim;
        this.witnessed = witnessed;
        this.karmaApplied = karmaApplied;
        this.heatApplied = heatApplied;
        this.recordId = recordId;
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
}
