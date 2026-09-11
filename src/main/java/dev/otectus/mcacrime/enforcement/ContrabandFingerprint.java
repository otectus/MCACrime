package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.item.contraband.ContrabandProbe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * A stable 64-bit summary of what a search found, so the same haul is not charged twice (0.7.0).
 *
 * <p>Order-independent by construction: the listed stacks are sorted by item id and then count before
 * they are hashed, because a player who shuffles their inventory has not committed a second crime. The
 * hash is FNV-1a over {@code id:count;} — no cryptography is wanted here, only the same number for the
 * same haul across restarts, which {@code String.hashCode} on a set would not give.
 *
 * <p>Pure: it takes probes, never a level, a player, or config.
 */
public final class ContrabandFingerprint {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private ContrabandFingerprint() {
    }

    /** The fingerprint of a haul; {@code 0L} when nothing was found, which never compares equal. */
    public static long of(Collection<ContrabandProbe> listed) {
        if (listed == null || listed.isEmpty()) {
            return 0L;
        }
        List<ContrabandProbe> sorted = new ArrayList<>(listed.size());
        for (ContrabandProbe probe : listed) {
            if (probe != null) {
                sorted.add(probe);
            }
        }
        if (sorted.isEmpty()) {
            return 0L;
        }
        sorted.sort(Comparator.comparing(ContrabandProbe::idString)
                .thenComparingInt(ContrabandProbe::count));
        long hash = FNV_OFFSET_BASIS;
        for (ContrabandProbe probe : sorted) {
            hash = hashString(hash, probe.idString());
            hash = hashString(hash, ":");
            hash = hashString(hash, Integer.toString(probe.count()));
            hash = hashString(hash, ";");
        }
        return hash;
    }

    /**
     * Whether this find is chargeable: a different haul than last time, or the same one after the
     * recharge window. An empty haul is never charged.
     */
    public static boolean shouldCharge(long fingerprint, long lastFingerprint, long now,
                                       long lastChargeTick, long rechargeTicks) {
        if (fingerprint == 0L) {
            return false;
        }
        if (fingerprint != lastFingerprint) {
            return true;
        }
        return now - lastChargeTick >= rechargeTicks;
    }

    private static long hashString(long hash, String value) {
        long result = hash;
        for (int i = 0; i < value.length(); i++) {
            result ^= value.charAt(i);
            result *= FNV_PRIME;
        }
        return result;
    }
}
