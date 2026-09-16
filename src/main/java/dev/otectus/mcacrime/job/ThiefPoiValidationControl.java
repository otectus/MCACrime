package dev.otectus.mcacrime.job;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

/**
 * Defers a Thief's job-site validation while the station's chunk cannot be inspected (0.7.2 §10.4).
 *
 * <p>Spec: "station chunk unloads — treat it as unresolved/unavailable, not confirmed destroyed. Do
 * not revoke ownership solely because it cannot currently be inspected." Vanilla's validator has no
 * such distinction: a POI it cannot read is a POI that is not there, and it erases the memory.
 *
 * <p>This protects <b>ownership</b>, not the profession. Nothing here stops a reset — the trading-XP
 * floor does that — and a Thief whose station genuinely was broken still loses it as soon as the chunk
 * is loaded and the validator can say so honestly.
 *
 * <p>Applies to employed Thieves only. Every other entity, and every other memory, is delegated
 * unchanged.
 */
public final class ThiefPoiValidationControl implements BehaviorControl<LivingEntity> {

    private final BehaviorControl<? super LivingEntity> delegate;
    private final MemoryModuleType<GlobalPos> memory;

    public ThiefPoiValidationControl(BehaviorControl<? super LivingEntity> delegate,
                                     MemoryModuleType<GlobalPos> memory) {
        this.delegate = delegate;
        this.memory = memory;
    }

    @Override
    public Behavior.Status getStatus() {
        return delegate.getStatus();
    }

    @Override
    public boolean tryStart(ServerLevel level, LivingEntity entity, long time) {
        if (deferred(level, entity)) {
            return false;
        }
        return delegate.tryStart(level, entity, time);
    }

    private boolean deferred(ServerLevel level, LivingEntity entity) {
        if (!ThiefWorkRegistry.isEmployed(entity.getUUID())) {
            return false;
        }
        GlobalPos site = entity.getBrain().getMemory(memory).orElse(null);
        if (site == null) {
            return false;
        }
        // Another dimension is the same unresolved case as an unloaded chunk, and for the same reason:
        // there is no honest answer here, and forcing one would mean loading somebody else's world.
        return !level.dimension().equals(site.dimension()) || !level.isLoaded(site.pos());
    }

    @Override
    public void tickOrStop(ServerLevel level, LivingEntity entity, long time) {
        delegate.tickOrStop(level, entity, time);
    }

    @Override
    public void doStop(ServerLevel level, LivingEntity entity, long time) {
        delegate.doStop(level, entity, time);
    }

    @Override
    public String debugString() {
        return delegate.debugString();
    }
}
