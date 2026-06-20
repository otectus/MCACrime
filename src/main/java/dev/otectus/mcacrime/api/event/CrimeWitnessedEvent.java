package dev.otectus.mcacrime.api.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Fired only when a crime is <em>witnessed</em> (an MCA villager/guard had line of sight, spec §3.5/§16),
 * immediately before the {@link CrimeCommittedEvent}. This is the seam later phases hook for guard
 * pursuit and bounty eligibility — "the law saw it" is distinct from "a crime happened".
 */
public final class CrimeWitnessedEvent extends CrimeEvent {

    private final ResourceLocation crimeType;
    @Nullable
    private final UUID victim;
    private final int witnessCount;

    public CrimeWitnessedEvent(ServerPlayer offender, ResourceLocation crimeType, @Nullable UUID victim, int witnessCount) {
        super(offender);
        this.crimeType = crimeType;
        this.victim = victim;
        this.witnessCount = witnessCount;
    }

    public ResourceLocation getCrimeType() {
        return crimeType;
    }

    @Nullable
    public UUID getVictim() {
        return victim;
    }

    /** How many responder NPCs witnessed the act (at least 1). */
    public int getWitnessCount() {
        return witnessCount;
    }
}
