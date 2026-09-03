package dev.otectus.mcacrime.api.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fired whenever a player's Heat actually moves, after the change is committed.
 *
 * <p>This fills a real hole. {@code WantedStatusChangedEvent} only fires when Heat crosses the wanted
 * threshold, so a listener watching for "the player is cooling off" or "the player just picked up
 * pursuit pressure" had nothing to subscribe to — every change short of a boundary crossing was
 * invisible. A quest objective like "get your Heat under 20" needs exactly the changes this reports.
 *
 * <p>Fires only on a genuine change: setting Heat to the value it already holds posts nothing, which
 * is what keeps a retried packet or a replayed transaction from looking like activity.
 *
 * <p>{@code source} names what caused it ({@code mcacrime:fine}, {@code mcacrime:decay},
 * {@code mcaquests:reward}) and {@code dedupeKey} identifies the transaction, so a listener can
 * recognise a redelivery of work it has already accounted for.
 */
public final class HeatChangedEvent extends CrimeEvent {

    private final long oldHeat;
    private final long newHeat;
    private final ResourceLocation source;
    private final String dedupeKey;

    public HeatChangedEvent(ServerPlayer player, long oldHeat, long newHeat,
                            ResourceLocation source, String dedupeKey) {
        super(player);
        this.oldHeat = oldHeat;
        this.newHeat = newHeat;
        this.source = source;
        this.dedupeKey = dedupeKey == null ? "" : dedupeKey;
    }

    public long getOldHeat() {
        return oldHeat;
    }

    public long getNewHeat() {
        return newHeat;
    }

    /** Signed: negative when the player cooled off. */
    public long getDelta() {
        return newHeat - oldHeat;
    }

    /** What caused the change, as a namespaced id. Never null. */
    public ResourceLocation getSource() {
        return source;
    }

    /** The transaction key, or an empty string for an untracked internal change. */
    public String getDedupeKey() {
        return dedupeKey;
    }
}
