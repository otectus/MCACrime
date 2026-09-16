package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.server.MinecraftServer;
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

    /**
     * The oldest MCA: Reputation that carries the detection-authority API this adapter needs.
     *
     * <p>Not expressed as a {@code versionRange} in {@code mods.toml}, and that is a deliberate
     * choice rather than an oversight. Forge enforces the range of an optional dependency when the
     * mod is present, so declaring {@code [0.3,)} would stop the game launching for anybody running
     * the older companion — a hard failure over an integration that is, by definition, optional. The
     * range stays permissive and the shortfall is handled here instead: the integration switches off,
     * the built-in store takes over, and the log says which version would turn it back on.
     */
    private static final String MINIMUM_COMPANION_VERSION = "0.3.0";

    private static volatile ReputationOps ops;
    private static volatile boolean initialised;
    private static volatile String status = "not initialised";
    private static volatile boolean degraded;
    private static volatile ReputationCapabilitySnapshot capabilities = ReputationCapabilitySnapshot.absent();

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
                    + "recorded there once authority is claimed. Which of its newer facilities are "
                    + "actually used is decided by the capability handshake at server start, not by "
                    + "this version number.", version);
        } catch (NoClassDefFoundError | NoSuchMethodError e) {
            // An installed companion too old to carry the API this adapter is written against. The
            // declared dependency range stays permissive on purpose -- refusing to launch over an
            // optional integration is a worse outcome than running without it -- which makes this the
            // path an operator in that situation actually lands on, so it has to say what to do rather
            // than report an exception class and leave them to guess.
            ops = null;
            status = "needs MCA: Reputation " + MINIMUM_COMPANION_VERSION;
            McaCrime.LOGGER.error("MCA: Crime — the installed MCA: Reputation is older than {}, which is the "
                            + "first version with the detection-authority API this integration is built on. "
                            + "The integration is off and MCA: Crime is using its own village standing store; "
                            + "everything else works normally. Update MCA: Reputation to {} or newer to turn "
                            + "it on. ({})",
                    MINIMUM_COMPANION_VERSION, MINIMUM_COMPANION_VERSION, e.toString());
        } catch (Throwable t) {
            ops = null;
            status = "failed: " + t.getClass().getSimpleName();
            McaCrime.LOGGER.error("MCA: Crime — MCA: Reputation is installed but the integration could not "
                    + "start; falling back to the built-in store. MCA: Crime remains fully playable.", t);
        }
    }

    /**
     * Asks the companion what it can do, once per server, and caches the answer.
     *
     * <p>Separate from {@link #init()} and deliberately later: {@code init} runs during mod loading,
     * where there is no server, no datapack, and therefore no answer to give about anything that
     * depends on loaded content — which in 0.6.0 includes the whole public-profile layer. The
     * handshake belongs where the features are about to be used.
     *
     * <p>Once per server rather than per delivery because the answer is a property of the installed
     * jar and its config, and the pump asks the question for every operation it drains. Re-negotiated
     * on the next server start, so a config change between worlds is picked up.
     */
    public static synchronized void negotiate(@Nullable MinecraftServer server) {
        ReputationOps current = ops().orElse(null);
        if (current == null) {
            capabilities = ReputationCapabilitySnapshot.absent();
            return;
        }
        try {
            ReputationCapabilitySnapshot snapshot = current.capabilities(server);
            capabilities = snapshot == null ? ReputationCapabilitySnapshot.absent() : snapshot;
            McaCrime.LOGGER.info("MCA: Crime — MCA: Reputation capabilities: {}", capabilities.describe());
            if (!capabilities.supportsDelivery()) {
                McaCrime.LOGGER.info("MCA: Crime — this MCA: Reputation does not advertise keyed delivery, "
                        + "so civic writes use the older record path. They stay correct; they are simply "
                        + "not receipted, so a crash between its commit and our link write can leave one "
                        + "queued operation to retry.");
            }
        } catch (Throwable t) {
            capabilities = ReputationCapabilitySnapshot.absent();
            McaCrime.LOGGER.warn("MCA: Crime — negotiating MCA: Reputation capabilities threw; using the "
                    + "oldest supported surface.", t);
        }
    }

    /**
     * What the companion advertised at the last handshake.
     *
     * <p>Never null, and nothing advertised when there was no handshake — so a caller that forgets to
     * negotiate degrades to the oldest surface rather than calling a method that is not there.
     */
    public static ReputationCapabilitySnapshot capabilities() {
        return isAvailable() ? capabilities : ReputationCapabilitySnapshot.absent();
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
            capabilities = ReputationCapabilitySnapshot.absent();
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
        capabilities = ReputationCapabilitySnapshot.absent();
        status = "not initialised";
    }
}
