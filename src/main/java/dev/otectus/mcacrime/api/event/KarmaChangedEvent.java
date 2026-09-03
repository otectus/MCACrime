package dev.otectus.mcacrime.api.event;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.crime.KarmaSource;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fired after a player's Karma changes (spec §16). Carries the old/new karma and band so listeners can
 * react to either the raw delta or a band transition — the name-color sync, for instance, only
 * re-broadcasts when {@link #isBandChanged()} is true.
 */
public final class KarmaChangedEvent extends CrimeEvent {

    private final long oldKarma;
    private final long newKarma;
    private final Band oldBand;
    private final Band newBand;
    private final KarmaSource source;

    public KarmaChangedEvent(ServerPlayer player, long oldKarma, long newKarma,
                             Band oldBand, Band newBand, KarmaSource source) {
        super(player);
        this.oldKarma = oldKarma;
        this.newKarma = newKarma;
        this.oldBand = oldBand;
        this.newBand = newBand;
        this.source = source;
    }

    public long getOldKarma() {
        return oldKarma;
    }

    public long getNewKarma() {
        return newKarma;
    }

    public long getDelta() {
        return newKarma - oldKarma;
    }

    public Band getOldBand() {
        return oldBand;
    }

    public Band getNewBand() {
        return newBand;
    }

    public boolean isBandChanged() {
        return oldBand != newBand;
    }

    public KarmaSource getSource() {
        return source;
    }
}
