package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The optional-classloading seam for MCA Capitals (0.7.8), built to {@link TownsteadBridge}'s
 * discipline: {@link ModList} decides, {@code Class.forName} loads, and nothing in this file names a
 * type that would fail to resolve without Capitals installed.
 *
 * <h2>Why the implementation is reflective all the way down</h2>
 *
 * <p>Capitals is compiled against MCA, so its own descriptors name MCA types -- the authority resolver
 * returns an MCA {@code Rank}, its dialogue service takes a {@code VillagerEntityMCA}. Naming a Capitals
 * class here would put a relocated MCA type into this mod's constant pool, which is what
 * {@code NoMcaStaticLinkTest} exists to prevent. So the adapter under {@code compat.capitals} matches
 * members by name and arity, adapts every handle to an all-{@code Object} shape, and is reached only
 * through the dotted string below. {@code NoCapitalsStaticLinkTest} keeps it that way.
 *
 * <h2>What this bridge promises gameplay code</h2>
 *
 * <p><b>It never throws</b>, and every answer is a {@link CapitalsQueryResult} that keeps "Capitals
 * said nothing" apart from "Capitals could not be asked". Binding happens at {@code ServerStartedEvent}
 * beside the Townstead binding and is released on server stop. MCA: Crime reads Capitals and, when an
 * operator has switched it on, writes one chronicle line; it never writes Capitals' justice, warrant,
 * title or succession state, and it never registers itself as a jurisdiction provider.
 */
public final class CapitalsBridge {

    /** The MCA Capitals mod id, as it appears in {@code ModList}. */
    public static final String MOD_ID = "mcacapitals";

    /** The capabilities a royal decree cannot be judged without. */
    public static final Set<CapitalsCapability> DECREE_CAPABILITIES = Set.of(CapitalsCapability.REGISTRY,
            CapitalsCapability.AUTHORITY, CapitalsCapability.TITLES);

    /** How much of the declared read surface is live. */
    public enum State {
        /** Capitals is not installed. The normal, silent path -- never a warning. */
        ABSENT,
        /** Installed, but the config switched the integration off. */
        OFF,
        /** Installed and enabled, but nothing could be bound. */
        DISABLED,
        /** Some capabilities bound; the features behind the rest report as degraded. */
        PARTIAL,
        /** Every capability the manifest declares bound. */
        FULL
    }

    /**
     * What the reflective implementation provides. Declared here so this file can hold the reference
     * without naming anything under {@code compat.capitals}. Every method returns a value; none throws.
     */
    public interface Ops {

        State state();

        Set<CapitalsCapability> capabilities();

        String detectedVersion();

        /** Manifest members that did not bind, plus any that threw since binding. */
        List<String> unresolvedMembers();

        /** The capital seated in {@code villageId} of {@code level}, or a confirmed none. */
        CapitalsQueryResult<CapitalView> capitalAt(ServerLevel level, int villageId);

        /** Whether Capitals holds any capital at all; empty also while its data has not loaded. */
        CapitalsQueryResult<Boolean> anyCapitals();

        /**
         * What {@code player} holds in the capital seated at {@code villageId}, provided that capital is
         * still {@code capitalId}. A different capital answers absent rather than another capital's facts.
         */
        CapitalsQueryResult<CapitalStanding> standing(ServerLevel level, int villageId, UUID capitalId, UUID player);

        CapitalsQueryResult<CapitalCourtView> court(ServerLevel level, int villageId, UUID capitalId);

        CapitalsQueryResult<Boolean> royalGuard(ServerLevel level, int villageId, UUID capitalId, UUID villager);

        /** Writes one flat line into the capital's chronicle; true when it was written. */
        CapitalsQueryResult<Boolean> chronicle(ServerLevel level, int villageId, UUID capitalId, String entry,
                                               boolean herald);

        /** Whether Capitals' own player-warrant table holds {@code player} in that capital. */
        CapitalsQueryResult<Boolean> playerWarrant(ServerLevel level, int villageId, UUID capitalId, UUID player);
    }

    private static final String IMPLEMENTATION = "dev.otectus.mcacrime.compat.capitals.ReflectiveCapitalsBridge";

    private static volatile Ops ops;
    private static volatile boolean bound;
    private static volatile State state = State.ABSENT;
    private static volatile String status = "not initialised";
    /** Set only by {@link #installForTest}; a test implementation has no config to read. */
    private static volatile boolean testInstalled;

    private CapitalsBridge() {
    }

    /**
     * Binds the integration, once per server. Repeated calls are no-ops until {@link #release()}.
     *
     * <p>At {@code ServerStartedEvent}, beside the Townstead binding, because Capitals' records are a
     * per-server in-memory map mirrored from its saved data: there is nothing meaningful to ask before a
     * server exists, and an operator reading the log wants every companion's answer in one place.
     */
    public static synchronized void bind() {
        if (bound) {
            return;
        }
        bound = true;
        if (!installed()) {
            state = State.ABSENT;
            status = "not installed";
            return;
        }
        if (!integrationEnabled()) {
            state = State.OFF;
            status = "disabled by config";
            McaCrime.LOGGER.info("MCA: Crime — MCA Capitals is installed but [capitals] enabled is false; "
                    + "royal decrees are off and nothing is read from Capitals.");
            return;
        }
        try {
            Object candidate = Class.forName(IMPLEMENTATION).getDeclaredConstructor().newInstance();
            if (!(candidate instanceof Ops implementation)) {
                state = State.DISABLED;
                status = "adapter did not install";
                McaCrime.LOGGER.error("MCA: Crime — the MCA Capitals adapter loaded but did not implement the "
                        + "bridge; royal decrees are off and the rest of the mod is unaffected.");
                return;
            }
            ops = implementation;
            state = implementation.state();
            status = implementation.state().name().toLowerCase(java.util.Locale.ROOT) + " ("
                    + implementation.capabilities().size() + " capability/ies)";
            log(implementation);
        } catch (Throwable t) {
            ops = null;
            state = State.DISABLED;
            status = "failed: " + t.getClass().getSimpleName();
            McaCrime.LOGGER.error("MCA: Crime — MCA Capitals is installed but the integration could not start; "
                    + "royal decrees are off and the rest of the mod is unaffected.", t);
        }
    }

    private static void log(Ops implementation) {
        switch (implementation.state()) {
            case FULL -> McaCrime.LOGGER.info("MCA: Crime — MCA Capitals {} detected; all {} capabilities bound.",
                    implementation.detectedVersion(), implementation.capabilities().size());
            case PARTIAL -> McaCrime.LOGGER.warn("MCA: Crime — MCA Capitals {} detected, but only {} capability/ies "
                            + "bound; royal decrees {}. Run /crime debug capitals. Unbound member(s): {}",
                    implementation.detectedVersion(), implementation.capabilities().size(),
                    implementation.capabilities().containsAll(DECREE_CAPABILITIES) ? "still work" : "are off",
                    implementation.unresolvedMembers());
            default -> McaCrime.LOGGER.warn("MCA: Crime — MCA Capitals {} is installed but nothing could be bound. "
                            + "The usual cause is a Capitals build this MCA: Crime does not know; royal decrees "
                            + "are off. Unbound member(s): {}",
                    implementation.detectedVersion(), implementation.unresolvedMembers());
        }
    }

    /** Drops the binding at server stop, so a second world in one session re-binds cleanly. */
    public static synchronized void release() {
        ops = null;
        bound = false;
        testInstalled = false;
        state = State.ABSENT;
        status = "not initialised";
    }

    /** Re-negotiates after a common-config reload, including off-to-on changes. */
    public static synchronized void reload() {
        release();
        bind();
    }

    /** Test hook: installs an implementation directly, as if it had bound. */
    public static synchronized void installForTest(@Nullable Ops implementation) {
        ops = implementation;
        bound = implementation != null;
        testInstalled = implementation != null;
        state = implementation == null ? State.ABSENT : implementation.state();
        status = implementation == null ? "not initialised" : "test";
    }

    /** Whether Capitals is installed at all, regardless of what bound. */
    public static boolean installed() {
        try {
            ModList list = ModList.get();
            return list != null && list.isLoaded(MOD_ID);
        } catch (Throwable t) {
            return false; // no ModList outside a running game (unit tests): absent is the honest answer
        }
    }

    /** The {@code [capitals] enabled} switch; false when the config cannot be read yet. */
    public static boolean integrationEnabled() {
        try {
            return McaCrimeConfig.COMMON.capitalsEnabled.get();
        } catch (Throwable t) {
            return false;
        }
    }

    public static State state() {
        return state;
    }

    /** A short human-readable state for diagnostics. */
    public static String status() {
        return status;
    }

    public static String detectedVersion() {
        Ops current = ops;
        return current == null ? "" : current.detectedVersion();
    }

    public static List<String> unresolvedMembers() {
        Ops current = ops;
        return current == null ? List.of() : current.unresolvedMembers();
    }

    public static Set<CapitalsCapability> capabilities() {
        Ops current = ops;
        return current == null ? Set.of() : current.capabilities();
    }

    /** Whether one capability is live right now. */
    public static boolean has(@Nullable CapitalsCapability capability) {
        Ops current = ops;
        return capability != null && current != null && integrationEnabledOrTest()
                && current.capabilities().contains(capability);
    }

    /** Whether every capability a royal decree is judged by is live. */
    public static boolean decreesReady() {
        Ops current = ops;
        return current != null && integrationEnabledOrTest() && current.capabilities().containsAll(DECREE_CAPABILITIES);
    }

    /** The capabilities a decree needs that are not live, for a refusal or a diagnostic. */
    public static Set<CapitalsCapability> missingForDecrees() {
        EnumSet<CapitalsCapability> missing = EnumSet.noneOf(CapitalsCapability.class);
        for (CapitalsCapability capability : DECREE_CAPABILITIES) {
            if (!has(capability)) {
                missing.add(capability);
            }
        }
        return missing;
    }

    /**
     * The config switch, except under a test-installed implementation, which has no config to read. A
     * production bridge is only ever installed by {@link #bind()} after the switch was found on, and a
     * config reload re-binds, so this never lets a switched-off integration answer.
     */
    private static boolean integrationEnabledOrTest() {
        return testInstalled || integrationEnabled();
    }

    // --- queries; each total, each answering unavailable when nothing can be asked ----------------

    public static CapitalsQueryResult<CapitalView> capitalAt(@Nullable ServerLevel level, int villageId) {
        Ops current = ops;
        if (current == null || level == null || villageId < 0 || !has(CapitalsCapability.REGISTRY)) {
            return CapitalsQueryResult.missing(CapitalsCapability.REGISTRY);
        }
        try {
            return current.capitalAt(level, villageId);
        } catch (Throwable t) {
            return CapitalsQueryResult.failed(t.getClass().getSimpleName());
        }
    }

    public static CapitalsQueryResult<Boolean> anyCapitals() {
        Ops current = ops;
        if (current == null || !has(CapitalsCapability.REGISTRY)) {
            return CapitalsQueryResult.missing(CapitalsCapability.REGISTRY);
        }
        try {
            return current.anyCapitals();
        } catch (Throwable t) {
            return CapitalsQueryResult.failed(t.getClass().getSimpleName());
        }
    }

    public static CapitalsQueryResult<CapitalStanding> standing(@Nullable ServerLevel level, int villageId,
                                                                @Nullable UUID capitalId, @Nullable UUID player) {
        Ops current = ops;
        if (current == null || level == null || capitalId == null || player == null || !decreesReady()) {
            return CapitalsQueryResult.unavailable("standing cannot be read");
        }
        try {
            return current.standing(level, villageId, capitalId, player);
        } catch (Throwable t) {
            return CapitalsQueryResult.failed(t.getClass().getSimpleName());
        }
    }

    public static CapitalsQueryResult<CapitalCourtView> court(@Nullable ServerLevel level, int villageId,
                                                              @Nullable UUID capitalId) {
        Ops current = ops;
        if (current == null || level == null || capitalId == null || !has(CapitalsCapability.COURT)) {
            return CapitalsQueryResult.missing(CapitalsCapability.COURT);
        }
        try {
            return current.court(level, villageId, capitalId);
        } catch (Throwable t) {
            return CapitalsQueryResult.failed(t.getClass().getSimpleName());
        }
    }

    public static CapitalsQueryResult<Boolean> royalGuard(@Nullable ServerLevel level, int villageId,
                                                          @Nullable UUID capitalId, @Nullable UUID villager) {
        Ops current = ops;
        if (current == null || level == null || capitalId == null || villager == null
                || !has(CapitalsCapability.COURT)) {
            return CapitalsQueryResult.missing(CapitalsCapability.COURT);
        }
        try {
            return current.royalGuard(level, villageId, capitalId, villager);
        } catch (Throwable t) {
            return CapitalsQueryResult.failed(t.getClass().getSimpleName());
        }
    }

    public static CapitalsQueryResult<Boolean> chronicle(@Nullable ServerLevel level, int villageId,
                                                         @Nullable UUID capitalId, @Nullable String entry,
                                                         boolean herald) {
        Ops current = ops;
        if (current == null || level == null || capitalId == null || entry == null || entry.isBlank()
                || !has(CapitalsCapability.CHRONICLE)) {
            return CapitalsQueryResult.missing(CapitalsCapability.CHRONICLE);
        }
        try {
            return current.chronicle(level, villageId, capitalId, entry, herald);
        } catch (Throwable t) {
            return CapitalsQueryResult.failed(t.getClass().getSimpleName());
        }
    }

    public static CapitalsQueryResult<Boolean> playerWarrant(@Nullable ServerLevel level, int villageId,
                                                             @Nullable UUID capitalId, @Nullable UUID player) {
        Ops current = ops;
        if (current == null || level == null || capitalId == null || player == null
                || !has(CapitalsCapability.PLAYER_WARRANTS)) {
            return CapitalsQueryResult.missing(CapitalsCapability.PLAYER_WARRANTS);
        }
        try {
            return current.playerWarrant(level, villageId, capitalId, player);
        } catch (Throwable t) {
            return CapitalsQueryResult.failed(t.getClass().getSimpleName());
        }
    }
}
