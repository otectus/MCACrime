package dev.otectus.mcacrime.detect;

import dev.otectus.mcacrime.incident.IncidentNotifications;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/** One death's dependent actions. Cancellation/revival runs none; confirmation consumes before callbacks. */
public final class DeathConsequences {
    private final List<Runnable> actions;
    private boolean consumed;

    public DeathConsequences(List<Runnable> actions) { this.actions = List.copyOf(actions); }

    public boolean confirm(boolean confirmed) {
        if (!confirmed || consumed) return false;
        consumed = true;
        actions.forEach(IncidentNotifications::safely);
        return true;
    }

    public static DeathConsequences capture(ServerLevel level, LivingEntity victim, DamageSource source) {
        // Capture legal eligibility and warrant/price before death cleanup changes custody or Heat.
        var reward = new java.util.concurrent.atomic.AtomicReference<dev.otectus.mcacrime.bounty.BountyService.KillOffer>();
        IncidentNotifications.safely(() -> reward.set(dev.otectus.mcacrime.bounty.BountyService.prepareKill(victim, source)));
        return new DeathConsequences(List.of(
                () -> dev.otectus.mcacrime.bounty.BountyService.confirmKill(level.getServer(), reward.get()),
                () -> dev.otectus.mcacrime.mug.npc.StolenGoodsRecovery.confirmedDeath(level, victim),
                () -> CrimeDetectionHandlers.confirmedDeath(victim, level),
                () -> dev.otectus.mcacrime.ai.CrimeReactionService.clear(level, victim.getUUID()),
                () -> dev.otectus.mcacrime.ai.thief.ThiefTicker.confirmedDeath(victim.getUUID())));
    }
}
