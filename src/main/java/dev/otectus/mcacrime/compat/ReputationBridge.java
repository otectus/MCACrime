package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * The optional-classloading seam for MCA: Reputation, built to the same discipline MCA: Quests' and
 * MCA: Conversations' own Reputation bridges already use in this suite.
 *
 * <h2>The rule this class exists to enforce</h2>
 *
 * <p><b>Nothing in this file, or in anything it can reach without the mod-present check, may name a
 * {@code mcareputation} type.</b> Java resolves a class's references lazily, but lazily is not never:
 * a field type, a method signature, an annotation, or an event subscriber that mentions a missing
 * class throws {@code NoClassDefFoundError} the moment something touches it, and exactly when that
 * happens is a JVM detail no mod should depend on. So the real implementation lives entirely under
 * {@code compat.reputation}, and the only reference to it is the string below, resolved after
 * {@link ModList} confirms the mod is present.
 *
 * <h2>Two failure modes, both handled</h2>
 *
 * <p>Reputation missing or broken means we fall back to our own village standing store, which is what
 * the mod did before any of this existed. But there is a second, subtler failure: Reputation present
 * and working, yet refusing our authority claim. That must <em>also</em> stop us recording the
 * overlapping deeds, because Reputation is still detecting them itself — and two producers is the one
 * outcome worse than none.
 */
public final class ReputationBridge {

    /** The Reputation API generation this build was written against. */
    public static final int REQUIRED_API_VERSION = 1;

    private static volatile ReputationOps ops;
    private static volatile boolean initialised;
    private static volatile String status = "not initialised";
    private static volatile boolean degraded;

    private ReputationBridge() {
    }

    /**
     * Installs the implementation. Called by {@code CrimeReputationCompat} once it has loaded, which
     * only happens after the presence check below has passed.
     */
    public static void setOps(@Nullable ReputationOps implementation) {
        ops = implementation;
    }

    /**
     * Chooses whether the integration runs. Called once from common setup, after Forge has loaded
     * every mod, so {@link ModList} is authoritative by this point.
     */
    public static synchronized void init() {
        if (initialised) {
            return;
        }
        initialised = true;

        if (!McaCrimeConfig.COMMON.enableReputation.get()) {
            status = "disabled by config";
            McaCrime.LOGGER.info("MCA: Crime — Reputation integration is disabled in the config; using the "
                    + "built-in village standing store.");
            return;
        }
        if (!ModList.get().isLoaded("mcareputation")) {
            status = "not installed";
            McaCrime.LOGGER.info("MCA: Crime — MCA: Reputation is not installed; using the built-in village "
                    + "standing store.");
            return;
        }
        try {
            // Class.forName rather than a direct reference: naming the class here would put it in this
            // class's constant pool and defeat the whole seam.
            Class.forName("dev.otectus.mcacrime.compat.reputation.CrimeReputationCompat")
                    .getMethod("register").invoke(null);
            ReputationOps candidate = ops;
            if (candidate == null) {
                status = "adapter did not install";
                McaCrime.LOGGER.error("MCA: Crime — the MCA: Reputation adapter loaded but installed nothing; "
                        + "the integration is off and MCA: Crime will use its own store.");
                return;
            }
            int version = candidate.apiVersion();
            if (version != REQUIRED_API_VERSION) {
                ops = null;
                status = "incompatible API v" + version;
                McaCrime.LOGGER.error("MCA: Crime — MCA: Reputation reports API version {} but this build needs "
                                + "v{}. The integration is disabled and MCA: Crime will use its own store. "
                                + "Update whichever of the two mods is older.",
                        version, REQUIRED_API_VERSION);
                return;
            }
            status = "ready";
            McaCrime.LOGGER.info("MCA: Crime — MCA: Reputation detected (API v{}); community standing will be "
                    + "recorded there once authority is claimed.", version);
        } catch (Throwable t) {
            ops = null;
            status = "failed: " + t.getClass().getSimpleName();
            McaCrime.LOGGER.error("MCA: Crime — MCA: Reputation is installed but the integration could not "
                    + "start; falling back to the built-in store. MCA: Crime remains fully playable.", t);
        }
    }

    /**
     * Claims ownership of villager assault and killing. Called after the first datapack load, because
     * the incident definitions we are about to produce do not exist before then.
     */
    public static synchronized void claimAuthority() {
        ReputationOps current = ops;
        if (current == null) {
            return;
        }
        try {
            if (current.claimAuthority()) {
                status = "ready (authority held)";
                McaCrime.LOGGER.info("MCA: Crime now owns villager assault and killing detection; MCA: "
                        + "Reputation's own detector has stood down for those deeds.");
            } else {
                status = "ready (authority refused)";
                McaCrime.LOGGER.warn("MCA: Crime — MCA: Reputation refused the detection authority claim. "
                        + "Its own detector stays active, so MCA: Crime will not record assault or killing "
                        + "incidents; everything else still works. Check for another mod claiming the same "
                        + "deeds, or for enableCrimeIntegration=false on the Reputation side.");
            }
        } catch (Throwable t) {
            status = "authority claim failed";
            McaCrime.LOGGER.error("MCA: Crime — claiming detection authority threw; MCA: Reputation keeps its "
                    + "own detector and MCA: Crime will not record overlapping incidents.", t);
        }
    }

    /** Hands detection back. Called on server shutdown. */
    public static synchronized void releaseAuthority() {
        ReputationOps current = ops;
        if (current == null) {
            return;
        }
        try {
            current.releaseAuthority();
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — releasing detection authority threw; ignoring", t);
        }
    }

    /** The active implementation, or empty when the integration is off for any reason. */
    public static Optional<ReputationOps> ops() {
        return degraded ? Optional.empty() : Optional.ofNullable(ops);
    }

    /** Whether the integration is live. */
    public static boolean isAvailable() {
        return ops().isPresent();
    }

    /**
     * Whether we currently own the deeds MCA: Reputation would otherwise detect itself.
     *
     * <p>Checked per crime rather than cached, so a claim that lapses — a config reload, another mod
     * registering a competing authority — stops us recording overlapping deeds on the very next event
     * rather than at the next restart.
     */
    public static boolean holdsAuthority() {
        return ops().map(current -> {
            try {
                return current.holdsAuthority();
            } catch (Throwable t) {
                return false;
            }
        }).orElse(false);
    }

    /**
     * Switches the integration off for the rest of the session after a failure that retrying cannot
     * fix — most importantly, incident definitions the companion does not recognise. Without this, an
     * authority claim we hold but cannot honour would be an incident black hole: nobody records the
     * deed at all.
     */
    public static void degrade(String reason) {
        if (degraded) {
            return;
        }
        degraded = true;
        status = "degraded: " + reason;
        releaseAuthority();
        McaCrime.LOGGER.error("MCA: Crime — the MCA: Reputation integration has been switched off for this "
                + "session ({}). Detection authority has been released, so MCA: Reputation resumes recording "
                + "villager assault and killing itself. No crime data has been lost.", reason);
    }

    /** A short human-readable state for {@code /crime debug integrations}. */
    public static String status() {
        return status;
    }

    /** Test and shutdown hook. */
    public static synchronized void reset() {
        ops = null;
        initialised = false;
        degraded = false;
        status = "not initialised";
    }
}
