package dev.otectus.mcacrime.economy;

import net.minecraft.server.level.ServerPlayer;

/**
 * An abstract currency for fines/bail/ransom (spec §11.5), so the emerald default can be swapped for an
 * economy mod later without touching the fine logic. Charges must be <b>atomic</b>: a failed charge
 * leaves the player's balance untouched (no partial deduction).
 */
public interface Currency {

    long balance(ServerPlayer player);

    /** Atomically removes {@code amount} if affordable; returns false (and changes nothing) otherwise. */
    boolean tryCharge(ServerPlayer player, long amount);
}
