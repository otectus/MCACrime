package dev.otectus.mcacrime.api.event;

import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Fired once when a captive leaves captivity by any path — escape, rescue, captor-gone, captivity cap,
 * admin release, ransom paid, or death (spec §16, §8.4). Like {@link EntityKidnappedEvent} the captive may
 * be an NPC, so it carries a UUID with a nullable {@link ServerPlayer} accessor. Idempotent at the source:
 * a second release of an already-free entity fires nothing. Not cancellable.
 */
public final class EntityReleasedFromCaptivityEvent extends Event {

    private final UUID captive;
    private final boolean captiveIsPlayer;
    @Nullable
    private final ServerPlayer captivePlayer;
    @Nullable
    private final UUID formerCaptor;
    private final CustodyReleaseReason reason;

    public EntityReleasedFromCaptivityEvent(UUID captive, boolean captiveIsPlayer,
                                            @Nullable ServerPlayer captivePlayer, @Nullable UUID formerCaptor,
                                            CustodyReleaseReason reason) {
        this.captive = captive;
        this.captiveIsPlayer = captiveIsPlayer;
        this.captivePlayer = captivePlayer;
        this.formerCaptor = formerCaptor;
        this.reason = reason;
    }

    public UUID getCaptive() {
        return captive;
    }

    public boolean isCaptivePlayer() {
        return captiveIsPlayer;
    }

    @Nullable
    public ServerPlayer getCaptivePlayer() {
        return captivePlayer;
    }

    @Nullable
    public UUID getFormerCaptor() {
        return formerCaptor;
    }

    public CustodyReleaseReason getReason() {
        return reason;
    }
}
