package dev.otectus.mcacrime.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * Which villager is being ticked right now, for hooks that are handed no villager at all.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>Every one of Townstead's per-villager entry points takes its own MCA villager type, and MCA: Crime
 * may not name that type anywhere — Townstead is compiled against MCA, so a captured parameter would
 * drag a relocated MCA descriptor into this mod's constant pool and re-link it to one MCA package
 * layout. So the Townstead mixins never capture the villager. They redirect a <em>vanilla</em> call
 * inside Townstead's method — {@code Brain.eraseMemory}, {@code PathNavigation.stop} — and get a
 * {@code Brain} or a {@code PathNavigation} and nothing else. Neither can name its owner:
 * {@code PathNavigation#getMob} does not exist in 1.20.1, and a {@code Brain} has never known whose
 * it is.
 *
 * <p>Identity therefore has to come from outside the call, and this is it.
 *
 * <h2>Why it is sound</h2>
 *
 * <p>Townstead's whole villager tick runs inside the villager's own {@code aiStep}:
 * {@code VillagerServerTickMixin} injects at the TAIL of {@code aiStep} and calls
 * {@code VillagerServerTickDispatcher.tick}, which is what runs {@code GuardRestEnforcerTicker} and
 * the rest. Forge fires {@code LivingTickEvent} at the start of {@code LivingEntity.tick()}, which is
 * the same tick of the same entity, on the same thread, and strictly earlier. So a context recorded
 * from that event is exactly the villager whose Townstead tickers run next.
 *
 * <h2>Why it is still checked</h2>
 *
 * <p>"Should be" is not a safety property, and a wrong answer here would mean MCA: Crime silently
 * suppressing Townstead's behaviour on the wrong villager. Four things are verified before the
 * context answers: the reader is on the thread that wrote it, the recorded level still matches, the
 * level's game time still matches the tick the context was taken in, and the live entity still has
 * the recorded id. Anything else reads as "no context", and every caller treats that as "do not
 * interfere" rather than as an error.
 *
 * <p>Recording is also gated on {@link TownsteadMixinStatus#anyApplied()} — one volatile read — so on
 * the overwhelmingly common install, where Townstead is not present and nothing was applied, a
 * villager tick pays for a field read and nothing else.
 */
public final class TownsteadTickContext {

    /**
     * One villager, one tick.
     *
     * @param entity    who was being ticked
     * @param gameTime  the level's game time at that moment; a context from an earlier tick is stale
     * @param dimension where they were; a context never answers for another level
     */
    public record Ticking(UUID entity, long gameTime, @Nullable ResourceLocation dimension) {
        public Ticking {
            if (entity == null) {
                throw new IllegalArgumentException("a tick context must name an entity");
            }
        }

        /** Whether this context describes {@code gameTime} in {@code dimension}. */
        public boolean matches(long now, @Nullable ResourceLocation level) {
            return gameTime == now && java.util.Objects.equals(dimension, level);
        }
    }

    private static volatile Ticking current;

    /**
     * The live entity, held only for the length of one tick.
     *
     * <p>It is what lets {@link #currentEntityId()} answer without being told the time: the entity
     * knows its own level, and the level knows the clock. Cleared at the end of every server tick, so
     * it can never pin an entity that unloaded.
     */
    private static volatile LivingEntity entity;

    private static volatile Thread thread;

    private TownsteadTickContext() {
    }

    /**
     * Records the villager whose tick is starting. Called from MCA: Crime's existing
     * {@code LivingTickEvent} handler, which already runs for every living entity on the server.
     */
    public static void observe(@Nullable LivingEntity living) {
        if (living == null || !TownsteadMixinStatus.anyApplied()) {
            return;
        }
        Level level = living.level();
        if (level == null || level.isClientSide()) {
            return;
        }
        entity = living;
        thread = Thread.currentThread();
        current = new Ticking(living.getUUID(), level.getGameTime(), level.dimension().location());
    }

    /**
     * Records a context directly.
     *
     * <p>The entity-free form, which is what makes the staleness rules testable without a server: the
     * decision {@link #at(long, ResourceLocation)} makes is the same one {@link #currentEntityId()}
     * makes, just with the clock supplied rather than read off a live level.
     */
    public static void set(@Nullable UUID id, long gameTime, @Nullable ResourceLocation dimension) {
        if (id == null) {
            clear();
            return;
        }
        entity = null;
        thread = Thread.currentThread();
        current = new Ticking(id, gameTime, dimension);
    }

    /** Drops the context. Called at the END phase of every server tick. */
    public static void clear() {
        current = null;
        entity = null;
        thread = null;
    }

    /** The context, if it describes {@code now} in {@code dimension}. Stale or foreign reads empty. */
    public static Optional<Ticking> at(long now, @Nullable ResourceLocation dimension) {
        Ticking context = current;
        if (context == null || Thread.currentThread() != thread) {
            return Optional.empty();
        }
        return context.matches(now, dimension) ? Optional.of(context) : Optional.empty();
    }

    /**
     * Who is being ticked right now, or {@code null}.
     *
     * <p>What the mixin handlers call. Every failure mode — no context, another thread, a context from
     * an earlier tick, a level that moved on, an entity that was swapped — answers {@code null}, and
     * every caller reads {@code null} as "leave Townstead alone".
     */
    @Nullable
    public static UUID currentEntityId() {
        Ticking context = current;
        LivingEntity living = entity;
        if (context == null || living == null || Thread.currentThread() != thread) {
            return null;
        }
        Level level = living.level();
        if (level == null || level.isClientSide()) {
            return null;
        }
        if (!context.matches(level.getGameTime(), level.dimension().location())) {
            return null;
        }
        return living.getUUID().equals(context.entity()) ? context.entity() : null;
    }

    /**
     * The level whose tick is running right now, or {@code null}.
     *
     * <p>The same staleness rules as {@link #currentEntityId()}, for the same reason: a hook that needs
     * a level rather than an identity -- the storage-sourcing hook does -- must not be handed one from
     * a tick that has already ended or from another thread. A client level never answers.
     */
    @Nullable
    public static ServerLevel currentServerLevel() {
        Ticking context = current;
        LivingEntity living = entity;
        if (context == null || living == null || Thread.currentThread() != thread) {
            return null;
        }
        if (!(living.level() instanceof ServerLevel level)) {
            return null;
        }
        return context.matches(level.getGameTime(), level.dimension().location()) ? level : null;
    }

    /** Whether anything is being tracked at all. Diagnostics and tests only. */
    public static boolean isEmpty() {
        return current == null;
    }
}
