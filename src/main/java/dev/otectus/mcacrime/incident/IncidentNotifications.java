package dev.otectus.mcacrime.incident;

import dev.otectus.mcacrime.McaCrime;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;

import java.util.ArrayList;
import java.util.List;

/** Buffers noncancelable notifications until the incident's state and evidence exist. */
public final class IncidentNotifications implements AutoCloseable {
    private static final ThreadLocal<IncidentNotifications> CURRENT = new ThreadLocal<>();
    private final IncidentNotifications parent = CURRENT.get();
    private final List<Runnable> pending = new ArrayList<>();

    public IncidentNotifications() { CURRENT.set(this); }

    public static void post(Event event) {
        if (event.isCancelable()) throw new IllegalArgumentException("Preflight events cannot be deferred");
        run(() -> MinecraftForge.EVENT_BUS.post(event));
    }

    public static void run(Runnable notification) {
        IncidentNotifications scope = CURRENT.get();
        if (scope == null) safely(notification);
        else scope.pending.add(notification);
    }

    public static void safely(Runnable notification) {
        try { notification.run(); }
        catch (RuntimeException exception) {
            McaCrime.LOGGER.error("Crime operation failed; subsequent operations will continue", exception);
        }
    }

    @Override public void close() {
        if (parent == null) CURRENT.remove();
        else CURRENT.set(parent);
        for (Runnable notification : pending) run(notification);
    }
}
