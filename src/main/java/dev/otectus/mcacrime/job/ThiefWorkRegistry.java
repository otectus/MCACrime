package dev.otectus.mcacrime.job;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which villagers are employed Thieves, and which of their brains have had work wrapped (0.7.2 §10.3).
 *
 * <h2>Why a registry rather than a lookup</h2>
 *
 * <p>{@code ThiefBrainMixin} runs at the head of <em>every</em> {@code Brain.tick} — every mob, every
 * tick — so its first question has to be answerable without touching saved data, an entity's
 * capabilities or a map lookup. {@link #EMPLOYED} is usually empty, so that first question is one
 * {@code isEmpty()}.
 *
 * <h2>Why brains are re-wrapped</h2>
 *
 * <p>MCA's profession setter calls {@code refreshBrain}, which builds a completely new {@link Brain}
 * and installs a fresh task list. A one-off wrap at commit time would therefore survive exactly until
 * the next profession change, a cure, or a reload. Wrapping is idempotent and keyed on brain identity
 * instead, so each new brain is wrapped once and the old one is forgotten when it is collected.
 */
public final class ThiefWorkRegistry {

    private static final Set<UUID> EMPLOYED = ConcurrentHashMap.newKeySet();

    /** Brains already wrapped. Weak and identity-based: a brain this holds must still be collectable. */
    private static final Set<Brain<?>> WRAPPED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private ThiefWorkRegistry() {
    }

    public static void markEmployed(UUID villager) {
        if (villager != null) {
            EMPLOYED.add(villager);
        }
    }

    public static void clearEmployed(UUID villager) {
        if (villager != null) {
            EMPLOYED.remove(villager);
        }
    }

    public static boolean isEmployed(@Nullable UUID villager) {
        return villager != null && !EMPLOYED.isEmpty() && EMPLOYED.contains(villager);
    }

    /** Dropped on server stop: every one of these is a fact about a world that is no longer loaded. */
    public static void clearAll() {
        EMPLOYED.clear();
        WRAPPED.clear();
    }

    /** The mixin's first question, and the cheap one. */
    public static boolean needsWrapping(@Nullable LivingEntity entity) {
        return !EMPLOYED.isEmpty() && entity instanceof Villager && EMPLOYED.contains(entity.getUUID());
    }

    /**
     * Replaces this brain's {@code WORK} behaviours with yielding wrappers, once.
     *
     * <p>Only the {@link Activity#WORK} entries of this one brain are touched. Other activities, other
     * priorities and every other entity's brain are left exactly as they were built, which is what
     * keeps a change this deep inside vanilla's brain from being a change to anybody else's mob.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void wrapWorkBehaviors(Brain<?> brain, Map<Integer, Map<Activity, Set<?>>> byPriority) {
        if (brain == null || byPriority == null || !WRAPPED.add(brain)) {
            return;
        }
        try {
            for (Map<Activity, Set<?>> byActivity : byPriority.values()) {
                Set<?> work = byActivity.get(Activity.WORK);
                if (work == null || work.isEmpty()) {
                    continue;
                }
                Set replacement = new LinkedHashSet();
                boolean changed = false;
                for (Object behavior : work) {
                    if (behavior instanceof ThiefWorkBehaviorControl || !(behavior instanceof BehaviorControl<?>)) {
                        replacement.add(behavior);
                        continue;
                    }
                    replacement.add(new ThiefWorkBehaviorControl((BehaviorControl) behavior));
                    changed = true;
                }
                if (changed) {
                    Set raw = (Set) work;
                    raw.clear();
                    raw.addAll(replacement);
                }
            }
        } catch (Throwable t) {
            // A brain whose task list another mod made immutable simply keeps its own work behaviour.
            // That is a lost yield, not a broken villager, and it must never take the tick with it.
            WRAPPED.remove(brain);
        }
    }
}
