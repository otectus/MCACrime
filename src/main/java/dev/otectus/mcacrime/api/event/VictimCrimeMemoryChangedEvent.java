package dev.otectus.mcacrime.api.event;

import dev.otectus.mcacrime.api.model.VictimMemoryView;
import net.neoforged.bus.api.Event;
import java.util.UUID;

/** Posted after memory creation, repetition or reconciliation. Safe optional-addon hook. */
public final class VictimCrimeMemoryChangedEvent extends Event {
    private final UUID villager;
    private final VictimMemoryView memory;
    private final String reason;
    public VictimCrimeMemoryChangedEvent(UUID villager, VictimMemoryView memory, String reason) {
        this.villager = villager; this.memory = memory; this.reason = reason;
    }
    public UUID getVillager() { return villager; }
    public VictimMemoryView getMemory() { return memory; }
    public String getReason() { return reason; }
}
