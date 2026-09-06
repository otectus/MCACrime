package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.captivity.RestraintType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * The single answer to "is this player restrained, and with what".
 *
 * <p>There are two ways to end up in chains and until now only one of them counted. {@link
 * ArrestStates} owns the lawful arrest phases, and everything that restraint means — the movement
 * penalty, the suppressed interactions, the pose the client draws — was read from it alone. A
 * kidnapping victim, or an outlaw a bounty hunter had taken alive, held a {@link CustodyRecord}
 * saying exactly which restraint was on them and was nonetheless free to mine, fight and sprint.
 *
 * <p>So the sources are combined here rather than at each of the three call sites that ask, because
 * three readings of two sources are six chances to disagree. The arrest phase wins when it applies:
 * it is the stricter state, and a player being escorted to a cell is restrained whatever the custody
 * table says.
 */
public final class RestraintPolicy {

    private RestraintPolicy() {
    }

    /**
     * The restraint {@code player} is actually under, or empty when they are free.
     *
     * <p>Any owner kind counts. A guard's arrest, a jail's hold, a bounty hunter's rope and a
     * kidnapper's cuffs are different in law and identical in what the player can do while wearing
     * them.
     */
    public static Optional<RestraintType> effective(@Nullable ServerPlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        MinecraftServer server = player.getServer();
        CustodyRecord record = server == null ? null
                : CustodyRegistry.get(server, player.getUUID()).orElse(null);
        return effective(ArrestStates.isRestrained(player),
                record == null ? null : record.getRestraint(), record);
    }

    /**
     * The same decision without a player to read it from.
     *
     * <p>{@code arrestType} is what the arrest itself says it applied. An arrest phase says
     * "restrained" without saying with what, so in practice the caller passes the custody record's
     * restraint and the default below stands in when there is none — cuffs, because that is what an
     * arrest applies, and because it is what the renderer already assumed.
     */
    public static Optional<RestraintType> effective(boolean arrestRestrained,
                                                    @Nullable RestraintType arrestType,
                                                    @Nullable CustodyRecord record) {
        if (arrestRestrained) {
            return Optional.of(arrestType == null || arrestType == RestraintType.NONE
                    ? RestraintType.CUFFS : arrestType);
        }
        if (record == null) {
            return Optional.empty();
        }
        RestraintType held = record.getRestraint();
        return held == null || held == RestraintType.NONE ? Optional.empty() : Optional.of(held);
    }
}
