package dev.otectus.mcacrime.action;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Code allowlist. Data definitions may select only handlers registered here. */
public final class ActionHandlerRegistry {
    private static final Map<ResourceLocation, CrimeActionHandler> HANDLERS = new ConcurrentHashMap<>();

    private ActionHandlerRegistry() {}

    public static void register(ResourceLocation id, CrimeActionHandler handler) {
        CrimeActionHandler prior = HANDLERS.putIfAbsent(id, handler);
        if (prior != null && prior != handler) {
            throw new IllegalStateException("Duplicate crime action handler " + id);
        }
    }

    public static CrimeActionHandler get(ResourceLocation id) { return HANDLERS.get(id); }
}
