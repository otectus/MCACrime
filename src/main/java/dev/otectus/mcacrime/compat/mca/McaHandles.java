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
    private static final MethodHandle H_RESIDENCY = R.handle(McaBinding.GET_RESIDENCY);
    private static final MethodHandle H_PROFESSION_ID = R.handle(McaBinding.GET_PROFESSION_ID);
    private static final MethodHandle H_AGE_STATE = R.handle(McaBinding.GET_AGE_STATE);
    private static final MethodHandle H_MEMORIES_FOR = R.handle(McaBinding.GET_MEMORIES_FOR_PLAYER);
    private static final MethodHandle H_REWARD_HEARTS = R.handle(McaBinding.REWARD_HEARTS);
    private static final MethodHandle H_HEARTS = R.handle(McaBinding.GET_HEARTS);
    private static final MethodHandle H_HOME_VILLAGE = R.handle(McaBinding.GET_HOME_VILLAGE);
    private static final MethodHandle H_VILLAGE_ID = R.handle(McaBinding.VILLAGE_GET_ID);
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
