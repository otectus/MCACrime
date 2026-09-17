package dev.otectus.mcacrime.property;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Who is reaching into a container, reduced to the four things {@link PropertyAccess} actually asks
 * about.
 *
 * <p>Deliberately free of Minecraft types. The access decision is the part of property law that has
 * to be provable, and a rule table that can only be exercised by standing a server up is a rule table
 * nobody exercises. Everything entity-shaped — reading a player's UUID, asking the settlement mod for
 * a villager's village or work role — happens at the call site and arrives here as plain values.
 *
 * <p>{@link Kind#UNKNOWN} is a first-class answer rather than a null. An actor nobody could identify
 * is the case §10.1 says must never authorise a theft charge, and making it a value means the rule
 * table has to say so out loud instead of a caller remembering to check.
 *
 * @param kind       player, villager, or nobody we could name
 * @param id         the actor's UUID, absent when the kind is unknown
 * @param villageId  the settlement the actor belongs to, or {@link #NO_VILLAGE}
 * @param resident   whether the actor is a resident of {@code villageId} rather than merely near it
 * @param workerRole the settlement work role the actor holds right now, or null
 */
public record PropertyActor(Kind kind, @Nullable UUID id, int villageId, boolean resident,
                            @Nullable String workerRole) {

    /** The actor belongs to no settlement anybody could name. */
    public static final int NO_VILLAGE = -1;

    public enum Kind {
        PLAYER,
        VILLAGER,
        /** Nobody could be identified. Never charged, never denied — only {@code UNKNOWN}. */
        UNKNOWN
    }

    public PropertyActor {
        if (kind == null) {
            kind = Kind.UNKNOWN;
        }
        if (kind == Kind.UNKNOWN) {
            id = null;
        }
        villageId = villageId < 0 ? NO_VILLAGE : villageId;
        if (workerRole != null && workerRole.isBlank()) {
            workerRole = null;
        }
    }

    /** Nobody. The identity every uncertain path resolves to. */
    public static PropertyActor unknown() {
        return new PropertyActor(Kind.UNKNOWN, null, NO_VILLAGE, false, null);
    }

    public static PropertyActor player(UUID id, int villageId, boolean resident) {
        return id == null ? unknown() : new PropertyActor(Kind.PLAYER, id, villageId, resident, null);
    }

    public static PropertyActor villager(UUID id, int villageId, boolean resident, @Nullable String role) {
        return id == null ? unknown() : new PropertyActor(Kind.VILLAGER, id, villageId, resident, role);
    }

    /** Whether anything at all is known about who this is. */
    public boolean identified() {
        return kind != Kind.UNKNOWN && id != null;
    }

    /** Whether this actor belongs to the settlement with this id. */
    public boolean memberOf(int village) {
        return resident && village >= 0 && villageId == village;
    }

    /** Whether this actor is working for the settlement with this id right now. */
    public boolean worksFor(int village) {
        return workerRole != null && village >= 0 && villageId == village;
    }
}
