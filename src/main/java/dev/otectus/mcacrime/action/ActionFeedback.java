package dev.otectus.mcacrime.action;

import dev.otectus.mcacrime.network.ActionProgressS2CPacket;
import dev.otectus.mcacrime.network.CrimeNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.UUID;

/**
 * Tells an actor's client what their action is doing.
 *
 * <p>Every method here is a no-op when there is no server — which is the case in unit tests, and is
 * why {@link ActionSessionManager} can keep calling into this without gaining a server dependency it
 * would then need mocking for.
 *
 * <p>Feedback goes to the actor alone. An action channel's remaining duration is precisely the
 * information the interruption rules turn on, so broadcasting it would hand every nearby player a
 * timer on somebody else's crime.
 */
public final class ActionFeedback {

    private ActionFeedback() {
    }

    /** The label the HUD shows for a session, falling back to a generic one for an unregistered id. */
    private static String labelFor(ActionSession session) {
        CrimeActionHandler handler = ActionHandlerRegistry.get(session.actionId());
        return handler == null ? "gui.mcacrime.actions" : handler.descriptor().labelKey();
    }

    private static ServerPlayer actor(UUID actorId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.getPlayerList().getPlayer(actorId);
    }

    /** A channel has opened. Draws the bar at zero. */
    public static void started(ActionSession session) {
        ServerPlayer player = actor(session.actorId());
        if (player == null) return;
        CrimeNetwork.sendActionProgress(player, ActionProgressS2CPacket.started(
                session.sessionId(), labelFor(session), session.requiredTicks()));
    }

    /** A routine advance. Called on an interval, never every tick. */
    public static void progress(ActionSession session) {
        ServerPlayer player = actor(session.actorId());
        if (player == null) return;
        CrimeNetwork.sendActionProgress(player, new ActionProgressS2CPacket(session.sessionId(),
                labelFor(session), session.progress(), session.requiredTicks(),
                ActionProgressS2CPacket.Phase.PROGRESS, ""));
    }

    /**
     * The action ended. {@code outcomeKey} is shown for a few seconds and then fades.
     *
     * <p>For a cancellation this is the first time in the mod's history that the reason reaches the
     * player: the nine {@link CancelReason} values were recorded into a replay result that nothing
     * read, so an interrupted action simply stopped with no explanation.
     */
    public static void ended(ActionSession session, ActionProgressS2CPacket.Phase phase, String outcomeKey) {
        ServerPlayer player = actor(session.actorId());
        if (player == null) return;
        CrimeNetwork.sendActionProgress(player, ActionProgressS2CPacket.ended(
                session.sessionId(), labelFor(session), phase, outcomeKey));
    }
}
