package dev.otectus.mcacrime.enchantment;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.ManaCompat;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Famine, Shroud, Exhaust and Silence, applied from the restraint tick (0.7.5 §3.10, M6.1).
 *
 * <p>The enchantment classes are inert; this is where the four passive ones happen, on the same
 * once-a-second batch as the rest of the restraint bookkeeping. Three differences from upstream's
 * {@code AbstractRestraint.onTickServer:145-164}, all of them the same kind of difference:
 *
 * <ul>
 *   <li><b>Bounded duration, refreshed.</b> Upstream adds a 100-tick instance only when the effect is
 *       entirely absent, so a shorter foreign instance of the same effect stomps it and a longer one
 *       stomps the prisoner's. Here the instance is re-offered every pass and vanilla's own
 *       {@code MobEffectInstance} merge rule keeps whichever is stronger — so somebody else's
 *       Blindness is never shortened by ours and ours is never silently dropped.</li>
 *   <li><b>Cleanup that knows what it applied.</b> A removal strips only the instances this class put
 *       on, matched by amplifier and by a remaining duration no longer than ours. A potion of
 *       blindness somebody drank survives being un-hooded.</li>
 *   <li><b>A configured drain rate.</b> Silence drains at {@code enchantments.manaDrainPerTick}
 *       rather than upstream's hard-coded {@code 0.005D} with its {@code TODO}, and it drains through
 *       {@code compat/ManaCompat}, which reports honestly when no supported spell mod is installed
 *       instead of claiming to have drained something.</li>
 * </ul>
 */
public final class RestraintEffects {

    /** The documented default duration of one applied instance, in ticks. */
    public static final int DEFAULT_DURATION_TICKS = 100;

    /** The documented default amplifier. Upstream's figure, kept. */
    public static final int DEFAULT_AMPLIFIER = 1;

    /** What this class has applied to whom, so a removal strips ours and nobody else's. */
    private static final Map<UUID, Set<CrimeEnchantKind>> APPLIED = new ConcurrentHashMap<>();

    private RestraintEffects() {
    }

    /**
     * One pass over every restrained subject.
     *
     * <p>Also rebuilds the Imbue reverse index from the same walk, which is the fix for upstream's
     * whole-player-list scan on every damage event ({@code ModServerEvents.java:407-415}): the table
     * is walked once a second here rather than once per point of damage anywhere in the world.
     *
     * @param elapsedTicks how many ticks this batch stands for, so a per-tick drain rate is honest
     */
    public static void tick(@Nullable MinecraftServer server, @Nullable CrimeWorldData data,
                            int elapsedTicks) {
        if (server == null || data == null || elapsedTicks <= 0) {
            return;
        }
        List<PhysicalRestraintState> states = data.physicalRestraints();
        ImbueIndex.rebuild(states);
        if (states.isEmpty()) {
            APPLIED.clear();
            return;
        }
        int duration = durationTicks();
        int amplifier = amplifier();
        for (PhysicalRestraintState state : states) {
            LivingEntity subject = living(server, state.subject());
            if (subject == null || !subject.isAlive()) {
                continue;
            }
            Set<CrimeEnchantKind> active = EnumSet.noneOf(CrimeEnchantKind.class);
            if (RestraintEnchantments.present(state, CrimeEnchantKind.FAMINE)) {
                active.add(CrimeEnchantKind.FAMINE);
                offer(subject, MobEffects.HUNGER, duration, amplifier);
            }
            if (RestraintEnchantments.present(state, CrimeEnchantKind.SHROUD)) {
                active.add(CrimeEnchantKind.SHROUD);
                offer(subject, MobEffects.BLINDNESS, duration, amplifier);
            }
            if (RestraintEnchantments.present(state, CrimeEnchantKind.EXHAUST)) {
                active.add(CrimeEnchantKind.EXHAUST);
                offer(subject, MobEffects.DIG_SLOWDOWN, duration, amplifier);
                offer(subject, MobEffects.WEAKNESS, duration, amplifier);
            }
            int silence = RestraintEnchantments.levelOn(state, CrimeEnchantKind.SILENCE);
            if (silence > 0 && subject instanceof ServerPlayer player) {
                active.add(CrimeEnchantKind.SILENCE);
                ManaCompat.drain(player, manaDrainPerTick() * silence * elapsedTicks);
            }
            reconcile(subject, active, duration, amplifier);
        }
    }

    /**
     * Everything this class applied to one subject comes off.
     *
     * <p>Called when the last restraint is removed and on logout. Idempotent, and safe for a subject
     * who never wore an enchanted anything.
     */
    public static void clear(@Nullable LivingEntity subject) {
        if (subject == null) {
            return;
        }
        Set<CrimeEnchantKind> applied = APPLIED.remove(subject.getUUID());
        if (applied == null || applied.isEmpty()) {
            return;
        }
        int duration = durationTicks();
        int amplifier = amplifier();
        for (MobEffect effect : effectsFor(applied)) {
            withdraw(subject, effect, duration, amplifier);
        }
    }

    /** Forgets one subject's bookkeeping without touching their effects. Logout and shutdown. */
    public static void forget(@Nullable UUID subject) {
        if (subject != null) {
            APPLIED.remove(subject);
        }
    }

    /** Forgets everything. Server stop, and every test's setup. */
    public static void clearAll() {
        APPLIED.clear();
    }

    /** Which subjects this class currently believes it is affecting. Diagnostics and tests. */
    public static Set<CrimeEnchantKind> appliedTo(@Nullable UUID subject) {
        Set<CrimeEnchantKind> applied = subject == null ? null : APPLIED.get(subject);
        return applied == null ? Set.of() : Set.copyOf(applied);
    }

    // --- internals ----------------------------------------------------------------------------------

    /** Strips what is no longer carried and records what is. */
    private static void reconcile(LivingEntity subject, Set<CrimeEnchantKind> active, int duration,
                                  int amplifier) {
        UUID id = subject.getUUID();
        Set<CrimeEnchantKind> previous = APPLIED.get(id);
        if (previous != null) {
            Set<CrimeEnchantKind> gone = EnumSet.copyOf(previous);
            gone.removeAll(active);
            for (MobEffect effect : effectsFor(gone)) {
                withdraw(subject, effect, duration, amplifier);
            }
        }
        if (active.isEmpty()) {
            APPLIED.remove(id);
        } else {
            APPLIED.put(id, active);
        }
    }

    /** The vanilla effects one set of enchantments is responsible for. */
    private static List<MobEffect> effectsFor(Set<CrimeEnchantKind> kinds) {
        List<MobEffect> effects = new ArrayList<>(4);
        if (kinds.contains(CrimeEnchantKind.FAMINE)) {
            effects.add(MobEffects.HUNGER);
        }
        if (kinds.contains(CrimeEnchantKind.SHROUD)) {
            effects.add(MobEffects.BLINDNESS);
        }
        if (kinds.contains(CrimeEnchantKind.EXHAUST)) {
            effects.add(MobEffects.DIG_SLOWDOWN);
            effects.add(MobEffects.WEAKNESS);
        }
        return effects;
    }

    /** Offers our instance; vanilla keeps whichever of the two is stronger. */
    private static void offer(LivingEntity subject, MobEffect effect, int duration, int amplifier) {
        subject.addEffect(new MobEffectInstance(effect, duration, amplifier, true, false, true));
    }

    /**
     * Takes ours back off, and only ours.
     *
     * <p>The test is conservative on purpose: an instance with a bigger amplifier, or one that lasts
     * longer than the instance we would have applied, belongs to somebody else and is left alone.
     */
    private static void withdraw(LivingEntity subject, MobEffect effect, int duration, int amplifier) {
        MobEffectInstance instance = subject.getEffect(effect);
        if (instance == null) {
            return;
        }
        if (instance.getAmplifier() == amplifier && instance.getDuration() <= duration) {
            subject.removeEffect(effect);
        }
    }

    @Nullable
    private static LivingEntity living(MinecraftServer server, @Nullable UUID id) {
        if (id == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = level.getEntity(id);
            if (found instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    static int durationTicks() {
        try {
            return McaCrimeConfig.COMMON.enchantEffectDurationTicks.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return DEFAULT_DURATION_TICKS;
        }
    }

    static int amplifier() {
        try {
            return McaCrimeConfig.COMMON.enchantEffectAmplifier.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return DEFAULT_AMPLIFIER;
        }
    }

    static double manaDrainPerTick() {
        try {
            return McaCrimeConfig.COMMON.manaDrainPerTick.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return 0.005D;
        }
    }
}
