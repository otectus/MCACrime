package dev.otectus.mcacrime.api.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;
import java.util.Set;
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
    private final Set<UUID> witnessIds;

    /**
     * @deprecated prefer the overload carrying witness identities. Kept so existing callers and
     *         listeners keep working; it reports an empty identity set rather than inventing one.
     */
    @Deprecated
    public CrimeWitnessedEvent(ServerPlayer offender, ResourceLocation crimeType, @Nullable UUID victim, int witnessCount) {
        this(offender, crimeType, victim, witnessCount, Set.of());
    }

    public CrimeWitnessedEvent(ServerPlayer offender, ResourceLocation crimeType, @Nullable UUID victim,
                               int witnessCount, Set<UUID> witnessIds) {
        super(offender);
        this.crimeType = crimeType;
        this.victim = victim;
        this.witnessCount = witnessCount;
        this.witnessIds = witnessIds == null ? Set.of() : Set.copyOf(witnessIds);
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

    /**
     * Exactly which villagers saw it, as an immutable set.
     *
     * <p>Empty does not mean unwitnessed. A jailbreak is known to the law with no villager present,
     * and a record migrated from before identities were stored knows it was seen but not by whom.
     * Both keep an empty set on purpose: fabricating a witness would hand a stranger knowledge of a
     * crime they never saw, which is the one thing this data exists to prevent.
     */
    public Set<UUID> getWitnessIds() {
        return witnessIds;
    }
}
