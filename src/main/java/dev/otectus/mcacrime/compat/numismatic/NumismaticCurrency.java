package dev.otectus.mcacrime.compat.numismatic;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.economy.Currency;
import dev.otectus.mcacrime.economy.TransactionReason;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

/**
 * MCA: Crime's money, kept in a Numismatic Overhaul purse (Reforged Again 2.0.1).
 *
 * <p>Loaded only through {@code compat.NumismaticBridge}, after {@code ModList} has confirmed the mod
 * is installed — this class is the isolated half of that seam, and is the only place in the mod that
 * names a Numismatic symbol at all. It names them as strings: the capability lives at
 * {@code tallestred.numismaticoverhaul.cap.*}, a package that a future release is free to move, and a
 * compiled reference to it would be a {@code NoClassDefFoundError} rather than a graceful fallback.
 *
 * <h2>Units</h2>
 *
 * <p>Numismatic's stored value is in <b>bronze</b>: 100 bronze is one silver, 10 000 is one gold. Every
 * amount MCA: Crime hands over or asks for is therefore a bronze amount, and the fine, bail, ransom and
 * bounty numbers in the config read as bronze on a server that selects this currency. That is worth
 * knowing before setting {@code fineAmount} to 12.
 *
 * <h2>Which mutator, and why</h2>
 *
 * <p>Debits use {@code silentModify}, credits use {@code modify}. Numismatic's {@code modify} queues a
 * player-visible "+ coins" line; that is welcome for a bounty reward and wrong for a fine, where MCA:
 * Crime prints its own message and a second, contradictory one from the economy mod is just noise.
 */
public final class NumismaticCurrency implements Currency {

    /** The id {@code integrations.currencyId} uses to select this provider. */
    public static final ResourceLocation ID = new ResourceLocation("mcacrime", "numismatic");

    private final MethodHandle holderOf;
    private final MethodHandle getValue;
    private final MethodHandle silentModify;
    private final MethodHandle modify;

    private NumismaticCurrency(MethodHandle holderOf, MethodHandle getValue, MethodHandle silentModify,
                               MethodHandle modify) {
        this.holderOf = holderOf;
        this.getValue = getValue;
        this.silentModify = silentModify;
        this.modify = modify;
    }

    /**
     * Binds to Numismatic and registers this currency, or does nothing at all.
     *
     * <p>Invoked reflectively by {@code NumismaticBridge}; a failure to resolve any one handle means no
     * registration, never a stub. A currency that silently ate debits would let players pay fines for
     * free, which is a worse outcome than the integration being unavailable.
     */
    public static void registerIfUsable() throws Exception {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Class<?> attacher = Class.forName("tallestred.numismaticoverhaul.cap.CurrencyHolderAttacher");
        Class<?> holder = Class.forName("tallestred.numismaticoverhaul.cap.CurrencyHolder");

        MethodHandle holderOf = lookup.findStatic(attacher, "getExampleHolderUnwrap",
                MethodType.methodType(holder, net.minecraft.world.entity.Entity.class));
        MethodHandle getValue = lookup.findVirtual(holder, "getValue", MethodType.methodType(long.class));
        MethodHandle silentModify = lookup.findVirtual(holder, "silentModify",
                MethodType.methodType(void.class, long.class));
        MethodHandle modify = lookup.findVirtual(holder, "modify",
                MethodType.methodType(void.class, long.class));

        Currencies.register(new NumismaticCurrency(holderOf, getValue, silentModify, modify));
        McaCrime.LOGGER.info("MCA: Crime registered the Numismatic Overhaul purse as '{}' "
                + "(amounts are in bronze).", ID);
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public long balance(ServerPlayer player) {
        Object holder = holder(player);
        if (holder == null) {
            return 0L;
        }
        try {
            return (long) getValue.invoke(holder);
        } catch (Throwable failure) {
            return 0L;
        }
    }

    @Override
    public long debit(ServerPlayer player, long requested, TransactionReason reason) {
        if (requested <= 0L) {
            return 0L;
        }
        Object holder = holder(player);
        if (holder == null) {
            return 0L;
        }
        try {
            long taken = Math.min(requested, (long) getValue.invoke(holder));
            if (taken <= 0L) {
                return 0L;
            }
            silentModify.invoke(holder, -taken); // MCA: Crime narrates its own fines; no second message
            return taken;
        } catch (Throwable failure) {
            return 0L;
        }
    }

    @Override
    public void credit(ServerPlayer player, long amount, TransactionReason reason) {
        if (amount <= 0L) {
            return;
        }
        Object holder = holder(player);
        if (holder == null) {
            throw new IllegalStateException("no Numismatic purse for " + player);
        }
        try {
            modify.invoke(holder, amount); // Numismatic prints its own "+ coins" line for this one
        } catch (Throwable failure) {
            throw new IllegalStateException("Numismatic credit failed", failure);
        }
    }

    @Override
    public Component format(long amount) {
        return Component.translatable("mcacrime.currency.numismatic", amount);
    }

    @Override
    public boolean hasItemForm() {
        return false; // a purse balance; coins are minted on withdrawal, not by us
    }

    @Override
    public List<ItemStack> toStacks(long amount) {
        return List.of();
    }

    @Override
    public long stacksNeeded(long amount) {
        return 0L;
    }

    private Object holder(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        try {
            return holderOf.invoke(player);
        } catch (Throwable failure) {
            return null;
        }
    }
}
