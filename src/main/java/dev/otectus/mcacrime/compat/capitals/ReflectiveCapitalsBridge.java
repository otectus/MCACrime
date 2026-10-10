package dev.otectus.mcacrime.compat.capitals;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.compat.CapitalCourtView;
import dev.otectus.mcacrime.compat.CapitalStanding;
import dev.otectus.mcacrime.compat.CapitalView;
import dev.otectus.mcacrime.compat.CapitalsBridge;
import dev.otectus.mcacrime.compat.CapitalsCapability;
import dev.otectus.mcacrime.compat.CapitalsQueryResult;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

import org.jetbrains.annotations.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real {@link CapitalsBridge.Ops}, over a resolved {@link CapitalsBinding.Resolution} (0.7.8).
 *
 * <p>Instantiated by name from {@code CapitalsBridge} only after {@code ModList} has confirmed Capitals
 * is present, which is why this class may reference {@link CapitalsBinding} -- and why nothing outside
 * this package may reference <em>it</em>.
 *
 * <p>Every Capitals value crosses this class as an {@link Object} and leaves it as a UUID, a string or a
 * boolean inside one of MCA: Crime's own view records. Enum constants are read back through
 * {@link Enum#name()}, so no Capitals enum is ever named in this mod's bytecode.
 *
 * <p>A call that throws answers {@code Failed} for that one question, and the first failure of each
 * member is logged once at WARN: a decree is then refused or suspended, never decided on a guess. The
 * member is not disabled for the rest of the session, because one odd record should not turn the law
 * off for every other village.
 */
public final class ReflectiveCapitalsBridge implements CapitalsBridge.Ops {

    private final CapitalsBinding.Resolution resolution;
    private final String version;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    public ReflectiveCapitalsBridge() {
        this(resolveQuietly(), versionOf());
    }

    /** Test seam: a bridge over an explicit resolution. */
    ReflectiveCapitalsBridge(CapitalsBinding.Resolution resolution, String version) {
        this.resolution = resolution;
        this.version = version == null ? "" : version;
    }

    private static CapitalsBinding.Resolution resolveQuietly() {
        try {
            return CapitalsBinding.resolveAgainst(ReflectiveCapitalsBridge.class.getClassLoader());
        } catch (Throwable t) {
            return CapitalsBinding.absent();
        }
    }

    private static String versionOf() {
        try {
            return ModList.get().getModContainerById(CapitalsBridge.MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("");
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public CapitalsBridge.State state() {
        return resolution.state();
    }

    @Override
    public Set<CapitalsCapability> capabilities() {
        return resolution.capabilities();
    }

    @Override
    public String detectedVersion() {
        return version;
    }

    @Override
    public List<String> unresolvedMembers() {
        return resolution.unresolved();
    }

    // --- registry ----------------------------------------------------------------------------------

    @Override
    public CapitalsQueryResult<CapitalView> capitalAt(ServerLevel level, int villageId) {
        if (!resolution.has(CapitalsCapability.REGISTRY)) {
            return CapitalsQueryResult.missing(CapitalsCapability.REGISTRY);
        }
        try {
            Object record = call(CapitalsBinding.CAPITAL_BY_VILLAGE, level, villageId);
            return record == null ? CapitalsQueryResult.absent() : CapitalsQueryResult.available(view(record));
        } catch (CallFailed failure) {
            return CapitalsQueryResult.failed(failure.getMessage());
        }
    }

    @Override
    public CapitalsQueryResult<Boolean> anyCapitals() {
        if (!resolution.has(CapitalsCapability.REGISTRY)) {
            return CapitalsQueryResult.missing(CapitalsCapability.REGISTRY);
        }
        try {
            Object all = call(CapitalsBinding.ALL_CAPITALS);
            return all instanceof Collection<?> records ? CapitalsQueryResult.available(!records.isEmpty())
                    : CapitalsQueryResult.failed("getAllCapitalRecords returned no collection");
        } catch (CallFailed failure) {
            return CapitalsQueryResult.failed(failure.getMessage());
        }
    }

    private CapitalView view(Object record) throws CallFailed {
        Object id = call(CapitalsBinding.REC_CAPITAL_ID, record);
        Object village = call(CapitalsBinding.REC_VILLAGE_ID, record);
        if (!(id instanceof UUID capitalId) || !(village instanceof Integer villageId)) {
            throw new CallFailed("capital record without an id or village");
        }
        Object dimension = call(CapitalsBinding.REC_DIMENSION, record);
        String state = enumName(call(CapitalsBinding.REC_STATE, record));
        UUID playerSovereign = null;
        if (resolution.has(CapitalsCapability.AUTHORITY)
                && Boolean.TRUE.equals(call(CapitalsBinding.REC_IS_PLAYER_SOVEREIGN, record))) {
            playerSovereign = call(CapitalsBinding.REC_PLAYER_SOVEREIGN_ID, record) instanceof UUID uuid ? uuid : null;
        }
        return new CapitalView(capitalId, villageId, dimension instanceof String text ? text : "",
                state == null ? "" : state, playerSovereign);
    }

    /** The record seated at {@code villageId}, provided it is still {@code capitalId}; null otherwise. */
    @Nullable
    private Object record(ServerLevel level, int villageId, UUID capitalId) throws CallFailed {
        Object record = call(CapitalsBinding.CAPITAL_BY_VILLAGE, level, villageId);
        if (record == null) {
            return null;
        }
        return capitalId.equals(call(CapitalsBinding.REC_CAPITAL_ID, record)) ? record : null;
    }

    // --- authority and titles -----------------------------------------------------------------------

    @Override
    public CapitalsQueryResult<CapitalStanding> standing(ServerLevel level, int villageId, UUID capitalId, UUID player) {
        for (CapitalsCapability needed : CapitalsBridge.DECREE_CAPABILITIES) {
            if (!resolution.has(needed)) {
                return CapitalsQueryResult.missing(needed);
            }
        }
        try {
            Object record = record(level, villageId, capitalId);
            if (record == null) {
                return CapitalsQueryResult.absent();
            }
            Object authority = call(CapitalsBinding.RESOLVE_AUTHORITY, level, record, player);
            String rank = authority == null ? null : enumName(call(CapitalsBinding.AUTHORITY_RANK, authority));
            if (rank == null) {
                return CapitalsQueryResult.failed("the authority resolver gave no rank");
            }
            return CapitalsQueryResult.available(new CapitalStanding(rank,
                    enumName(call(CapitalsBinding.GRANTED_TITLE, level, record, player)),
                    enumName(call(CapitalsBinding.MARRIAGE_TITLE, level, record, player)),
                    enumName(call(CapitalsBinding.DOWAGER_TITLE, level, record, player))));
        } catch (CallFailed failure) {
            return CapitalsQueryResult.failed(failure.getMessage());
        }
    }

    // --- court ---------------------------------------------------------------------------------------

    @Override
    public CapitalsQueryResult<CapitalCourtView> court(ServerLevel level, int villageId, UUID capitalId) {
        if (!resolution.has(CapitalsCapability.COURT)) {
            return CapitalsQueryResult.missing(CapitalsCapability.COURT);
        }
        try {
            Object record = record(level, villageId, capitalId);
            if (record == null) {
                return CapitalsQueryResult.absent();
            }
            return CapitalsQueryResult.available(new CapitalCourtView(
                    uuid(call(CapitalsBinding.REC_HAND, record)),
                    uuid(call(CapitalsBinding.REC_COMMANDER, record)),
                    uuid(call(CapitalsBinding.REC_MASTER_OF_LAWS, record)),
                    uuid(call(CapitalsBinding.REC_HERALD, record))));
        } catch (CallFailed failure) {
            return CapitalsQueryResult.failed(failure.getMessage());
        }
    }

    @Override
    public CapitalsQueryResult<Boolean> royalGuard(ServerLevel level, int villageId, UUID capitalId, UUID villager) {
        if (!resolution.has(CapitalsCapability.COURT)) {
            return CapitalsQueryResult.missing(CapitalsCapability.COURT);
        }
        try {
            Object record = record(level, villageId, capitalId);
            return record == null ? CapitalsQueryResult.absent() : CapitalsQueryResult.available(
                    Boolean.TRUE.equals(call(CapitalsBinding.REC_IS_ROYAL_GUARD, record, villager)));
        } catch (CallFailed failure) {
            return CapitalsQueryResult.failed(failure.getMessage());
        }
    }

    // --- chronicle and Capitals' own warrants --------------------------------------------------------

    @Override
    public CapitalsQueryResult<Boolean> chronicle(ServerLevel level, int villageId, UUID capitalId, String entry,
                                                  boolean herald) {
        if (!resolution.has(CapitalsCapability.CHRONICLE)) {
            return CapitalsQueryResult.missing(CapitalsCapability.CHRONICLE);
        }
        try {
            Object record = record(level, villageId, capitalId);
            if (record == null) {
                return CapitalsQueryResult.absent();
            }
            if (herald) {
                call(CapitalsBinding.CHRONICLE_ENTRY, level, record, entry);
            } else {
                call(CapitalsBinding.CHRONICLE_ENTRY_QUIET, record, entry);
            }
            call(CapitalsBinding.MARK_DIRTY, level);
            return CapitalsQueryResult.available(true);
        } catch (CallFailed failure) {
            return CapitalsQueryResult.failed(failure.getMessage());
        }
    }

    @Override
    public CapitalsQueryResult<Boolean> playerWarrant(ServerLevel level, int villageId, UUID capitalId, UUID player) {
        if (!resolution.has(CapitalsCapability.PLAYER_WARRANTS)) {
            return CapitalsQueryResult.missing(CapitalsCapability.PLAYER_WARRANTS);
        }
        try {
            if (record(level, villageId, capitalId) == null) {
                return CapitalsQueryResult.absent();
            }
            return CapitalsQueryResult.available(
                    Boolean.TRUE.equals(call(CapitalsBinding.HAS_PLAYER_WARRANT, level, player, capitalId)));
        } catch (CallFailed failure) {
            return CapitalsQueryResult.failed(failure.getMessage());
        }
    }

    // --- test seams: the probe drives real Capitals records through the bound handles ---------------

    /** A real Capitals record, read the way {@link #capitalAt} reads one. Probe use only. */
    CapitalsQueryResult<CapitalView> viewForTest(Object record) {
        try {
            return CapitalsQueryResult.available(view(record));
        } catch (CallFailed failure) {
            return CapitalsQueryResult.failed(failure.getMessage());
        }
    }

    /**
     * The rank Capitals' authority resolver gives {@code player} in {@code record} with no level, which
     * its own guard answers {@code STRANGER} for. Proves the erased handle crosses into the real resolver
     * and the returned enum is read by name. Probe use only.
     */
    CapitalsQueryResult<String> rankForTest(Object record, UUID player) {
        try {
            Object authority = call(CapitalsBinding.RESOLVE_AUTHORITY, null, record, player);
            String rank = authority == null ? null : enumName(call(CapitalsBinding.AUTHORITY_RANK, authority));
            return rank == null ? CapitalsQueryResult.absent() : CapitalsQueryResult.available(rank);
        } catch (CallFailed failure) {
            return CapitalsQueryResult.failed(failure.getMessage());
        }
    }

    // --- plumbing ------------------------------------------------------------------------------------

    /** Thrown inside this class only: a member that is unbound or threw. */
    private static final class CallFailed extends Exception {
        CallFailed(String message) {
            super(message, null, false, false);
        }
    }

    @Nullable
    private Object call(CapitalsBinding.Member member, Object... args) throws CallFailed {
        if (!resolution.has(member)) {
            throw new CallFailed(member + " is not bound");
        }
        try {
            return resolution.handle(member).invokeWithArguments(args);
        } catch (Throwable t) {
            if (reported.add(member.toString())) {
                McaCrime.LOGGER.warn("MCA: Crime — MCA Capitals' {} threw {}; that answer is treated as "
                        + "unavailable, so a royal decree that needs it is refused or held.", member,
                        t.getClass().getSimpleName(), t);
            }
            String detail = t.getMessage() == null ? "" : ": " + t.getMessage();
            throw new CallFailed(member + ": " + t.getClass().getSimpleName()
                    + (detail.length() > 160 ? detail.substring(0, 160) : detail));
        }
    }

    @Nullable
    private static UUID uuid(@Nullable Object value) {
        return value instanceof UUID id ? id : null;
    }

    @Nullable
    private static String enumName(@Nullable Object value) {
        return value instanceof Enum<?> constant ? constant.name() : null;
    }
}
