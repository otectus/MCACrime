package dev.otectus.mcacrime.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of who is currently restrained, and which entity is holding them.
 *
 * <p>The twin of {@link ClientBandData}: display-only, fed entirely by the server, and never
 * classloaded on a dedicated server. Nothing here decides anything -- the cuffs layer, the pose, and
 * the rope all read it, and none of them can make a player restrained by writing to it.
 *
 * <p>Values are entity ids, which are per level. A guard that does not resolve in the viewer's own
 * level draws no rope, which is also the right answer when the prisoner is in another dimension.
 */
public final class ClientRestraintData {

    private static final Map<UUID, Integer> RESTRAINED = new ConcurrentHashMap<>();

    private ClientRestraintData() {
    }

    public static boolean restrained(UUID player) {
        return player != null && RESTRAINED.containsKey(player);
    }

    /** The entity id of the guard holding this player, or -1 when there is none. */
    public static int guardEntityId(UUID player) {
        Integer id = player == null ? null : RESTRAINED.get(player);
        return id == null ? -1 : id;
    }

    /** Every restrained player right now, for the rope renderer to walk. */
    public static Map<UUID, Integer> all() {
        return RESTRAINED;
    }

    public static void put(UUID player, boolean restrained, int guardEntityId) {
        if (player == null) {
            return;
        }
        if (restrained) {
            RESTRAINED.put(player, guardEntityId);
        } else {
            RESTRAINED.remove(player);
        }
    }

    public static void putAll(Map<UUID, Integer> snapshot) {
        RESTRAINED.clear();
        if (snapshot != null) {
            RESTRAINED.putAll(snapshot);
        }
    }

    public static void clear() {
        RESTRAINED.clear();
    }
}
