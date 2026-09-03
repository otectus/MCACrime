package dev.otectus.mcacrime.compat.mca;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.server.level.ServerLevel;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves Minecraft Comes Alive: Reborn at <em>runtime</em>, by name, so one MCA: Crime jar works
 * across MCA's package-root migrations instead of hard-linking one of them.
 *
 * <h2>Why this exists</h2>
 *
 * <p>MCA repackaged mid-line. Its old Forge-era jars were Forgix-merged, so the loader-specific
 * classes sat under a {@code forge.} prefix, and the base package moved from {@code net.mca} to
 * {@code net.conczin.mca} partway through 7.7. Because {@code McaCompat} used to
 * {@code import forge.net.mca.*}, the very first MCA reference on a renamed build threw
 * {@code NoClassDefFoundError: forge/net/mca/entity/VillagerEntityMCA} — from an
 * {@code EntityInteract} handler, so a dedicated server died the instant any player right-clicked any
 * entity.
 *
 * <p>The NeoForge 1.21.1 artifact ({@code net.conczin.mca:mca-neoforge}) is un-merged and ships a
 * single root, {@code net.conczin.mca}, which is why that root is probed first. The layout still
 * <b>cannot be inferred from the version number</b> — MCA has changed both axes independently
 * before — so this stays a class probe, never a version comparison. Class-relative names are
 * identical across every layout seen, so the whole difference is the one prefix in
 * {@link #CANDIDATE_ROOTS}.
 *
 * <h2>The contract</h2>
 *
 * <p><b>Resolution never throws and never returns null.</b> An unresolved member becomes a
 * <em>constant stub</em>: a {@link MethodHandle} of the identical erased type that returns the type's
 * default ({@code null}/{@code 0}/{@code false}/nothing). That is what makes per-member degradation
 * free — callers need no null checks, and a member MCA removed simply reads as "absent" rather than
 * exploding. Whole-class failures degrade the same way, via {@link Resolution#cls} returning null and
 * every dependent member falling back to a stub.
 *
 * <p>Members are declared in {@link #MANIFEST} as {@link Member} constants, which are the only keys
 * {@link McaHandles} uses — so the manifest is the single source of truth for what this mod needs
 * from MCA, and {@code McaBindingProbeTest} can replay it against any MCA jar in a throwaway
 * {@link ClassLoader} without loading a single MCA class into the test JVM.
 *
 * @see McaHandles for the resolved handles themselves
 */
public final class McaBinding {

    /**
     * Package roots to probe, in order. Each ends with a dot and is stored <em>dotted</em>, never in
     * internal slash form — that is what lets {@code NoMcaStaticLinkTest} byte-scan compiled classes
     * for slash-form MCA references and treat any hit as a regression.
     *
     * <p>The root the 1.21.1 NeoForge artifact actually uses comes first, so the common case matches
     * on the first probe. The rest are kept because both axes have varied independently before and a
     * probe log that can name what it tried is worth more than three dead strings cost.
     */
    private static final String[] CANDIDATE_ROOTS = {
            "net.conczin.mca.",       // MCA 1.21.1 NeoForge: un-merged jar, current base package
            // Forgix-merged roots from the 1.20.1 Forge era; the NeoForge 1.21.1 artifact ships
            // un-merged at net.conczin.mca — kept so a probe log can name what was tried.
            "forge.net.conczin.mca.",
            "forge.net.mca.",
            "net.mca.",
    };

    /** The class whose presence identifies a root. Every layout has it at this relative name. */
    private static final String PROBE_CLASS = "entity.VillagerEntityMCA";

    public enum Status {
        /** No MCA on the classloader at all. */
        ABSENT,
        /** MCA is loaded but no candidate root matched — an unknown future layout. */
        UNBINDABLE,
        /** Root found, but at least one required member did not resolve. */
        PARTIAL,
        /** Everything required resolved. */
        BOUND
    }

    // ---------------------------------------------------------------------------------------------
    // Member descriptors
    // ---------------------------------------------------------------------------------------------

    private enum Kind { CLASS, VIRTUAL, STATIC, GETTER }

    /**
     * One thing this mod needs from MCA, named relative to the package root. Identity-compared, so
     * {@link McaHandles} refers to members by constant rather than by a string that could typo.
     */
    public static final class Member {
        private final Kind kind;
        private final String ownerRelative;
        private final String name;
        private final Class<?> returnType;
        private final int arity;
        private final Class<?> firstParamHint;
        private final boolean required;

        private Member(Kind kind, String ownerRelative, String name, Class<?> returnType, int arity,
                       Class<?> firstParamHint, boolean required) {
            this.kind = kind;
            this.ownerRelative = ownerRelative;
            this.name = name;
            this.returnType = returnType;
            this.arity = arity;
            this.firstParamHint = firstParamHint;
            this.required = required;
        }

        /** {@code true} when a miss should fail the build rather than merely degrade a feature. */
        public boolean required() {
            return required;
        }

        /**
         * A copy of this member under a different name, for {@code McaBindingProbeTest}. Renaming one
         * entry to something no MCA declares is exactly the shape a removed member has, which is how
         * the test proves a miss disables only its own bridge — without reflecting into private
         * state or hand-editing the real {@link #MANIFEST}.
         */
        public Member renamed(String replacement) {
            return new Member(kind, ownerRelative, replacement, returnType, arity, firstParamHint,
                    required);
        }

        @Override
        public String toString() {
            return switch (kind) {
                case CLASS -> ownerRelative;
                case GETTER -> ownerRelative + "." + name;
                default -> ownerRelative + "#" + name + "/" + arity;
            };
        }

        /**
         * The erased handle shape. Every parameter is {@link Object} (including the receiver for a
         * virtual) and {@code asType} does the boxing, so callers pass plain references; only the
         * return type is kept faithful, so a primitive stub can be a real {@code 0}/{@code false}.
         */
        private MethodType erasedType() {
            int params = switch (kind) {
                case VIRTUAL, GETTER -> arity + 1; // receiver first
                case STATIC -> arity;
                case CLASS -> 0;
            };
            return MethodType.methodType(returnType, Collections.nCopies(params, Object.class));
        }
    }

    private static Member cls(String ownerRelative) {
        return new Member(Kind.CLASS, ownerRelative, "<class>", void.class, 0, null, true);
    }

    private static Member virtual(String ownerRelative, String name, Class<?> ret, int arity) {
        return new Member(Kind.VIRTUAL, ownerRelative, name, ret, arity, null, true);
    }

    private static Member virtual(String ownerRelative, String name, Class<?> ret, int arity, Class<?> hint) {
        return new Member(Kind.VIRTUAL, ownerRelative, name, ret, arity, hint, true);
    }

    /** As {@link #virtual}, but a miss is recorded and tolerated instead of failing the probe test. */
    private static Member optionalVirtual(String ownerRelative, String name, Class<?> ret, int arity) {
        return new Member(Kind.VIRTUAL, ownerRelative, name, ret, arity, null, false);
    }

    /**
     * As {@link #optionalVirtual}, with a first-parameter hint to separate same-arity overloads.
     *
     * <p>The first genuine use of the hint machinery, which was written for exactly one case and then
     * declared nowhere: {@code Village#getResidents} has two one-argument overloads --
     * {@code getResidents(int)} returning names and {@code getResidents(ServerLevel)} returning live
     * entities -- and without the hint whichever one {@code getMethods()} happened to report first
     * would win a coin flip.
     */
    private static Member optionalVirtual(String ownerRelative, String name, Class<?> ret, int arity,
                                          Class<?> hint) {
        return new Member(Kind.VIRTUAL, ownerRelative, name, ret, arity, hint, false);
    }

    private static Member statik(String ownerRelative, String name, Class<?> ret, int arity) {
        return new Member(Kind.STATIC, ownerRelative, name, ret, arity, null, true);
    }

    /** As {@link #statik}, but a miss is recorded and tolerated instead of failing the probe test. */
    private static Member optionalStatik(String ownerRelative, String name, Class<?> ret, int arity) {
        return new Member(Kind.STATIC, ownerRelative, name, ret, arity, null, false);
    }

    private static Member getter(String ownerRelative, String field) {
        return new Member(Kind.GETTER, ownerRelative, field, Object.class, 0, null, true);
    }

    // ---------------------------------------------------------------------------------------------
    // The manifest — every MCA class and member MCA: Crime depends on.
    //
    // Verified present, unambiguous by name, and signature-identical with erased descriptors in both
    // probed 1.21.1 NeoForge builds, 7.7.33+1.21.1 and 7.7.36-beta.3+1.21.1 (both net.conczin.mca).
    // Nothing here is overloaded, so name plus arity separates every entry without a param hint.
    //
    // Deliberately absent: everything Crime reaches through vanilla rather than MCA — Mob#getTarget,
    // Mob#setTarget, Mob#setLeashedTo, PathfinderMob#getNavigation, LivingEntity#isSleeping and
    // Entity#getDisplayName. Those are Minecraft methods; they carry SRG names in a production jar and
    // so cannot be bound by their readable names, they are reached by a plain cast to the vanilla
    // supertype instead, and they are not what drifts when MCA repackages.
    // ---------------------------------------------------------------------------------------------

    private static final String C_VILLAGER = "entity.VillagerEntityMCA";
    private static final String C_VILLAGER_LIKE = "entity.VillagerLike";
    private static final String C_BRAIN = "entity.ai.brain.VillagerBrain";
    private static final String C_MEMORIES = "entity.ai.Memories";
    private static final String C_RESIDENCY = "entity.ai.Residency";
    private static final String C_RELATIONSHIP = "entity.ai.relationship.EntityRelationship";
    private static final String C_FAMILY_NODE = "server.world.data.FamilyTreeNode";
    private static final String C_VILLAGE = "server.world.data.Village";
    private static final String C_VILLAGE_MANAGER = "server.world.data.VillageManager";

    // Classes --------------------------------------------------------------------------------------
    public static final Member VILLAGER_CLASS = cls(C_VILLAGER);
    public static final Member VILLAGER_LIKE_CLASS = cls(C_VILLAGER_LIKE);

    // VillagerEntityMCA / VillagerLike --------------------------------------------------------------
    public static final Member GET_VILLAGER_BRAIN = virtual(C_VILLAGER, "getVillagerBrain", Object.class, 0);
    public static final Member GET_RESIDENCY = virtual(C_VILLAGER, "getResidency", Object.class, 0);
    public static final Member GET_PROFESSION_ID = virtual(C_VILLAGER_LIKE, "getProfessionId", Object.class, 0);
    public static final Member GET_AGE_STATE = virtual(C_VILLAGER_LIKE, "getAgeState", Object.class, 0);

    // Hearts — MCA's relationship currency, distinct from this mod's Karma ---------------------------
    // getMemoriesForPlayer takes net.minecraft.world.entity.player.Player, not ServerPlayer. The erased
    // Object parameter shape accepts either, so no hint is needed.
    public static final Member GET_MEMORIES_FOR_PLAYER = virtual(C_BRAIN, "getMemoriesForPlayer", Object.class, 1);
    public static final Member REWARD_HEARTS = virtual(C_BRAIN, "rewardHearts", void.class, 2);
    public static final Member GET_HEARTS = virtual(C_MEMORIES, "getHearts", int.class, 0);

    // Residency / Village — the per-village reputation key -------------------------------------------
    public static final Member GET_HOME_VILLAGE = virtual(C_RESIDENCY, "getHomeVillage", Object.class, 0);
    public static final Member VILLAGE_GET_ID = virtual(C_VILLAGE, "getId", int.class, 0);

    // Village names — what a jurisdiction is called, as opposed to how it is keyed -------------------
    // A CrimeCommunityKey is "minecraft:overworld/0", which is the right thing to write into NBT and
    // the wrong thing to show a player. MCA names its villages and lets a player rename them, so the
    // name is the only jurisdiction label that means anything at the table. VillageManager is reached
    // statically off a ServerLevel because a case in the ledger has a village id and no entity to ask.
    //
    // Optional, and deliberately so. All three are present and signature-identical in every MCA build
    // the probe checks, so they would bind as required — but what they carry is a label on a screen.
    // A required member that vanishes in a future MCA turns the resolution PARTIAL and takes the mod
    // with it; the worst an absent name can do here is put "an unnamed village" on a panel. Nothing
    // cosmetic belongs on the critical path.
    //
    // The return type is erased to Object rather than String so that an MCA that starts returning a
    // Component still binds; McaHandles type-checks what actually comes back.
    public static final Member VILLAGE_GET_NAME = optionalVirtual(C_VILLAGE, "getName", Object.class, 0);
    public static final Member VILLAGE_MANAGER_GET =
            optionalStatik(C_VILLAGE_MANAGER, "get", Object.class, 1);
    public static final Member VILLAGE_MANAGER_GET_OR_EMPTY =
            optionalVirtual(C_VILLAGE_MANAGER, "getOrEmpty", Object.class, 1);

    // Guard population — converting villagers to guards -------------------------------------------
    //
    // All optional, and that is load-bearing rather than cautious. A *required* member that fails to
    // resolve turns the whole resolution PARTIAL, and McaHandles then disables every MCA feature this
    // mod has. Guard population is an opt-in convenience; it must never be able to take crime
    // detection down with it if a future MCA renames one of these.
    //
    // getResidents is the one member in this manifest that genuinely needs a parameter hint: MCA
    // declares getResidents(int) -> List<String> and getResidents(ServerLevel) -> List<VillagerEntityMCA>,
    // both one-argument, and only the second is any use here.
    //
    // setProfession takes a *vanilla* VillagerProfession, so the value is fetched from
    // BuiltInRegistries and no MCA symbol is named on either side of the call.
    public static final Member VILLAGE_GET_RESIDENTS =
            optionalVirtual(C_VILLAGE, "getResidents", Object.class, 1, ServerLevel.class);
    public static final Member VILLAGE_GET_POPULATION =
            optionalVirtual(C_VILLAGE, "getPopulation", int.class, 0);
    public static final Member VILLAGE_IS_VILLAGE =
            optionalVirtual(C_VILLAGE, "isVillage", boolean.class, 0);
    public static final Member VILLAGER_IS_GUARD =
            optionalVirtual(C_VILLAGER, "isGuard", boolean.class, 0);
    public static final Member VILLAGER_IS_PROFESSION_IMPORTANT =
            optionalVirtual(C_VILLAGER, "isProfessionImportant", boolean.class, 0);
    public static final Member VILLAGER_SET_PROFESSION =
            optionalVirtual(C_VILLAGER, "setProfession", void.class, 1);

    // EntityRelationship / FamilyTreeNode — the ransom payer graph -----------------------------------
    // Every EntityRelationship member below is an abstract or default interface method; getMethods() on
    // an interface reports both, so they bind on the interface rather than on each implementation.
    public static final Member RELATIONSHIP_OF = statik(C_RELATIONSHIP, "of", Object.class, 1);
    public static final Member GET_PARTNER_UUID = virtual(C_RELATIONSHIP, "getPartnerUUID", Object.class, 0);
    public static final Member GET_FAMILY_ENTRY = virtual(C_RELATIONSHIP, "getFamilyEntry", Object.class, 0);
    public static final Member NODE_STREAM_PARENTS = virtual(C_FAMILY_NODE, "streamParents", Object.class, 0);
    public static final Member NODE_CHILDREN = virtual(C_FAMILY_NODE, "children", Object.class, 0);
    public static final Member NODE_SIBLINGS = virtual(C_FAMILY_NODE, "siblings", Object.class, 0);
    public static final Member NODE_ALL_RELATIVES = virtual(C_FAMILY_NODE, "getAllRelatives", Object.class, 1);

    /** Every member above, in declaration order. The single source of truth for what MCA must provide. */
    public static final List<Member> MANIFEST = List.of(
            VILLAGER_CLASS, VILLAGER_LIKE_CLASS,
            GET_VILLAGER_BRAIN, GET_RESIDENCY, GET_PROFESSION_ID, GET_AGE_STATE,
            GET_MEMORIES_FOR_PLAYER, REWARD_HEARTS, GET_HEARTS,
            GET_HOME_VILLAGE, VILLAGE_GET_ID,
            VILLAGE_GET_NAME, VILLAGE_MANAGER_GET, VILLAGE_MANAGER_GET_OR_EMPTY,
            VILLAGE_GET_RESIDENTS, VILLAGE_GET_POPULATION, VILLAGE_IS_VILLAGE,
            VILLAGER_IS_GUARD, VILLAGER_IS_PROFESSION_IMPORTANT, VILLAGER_SET_PROFESSION,
            RELATIONSHIP_OF, GET_PARTNER_UUID, GET_FAMILY_ENTRY,
            NODE_STREAM_PARENTS, NODE_CHILDREN, NODE_SIBLINGS, NODE_ALL_RELATIVES);

    // ---------------------------------------------------------------------------------------------
    // Resolution
    // ---------------------------------------------------------------------------------------------

    /**
     * The outcome of resolving {@link #MANIFEST} against one {@link ClassLoader}. Immutable once
     * built; {@link McaHandles} keeps one for the game's lifetime and the probe test builds a
     * throwaway one per MCA jar.
     */
    public static final class Resolution {

        private final Status status;
        private final String root;
        private final Map<Member, Object> resolved;
        private final List<String> unresolvedRequired;
        private final List<String> unresolvedOptional;

        private Resolution(Status status, String root, Map<Member, Object> resolved,
                           List<String> unresolvedRequired, List<String> unresolvedOptional) {
            this.status = status;
            this.root = root;
            this.resolved = resolved;
            this.unresolvedRequired = List.copyOf(unresolvedRequired);
            this.unresolvedOptional = List.copyOf(unresolvedOptional);
        }

        public Status status() {
            return status;
        }

        /** The matched package root (dotted, trailing dot), or {@code null} when nothing matched. */
        public String root() {
            return root;
        }

        public List<String> unresolvedRequired() {
            return unresolvedRequired;
        }

        public List<String> unresolvedOptional() {
            return unresolvedOptional;
        }

        /** The resolved class for a {@code CLASS} member, or {@code null} when it did not resolve. */
        public Class<?> cls(Member member) {
            Object value = resolved.get(member);
            return value instanceof Class<?> c ? c : null;
        }

        /**
         * The handle for a method/field member. <b>Never null</b> — an unresolved member yields a
         * constant stub of the same erased type returning that type's default, so call sites need no
         * guard of their own.
         */
        public MethodHandle handle(Member member) {
            Object value = resolved.get(member);
            return value instanceof MethodHandle h ? h : MethodHandles.empty(member.erasedType());
        }

        /**
         * True when this member actually bound. Only worth asking for an {@code optional} member whose
         * absence selects a different code path — everything else can just call through the stub.
         */
        public boolean has(Member member) {
            return resolved.get(member) instanceof MethodHandle;
        }

        /**
         * An enum constant on a resolved MCA enum class, or {@code null}. Reads (age state, mood,
         * personality, relationship state) go through {@code Enum#name} instead and need no binding;
         * this is only for the handful of places that must pass a real MCA enum <em>value</em> back in.
         */
        public Object enumConstant(Member enumClass, String constant) {
            Class<?> type = cls(enumClass);
            if (type == null || !type.isEnum()) {
                return null;
            }
            for (Object candidate : type.getEnumConstants()) {
                if (candidate instanceof Enum<?> e && e.name().equals(constant)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    /**
     * A resolution in which nothing is bound. Used as the last-ditch value when even
     * {@link #resolveAgainst} fails, so {@link McaHandles} always has a non-null {@code Resolution}
     * and every handle it hands out is a working stub.
     */
    public static Resolution absent() {
        return new Resolution(Status.ABSENT, null, Map.of(), List.of(), List.of());
    }

    /**
     * Resolves the whole manifest against {@code loader}. Never throws: any failure is recorded and
     * turned into a stub, because this runs from a {@code <clinit>} whose escape would reintroduce
     * exactly the {@code NoClassDefFoundError} cascade this class exists to remove.
     */
    public static Resolution resolveAgainst(ClassLoader loader) {
        return resolveAgainst(loader, MANIFEST);
    }

    /**
     * As {@link #resolveAgainst(ClassLoader)}, but over an arbitrary member list. Production always
     * passes {@link #MANIFEST}; the overload exists so {@code McaBindingProbeTest} can resolve a
     * manifest with one member deliberately renamed away and check that only that member's bridge
     * goes dark.
     */
    public static Resolution resolveAgainst(ClassLoader loader, List<Member> manifest) {
        Map<Member, Object> resolved = new IdentityHashMap<>();
        List<String> missingRequired = new ArrayList<>();
        List<String> missingOptional = new ArrayList<>();

        String root = probeRoot(loader);
        if (root == null) {
            return new Resolution(mcaOnClasspath(loader) ? Status.UNBINDABLE : Status.ABSENT,
                    null, resolved, missingRequired, missingOptional);
        }

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Map<String, Class<?>> classes = new java.util.HashMap<>();
        for (Member member : manifest) {
            try {
                Class<?> owner = classes.computeIfAbsent(member.ownerRelative,
                        relative -> loadOrNull(loader, root + relative));
                if (owner == null) {
                    record(member, missingRequired, missingOptional);
                    continue;
                }
                Object value = switch (member.kind) {
                    case CLASS -> owner;
                    case GETTER -> bindGetter(lookup, owner, member);
                    default -> bindMethod(lookup, owner, member);
                };
                if (value == null) {
                    record(member, missingRequired, missingOptional);
                } else {
                    resolved.put(member, value);
                }
            } catch (Throwable t) {
                record(member, missingRequired, missingOptional);
            }
        }

        Status status = missingRequired.isEmpty() ? Status.BOUND : Status.PARTIAL;
        return new Resolution(status, root, resolved, missingRequired, missingOptional);
    }

    private static void record(Member member, List<String> required, List<String> optional) {
        (member.required ? required : optional).add(member.toString());
    }

    /** The first candidate root whose probe class loads, or {@code null}. */
    private static String probeRoot(ClassLoader loader) {
        for (String root : CANDIDATE_ROOTS) {
            if (loadOrNull(loader, root + PROBE_CLASS) != null) {
                return root;
            }
        }
        return null;
    }

    /**
     * MCA-specific resources at a jar root, used only to tell "MCA absent" (fine, and the expected
     * state in unit tests) apart from "MCA present in a layout we do not know" (worth an ERROR).
     * Several, because MCA has renamed these too: {@code mca.png} in 7.6, {@code mca.classtweaker} from
     * 7.7. Only the diagnostic differs — behaviour is identical either way.
     */
    private static final String[] MCA_MARKER_RESOURCES = {
            "mca.png", "mca.classtweaker", "mca.mixins.json", "forge-mca.mixin.json", "fabric-mca.mixin.json"};

    /** True when MCA looks installed even though no candidate root matched. */
    private static boolean mcaOnClasspath(ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        for (String marker : MCA_MARKER_RESOURCES) {
            if (loader.getResource(marker) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code initialize = false} is deliberate: {@code VillagerEntityMCA}'s static initialiser builds
     * MCA's tracked-data parameter set, and a mere probe must not force that.
     */
    private static Class<?> loadOrNull(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Finds a method by name, arity, and staticness — never by exact parameter types, which would
     * mean naming MCA types. Every member in the manifest is unique under that key in both known MCA
     * layouts, except {@code Village#getResidents}, whose two one-argument overloads are separated by
     * {@link Member#firstParamHint}.
     */
    private static MethodHandle bindMethod(MethodHandles.Lookup lookup, Class<?> owner, Member member) {
        Method match = null;
        for (Method candidate : owner.getMethods()) {
            // Bridges are skipped, not merely deprioritised. A covariant override leaves two arity-0
            // entries with the same name -- VillagerEntityMCA#getInteractions is the live example,
            // declaring both the real VillagerCommandHandler return and an EntityCommandHandler
            // bridge -- and getMethods() has no defined order, so binding whichever came first would
            // be a coin flip that a passing probe test could not distinguish.
            if (candidate.isBridge()
                    || !candidate.getName().equals(member.name)
                    || candidate.getParameterCount() != member.arity
                    || Modifier.isStatic(candidate.getModifiers()) != (member.kind == Kind.STATIC)) {
                continue;
            }
            if (member.firstParamHint != null
                    && (member.arity == 0 || !candidate.getParameterTypes()[0].equals(member.firstParamHint))) {
                continue;
            }
            match = candidate;
            break;
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

    private static MethodHandle bindGetter(MethodHandles.Lookup lookup, Class<?> owner, Member member) {
        try {
            Field field = owner.getField(member.name);
            field.setAccessible(true);
            return lookup.unreflectGetter(field).asType(member.erasedType());
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Production surface
    // ---------------------------------------------------------------------------------------------

    private static boolean logged;

    private McaBinding() {
    }

    /**
     * Logs the binding outcome exactly once, from common setup — after the loader has constructed
     * every mod, so the classloader is authoritative. Deliberately one line per state rather than a warning
     * per failed call: a partially-bound MCA would otherwise flood the log during an eligibility pass.
     */
    public static synchronized void init() {
        if (logged) {
            return;
        }
        logged = true;
        Resolution resolution = McaHandles.resolution();
        switch (resolution.status()) {
            case BOUND -> McaCrime.LOGGER.info(
                    "[MCA: Crime] Bound to Minecraft Comes Alive at '{}' ({} members).",
                    resolution.root(), MANIFEST.size());
            case PARTIAL -> McaCrime.LOGGER.warn(
                    "[MCA: Crime] Bound to Minecraft Comes Alive at '{}', but {} required member(s) did not "
                            + "resolve: {}. The features that need them are disabled; everything else works. "
                            + "Please report this with your MCA version.",
                    resolution.root(), resolution.unresolvedRequired().size(), resolution.unresolvedRequired());
            case UNBINDABLE -> McaCrime.LOGGER.error(
                    "[MCA: Crime] Minecraft Comes Alive is installed but none of the known package roots {} "
                            + "matched, so MCA-backed features are disabled: no crime is detected, no ransom "
                            + "is offered and no capture is possible. Your server will NOT crash. Please "
                            + "report this with your MCA version.", String.join(", ", CANDIDATE_ROOTS));
            case ABSENT -> McaCrime.LOGGER.info(
                    "[MCA: Crime] Minecraft Comes Alive was not found on the classpath; MCA-backed features "
                            + "are inactive.");
        }
        if (!resolution.unresolvedOptional().isEmpty()) {
            McaCrime.LOGGER.info("[MCA: Crime] Optional MCA members absent in this version (expected on "
                    + "newer builds; a fallback is used): {}", resolution.unresolvedOptional());
        }
    }

    /** A one-line human-readable summary, for {@code /crime debug mca}. */
    public static String describe() {
        Resolution resolution = McaHandles.resolution();
        return "status=" + resolution.status()
                + " root=" + (resolution.root() == null ? "<none>" : resolution.root())
                + " members=" + MANIFEST.size()
                + " missingRequired=" + resolution.unresolvedRequired()
                + " missingOptional=" + resolution.unresolvedOptional();
    }
}
