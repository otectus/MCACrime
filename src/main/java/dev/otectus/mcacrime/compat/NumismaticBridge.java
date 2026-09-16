package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import net.minecraftforge.fml.ModList;

/**
 * The optional-classloading seam for Numismatic Overhaul (Reforged Again 2.0.1), built to the same
 * discipline as {@link ReputationBridge}.
 *
 * <p><b>Nothing reachable from here without the mod-present check may name a {@code numismaticoverhaul}
 * type.</b> The real adapter lives in {@code compat.numismatic} and talks to the mod entirely through
 * {@code MethodHandle}s resolved from strings, so a server without Numismatic never loads a class that
 * mentions it.
 *
 * <h2>Failure registers nothing</h2>
 *
 * <p>If the capability class moves, or a method is renamed in a future Numismatic, this bridge logs one
 * warning and registers <em>no</em> currency. That is deliberate and it is the whole reason the class
 * exists in this shape: a stub that accepted debits and did nothing would let players pay fines with
 * money that was never taken, and a stub that accepted credits would delete bounty rewards. An absent
 * provider instead makes {@code integrations.currencyId = mcacrime:numismatic} fall back to emeralds
 * with the warning {@code Currencies} already prints, which is a visible, recoverable state.
 */
public final class NumismaticBridge {

    /** The mod id to look for, and the owner of every class name resolved below. */
    public static final String MOD_ID = "numismaticoverhaul";

    private static volatile boolean attempted;

    private NumismaticBridge() {
    }

    /**
     * Registers the Numismatic currency if that mod is present and its API is where we expect it.
     *
     * <p>Idempotent and cheap to call on every config reload: the first call decides, every later one
     * returns immediately. Any throwable — including the {@code ModList} not existing at all, which is
     * how this looks from a unit test — leaves the registry untouched.
     */
    public static void registerIfPresent() {
        if (attempted) {
            return;
        }
        attempted = true;
        try {
            if (!ModList.get().isLoaded(MOD_ID)) {
                return;
            }
        } catch (Throwable noModList) {
            return; // no Forge loader around us (tests, or far too early); nothing to integrate with
        }
        try {
            Class<?> adapter = Class.forName("dev.otectus.mcacrime.compat.numismatic.NumismaticCurrency");
            adapter.getMethod("registerIfUsable").invoke(null);
        } catch (Throwable failure) {
            McaCrime.LOGGER.warn("MCA: Crime found Numismatic Overhaul but could not bind to its currency "
                    + "capability; 'mcacrime:numismatic' will not be offered.", failure);
        }
    }
}
