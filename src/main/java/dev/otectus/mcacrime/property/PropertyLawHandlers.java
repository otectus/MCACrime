package dev.otectus.mcacrime.property;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.api.event.FinePaidEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The server-side seam between a player opening a container and MCA: Crime deciding whether anything
 * was stolen.
 *
 * <h2>Inert when the switch is off</h2>
 *
 * <p>Every handler here begins with {@link PropertyRegistry#enabled()}, which is one boolean read, and
 * property law ships off. On a default server the container-open handler reads a config value and
 * returns, the tick handler finds an empty map and returns, and nothing else in this package is ever
 * touched. That is a requirement rather than an optimisation: an opt-in subsystem that costs anything
 * on the servers that did not opt in is a subsystem nobody should ship.
 *
 * <h2>Why the position comes from the right-click</h2>
 *
 * <p>A container menu does not know where its container is. {@code ChestMenu} exposes a
 * {@code Container}, which for a double chest is a {@code CompoundContainer} that names neither half's
 * position, and reaching into it would mean either a mixin or a guess. The block a player right-clicked
 * one tick before their menu opened is neither: it is the server's own record of what they interacted
 * with. A menu that opened without one — a command, another mod — is reported unsupported and
 * attributes nothing.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class PropertyLawHandlers {

    /** How many containers may be watched at once. Far more than a server has players. */
    private static final int MAX_WATCHERS = 256;

    /** How many distinct unsupported sources are remembered for the diagnostic. */
    private static final int MAX_UNSUPPORTED_REPORTED = 16;

    /** One watcher per player; a player has exactly one menu open. */
    private static final Map<UUID, ContainerTransferWatcher> WATCHERS = new LinkedHashMap<>();

    /** The block each player last interacted with, and when. Cleared aggressively. */
    private static final Map<UUID, Interaction> INTERACTIONS = new LinkedHashMap<>();

    /** The gaps an operator can act on: menus that claimed property but could not be proved through. */
    private static final Set<String> UNSUPPORTED = new LinkedHashSet<>();

    private static boolean swept;

    private record Interaction(BlockPos pos, long gameTime) {
    }

    private PropertyLawHandlers() {
    }

    /**
     * Remembers where a player just reached.
     *
     * <p>Recorded even when property law is off, and deliberately so: the container-open event arrives
     * in the same tick, and a config reloaded between the two would otherwise leave a watcher with no
     * position. One {@code BlockPos} per player is not a cost worth gating.
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (INTERACTIONS.size() > MAX_WATCHERS) {
            INTERACTIONS.clear();
        }
        INTERACTIONS.put(player.getUUID(),
                new Interaction(event.getPos().immutable(), player.serverLevel().getGameTime()));
    }

    /** Starts watching a container that something actually claims. */
    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!PropertyRegistry.enabled() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        AbstractContainerMenu menu = event.getContainer();
        if (menu == null) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = interactionPosition(player, level.getGameTime());
        if (pos == null) {
            return; // nothing was right-clicked; nothing can be attributed, and nothing is guessed
        }
        if (PropertyRegistry.policyAt(level, pos).isEmpty()) {
            // Unclaimed, which is nearly every container in nearly every world. Watching it would cost a
            // per-tick diff for a container nobody owns.
            return;
        }
        if (WATCHERS.size() >= MAX_WATCHERS) {
            return;
        }
        ContainerTransferWatcher watcher = ContainerTransferWatcher.open(player, menu, pos);
        if (!watcher.supported()) {
            reportUnsupported(menu, watcher.unsupportedReason());
            return;
        }
        WATCHERS.put(player.getUUID(), watcher);
    }

    /** One last diff before the menu goes, which is what catches a cursor stack put away on close. */
    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ContainerTransferWatcher watcher = WATCHERS.remove(player.getUUID());
        if (watcher != null && PropertyRegistry.enabled()) {
            process(player, watcher);
        }
        INTERACTIONS.remove(player.getUUID());
    }

    /**
     * The per-tick diff, and the one-off automatic protection sweep.
     *
     * <p>{@code Post} rather than {@code Pre}: the transfers of this tick have happened by then, and
     * observing them in the same tick they were committed is what keeps two stacks moved in one tick
     * from being minted as one transfer id. (NeoForge 1.21.1 splits the Forge-era phased
     * {@code TickEvent.ServerTickEvent} into two classes; {@code Post} is the {@code END} phase.)
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!PropertyRegistry.enabled()) {
            return;
        }
        if (!swept) {
            // Deferred to the first tick rather than done at ServerStartedEvent, because the settlement
            // bridge binds in that same event and the order between two subscribers is not ours to
            // choose. By the first tick it has bound or it has not, and either answer is stable.
            swept = true;
            PropertyAutoProtection.sweep(event.getServer());
        }
        if (WATCHERS.isEmpty()) {
            return;
        }
        List<UUID> finished = new ArrayList<>();
        for (Map.Entry<UUID, ContainerTransferWatcher> entry : WATCHERS.entrySet()) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            ContainerTransferWatcher watcher = entry.getValue();
            if (player == null || !watcher.watching(player)) {
                finished.add(entry.getKey());
                continue;
            }
            process(player, watcher);
        }
        finished.forEach(WATCHERS::remove);
    }

    /**
     * A paid fine settles the property losses of the cases it closed (§10.6).
     *
     * <p>The repair-or-replace route, for goods that were eaten, burnt or built into a wall and cannot
     * be handed back. It resolves only the corresponding property loss: the case itself was already
     * settled by the payment, and anything else bound to that case -- an assault, a kidnapping -- is
     * untouched, which §10.6 requires in as many words.
     */
    @SubscribeEvent
    public static void onFinePaid(FinePaidEvent event) {
        if (!PropertyRegistry.enabled() || event.getAffectedCaseIds().isEmpty()) {
            return;
        }
        ServerPlayer player = event.getPlayer();
        if (player == null || player.getServer() == null) {
            return;
        }
        PropertyTheftService.restoreForCases(player.getServer(), event.getAffectedCaseIds(),
                player.serverLevel().getGameTime());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        clearAll();
    }

    /** Drops every watcher and every work authorisation. Server stop, and tests. */
    public static void clearAll() {
        WATCHERS.clear();
        INTERACTIONS.clear();
        UNSUPPORTED.clear();
        WorkTransferContext.clearAll();
        swept = false;
    }

    /** The sources that claimed property but could not be proved through. Operator diagnostics. */
    public static List<String> unsupportedSources() {
        return List.copyOf(UNSUPPORTED);
    }

    /** How many containers are being watched right now. Operator diagnostics and tests. */
    public static int watcherCount() {
        return WATCHERS.size();
    }

    private static void process(ServerPlayer player, ContainerTransferWatcher watcher) {
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        PropertyActor actor = PropertyRegistry.actorOf(player);
        for (TransferAttribution.CommittedTransfer transfer : watcher.poll(player, now)) {
            TransferAttribution attribution =
                    PropertyTheftService.judge(level, transfer, actor, watcher.supported());
            if (attribution.charges()) {
                PropertyTheftService.commit(level, player, attribution);
            } else if (transfer.direction() == TransferAttribution.Direction.IN) {
                // Putting the goods back where they came from settles the loss and nothing else (§10.6).
                PropertyTheftService.restore(level, player.getUUID(), transfer);
            }
        }
    }

    /** The block this player reached for in this tick or the one before, or null. */
    @Nullable
    private static BlockPos interactionPosition(ServerPlayer player, long now) {
        Interaction interaction = INTERACTIONS.get(player.getUUID());
        if (interaction == null) {
            return null;
        }
        // One tick of tolerance. The interact event and the menu open happen in the same tick today;
        // accepting the previous one as well costs nothing and survives a loader that defers the open.
        return now - interaction.gameTime() <= 1 ? interaction.pos() : null;
    }

    private static void reportUnsupported(AbstractContainerMenu menu, String reason) {
        String source = menu.getClass().getName();
        if (UNSUPPORTED.size() >= MAX_UNSUPPORTED_REPORTED || !UNSUPPORTED.add(source + " — " + reason)) {
            return;
        }
        // One line per distinct source per session, and never a charge. §10.2: an unsupported source is
        // reported as unsupported rather than charged to the nearest player.
        McaCrime.LOGGER.info("MCA: Crime property law cannot prove a transfer through {}; {}. No theft "
                + "will be attributed through this container until an adapter for it exists.", source, reason);
    }
}
