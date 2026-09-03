package dev.otectus.mcacrime.api.event;

import dev.otectus.mcacrime.captivity.RestraintType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Fired once when an entity is taken into <em>unlawful</em> captivity — a kidnapping (spec §16, §8). Unlike
 * {@link CrimeEvent}, this does <b>not</b> extend the player-scoped base: either the captive or the captor
 * may be a non-player NPC, so the parties are exposed as UUIDs with nullable {@link ServerPlayer}
 * convenience accessors (present only when that party is an online player). Not cancellable — the custody
 * record already exists when this fires.
 */
public final class EntityKidnappedEvent extends Event {

    private final UUID captive;
    private final boolean captiveIsPlayer;
    private final UUID captor;
    private final boolean captorIsPlayer;
    private final RestraintType restraint;
    @Nullable
    private final ServerPlayer captivePlayer;
    @Nullable
    private final ServerPlayer captorPlayer;

    public EntityKidnappedEvent(UUID captive, boolean captiveIsPlayer, UUID captor, boolean captorIsPlayer,
                                RestraintType restraint, @Nullable ServerPlayer captivePlayer,
                                @Nullable ServerPlayer captorPlayer) {
        this.captive = captive;
        this.captiveIsPlayer = captiveIsPlayer;
        this.captor = captor;
        this.captorIsPlayer = captorIsPlayer;
        this.restraint = restraint;
        this.captivePlayer = captivePlayer;
        this.captorPlayer = captorPlayer;
    }

    public UUID getCaptive() {
        return captive;
    }

    public boolean isCaptivePlayer() {
        return captiveIsPlayer;
    }

    public UUID getCaptor() {
        return captor;
    }

    public boolean isCaptorPlayer() {
        return captorIsPlayer;
    }

    public RestraintType getRestraint() {
        return restraint;
    }

    @Nullable
    public ServerPlayer getCaptivePlayer() {
        return captivePlayer;
    }

    @Nullable
    public ServerPlayer getCaptorPlayer() {
        return captorPlayer;
    }
}
