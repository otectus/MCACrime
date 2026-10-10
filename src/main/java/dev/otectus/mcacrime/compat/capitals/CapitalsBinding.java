package dev.otectus.mcacrime.compat.capitals;

import dev.otectus.mcacrime.compat.CapitalsBridge;
import dev.otectus.mcacrime.compat.CapitalsCapability;

import org.jetbrains.annotations.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves MCA Capitals at <em>runtime</em>, by name, and reports what bound as
 * {@link CapitalsCapability capabilities} (0.7.8).
 *
 * <p>{@code TownsteadBinding}'s design, for the same reason. Capitals is compiled against MCA and its
 * descriptors name MCA types ({@code CapitalPlayerAuthorityResolver.resolveCompatibilityRank} returns an
 * MCA {@code Rank}), so nothing here is bound by parameter type: methods are matched on owner, name,
 * arity and staticness, and every handle is adapted to an erased shape whose parameters are all
 * {@link Object}. A Capitals value only ever crosses as a reference this mod never names.
 *
 * <p>Every member below was verified with {@code javap} to be unique under that key in MCA Capitals 1.3.7
 * (Forge 1.20.1) and 1.3.5 (NeoForge 1.21.1), the two builds this release targets; MCA: Quests' and MCA:
 * Conversations' Capitals bindings read the same registry and throne members.
 *
 * <h2>The contract</h2>
 *
 * <p><b>Resolution never throws and never returns null.</b> An unresolved member becomes a constant stub
 * returning its type's default, so {@link ReflectiveCapitalsBridge} needs no guard per call. Enumerating a
 * class's methods resolves their descriptors, so a Capitals built against a different MCA layout than the
 * installed one throws {@code NoClassDefFoundError} out of {@code getMethods()}; caught per owner, that
 * reads as "this capability is unbound" rather than a crash.
 *
 * <p>Every capability needs {@link CapitalsCapability#REGISTRY}: without it there is no capital record to
 * read anything else from, so a resolution that lost the registry reports nothing bound at all.
 */
public final class CapitalsBinding {

    /**
     * Capitals' package root, stored <em>dotted</em>, never in internal slash form -- what lets
     * {@code NoCapitalsStaticLinkTest} byte-scan for slash-form references and treat any hit as a
     * regression, with no exemption for this file.
     */
    static final String PACKAGE = "com.majesttyx.mcacapitals.";

    /** The class whose presence identifies an installed Capitals. */
    static final String PROBE_CLASS = "capital.CapitalManager";

    private enum Kind { VIRTUAL, STATIC }

    /** One thing MCA: Crime reads from Capitals, named relative to {@link #PACKAGE}. */
    public static final class Member {

        private final Kind kind;
        private final String ownerRelative;
        private final String name;
        private final Class<?> returnType;
        private final int arity;
        private final CapitalsCapability capability;

        private Member(Kind kind, String ownerRelative, String name, Class<?> returnType, int arity,
                       CapitalsCapability capability) {
            this.kind = kind;
            this.ownerRelative = ownerRelative;
            this.name = name;
            this.returnType = returnType;
            this.arity = arity;
            this.capability = capability;
        }

        public CapitalsCapability capability() {
            return capability;
        }

        public String ownerRelative() {
            return ownerRelative;
        }

        public String memberName() {
            return name;
        }

        public int arity() {
            return arity;
        }

        public boolean isStatic() {
            return kind == Kind.STATIC;
        }

        @Override
        public String toString() {
            return ownerRelative + "#" + name + "/" + arity;
        }

        /** The erased shape: every parameter {@link Object}, the receiver first for a virtual. */
        MethodType erasedType() {
            int params = kind == Kind.VIRTUAL ? arity + 1 : arity;
            return MethodType.methodType(returnType, Collections.nCopies(params, Object.class));
        }
    }

    private static Member statik(String owner, String name, Class<?> ret, int arity, CapitalsCapability capability) {
        return new Member(Kind.STATIC, owner, name, ret, arity, capability);
    }

    private static Member virtual(String owner, String name, Class<?> ret, int arity, CapitalsCapability capability) {
        return new Member(Kind.VIRTUAL, owner, name, ret, arity, capability);
    }

    // ---------------------------------------------------------------------------------------------
    // The manifest -- every Capitals member MCA: Crime touches. Reads, plus the one chronicle write.
    // Nothing here issues a warrant, sets a judgment, grants a title or moves a throne: those are
    // Capitals' own ceremonies.
    // ---------------------------------------------------------------------------------------------

    private static final String O_MANAGER = "capital.CapitalManager";
    private static final String O_RECORD = "capital.CapitalRecord";
    private static final String O_AUTHORITY = "capital.CapitalPlayerAuthorityResolver";
    private static final String O_RESOLVED = "capital.CapitalPlayerAuthorityResolver$ResolvedAuthority";
    private static final String O_TITLES = "player.PlayerCapitalTitleService";
    private static final String O_CHRONICLE = "capital.CapitalChronicleService";
    private static final String O_DATA = "data.CapitalDataAccess";
    private static final String O_WARRANTS = "data.CapitalPlayerWarrantDataAccess";

    private static final CapitalsCapability REGISTRY = CapitalsCapability.REGISTRY;
    private static final CapitalsCapability AUTHORITY = CapitalsCapability.AUTHORITY;
    private static final CapitalsCapability TITLES = CapitalsCapability.TITLES;
    private static final CapitalsCapability COURT = CapitalsCapability.COURT;
    private static final CapitalsCapability CHRONICLE = CapitalsCapability.CHRONICLE;
    private static final CapitalsCapability PLAYER_WARRANTS = CapitalsCapability.PLAYER_WARRANTS;

    // REGISTRY. Arity 2 on purpose: the one-argument getCapitalByVillageId beside it ignores the dimension.
    public static final Member CAPITAL_BY_VILLAGE = statik(O_MANAGER, "getCapitalByVillageId", Object.class, 2, REGISTRY);
    public static final Member ALL_CAPITALS = statik(O_MANAGER, "getAllCapitalRecords", Object.class, 0, REGISTRY);
    public static final Member REC_CAPITAL_ID = virtual(O_RECORD, "getCapitalId", Object.class, 0, REGISTRY);
    public static final Member REC_VILLAGE_ID = virtual(O_RECORD, "getVillageId", Object.class, 0, REGISTRY);
    public static final Member REC_DIMENSION = virtual(O_RECORD, "getVillageDimensionId", Object.class, 0, REGISTRY);
    public static final Member REC_STATE = virtual(O_RECORD, "getState", Object.class, 0, REGISTRY);

    // AUTHORITY. A player throne is its own pair of fields beside the villager sovereign.
    public static final Member REC_IS_PLAYER_SOVEREIGN = virtual(O_RECORD, "isPlayerSovereign", boolean.class, 0, AUTHORITY);
    public static final Member REC_PLAYER_SOVEREIGN_ID = virtual(O_RECORD, "getPlayerSovereignId", Object.class, 0, AUTHORITY);
    public static final Member RESOLVE_AUTHORITY = statik(O_AUTHORITY, "resolve", Object.class, 3, AUTHORITY);
    public static final Member AUTHORITY_RANK = virtual(O_RESOLVED, "rank", Object.class, 0, AUTHORITY);

    // TITLES. Each answers COMMONER (or null) for "no title".
    public static final Member GRANTED_TITLE = statik(O_TITLES, "getGrantedTitle", Object.class, 3, TITLES);
    public static final Member MARRIAGE_TITLE = statik(O_TITLES, "getMarriageTitle", Object.class, 3, TITLES);
    public static final Member DOWAGER_TITLE = statik(O_TITLES, "getDowagerBaseTitle", Object.class, 3, TITLES);

    // COURT. Read to decide who may receive a decree; no office gains a power from it.
    public static final Member REC_HAND = virtual(O_RECORD, "getHand", Object.class, 0, COURT);
    public static final Member REC_COMMANDER = virtual(O_RECORD, "getCommander", Object.class, 0, COURT);
    public static final Member REC_MASTER_OF_LAWS = virtual(O_RECORD, "getMasterOfLaws", Object.class, 0, COURT);
    public static final Member REC_HERALD = virtual(O_RECORD, "getHerald", Object.class, 0, COURT);
    public static final Member REC_IS_ROYAL_GUARD = virtual(O_RECORD, "isRoyalGuard", boolean.class, 1, COURT);

    // CHRONICLE. Flat server-locale text, de-duplicated by Capitals on the exact string; the record is
    // then marked dirty the way Capitals' own record mutations are.
    public static final Member CHRONICLE_ENTRY = statik(O_CHRONICLE, "addEntry", void.class, 3, CHRONICLE);
    public static final Member CHRONICLE_ENTRY_QUIET = statik(O_CHRONICLE, "addEntryWithoutHerald", void.class, 2, CHRONICLE);
    public static final Member MARK_DIRTY = statik(O_DATA, "markDirty", void.class, 1, CHRONICLE);

    // PLAYER_WARRANTS. (level, playerId, capitalId), read only.
    public static final Member HAS_PLAYER_WARRANT = statik(O_WARRANTS, "hasWarrant", boolean.class, 3, PLAYER_WARRANTS);

    /** Every member, in declaration order. The single source of truth for what this mod touches. */
    public static final List<Member> MANIFEST = List.of(
            CAPITAL_BY_VILLAGE, ALL_CAPITALS, REC_CAPITAL_ID, REC_VILLAGE_ID, REC_DIMENSION, REC_STATE,
            REC_IS_PLAYER_SOVEREIGN, REC_PLAYER_SOVEREIGN_ID, RESOLVE_AUTHORITY, AUTHORITY_RANK,
            GRANTED_TITLE, MARRIAGE_TITLE, DOWAGER_TITLE,
            REC_HAND, REC_COMMANDER, REC_MASTER_OF_LAWS, REC_HERALD, REC_IS_ROYAL_GUARD,
            CHRONICLE_ENTRY, CHRONICLE_ENTRY_QUIET, MARK_DIRTY,
            HAS_PLAYER_WARRANT);

    /** The capabilities this manifest covers; status is measured against these. */
    public static final Set<CapitalsCapability> DECLARED_CAPABILITIES = declared();

    private static Set<CapitalsCapability> declared() {
        EnumSet<CapitalsCapability> declared = EnumSet.noneOf(CapitalsCapability.class);
        for (Member member : MANIFEST) {
            declared.add(member.capability);
        }
        return Collections.unmodifiableSet(declared);
    }

    /** The outcome of resolving {@link #MANIFEST} against one classloader. Immutable. */
    public static final class Resolution {

        private final CapitalsBridge.State state;
        private final Set<CapitalsCapability> capabilities;
        private final Map<Member, MethodHandle> resolved;
        private final List<String> unresolved;

        private Resolution(CapitalsBridge.State state, Set<CapitalsCapability> capabilities,
                           Map<Member, MethodHandle> resolved, List<String> unresolved) {
            this.state = state;
            this.capabilities = capabilities;
            this.resolved = resolved;
            this.unresolved = List.copyOf(unresolved);
        }

        public CapitalsBridge.State state() {
            return state;
        }

        public Set<CapitalsCapability> capabilities() {
            return capabilities;
        }

        public List<String> unresolved() {
            return unresolved;
        }

        /** The handle for a member; never null -- an unresolved one is a stub of the same erased type. */
        public MethodHandle handle(Member member) {
            MethodHandle handle = resolved.get(member);
            return handle != null ? handle : MethodHandles.empty(member.erasedType());
        }

        public boolean has(Member member) {
            return resolved.containsKey(member);
        }

        public boolean has(CapitalsCapability capability) {
            return capabilities.contains(capability);
        }
    }

    /** Nothing bound: Capitals absent, or resolution itself failing. Every handle is still a stub. */
    public static Resolution absent() {
        return new Resolution(CapitalsBridge.State.ABSENT, Set.of(), Map.of(), List.of());
    }

    /** Resolves the whole manifest against {@code loader}. Never throws. */
    public static Resolution resolveAgainst(ClassLoader loader) {
        if (loadOrNull(loader, PACKAGE + PROBE_CLASS) == null) {
            return absent();
        }
        Map<Member, MethodHandle> resolved = new IdentityHashMap<>();
        List<String> unresolved = new ArrayList<>();
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Map<String, Method[]> cache = new HashMap<>();
        for (Member member : MANIFEST) {
            MethodHandle handle = null;
            try {
                handle = bindMethod(lookup, methodsOf(loader, cache, member.ownerRelative), member);
            } catch (Throwable ignored) {
                // Recorded as an ordinary miss; see the class javadoc for why this must not escape.
            }
            if (handle == null) {
                unresolved.add(member.toString());
            } else {
                resolved.put(member, handle);
            }
        }
        EnumSet<CapitalsCapability> bound = EnumSet.copyOf(DECLARED_CAPABILITIES);
        for (Member member : MANIFEST) {
            if (!resolved.containsKey(member)) {
                bound.remove(member.capability);
            }
        }
        if (!bound.contains(REGISTRY)) {
            bound.clear(); // nothing else can reach a record without the registry
        }
        CapitalsBridge.State state = bound.isEmpty() ? CapitalsBridge.State.DISABLED
                : bound.size() == DECLARED_CAPABILITIES.size() ? CapitalsBridge.State.FULL
                : CapitalsBridge.State.PARTIAL;
        return new Resolution(state, Collections.unmodifiableSet(bound), resolved, unresolved);
    }

    private static Method[] methodsOf(ClassLoader loader, Map<String, Method[]> cache, String ownerRelative) {
        return cache.computeIfAbsent(ownerRelative, relative -> {
            Class<?> owner = loadOrNull(loader, PACKAGE + relative);
            if (owner == null) {
                return new Method[0];
            }
            try {
                return owner.getMethods();
            } catch (Throwable t) {
                return new Method[0];
            }
        });
    }

    /** {@code initialize = false}: a probe must not run a Capitals static initialiser. */
    @Nullable
    private static Class<?> loadOrNull(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    /** By name, arity and staticness -- never by parameter type. Ambiguity is a miss, never a guess. */
    @Nullable
    private static MethodHandle bindMethod(MethodHandles.Lookup lookup, Method[] candidates, Member member) {
        Method match = null;
        for (Method candidate : candidates) {
            if (candidate.isBridge() || candidate.isSynthetic()) {
                continue; // a covariant bridge would otherwise read as a second overload
            }
            if (!candidate.getName().equals(member.name) || candidate.getParameterCount() != member.arity
                    || Modifier.isStatic(candidate.getModifiers()) != (member.kind == Kind.STATIC)) {
                continue;
            }
            if (match != null) {
                return null; // two candidates under one key: a later Capitals added an overload
            }
            match = candidate;
        }
        if (match == null) {
            return null;
        }
        try {
            match.setAccessible(true);
            return lookup.unreflect(match).asType(member.erasedType());
        } catch (Throwable t) {
            return null;
        }
    }

    private CapitalsBinding() {
    }
}
