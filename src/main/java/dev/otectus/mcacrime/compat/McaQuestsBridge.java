package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.neoforged.fml.ModList;

import org.jetbrains.annotations.Nullable;

/**
 * The optional-classloading seam for MCA: Quests, built to the same discipline as
 * {@link ReputationBridge} and {@link LocksReforgedBridge}.
 *
 * <p><b>Nothing reachable from here without the mod-present check may name a {@code mcaquests}
 * type.</b> The adapter that does live entirely under {@code compat.mcaquests}, and the only
 * reference to it is the string below, resolved after {@link ModList} says the mod is installed.
 *
 * <h2>Why the handshake is presence-based</h2>
 *
 * <p>MCA: Reputation publishes an API generation number, so its bridge can refuse a companion that is
 * merely too old. MCA: Quests publishes no such constant: what it offers is a registration API and a
 * marker interface, and the honest test of "is this build the one this adapter was written against"
 * is therefore whether calling them works. So {@code register()} touches
 * {@code McaQuestsApi.registerObjective} and {@code ExternalSignalObjective} directly, and a
 * {@code NoSuchMethodError} or {@code NoClassDefFoundError} out of that call means an incompatible
 * version — handled here exactly as a missing mod is, with one line in the log saying so.
 *
 * <p>Every failure ends the same way: bounties still work, are still paid, and are simply not
 * presented as quests.
 */
public final class McaQuestsBridge {

    /** The MCA: Quests mod id, as declared in its own mods.toml. */
    private static final String MOD_ID = "mcaquests";

    private static volatile BountyQuestBridge implementation = BountyQuestBridge.NOOP;
    private static volatile boolean initialised;
    private static volatile String status = "not initialised";

    private McaQuestsBridge() {
    }

    /**
     * Installs the implementation. Called by {@code McaQuestsBountyCompat} once it has loaded, which
     * only happens after the presence check below has passed.
     */
    public static void setImplementation(@Nullable BountyQuestBridge bridge) {
        implementation = bridge == null ? BountyQuestBridge.NOOP : bridge;
    }

    /** Chooses whether the integration runs. Called once from common setup, after every mod has loaded. */
    public static synchronized void init() {
        if (initialised) {
            return;
        }
        initialised = true;

        if (!McaCrimeConfig.COMMON.mcaQuestsBounties.get()) {
            status = "disabled by config";
            return;
        }
        if (!ModList.get().isLoaded(MOD_ID)) {
            status = "not installed";
            return;
        }
        try {
            // Class.forName rather than a direct reference: naming the adapter here would put its
            // whole constant pool -- MCA: Quests types included -- behind a class that loads always.
            Class.forName("dev.otectus.mcacrime.compat.mcaquests.McaQuestsBountyCompat")
                    .getMethod("register").invoke(null);
            if (!implementation.available()) {
                implementation = BountyQuestBridge.NOOP;
                status = "adapter did not install";
                McaCrime.LOGGER.error("MCA: Crime - the MCA: Quests adapter loaded but installed nothing; "
                        + "bounties are still paid, they are simply not offered as quests.");
                return;
            }
            status = "ready";
            McaCrime.LOGGER.info("MCA: Crime - MCA: Quests detected; open bounties will be offered as "
                    + "contracts. The principal reward is still paid by MCA: Crime, once.");
        } catch (NoClassDefFoundError | NoSuchMethodError e) {
            // The presence handshake failing: an installed MCA: Quests whose API is not the one this
            // adapter was written against. Nothing to version-check against, so the call itself is the
            // check, and this is the branch an operator on a mismatched pair actually lands on.
            implementation = BountyQuestBridge.NOOP;
            status = "incompatible MCA: Quests";
            McaCrime.LOGGER.error("MCA: Crime - the installed MCA: Quests does not carry the add-on API this "
                    + "integration is built on, so bounty contracts are off. Bounties are still posted, "
                    + "claimed and paid normally; update MCA: Quests to offer them as quests. ({})",
                    e.toString());
        } catch (Throwable t) {
            implementation = BountyQuestBridge.NOOP;
            status = "failed: " + t.getClass().getSimpleName();
            McaCrime.LOGGER.error("MCA: Crime - MCA: Quests is installed but the bounty-contract integration "
                    + "could not start; bounties are still paid, just not offered as quests.", t);
        }
    }

    /** The live bridge, or {@link BountyQuestBridge#NOOP} when the integration is off for any reason. */
    public static BountyQuestBridge get() {
        return implementation;
    }

    /** A short human-readable state for the debug commands. */
    public static String status() {
        return status;
    }

    /** Test and shutdown hook. */
    public static synchronized void reset() {
        implementation = BountyQuestBridge.NOOP;
        initialised = false;
        status = "not initialised";
    }
}
