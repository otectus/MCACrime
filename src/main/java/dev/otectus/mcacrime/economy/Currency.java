package dev.otectus.mcacrime.economy;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * An abstract currency for fines/bail/ransom/theft (spec §11.5), so the emerald default can be swapped
 * for an economy mod without touching any of the logic that spends it. Charges must be <b>atomic</b>:
 * a failed charge leaves the player's balance untouched (no partial deduction).
 *
 * <p>0.5.1 widens the interface because theft needs something fines never did: the ability to take
 * <em>as much as is there</em> and be told how much that was. {@link #debit} is therefore the
 * primitive and {@link #tryCharge} is a default written on top of it, rather than the other way
 * round — a mugging that finds four emeralds where it wanted ten must take four and say so, while a
 * fine that cannot be paid in full must take nothing at all.
 *
 * <p>{@link #toStacks} exists for the one thing an abstract balance cannot do: fall on the ground.
 * A currency with no item form returns an empty list, and callers credit a player directly instead.
 */
public interface Currency {

    /** Registry-style identity, matched against {@code integrations.currencyId}. */
    ResourceLocation id();

    long balance(ServerPlayer player);

    /**
     * Removes up to {@code requested} and returns how much was actually removed (never negative,
     * never more than the balance). A non-positive request removes nothing and returns 0.
     */
    long debit(ServerPlayer player, long requested, TransactionReason reason);

    /** Gives {@code amount} to the player. A non-positive amount does nothing. */
    void credit(ServerPlayer player, long amount, TransactionReason reason);

    /** How this currency reads to a player: 12 emeralds, or whatever the implementation calls it. */
    Component format(long amount);

    /**
     * The item form of {@code amount}, for dropping it on the ground. Empty for a currency that has
     * no item form; callers must then credit a player directly rather than silently losing it.
     */
    List<ItemStack> toStacks(long amount);

    /** Whether {@link #toStacks} can produce anything. */
    default boolean hasItemForm() {
        return true;
    }

    /**
     * Atomically removes {@code amount} if affordable; returns false (and changes nothing) otherwise.
     *
     * <p>Default rather than abstract so an implementer only has to get {@link #debit} right. The
     * balance check happens first, so the debit below it can never come up short.
     */
    default boolean tryCharge(ServerPlayer player, long amount) {
        return tryCharge(player, amount, TransactionReason.OTHER);
    }

    /** {@link #tryCharge(ServerPlayer, long)} with a stated reason. */
    default boolean tryCharge(ServerPlayer player, long amount, TransactionReason reason) {
        if (amount <= 0L) {
            return true;
        }
        if (balance(player) < amount) {
            return false; // atomic: charge nothing on insufficient funds
        }
        return debit(player, amount, reason) >= amount;
    }
}
