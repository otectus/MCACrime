package dev.otectus.mcacrime.job;

import dev.otectus.mcacrime.ai.thief.ThiefWorkGate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;

/**
 * One {@code Activity.WORK} behaviour of one Thief, wrapped so it yields (0.7.2 §10.3).
 *
 * <p>Refuses to start while {@link ThiefWorkGate} says something more important owns the villager, and
 * stops a behaviour that was already running when that becomes true. Everything else — which work
 * behaviours exist, what they do, how long they run — is MCA's and vanilla's, unchanged.
 *
 * <p>Wrapping the individual behaviours rather than suppressing the whole activity is what keeps this
 * narrow: CORE, REST, PANIC and the social activities are never touched, so a panicking Thief still
 * panics and a sleeping one still sleeps.
 */
public final class ThiefWorkBehaviorControl implements BehaviorControl<LivingEntity> {

    private final BehaviorControl<? super LivingEntity> delegate;

    public ThiefWorkBehaviorControl(BehaviorControl<? super LivingEntity> delegate) {
        this.delegate = delegate;
    }

    /** The behaviour this wraps, so a brain is never wrapped twice. */
    public BehaviorControl<? super LivingEntity> delegate() {
        return delegate;
    }

    @Override
    public Behavior.Status getStatus() {
        return delegate.getStatus();
    }

    @Override
    public boolean tryStart(ServerLevel level, LivingEntity entity, long time) {
        if (ThiefWorkGate.yields(level.getServer(), entity)) {
            return false;
        }
        return delegate.tryStart(level, entity, time);
    }

    @Override
    public void tickOrStop(ServerLevel level, LivingEntity entity, long time) {
        if (ThiefWorkGate.yields(level.getServer(), entity)) {
            delegate.doStop(level, entity, time);
            return;
        }
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
