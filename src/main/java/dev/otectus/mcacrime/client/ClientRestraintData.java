package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.enforcement.RestraintVisualState;
import dev.otectus.mcacrime.enforcement.RestraintVisualType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of who is currently restrained, with what, and who is holding them.
 *
 * <p>The twin of {@link ClientBandData}: display-only, fed entirely by the server, and never
 * classloaded on a dedicated server. Nothing here decides anything -- the wrist layer, the pose, and
 * the rope all read it, and none of them can make anybody restrained by writing to it.
 *
 * <p>Keyed by UUID rather than entity id because a restrained subject may not be loaded when the
 * packet arrives, and because the same key has to work for a player and for a villager.
 */
public final class ClientRestraintData {

    /** What one restrained subject looks like. Entity ids are per level; {@code -1} is nobody. */
    public record ClientRestraintEntry(RestraintVisualType type, int guardEntityId) {
    }

    private static final Map<UUID, ClientRestraintEntry> RESTRAINED = new ConcurrentHashMap<>();

    private ClientRestraintData() {
    }

    public static boolean restrained(UUID subject) {
        return subject != null && RESTRAINED.containsKey(subject);
    }

    /** The wrist binding to draw, or {@link RestraintVisualType#NONE} when this subject is free. */
    public static RestraintVisualType type(UUID subject) {
        ClientRestraintEntry entry = subject == null ? null : RESTRAINED.get(subject);
        return entry == null ? RestraintVisualType.NONE : entry.type();
    }

    /** The entity id of the guard holding this subject, or -1 when there is none. */
    public static int guardEntityId(UUID subject) {
        ClientRestraintEntry entry = subject == null ? null : RESTRAINED.get(subject);
        return entry == null ? -1 : entry.guardEntityId();
    }

    /** Every restrained subject right now, for the rope renderer to walk. */
    public static Map<UUID, ClientRestraintEntry> all() {
        return RESTRAINED;
    }

    public static void put(UUID subject, RestraintVisualState state) {
        if (subject == null || state == null) {
            return;
        }
        if (state.restrained() && state.type() != RestraintVisualType.NONE) {
            RESTRAINED.put(subject, new ClientRestraintEntry(state.type(), state.escortEntityId()));
        } else {
            RESTRAINED.remove(subject);
        }
    }

    public static void remove(UUID subject) {
        if (subject != null) {
            RESTRAINED.remove(subject);
        }
    }

    public static void putAll(Map<UUID, RestraintVisualState> snapshot) {
        RESTRAINED.clear();
        if (snapshot != null) {
            snapshot.forEach(ClientRestraintData::put);
        }
    }

    public static void clear() {
        RESTRAINED.clear();
    }
}
