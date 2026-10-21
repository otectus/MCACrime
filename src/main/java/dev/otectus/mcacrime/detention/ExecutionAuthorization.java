package dev.otectus.mcacrime.detention;

import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The permission a guillotine needs before it may take a life (0.7.5 M4.10, §3.19).
 *
 * <p>Three guarantees, and each is a line of code here rather than a promise in a document.
 *
 * <ol>
 *   <li><b>Never automatic.</b> An authorisation is created by {@link #arm} and by nothing else, and
 *       {@code arm} takes an explicit actor. No timer, comparator, redstone edge, packet or scheduled
 *       task reaches it, and the delay window on its own never kills anybody: it only decides how long
 *       an authorisation somebody deliberately created remains usable.</li>
 *   <li><b>Only the condemned.</b> {@link #condemned} is the gate, and its answer comes from the legal
 *       system through {@link CondemnedSource}. The default source says nobody is condemned, so until
 *       M6.6 adds the capital sentence kind a guillotine detains and releases and never executes —
 *       which is the correct behaviour for a build with no capital sentence in it, not a stub.</li>
 *   <li><b>Cleared, never expired into a death.</b> Pardon, commutation, rescue, escape, guard death,
 *       device destruction and chunk unload all {@link #clear} the record, and every one of them
 *       returns the subject to <i>condemned in custody</i> rather than to freedom.</li>
 * </ol>
 *
 * <p>Memory-only, deliberately. A restart is a chunk unload for every chunk at once, and the clearing
 * rule for a chunk unload is "return them to custody" — so an authorisation that survived a restart
 * would be the one clearing rule the table could not honour. A guillotine's blade delay <em>is</em>
 * persisted with the block, but a blade still falling after a reload has no order to carry out: every
 * order has its own {@link Pending#orderId}, the device remembers only the id it armed (in memory), and
 * the strike compares the two, so an old fall can never borrow a newer order for the same subject.
 *
 * <p><b>Timing (0.8.0).</b> An order is usable for {@link #windowTicks()}: the configured
 * {@code executionDelayTicks}, stretched when necessary to cover the blade's own fall
 * ({@code guillotineActivationDelayTicks}) plus {@link #BLADE_MARGIN_TICKS}. Before 0.8.0 a blade delay
 * at least as long as the window -- both legal settings -- let every order expire mid-fall, so neither
 * the lever nor a guard ever struck. {@code ConfigValidator} reports the stretch so the operator can see
 * the effective value.
 */
public final class ExecutionAuthorization {

    /**
     * One armed device, waiting.
     *
     * @param subject    who is to be executed
     * @param dimension  where the device is
     * @param device     the device's canonical position
     * @param actor      the player or guard who gave the order; the death is attributed to them
     * @param armedAt    the game time the order was given
     * @param expiresAt  the game time the window closes at
     * @param generation the subject's physical generation when the order was given, so an order
     *                   cannot outlive the custody episode it belongs to
     * @param orderId    this order's own identity; a device strikes only for the order it armed
     * @param detentionId the occupancy the subject was in when the order was given, or null when the
     *                   arming path had none to name; a release and re-detention is a new occupancy
     */
    public record Pending(UUID subject, @Nullable ResourceLocation dimension, BlockPos device,
                          UUID actor, long armedAt, long expiresAt, long generation,
                          UUID orderId, @Nullable UUID detentionId) {

        public Pending {
            device = device == null ? BlockPos.ZERO : device.immutable();
            orderId = orderId == null ? UUID.randomUUID() : orderId;
        }

        public Pending(UUID subject, @Nullable ResourceLocation dimension, BlockPos device, UUID actor,
                       long armedAt, long expiresAt, long generation) {
            this(subject, dimension, device, actor, armedAt, expiresAt, generation, UUID.randomUUID(), null);
        }

        /** Whether the window is still open at {@code now}. */
        public boolean live(long now) {
            return now < expiresAt;
        }

        /** Whether this order names this exact device. */
        public boolean at(@Nullable ResourceLocation dim, @Nullable BlockPos pos) {
            return pos != null && device.equals(pos)
                    && (dimension == null || dim == null || dimension.equals(dim));
        }
    }

    /** Why an authorisation ended. Every value returns the subject to custody, never to freedom. */
    public enum ClearReason {
        PARDONED,
        COMMUTED,
        RESCUED,
        ESCAPED,
        GUARD_DIED,
        DEVICE_DESTROYED,
        CHUNK_UNLOADED,
        CARRIED_OUT,
        EXPIRED
    }

    /**
     * Where "is this subject under a capital sentence?" is answered.
     *
     * <p>An interface rather than a direct call into {@code ledger/}, because the sentence kind
     * arrives in M6.6 and this milestone owns only the device half. The default answers no for
     * everybody, which is the truthful answer in a build that cannot yet pass a capital sentence.
     */
    @FunctionalInterface
    public interface CondemnedSource {
        boolean condemned(@Nullable MinecraftServer server, @Nullable UUID subject);
    }

    private static final CondemnedSource NOBODY = (server, subject) -> false;

    private static volatile CondemnedSource source = NOBODY;

    private static final Map<UUID, Pending> PENDING = new LinkedHashMap<>();

    private ExecutionAuthorization() {
    }

    /**
     * Installs the legal system's answer. Called once, by M6.6.
     *
     * <p>A null source restores "nobody is condemned", which is also what a test resets to: leaving a
     * previous test's source installed would make a later one pass for the wrong reason.
     */
    public static synchronized void bind(@Nullable CondemnedSource legal) {
        source = legal == null ? NOBODY : legal;
    }

    /** Whether {@code subject} is under a live capital sentence. */
    public static boolean condemned(@Nullable MinecraftServer server, @Nullable UUID subject) {
        try {
            return subject != null && source.condemned(server, subject);
        } catch (RuntimeException failed) {
            return false; // a broken legal source must not authorise a death
        }
    }

    // --- configuration -----------------------------------------------------------------------------

    /** {@code sentencing.capitalPunishment.enabled}, defaulting to the documented true. */
    public static boolean featureEnabled() {
        try {
            return McaCrimeConfig.COMMON.capitalPunishmentEnabled.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return true;
        }
    }

    /** {@code sentencing.capitalPunishment.executionDelayTicks}, defaulting to the documented 1200. */
    public static int delayTicks() {
        try {
            return McaCrimeConfig.COMMON.executionDelayTicks.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return 1200;
        }
    }

    /** {@code detention.guillotineActivationDelayTicks}: how long the blade takes to land once released. */
    public static int bladeTicks() {
        try {
            return McaCrimeConfig.COMMON.guillotineActivationDelayTicks.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return 5;
        }
    }

    /**
     * Ticks an order must outlast the blade by. Three covers the release tick, the landing tick and the
     * one-tick difference between the server tick that arms a guard's order and the level tick that
     * moves the blade, which is the boundary {@code CondemnedEscortService.dropAt} plans around.
     */
    public static final int BLADE_MARGIN_TICKS = 3;

    /** How long an armed order stays usable on this server. See the class documentation. */
    public static int windowTicks() {
        return effectiveWindow(delayTicks(), bladeTicks());
    }

    /**
     * The window an order actually gets: the configured one, or the blade's fall plus the margin when
     * that is longer. Pure, so the boundary is a unit test.
     */
    public static int effectiveWindow(int configuredWindow, int bladeTicks) {
        return Math.max(Math.max(1, configuredWindow), Math.max(1, bladeTicks) + BLADE_MARGIN_TICKS);
    }

    /** Whether the configured window had to be stretched to cover the blade. Diagnostics. */
    public static boolean windowStretched(int configuredWindow, int bladeTicks) {
        return effectiveWindow(configuredWindow, bladeTicks) > Math.max(1, configuredWindow);
    }

    /** {@code sentencing.capitalPunishment.guardMayExecute}. */
    public static boolean guardMayExecute() {
        try {
            return McaCrimeConfig.COMMON.guardMayExecute.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return true;
        }
    }

    // --- the order ----------------------------------------------------------------------------------

    /** Why an order was refused. Each is a distinct message rather than a silent no-op. */
    public enum Refusal {
        NONE,
        FEATURE_DISABLED,
        NOT_CONDEMNED,
        NO_ACTOR,
        ACTOR_NOT_PERMITTED,
        NO_DEVICE,
        ALREADY_ARMED
    }

    /** The lang key that explains one refusal, enumerated by the coverage test. */
    public static String messageKey(@Nullable Refusal refusal) {
        if (refusal == null) {
            return "mcacrime.execution.refused.no_order";
        }
        return switch (refusal) {
            case NONE -> "mcacrime.execution.armed";
            case FEATURE_DISABLED -> "mcacrime.execution.refused.disabled";
            case NOT_CONDEMNED -> "mcacrime.execution.refused.not_condemned";
            case NO_ACTOR -> "mcacrime.execution.refused.no_order";
            case ACTOR_NOT_PERMITTED -> "mcacrime.execution.refused.not_permitted";
            case NO_DEVICE -> "mcacrime.execution.refused.no_device";
            case ALREADY_ARMED -> "mcacrime.execution.refused.already_armed";
        };
    }

    /**
     * Whether an order would stand, as a pure function of the facts.
     *
     * <p>Pure so the guarantee "no timer, redstone signal, packet or scheduled task can carry out the
     * sentence" is assertable: every one of those paths fails {@code actorIsPlayer || actorIsGuard},
     * because none of them has an actor at all.
     */
    public static Refusal check(boolean enabled, boolean condemned, boolean actorIsPlayer,
                                boolean actorIsGuard, boolean guardMayExecute, boolean devicePresent,
                                boolean alreadyArmed) {
        if (!enabled) {
            return Refusal.FEATURE_DISABLED;
        }
        if (!actorIsPlayer && !actorIsGuard) {
            return Refusal.NO_ACTOR;
        }
        if (actorIsGuard && !actorIsPlayer && !guardMayExecute) {
            return Refusal.ACTOR_NOT_PERMITTED;
        }
        if (!devicePresent) {
            return Refusal.NO_DEVICE;
        }
        if (!condemned) {
            return Refusal.NOT_CONDEMNED;
        }
        if (alreadyArmed) {
            return Refusal.ALREADY_ARMED;
        }
        return Refusal.NONE;
    }

    /**
     * Records a deliberate order to execute {@code subject} at {@code device}.
     *
     * @param actorIsPlayer whether the order came from a player
     * @param actorIsGuard  whether it came from an on-duty enforcement guard
     * @return the pending record, or empty with the refusal available from {@link #lastRefusal()}
     */
    public static synchronized Optional<Pending> arm(@Nullable MinecraftServer server,
                                                     @Nullable UUID subject, @Nullable UUID actor,
                                                     boolean actorIsPlayer, boolean actorIsGuard,
                                                     @Nullable ResourceLocation dimension,
                                                     @Nullable BlockPos device, long now,
                                                     long generation) {
        return arm(server, subject, actor, actorIsPlayer, actorIsGuard, dimension, device, now, generation,
                null);
    }

    /** As above, naming the occupancy the order belongs to, which the strike then requires unchanged. */
    public static synchronized Optional<Pending> arm(@Nullable MinecraftServer server,
                                                     @Nullable UUID subject, @Nullable UUID actor,
                                                     boolean actorIsPlayer, boolean actorIsGuard,
                                                     @Nullable ResourceLocation dimension,
                                                     @Nullable BlockPos device, long now,
                                                     long generation, @Nullable UUID detentionId) {
        Refusal refusal = check(featureEnabled(), condemned(server, subject), actorIsPlayer, actorIsGuard,
                guardMayExecute(), device != null && subject != null && actor != null,
                subject != null && PENDING.containsKey(subject));
        lastRefusal = refusal;
        if (refusal != Refusal.NONE) {
            return Optional.empty();
        }
        Pending pending = new Pending(subject, dimension, device, actor, now,
                now + windowTicks(), Math.max(1L, generation), UUID.randomUUID(), detentionId);
        PENDING.put(subject, pending);
        return Optional.of(pending);
    }

    private static volatile Refusal lastRefusal = Refusal.NONE;

    /** Why the last {@link #arm} was refused. Diagnostics and the device's message. */
    public static Refusal lastRefusal() {
        return lastRefusal;
    }

    /** The live order for this subject, if the window is still open at {@code now}. */
    public static synchronized Optional<Pending> pending(@Nullable UUID subject, long now) {
        Pending pending = subject == null ? null : PENDING.get(subject);
        if (pending == null) {
            return Optional.empty();
        }
        if (!pending.live(now)) {
            PENDING.remove(subject);
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    /**
     * Whether this device may take this subject's life right now.
     *
     * <p>The single question the guillotine asks. Everything else — the sentence, the ceremony, the
     * actor's authority — has already been decided by the time an authorisation exists.
     */
    public static boolean authorised(@Nullable UUID subject, @Nullable ResourceLocation dimension,
                                     @Nullable BlockPos device, long now) {
        return pending(subject, now).filter(p -> p.at(dimension, device)).isPresent();
    }

    /** Why a strike would or would not land, checked at the moment of the blow (0.8.0). */
    public enum StrikeVerdict {
        /** Every fact still holds: one attributed damage attempt may be made. */
        STRIKE,
        /** No live order for this subject, or the window closed. */
        NO_ORDER,
        /** There is an order, but not the one this device armed: a stale fall or a newer order. */
        DIFFERENT_ORDER,
        /** The order names another device or dimension. */
        WRONG_DEVICE,
        /** The order was given by somebody other than the actor the device would attribute. */
        WRONG_ACTOR,
        /** The subject is no longer the occupant this order was given for. */
        OCCUPANCY_CHANGED,
        /** The capital sentence is gone: pardoned, commuted, or resolved. */
        NOT_CONDEMNED,
        /** The feature or the guillotine's own switch is off. */
        DISABLED,
        /** Nobody alive is there to strike. */
        NO_TARGET
    }

    /**
     * The strike-time check as a pure function of the facts the device gathered.
     *
     * <p>Order, actor and occupancy are compared by identity, never by "is there some order for this
     * subject": that is what keeps a blade persisted from before an unload, or dropped for an earlier
     * occupancy, from carrying out an order somebody gave later.
     *
     * @param order           the live order for this subject, or null
     * @param armedOrder      the order id the device recorded when the blade was released, or null
     * @param occupantId      who the detention at this device holds now, or null
     * @param occupancyId     the id of that detention, or null
     * @param occupancyGeneration that detention's occupant generation
     */
    public static StrikeVerdict strikeVerdict(boolean enabled, boolean targetAlive, boolean condemned,
                                              @Nullable Pending order, @Nullable UUID armedOrder,
                                              @Nullable UUID subject, @Nullable UUID actor,
                                              @Nullable ResourceLocation dimension, @Nullable BlockPos device,
                                              @Nullable UUID occupantId, @Nullable UUID occupancyId,
                                              long occupancyGeneration) {
        if (!enabled) {
            return StrikeVerdict.DISABLED;
        }
        if (!targetAlive || subject == null) {
            return StrikeVerdict.NO_TARGET;
        }
        if (order == null) {
            return StrikeVerdict.NO_ORDER;
        }
        if (armedOrder == null || !armedOrder.equals(order.orderId()) || !subject.equals(order.subject())) {
            return StrikeVerdict.DIFFERENT_ORDER;
        }
        if (!order.at(dimension, device)) {
            return StrikeVerdict.WRONG_DEVICE;
        }
        if (actor == null || !actor.equals(order.actor())) {
            return StrikeVerdict.WRONG_ACTOR;
        }
        if (occupantId == null || !occupantId.equals(subject)
                || (order.detentionId() != null && !order.detentionId().equals(occupancyId))
                || Math.max(1L, occupancyGeneration) != order.generation()) {
            return StrikeVerdict.OCCUPANCY_CHANGED;
        }
        if (!condemned) {
            return StrikeVerdict.NOT_CONDEMNED;
        }
        return StrikeVerdict.STRIKE;
    }

    /** Ends one order. Idempotent; the subject stays condemned and in custody. */
    public static synchronized Optional<Pending> clear(@Nullable UUID subject, ClearReason reason) {
        return Optional.ofNullable(subject == null ? null : PENDING.remove(subject));
    }

    /** Ends every order naming one device: its destruction, or its chunk unloading. */
    public static synchronized int clearAt(@Nullable ResourceLocation dimension, @Nullable BlockPos device,
                                           ClearReason reason) {
        if (device == null) {
            return 0;
        }
        List<UUID> doomed = new ArrayList<>();
        PENDING.forEach((subject, pending) -> {
            if (pending.at(dimension, device)) {
                doomed.add(subject);
            }
        });
        doomed.forEach(PENDING::remove);
        return doomed.size();
    }

    /** Ends every order given by one actor: the guard who ordered it died or logged out. */
    public static synchronized int clearByActor(@Nullable UUID actor, ClearReason reason) {
        if (actor == null) {
            return 0;
        }
        List<UUID> doomed = new ArrayList<>();
        PENDING.forEach((subject, pending) -> {
            if (actor.equals(pending.actor())) {
                doomed.add(subject);
            }
        });
        doomed.forEach(PENDING::remove);
        return doomed.size();
    }

    /** Drops orders whose window closed. Called from the server tick; kills nobody by construction. */
    public static synchronized int expire(long now) {
        List<UUID> doomed = new ArrayList<>();
        PENDING.forEach((subject, pending) -> {
            if (!pending.live(now)) {
                doomed.add(subject);
            }
        });
        doomed.forEach(PENDING::remove);
        return doomed.size();
    }

    /** Every live order. Diagnostics only. */
    public static synchronized List<Pending> all() {
        return new ArrayList<>(PENDING.values());
    }

    /** Forgets everything. Server stop, and every test's setup. */
    public static synchronized void clearAll() {
        PENDING.clear();
        lastRefusal = Refusal.NONE;
    }
}
