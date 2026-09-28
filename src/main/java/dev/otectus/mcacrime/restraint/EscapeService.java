package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Struggling out of a restraint, decided entirely on the server (§3.5, M2.7).
 *
 * <p>The client sends an <em>intent</em>: a session id, a slot, which input was pressed and a
 * sequence number. It sends no durability, no outcome and no identity — identity comes from the
 * connection. The source accepts a raw durability delta from the client
 * ({@code AbstractRestraint} applies whatever number arrives), which means a modified client sets its
 * own cuffs to zero, and it decides the break roll client-side as well.
 *
 * <p>Two upstream defects are fixed here rather than reproduced:
 * <ul>
 *   <li>the break cooldown is stamped as a deadline and therefore expires; the source sets a
 *       {@code breakCooldown} counter on a successful roll and decrements it nowhere, so a restraint
 *       that has been strained once can never be strained again;</li>
 *   <li>a restraint reaching zero durability releases <b>once</b>. The removal happens inside the
 *       single accepted input that took it to zero, and the slot is empty for every later arrival.</li>
 * </ul>
 *
 * <p>Accessibility: a hold-to-struggle binding resends the same bounded packet at the same
 * server-limited rate. The ergonomics change and the effective speed does not, because the interval
 * and the alternation are enforced here.
 */
public final class EscapeService {

    /** The pre-Unbreaking roll, matching the source's 0.5. */
    public static final double BASE_BREAK_CHANCE = 0.5D;

    private static final Random RANDOM = new Random();

    private EscapeService() {
    }

    /** What one struggle input did. */
    public enum Outcome {
        /** Nothing is worn on that slot, or the subject has no physical state. */
        NOTHING_WORN,
        /** That definition cannot be struggled out of at all. */
        NOT_BREAKABLE,
        /** Refused: repeated, too soon, not alternating, or still on cooldown. */
        REFUSED,
        /** Accepted, and the roll failed. The restraint is unchanged. */
        MISSED,
        /** Accepted, the roll succeeded, one point of durability spent. */
        PROGRESS,
        /** The last point was spent: the restraint came off in this same input. */
        BROKEN
    }

    /**
     * @param outcome             what happened
     * @param remainingDurability what is left, for the subject's own HUD
     * @param released            true only on the one input that took it to zero
     */
    public record Attempt(Outcome outcome, int remainingDurability, boolean released) {

        public static Attempt of(Outcome outcome) {
            return new Attempt(outcome, 0, false);
        }
    }

    // --- the pure rules ---------------------------------------------------------------------------

    /**
     * The chance one accepted input spends a point of durability.
     *
     * <p>The source's curve, kept for parity: {@code ((invert01(level/9) * 0.7) + 0.3) * 0.5}, where
     * {@code invert01} is one minus the value clamped to 0..1. At Unbreaking 0 that is the flat 0.5;
     * at level 3 it is 0.383; from level 9 up it floors at 0.15 rather than reaching zero, so an
     * enchanted restraint is always eventually escapable. A restraint nobody can ever leave is a
     * support ticket, not a mechanic.
     */
    public static double breakChance(int unbreakingLevel) {
        if (unbreakingLevel <= 0) {
            return BASE_BREAK_CHANCE;
        }
        double d = unbreakingLevel / 3.0D;
        double inverted = 1.0D - Math.max(0.0D, Math.min(1.0D, d / 3.0D));
        return ((inverted * 0.7D) + 0.3D) * BASE_BREAK_CHANCE;
    }

    /**
     * How long a successful strain locks the slot for, in ticks.
     *
     * <p>The source's {@code random(0..19) + floor(20 * (1 + level/3))}, with the roll supplied so the
     * shape can be asserted. Unlike the source's, the value this stamps is a deadline that expires.
     */
    public static int cooldownTicks(int unbreakingLevel, int roll) {
        double multiplier = 1.0D + Math.max(0, unbreakingLevel) / 3.0D;
        return Math.max(0, roll) + (int) Math.floor(20.0D * multiplier);
    }

    // --- the server path ---------------------------------------------------------------------------

    /**
     * One struggle input from {@code subject} against their own {@code slot}.
     *
     * <p>Every check is here and none of them is on the client. The subject is the sender by
     * construction — this is only ever called with the player the connection resolved — so there is no
     * actor field to forge.
     */
    public static Attempt struggle(@Nullable ServerPlayer subject, long sessionId,
                                   @Nullable RestraintSlot slot, @Nullable StruggleInput input,
                                   int inputSeq) {
        if (subject == null || slot == null || input == null || subject.level().isClientSide()) {
            return Attempt.of(Outcome.NOTHING_WORN);
        }
        CrimeWorldData data = RestraintService.data(subject);
        if (data == null) {
            return Attempt.of(Outcome.NOTHING_WORN);
        }
        PhysicalRestraintState state = data.physicalRestraint(subject.getUUID());
        AppliedRestraint worn = state == null ? null : state.slot(slot).orElse(null);
        if (worn == null) {
            return Attempt.of(Outcome.NOTHING_WORN);
        }
        RestraintDefinition definition = worn.definition().orElse(null);
        if (definition == null || !definition.escape().struggleBreakable()) {
            return Attempt.of(Outcome.NOT_BREAKABLE);
        }
        long now = subject.level().getGameTime();
        if (!withinBudget(subject.getUUID(), now)) {
            return new Attempt(Outcome.REFUSED, worn.remainingDurability(), false);
        }
        EscapeTiming timing = timing(subject.getUUID(), worn.instanceId(), now);
        int minInterval = minWorkIntervalTicks();
        if (!actorMayAccept(subject.getUUID(), now, minInterval)
                || !timing.mayAccept(now, minInterval)) {
            return new Attempt(Outcome.REFUSED, worn.remainingDurability(), false);
        }
        WorkSession session = session(subject, sessionId, slot, state.revision(), now);
        if (session == null) {
            return Attempt.of(Outcome.REFUSED);
        }
        // Interval and cooldown live with the worn instance below. The session still owns replay and
        // alternation checks, but replacing it during a slot switch cannot reset those two clocks.
        if (!session.acceptInput(inputSeq, now, 0, input.index(), true)) {
            return new Attempt(Outcome.REFUSED, worn.remainingDurability(), false);
        }
        timing.accept(now);
        ACTOR_LAST_INPUT.put(subject.getUUID(), now);

        int unbreaking = unbreakingLevel(subject, worn);
        if (RANDOM.nextDouble() >= breakChance(unbreaking)) {
            return new Attempt(Outcome.MISSED, worn.remainingDurability(), false);
        }
        long cooldownUntil = now + cooldownTicks(unbreaking, RANDOM.nextInt(20));
        session.stampCooldown(cooldownUntil);
        timing.stampCooldown(cooldownUntil);

        AppliedRestraint strained = worn.damaged(1);
        if (strained.remainingDurability() > 0) {
            if (!data.putPhysicalRestraint(state.with(slot, strained))) {
                return new Attempt(Outcome.REFUSED, worn.remainingDurability(), false);
            }
            RestraintService.publish(subject, data);
            return new Attempt(Outcome.PROGRESS, strained.remainingDurability(), false);
        }
        // Zero: the removal happens inside this one accepted input, so no later arrival can release
        // the same restraint a second time -- the slot it names is already empty.
        if (!data.putPhysicalRestraint(state.with(slot, strained))) {
            return new Attempt(Outcome.REFUSED, worn.remainingDurability(), false);
        }
        RemovalService.Result removal = RemovalService.remove(subject, slot, RemovalService.Reason.BROKEN,
                null);
        SessionRegistry.server().cancel(session.id(), SessionCancelCause.TARGET_REMOVED);
        // One award for the one input that took it to zero, not one per strain (M5.11).
        if (removal.removed()) {
            dev.otectus.mcacrime.stat.CrimeStats.awardRestraint(subject, definition.id(),
                    dev.otectus.mcacrime.stat.CrimeStatIds.Kind.BROKEN);
        }
        return new Attempt(Outcome.BROKEN, 0, removal.removed());
    }

    /**
     * One struggle input the server supplies for itself, for a command or a menu action.
     *
     * <p>{@code /crime escape} and the Escape action in the self panel are not a second escape
     * mechanic: they are one input each against the same session, the same interval and the same
     * durability as a key press. The alternating input is generated here so that a player using the
     * command is not silently refused for "not alternating" -- the alternation exists to stop a held
     * key, and a command cannot be held.
     *
     * <p>The slot is the first one wearing something a subject can struggle out of, head to legs, so
     * a hooded and cuffed prisoner works at the hood first. There is no choice to make here: struggle
     * work on a specific slot is what the client's own packet is for.
     */
    public static Attempt struggleOnce(@Nullable ServerPlayer subject) {
        if (subject == null || subject.level().isClientSide()) {
            return Attempt.of(Outcome.NOTHING_WORN);
        }
        PhysicalRestraintState state = RestraintService.state(subject);
        if (state == null) {
            return Attempt.of(Outcome.NOTHING_WORN);
        }
        RestraintSlot target = null;
        boolean anythingWorn = false;
        for (RestraintSlot slot : RestraintSlot.values()) {
            AppliedRestraint worn = state.slot(slot).orElse(null);
            if (worn == null) {
                continue;
            }
            anythingWorn = true;
            if (worn.definition().map(definition -> definition.escape().struggleBreakable()).orElse(false)) {
                target = slot;
                break;
            }
        }
        if (target == null) {
            return Attempt.of(anythingWorn ? Outcome.NOT_BREAKABLE : Outcome.NOTHING_WORN);
        }
        int next = SERVER_INPUTS.merge(subject.getUUID(), 1, Integer::sum);
        StruggleInput input = next % 2 == 0 ? StruggleInput.LEFT : StruggleInput.RIGHT;
        return struggle(subject, 0L, target, input, next);
    }

    /** Sequence numbers for server-generated inputs, one counter per subject. */
    private static final java.util.Map<UUID, Integer> SERVER_INPUTS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The session this input belongs to, opening one if the subject has none.
     *
     * <p>Opened lazily rather than by a separate "begin struggling" packet: the first input is the
     * begin, which removes a whole class of desynchronisation between a client that thinks it has a
     * session and a server that has expired it. A session id that does not match is refused rather
     * than adopted, so a stale id from a previous hold cannot drive the current one.
     */
    @Nullable
    private static WorkSession session(ServerPlayer subject, long sessionId, RestraintSlot slot,
                                       long stateRevision, long now) {
        SessionRegistry registry = SessionRegistry.server();
        UUID actor = subject.getUUID();
        Optional<Session> existing = registry.forActor(actor);
        if (existing.isPresent()) {
            Session session = existing.get();
            if (!(session instanceof WorkSession work) || work.slot() != slot || !session.live(now)) {
                registry.cancel(session.id(), SessionCancelCause.REPLACED);
            } else if (sessionId != 0L && sessionId != session.id()) {
                return null; // an id from some other hold; refuse rather than adopt
            } else {
                return work;
            }
        }
        WorkSession opened = new WorkSession(registry.allocateId(), actor, actor, stateRevision,
                subject.level().dimension().location(), null, now + sessionTimeoutTicks(), slot);
        return registry.open(opened).map(WorkSession.class::cast).orElse(null);
    }

    /**
     * The Unbreaking level on the worn instance.
     *
     * <p>Read from the instance's own item snapshot, not from anything the client said and not from
     * whatever the subject happens to be holding. Enchanted restraints are M6's feature; the read is
     * here because durability is spent here, and a restraint's toughness must not depend on which
     * milestone asks about it.
     */
    private static int unbreakingLevel(LivingEntity subject, AppliedRestraint worn) {
        if (!worn.hasItem()) {
            return 0;
        }
        try {
            return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.UNBREAKING, worn.stack());
        } catch (RuntimeException e) {
            return 0; // an unreadable snapshot is an unenchanted restraint, never an unbreakable one
        }
    }

    /**
     * The per-player second-window ceiling from {@code restraints.escape.escapeMaxInputsPerSecond}.
     *
     * <p>Counted before the session is touched, and counting <em>refused</em> inputs too, which is the
     * point: the minimum interval bounds how fast a struggle can succeed, and this bounds how much
     * work a client can make the server do by ignoring it.
     */
    private static boolean withinBudget(UUID subject, long now) {
        Window window = BUDGET.get(subject);
        if (window == null || now - window.start >= 20L || now < window.start) {
            BUDGET.put(subject, new Window(now, 1));
            if (BUDGET.size() > MAX_TRACKED) {
                BUDGET.entrySet().removeIf(entry -> now - entry.getValue().start >= 20L);
            }
            return true;
        }
        if (window.count >= maxInputsPerSecond()) {
            return false;
        }
        window.count++;
        return true;
    }

    /** One player's current second. Mutable and transient; nothing here survives a restart. */
    private static final class Window {
        private final long start;
        private int count;

        private Window(long start, int count) {
            this.start = start;
            this.count = count;
        }
    }

    /** A ceiling, not a target: only struggling players are tracked and their windows expire. */
    private static final int MAX_TRACKED = 256;

    private static final java.util.Map<UUID, Window> BUDGET = new java.util.LinkedHashMap<>();

    /** Escape cadence follows the piece of gear even when changing slots replaces its WorkSession. */
    private record TimingKey(UUID actor, UUID instance) {
    }

    private static final class EscapeTiming {
        private long lastInputTick = Long.MIN_VALUE;
        private long cooldownUntilTick = Long.MIN_VALUE;
        private long lastTouchedTick;

        private EscapeTiming(long now) {
            lastTouchedTick = now;
        }

        private boolean mayAccept(long now, int minIntervalTicks) {
            lastTouchedTick = now;
            return now >= cooldownUntilTick
                    && (lastInputTick == Long.MIN_VALUE
                    || now - lastInputTick >= Math.max(0, minIntervalTicks));
        }

        private void accept(long now) {
            lastInputTick = now;
            lastTouchedTick = now;
        }

        private void stampCooldown(long untilTick) {
            cooldownUntilTick = Math.max(cooldownUntilTick, untilTick);
        }
    }

    private static final int MAX_TIMINGS = 512;
    private static final long TIMING_IDLE_TICKS = 1_200L;
    private static final java.util.Map<TimingKey, EscapeTiming> TIMINGS = new java.util.LinkedHashMap<>();
    private static final java.util.Map<UUID, Long> ACTOR_LAST_INPUT = new java.util.LinkedHashMap<>();

    /** Slot switching cannot turn one actor's minimum interval into one interval per slot. */
    private static boolean actorMayAccept(UUID actor, long now, int minIntervalTicks) {
        Long last = ACTOR_LAST_INPUT.get(actor);
        return last == null || now < last || now - last >= Math.max(0, minIntervalTicks);
    }

    private static EscapeTiming timing(UUID actor, UUID instance, long now) {
        TimingKey key = new TimingKey(actor, instance);
        EscapeTiming timing = TIMINGS.get(key);
        if (timing != null) {
            timing.lastTouchedTick = now;
            return timing;
        }
        if (TIMINGS.size() >= MAX_TIMINGS) {
            TIMINGS.entrySet().removeIf(entry -> now < entry.getValue().lastTouchedTick
                    || now - entry.getValue().lastTouchedTick >= TIMING_IDLE_TICKS);
            if (TIMINGS.size() >= MAX_TIMINGS) {
                java.util.Iterator<TimingKey> oldest = TIMINGS.keySet().iterator();
                if (oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
        }
        EscapeTiming opened = new EscapeTiming(now);
        TIMINGS.put(key, opened);
        return opened;
    }

    /** Forgets one removed restraint's cadence without disturbing another slot. */
    public static void forget(@Nullable UUID actor, @Nullable UUID instance) {
        if (actor != null && instance != null) {
            TIMINGS.remove(new TimingKey(actor, instance));
        }
    }

    /** Forgets all transient escape state for a player who left the server. */
    public static void forget(@Nullable UUID actor) {
        if (actor == null) {
            return;
        }
        BUDGET.remove(actor);
        SERVER_INPUTS.remove(actor);
        ACTOR_LAST_INPUT.remove(actor);
        TIMINGS.keySet().removeIf(key -> actor.equals(key.actor()));
    }

    /** Forgets every struggle budget. Server stop, and the start of each test. */
    public static void resetBudgets() {
        BUDGET.clear();
        SERVER_INPUTS.clear();
        ACTOR_LAST_INPUT.clear();
        TIMINGS.clear();
    }

    private static int maxInputsPerSecond() {
        try {
            return McaCrimeConfig.COMMON.escapeMaxInputsPerSecond.get();
        } catch (IllegalStateException e) {
            return 5;
        }
    }

    private static int minWorkIntervalTicks() {
        try {
            return McaCrimeConfig.COMMON.escapeMinWorkIntervalTicks.get();
        } catch (IllegalStateException e) {
            return 4;
        }
    }

    private static int sessionTimeoutTicks() {
        try {
            return McaCrimeConfig.COMMON.sessionTimeoutTicks.get();
        } catch (IllegalStateException e) {
            return 200;
        }
    }
}
