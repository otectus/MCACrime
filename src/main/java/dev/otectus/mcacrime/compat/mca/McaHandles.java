package dev.otectus.mcacrime.compat.mca;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The typed facade over {@link McaBinding}: MCA's API expressed entirely in vanilla and JDK types.
 *
 * <p>Every field here is {@code static final} and assigned once in {@code <clinit>}, which is what
 * keeps this fast — HotSpot constant-folds a {@code static final} {@link Class} or
 * {@link MethodHandle}, so {@link #isVillager} folds to the same check {@code instanceof} would emit
 * and a bound handle inlines through to MCA's method. That matters more here than in the sibling mods:
 * {@code CrimeGate#resolveOffender} calls {@link #isVillager} on every {@code LivingHurtEvent} in the
 * world, so this is the hottest MCA read in the suite.
 *
 * <p><b>Nothing in this class can throw.</b> Unbound members are constant stubs (see
 * {@link McaBinding.Resolution#handle}), and every accessor below additionally swallows
 * {@link Throwable} and returns its documented empty value. That is deliberate belt-and-braces, and it
 * is the whole point of the layer: the bare {@code instanceof VillagerEntityMCA} this replaced could
 * not be caught at all, because the failure happened while the JVM resolved the type rather than
 * inside the method, and it crash-looped dedicated servers on MCA 7.7.1.
 *
 * <p>Keep {@link McaBinding}'s core (root probe, {@code Member}, {@code Resolution}, stub synthesis)
 * identical to the copies in MCA: Quests, MCA: Reputation and MCA: Conversations. Only the manifest
 * and this facade differ per mod.
 */
public final class McaHandles {

    private static final McaBinding.Resolution R = resolveQuietly();

    private McaHandles() {
    }

    private static McaBinding.Resolution resolveQuietly() {
        try {
            return McaBinding.resolveAgainst(McaHandles.class.getClassLoader());
        } catch (Throwable t) {
            return McaBinding.absent();
        }
    }

    /** The live resolution, for logging and {@code /crime debug mca}. */
    public static McaBinding.Resolution resolution() {
        return R;
    }

    /** True when MCA bound well enough to be useful. */
    public static boolean available() {
        return VILLAGER != null;
    }

    // --- classes -------------------------------------------------------------------------------
    private static final Class<?> VILLAGER = R.cls(McaBinding.VILLAGER_CLASS);
    private static final Class<?> VILLAGER_LIKE = R.cls(McaBinding.VILLAGER_LIKE_CLASS);

    // --- handles -------------------------------------------------------------------------------
    private static final MethodHandle H_BRAIN = R.handle(McaBinding.GET_VILLAGER_BRAIN);
    private static final MethodHandle H_PERSONALITY = R.handle(McaBinding.GET_PERSONALITY);

    public static String personalityName(Object entity) {
        return isVillager(entity) ? enumName(ref(H_PERSONALITY, ref(H_BRAIN, entity))) : null;
    }
    private static final MethodHandle H_RESIDENCY = R.handle(McaBinding.GET_RESIDENCY);
    private static final MethodHandle H_PROFESSION_ID = R.handle(McaBinding.GET_PROFESSION_ID);
    private static final MethodHandle H_AGE_STATE = R.handle(McaBinding.GET_AGE_STATE);
    private static final MethodHandle H_MEMORIES_FOR = R.handle(McaBinding.GET_MEMORIES_FOR_PLAYER);
    private static final MethodHandle H_REWARD_HEARTS = R.handle(McaBinding.REWARD_HEARTS);
    private static final MethodHandle H_HEARTS = R.handle(McaBinding.GET_HEARTS);
    private static final MethodHandle H_HOME_VILLAGE = R.handle(McaBinding.GET_HOME_VILLAGE);
    private static final MethodHandle H_VILLAGE_ID = R.handle(McaBinding.VILLAGE_GET_ID);
    private static final MethodHandle H_VILLAGE_NAME = R.handle(McaBinding.VILLAGE_GET_NAME);
    private static final MethodHandle H_VILLAGE_MANAGER = R.handle(McaBinding.VILLAGE_MANAGER_GET);
    private static final MethodHandle H_VILLAGE_BY_ID = R.handle(McaBinding.VILLAGE_MANAGER_GET_OR_EMPTY);
    private static final MethodHandle H_VILLAGE_RESIDENTS = R.handle(McaBinding.VILLAGE_GET_RESIDENTS);
    private static final MethodHandle H_VILLAGE_POPULATION = R.handle(McaBinding.VILLAGE_GET_POPULATION);
    private static final MethodHandle H_VILLAGE_IS_VILLAGE = R.handle(McaBinding.VILLAGE_IS_VILLAGE);
    private static final MethodHandle H_IS_GUARD = R.handle(McaBinding.VILLAGER_IS_GUARD);
    private static final MethodHandle H_PROFESSION_IMPORTANT = R.handle(McaBinding.VILLAGER_IS_PROFESSION_IMPORTANT);
    private static final MethodHandle H_SET_PROFESSION = R.handle(McaBinding.VILLAGER_SET_PROFESSION);
    private static final boolean POPULATION_BOUND =
            R.has(McaBinding.VILLAGE_GET_RESIDENTS)
                    && R.has(McaBinding.VILLAGE_GET_POPULATION)
                    && R.has(McaBinding.VILLAGER_SET_PROFESSION);
    private static final MethodHandle H_RELATIONSHIP_OF = R.handle(McaBinding.RELATIONSHIP_OF);
    private static final MethodHandle H_PARTNER_UUID = R.handle(McaBinding.GET_PARTNER_UUID);
    private static final MethodHandle H_FAMILY_ENTRY = R.handle(McaBinding.GET_FAMILY_ENTRY);
    private static final MethodHandle H_STREAM_PARENTS = R.handle(McaBinding.NODE_STREAM_PARENTS);
    private static final MethodHandle H_CHILDREN = R.handle(McaBinding.NODE_CHILDREN);
    private static final MethodHandle H_SIBLINGS = R.handle(McaBinding.NODE_SIBLINGS);
    private static final MethodHandle H_ALL_RELATIVES = R.handle(McaBinding.NODE_ALL_RELATIVES);

    // --- type tests ----------------------------------------------------------------------------

    /**
     * True for an MCA human villager. This is the replacement for {@code instanceof VillagerEntityMCA}
     * and, unlike it, cannot throw when MCA is absent or has moved: an unbound class is simply null.
     */
    public static boolean isVillager(Object entity) {
        Class<?> type = VILLAGER;
        return type != null && type.isInstance(entity);
    }

    /** True for anything implementing MCA's {@code VillagerLike} (villagers and the zombie variant). */
    public static boolean isVillagerLike(Object entity) {
        Class<?> type = VILLAGER_LIKE;
        return type != null && type.isInstance(entity);
    }

    // --- villager reads ------------------------------------------------------------------------

    /** The villager's profession id, or null. */
    public static ResourceLocation professionId(Object entity) {
        if (!isVillagerLike(entity)) {
            return null;
        }
        return ref(H_PROFESSION_ID, entity) instanceof ResourceLocation id ? id : null;
    }

    /**
     * Lowercased {@code name()} of the villager's MCA age state (e.g. {@code adult}), or null. MCA
     * enums never leave this class as MCA types — comparing the name is what lets one binary span a
     * version where the enum was reshaped.
     */
    public static String ageStateName(Object entity) {
        return isVillagerLike(entity) ? enumName(ref(H_AGE_STATE, entity)) : null;
    }

    /** The player's relationship hearts with this villager. Safe default: 0. */
    public static int hearts(Object villager, Player player) {
        if (!isVillager(villager) || player == null) {
            return 0;
        }
        Object memories = ref(H_MEMORIES_FOR, ref(H_BRAIN, villager), player);
        if (memories == null) {
            return 0;
        }
        try {
            return (int) H_HEARTS.invoke(memories);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Adds relationship hearts through MCA's own reward path. No-op on any failure. */
    public static void rewardHearts(Object villager, ServerPlayer player, int amount) {
        Object brain = isVillager(villager) ? ref(H_BRAIN, villager) : null;
        if (brain == null || player == null) {
            return;
        }
        try {
            H_REWARD_HEARTS.invoke(brain, player, amount);
        } catch (Throwable ignored) {
            // Hearts are progression, never correctness; a failed payout must not kill the tick.
        }
    }

    /** The id of the villager's MCA home village, or empty when it has none. */
    public static OptionalInt homeVillageId(Object villager) {
        Object residency = isVillager(villager) ? ref(H_RESIDENCY, villager) : null;
        Object village = unwrap(ref(H_HOME_VILLAGE, residency));
        if (village == null) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of((int) H_VILLAGE_ID.invoke(village));
        } catch (Throwable t) {
            // Distinguished from a real id of 0: an unreadable village is "no village", not village 0.
            return OptionalInt.empty();
        }
    }

    /**
     * The display name of a village in {@code level}, by MCA's own village id, or empty.
     *
     * <p>Empty covers every way this can fail to mean anything: MCA absent, the id belonging to a
     * village that has since been dissolved, or a village whose name is blank. A caller that gets
     * empty must fall back to something a player can read — never to the {@code dimension/id} key,
     * which is a storage detail.
     *
     * <p>Looked up per call rather than cached. Villages are renameable in MCA's own UI, and a name
     * cached at world load would go stale the first time somebody used it; this runs when a screen
     * opens, not in any tick loop.
     */
    public static Optional<String> villageName(Object serverLevel, int villageId) {
        if (serverLevel == null || villageId < 0) {
            return Optional.empty();
        }
        Object manager;
        try {
            manager = H_VILLAGE_MANAGER.invoke(serverLevel);
        } catch (Throwable t) {
            return Optional.empty();
        }
        Object village = unwrap(ref(H_VILLAGE_BY_ID, manager, villageId));
        if (village == null) {
            return Optional.empty();
        }
        Object name = ref(H_VILLAGE_NAME, village);
        return name instanceof String text && !text.isBlank() ? Optional.of(text) : Optional.empty();
    }

    // --- village population (guard top-up) ------------------------------------------------------

    /**
     * True when everything the guard-population pass needs actually bound.
     *
     * <p>Checked as a set rather than per call: converting villagers with only half the picture --
     * residents but no population, say -- would produce a target computed from nothing and a village
     * full of guards. Absent means the feature silently does not run, which is the correct behaviour
     * for an opt-in convenience on a soft dependency.
     */
    public static boolean populationAvailable() {
        return available() && POPULATION_BOUND;
    }

    /**
     * Every village in {@code serverLevel}.
     *
     * <p>No binding is needed to enumerate them: MCA's {@code VillageManager} implements
     * {@code java.lang.Iterable<Village>}, so a plain JDK type check on the already-bound manager is
     * enough and there is no extra member to drift.
     */
    public static List<Object> villagesIn(Object serverLevel) {
        if (serverLevel == null || !R.has(McaBinding.VILLAGE_MANAGER_GET)) {
            return List.of();
        }
        Object manager;
        try {
            manager = H_VILLAGE_MANAGER.invoke(serverLevel);
        } catch (Throwable t) {
            return List.of();
        }
        if (!(manager instanceof Iterable<?> villages)) {
            return List.of();
        }
        List<Object> out = new ArrayList<>();
        try {
            for (Object village : villages) {
                if (village != null) {
                    out.add(village);
                }
            }
        } catch (Throwable t) {
            return List.copyOf(out);
        }
        return out;
    }

    /**
     * Whether MCA considers this a real village rather than a couple of huts.
     *
     * <p>Defaults to <b>true</b> when unbound. An unreadable check should not stop the feature working;
     * the population figure below is what actually bounds it.
     */
    public static boolean isRealVillage(Object village) {
        if (village == null) {
            return false;
        }
        if (!R.has(McaBinding.VILLAGE_IS_VILLAGE)) {
            return true;
        }
        try {
            return (boolean) H_VILLAGE_IS_VILLAGE.invoke(village);
        } catch (Throwable t) {
            return true;
        }
    }

    /** The village population MCA itself uses for its guard target. Zero when unreadable. */
    public static int villagePopulation(Object village) {
        if (village == null || !R.has(McaBinding.VILLAGE_GET_POPULATION)) {
            return 0;
        }
        try {
            return Math.max(0, (int) H_VILLAGE_POPULATION.invoke(village));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** MCA's own id for a village object, or -1 when unreadable. Used only for logging and keys. */
    public static int villageIdOf(Object village) {
        if (village == null) {
            return -1;
        }
        try {
            return (int) H_VILLAGE_ID.invoke(village);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** The loaded residents of a village, or empty when unreadable. */
    public static List<Object> villageResidents(Object village, Object serverLevel) {
        if (village == null || serverLevel == null || !R.has(McaBinding.VILLAGE_GET_RESIDENTS)) {
            return List.of();
        }
        Object result;
        try {
            result = H_VILLAGE_RESIDENTS.invoke(village, serverLevel);
        } catch (Throwable t) {
            return List.of();
        }
        if (!(result instanceof List<?> list)) {
            return List.of();
        }
        List<Object> out = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * MCA's own guard test, which covers its archers as well as its guards.
     *
     * <p>Broader than {@code McaCompat.isGuard}, which matches the {@code guard} profession path only.
     * The population count deliberately uses the broader one: an archer MCA spawned is a guard as far
     * as MCA's own target is concerned, and counting it as an ordinary villager would have this mod
     * convert past the target it shares with MCA.
     */
    public static boolean isMcaGuard(Object villager) {
        if (!isVillager(villager) || !R.has(McaBinding.VILLAGER_IS_GUARD)) {
            return false;
        }
        try {
            return (boolean) H_IS_GUARD.invoke(villager);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether MCA considers this villager's profession too important to overwrite.
     *
     * <p>Defaults to <b>true</b> when unbound, the opposite way round from {@link #isRealVillage} and
     * deliberately so: an unreadable permissive check costs nothing, while an unreadable protective one
     * would let this mod convert a village's only cleric into a guard.
     */
    public static boolean isProfessionImportant(Object villager) {
        if (!isVillager(villager)) {
            return true;
        }
        if (!R.has(McaBinding.VILLAGER_IS_PROFESSION_IMPORTANT)) {
            return true;
        }
        try {
            return (boolean) H_PROFESSION_IMPORTANT.invoke(villager);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Sets a villager's profession through MCA's own setter. False on any failure. */
    public static boolean setProfession(Object villager, Object profession) {
        if (!isVillager(villager) || profession == null || !R.has(McaBinding.VILLAGER_SET_PROFESSION)) {
            return false;
        }
        try {
            H_SET_PROFESSION.invoke(villager, profession);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // --- relationship graph --------------------------------------------------------------------

    /** True once MCA's relationship API bound; {@code RansomService} falls back when it did not. */
    public static boolean relationshipApiAvailable() {
        return R.has(McaBinding.RELATIONSHIP_OF) && R.has(McaBinding.GET_FAMILY_ENTRY);
    }

    /** The entity's MCA spouse UUID, or empty. Works for players as well as villagers. */
    public static Optional<UUID> partnerUuid(Object entity) {
        Object relationship = relationshipOf(entity);
        return unwrap(ref(H_PARTNER_UUID, relationship)) instanceof UUID id ? Optional.of(id) : Optional.empty();
    }

    public static List<UUID> parentUuids(Object entity) {
        return uuids(ref(H_STREAM_PARENTS, familyEntry(entity)));
    }

    public static List<UUID> childUuids(Object entity) {
        return uuids(ref(H_CHILDREN, familyEntry(entity)));
    }

    public static List<UUID> siblingUuids(Object entity) {
        return uuids(ref(H_SIBLINGS, familyEntry(entity)));
    }

    /** UUIDs of relatives up to {@code generations} away (grandparents, grandchildren, and so on). */
    public static List<UUID> closeRelativeUuids(Object entity, int generations) {
        return uuids(ref(H_ALL_RELATIVES, familyEntry(entity), generations));
    }

    private static Object relationshipOf(Object entity) {
        if (entity == null) {
            return null;
        }
        try {
            return unwrap(H_RELATIONSHIP_OF.invoke(entity));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object familyEntry(Object entity) {
        return ref(H_FAMILY_ENTRY, relationshipOf(entity));
    }

    // --- shared containment --------------------------------------------------------------------

    private static Object ref(MethodHandle handle, Object receiver) {
        // The receiver null-check is not redundant with the stub contract: a *bound* handle would
        // throw NullPointerException on the receiver cast that asType inserted.
        if (receiver == null) {
            return null;
        }
        try {
            return handle.invoke(receiver);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object ref(MethodHandle handle, Object receiver, Object a) {
        if (receiver == null) {
            return null;
        }
        try {
            return handle.invoke(receiver, a);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object unwrap(Object maybeOptional) {
        return maybeOptional instanceof Optional<?> opt ? opt.orElse(null) : null;
    }

    /** Lowercased {@code name()} of an MCA enum value, or null — how every enum read leaves this class. */
    private static String enumName(Object value) {
        return value instanceof Enum<?> e ? e.name().toLowerCase(Locale.ROOT) : null;
    }

    /**
     * MCA's family-graph accessors are not uniform — {@code streamParents} and {@code getAllRelatives}
     * return a {@code Stream<UUID>} while {@code children} and {@code siblings} return a
     * {@code Set<UUID>} — so both shapes are accepted and anything else reads as empty.
     */
    private static List<UUID> uuids(Object value) {
        List<UUID> out = new ArrayList<>();
        try {
            if (value instanceof Stream<?> stream) {
                stream.forEach(e -> {
                    if (e instanceof UUID id) {
                        out.add(id);
                    }
                });
            } else if (value instanceof Collection<?> collection) {
                for (Object e : collection) {
                    if (e instanceof UUID id) {
                        out.add(id);
                    }
                }
            }
        } catch (Throwable t) {
            return List.of();
        }
        return out;
    }
}
