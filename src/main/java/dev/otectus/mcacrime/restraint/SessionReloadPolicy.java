package dev.otectus.mcacrime.restraint;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a mid-session config reload does to work that is already running (0.7.5 M7.1).
 *
 * <h2>The rule</h2>
 * A session either keeps the numbers it started with or it ends. It is never left running against a
 * mixture of the two. Every session this mod opens is short, transient and owned by one actor, so
 * <b>ending</b> is the answer here rather than snapshotting: a struggle priced against the old
 * durability, a lockpick metered by the old drain divisor or a frisk bounded by the old payload cap
 * would otherwise finish under rules nobody could look up afterwards — the file says one thing, the
 * session did another, and the difference is invisible.
 *
 * <p>Nothing is lost by ending one. A restraint is not removed, a lock is not relocked, seized items
 * are already in the box they were moved to, and the actor starts again with one interaction. That is
 * a far smaller cost than an outcome that cannot be explained from the config.
 *
 * <h2>Tethers are not sessions</h2>
 * A tether is persistent world state, not work in progress, and {@code tether/TetherService} reads
 * {@code transport.maxChainLength} and {@code overextensionLength} live on the tick it needs them. It
 * therefore has no stale snapshot to be inconsistent with: the tick after a reload is enforced under
 * the new numbers, whole. Detaching live tethers on a reload would drop prisoners mid-escort for a
 * setting that had not changed, so this deliberately does not.
 *
 * <p>The counting half is pure and takes a registry, so the policy can be asserted without a server.
 */
public final class SessionReloadPolicy {

    /** What a cancelled actor is told. Once, in the action bar, not in chat. */
    public static final String MESSAGE_KEY = "mcacrime.msg.session.config_reloaded";

    private SessionReloadPolicy() {
    }

    /**
     * Ends every live session in {@code registry}.
     *
     * @return the actors whose session ended, in the order the registry held them
     */
    public static List<UUID> cancelAll(@Nullable SessionRegistry registry) {
        if (registry == null) {
            return List.of();
        }
        List<UUID> actors = new ArrayList<>(registry.actors());
        for (UUID actor : actors) {
            registry.cancelForActor(actor, SessionCancelCause.CONFIG_RELOADED);
        }
        return actors;
    }

    /**
     * The gameplay path: ends every live session and tells whoever owned one.
     *
     * <p>A frisking searcher also has their container closed, because the menu is the session's own
     * window onto a transaction that no longer exists; leaving it open would invite clicks the server
     * would then refuse one at a time.
     */
    public static int cancelAll(@Nullable MinecraftServer server) {
        List<UUID> actors = cancelAll(SessionRegistry.server());
        if (server == null) {
            return actors.size();
        }
        for (UUID actor : actors) {
            ServerPlayer player = server.getPlayerList().getPlayer(actor);
            if (player == null) {
                continue;
            }
            if (player.containerMenu instanceof dev.otectus.mcacrime.menu.FriskingMenu) {
                player.closeContainer();
            }
            player.displayClientMessage(Component.translatable(MESSAGE_KEY), true);
        }
        return actors.size();
    }
}
