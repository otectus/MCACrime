package dev.otectus.mcacrime.mask;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.state.PlayerCrimeData.PendingMaskedHeat;
import dev.otectus.mcacrime.util.SafeMath;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * The bookkeeping behind deferred Heat (0.7.0): what a mask has kept off the books, and what comes due
 * when it comes off.
 *
 * <p>Pure list arithmetic — no {@code ServerPlayer}, no config reads, no clock of its own. Everything
 * it needs is passed in, which is what makes "does a spree of forty crimes still cost exactly what
 * forty crimes cost" a unit test rather than a playtest.
 */
public final class MaskHeatLedger {

    /**
     * The attribution a coalesced carry entry carries. It names a spree rather than a crime because
     * that is honestly what it is: several offences whose individual identities were dropped to keep
     * the list bounded, with their total intact.
     */
    public static final ResourceLocation MASKED_SPREE = McaCrime.id("masked_spree");

    private MaskHeatLedger() {
    }

    /**
     * Banks one masked crime's Heat, collapsing the oldest entries into a single carry entry when the
     * list would outgrow {@code cap}.
     *
     * <p>Collapsing rather than dropping is the whole point: the bound protects the save file, not the
     * player. The carry keeps the earliest tick of everything it swallowed, so expiry still measures
     * from the oldest crime it represents.
     */
    public static void defer(List<PendingMaskedHeat> ledger, PendingMaskedHeat entry, int cap) {
        if (ledger == null || entry == null || entry.heat() == 0L || cap <= 0) {
            return;
        }
        ledger.add(entry);
        if (ledger.size() <= cap) {
            return;
        }
        long carried = 0L;
        long earliest = Long.MAX_VALUE;
        int collapse = ledger.size() - cap + 1;
        for (int i = 0; i < collapse; i++) {
            PendingMaskedHeat oldest = ledger.remove(0);
            carried = SafeMath.addSat(carried, oldest.heat());
            earliest = Math.min(earliest, oldest.tick());
        }
        ledger.add(0, new PendingMaskedHeat(MASKED_SPREE, MASKED_SPREE.getPath(), carried,
                earliest == Long.MAX_VALUE ? entry.tick() : earliest));
    }

    /**
     * Drops entries older than {@code expiryTicks} on the player's own online clock. An expiry of 0 (or
     * less) means deferred Heat never lapses, which is the shipped default: a mask that only had to be
     * kept on long enough is a mask that voids Heat rather than deferring it.
     */
    public static void prune(List<PendingMaskedHeat> ledger, long now, long expiryTicks) {
        if (ledger == null || ledger.isEmpty() || expiryTicks <= 0L) {
            return;
        }
        ledger.removeIf(entry -> now - entry.tick() >= expiryTicks);
    }

    /** Takes everything owed and empties the ledger, in the order it was banked. */
    public static List<PendingMaskedHeat> drain(List<PendingMaskedHeat> ledger) {
        if (ledger == null || ledger.isEmpty()) {
            return List.of();
        }
        List<PendingMaskedHeat> drained = new ArrayList<>(ledger);
        ledger.clear();
        return drained;
    }

    /** What a flush would cost right now. Saturating, because a spree is allowed to be absurd. */
    public static long total(List<PendingMaskedHeat> ledger) {
        long total = 0L;
        if (ledger != null) {
            for (PendingMaskedHeat entry : ledger) {
                total = SafeMath.addSat(total, entry.heat());
            }
        }
        return total;
    }
}
