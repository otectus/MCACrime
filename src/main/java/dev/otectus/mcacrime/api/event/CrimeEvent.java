package dev.otectus.mcacrime.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

/**
 * Base class for MCA: Crime events, fired server-side on the Forge event bus
 * ({@code MinecraftForge.EVENT_BUS}) so other mods — Quests, economy/faction add-ons, future content —
 * can react (spec §16). These are notifications: they expose immutable data and the live player, never
 * the mod's internal mutable state, and are not cancellable (state has already changed when they fire).
 */
public abstract class CrimeEvent extends Event {

    private final ServerPlayer player;

    protected CrimeEvent(ServerPlayer player) {
        this.player = player;
    }

    public ServerPlayer getPlayer() {
        return player;
    }
}
