package dev.otectus.mcacrime.action;

import dev.otectus.mcacrime.compat.McaCompat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One decision per right-click on an MCA villager (port-only, 0.7.9).
 *
 * <p>Every MCA 1.21.1 build in {@code mca_probe_versions} opens its interaction screen from
 * {@code VillagerEntityMCA.interactAt} and answers SUCCESS on both sides, so a real client's click
 * arrives as INTERACT_AT ({@code EntityInteractSpecific}) and the plain INTERACT ({@code EntityInteract})
 * normally never follows. On Forge 1.20.1, MCA 7.7.x opens the screen from {@code mobInteract}, behind
 * {@code EntityInteract}, which is where the chain, armed-menu, Sand Bottle and victim-memory handlers
 * listen. For an MCA villager each of them therefore also takes the precise event, at its own priority,
 * which puts all four ahead of MCA's screen in the same order as on Forge.
 *
 * <p>A client whose own {@code interactAt} passed still sends the plain half of the same click. Each
 * handler records what it decided on the precise half, and the plain half repeats that decision instead
 * of making a second one: no second apology, refusal line, menu, chain or thrown bottle.
 */
public final class McaVillagerClicks {

    /** One tick of slack: both halves of one click are delivered back to back. */
    static final long WINDOW_TICKS = 1L;

    /** The handlers that decide a click here, each with its own record. */
    public enum Route { TETHER, ARMED_MENU, SAND_BOTTLE, MEMORY }

    /** What a route decided: the cancellation result, or null when it let the click through. */
    public record Decision(@Nullable InteractionResult result) {
        public boolean consumed() {
            return result != null;
        }
    }

    private record Key(Route route, UUID player) {
    }

    private record Click(UUID target, InteractionHand hand, long tick, Decision decision) {
    }

    private static final Map<Key, Click> DECIDED = new ConcurrentHashMap<>();

    private McaVillagerClicks() {
    }

    /** Whether this precise event is the deciding half of a click on an MCA villager, on the server. */
    public static boolean decidesHere(PlayerInteractEvent.EntityInteractSpecific event) {
        return event.getEntity() instanceof ServerPlayer && McaCompat.isMcaVillager(event.getTarget());
    }

    /** Records what {@code route} decided for the precise half of this click. */
    public static void decided(Route route, Player player, Entity target, InteractionHand hand,
                               @Nullable InteractionResult result) {
        DECIDED.put(new Key(route, player.getUUID()),
                new Click(target.getUUID(), hand, player.level().getGameTime(), new Decision(result)));
    }

    /**
     * What {@code route} decided for the precise half of this same click, or null when it decided nothing
     * for it: a new click, or one another handler consumed before this route saw the precise half.
     */
    @Nullable
    public static Decision echo(Route route, Player player, Entity target, InteractionHand hand) {
        Key key = new Key(route, player.getUUID());
        Click click = DECIDED.get(key);
        if (click == null || !click.target().equals(target.getUUID()) || click.hand() != hand) {
            return null;
        }
        long elapsed = player.level().getGameTime() - click.tick();
        if (elapsed < 0 || elapsed > WINDOW_TICKS) {
            DECIDED.remove(key, click);
            return null;
        }
        return click.decision();
    }

    /** Drops a player's records on logout, so the map cannot outlive the session that made them. */
    public static void forget(UUID player) {
        DECIDED.keySet().removeIf(key -> key.player().equals(player));
    }

    /** Drops every record when the server stops. */
    public static void clear() {
        DECIDED.clear();
    }
}
