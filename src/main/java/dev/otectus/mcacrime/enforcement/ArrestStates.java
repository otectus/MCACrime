package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.state.CrimeCapabilities;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * The single server-authoritative chokepoint for every arrest-phase change — the structural twin of
 * {@link dev.otectus.mcacrime.engine.CrimeState}, and for the same reason.
 *
 * <p>Nothing else calls {@code ArrestState.setPhase}; it is package-private so nothing can. Every
 * transition is validated against {@link ArrestPhases#allows}, and an illegal edge is a logged no-op
 * rather than an exception, which turns a class of bug — two systems writing contradictory arrest
 * state — into a debug line. This is the lesson the deleted {@code REFUSED} map already taught: two
 * writers for one fact is how the confrontation loop happened in the first place.
 *
 * <p>All methods take a {@link ServerPlayer}. There is no path to write an arrest phase from a client
 * value, which is what makes surrender, release, and arrest cancellation unspoofable.
 */
public final class ArrestStates {

    private ArrestStates() {
    }

    // ------------------------------------------------------------------ reads

    /** This player's phase; {@code NONE} when they have no arrest or no capability. */
    public static ArrestPhase phaseOf(@Nullable ServerPlayer player) {
        if (player == null) {
            return ArrestPhase.NONE;
        }
        return CrimeCapabilities.get(player).map(PlayerCrimeData::arrestPhase).orElse(ArrestPhase.NONE);
    }

    /** The live arrest record, or null when there is none. Callers must not write the phase on it. */
    @Nullable
    public static ArrestState of(@Nullable ServerPlayer player) {
        if (player == null) {
            return null;
        }
        return CrimeCapabilities.get(player).map(PlayerCrimeData::getArrest).orElse(null);
    }

    /** Whether the player is physically restrained right now. */
    public static boolean isRestrained(@Nullable ServerPlayer player) {
        return ArrestPhases.isRestrained(phaseOf(player));
    }

    /** Whether an arrest is under way, from the answer to the challenge to the end of the sentence. */
    public static boolean inProgress(@Nullable ServerPlayer player) {
        return ArrestPhases.inProgress(phaseOf(player));
    }

    /** The guard who owns this arrest, or null when nobody does. */
    @Nullable
    public static UUID owningGuard(@Nullable ServerPlayer player) {
        ArrestState state = of(player);
        return state == null ? null : state.getGuard();
    }

    // ------------------------------------------------------------------ writes

    /**
     * Moves the player to {@code to} if the edge is legal.
     *
     * @return true when the phase is now {@code to}
     */
    public static boolean transition(ServerPlayer player, ArrestPhase to) {
        if (player == null || to == null) {
            return false;
        }
        boolean wasRestrained = ArrestPhases.isRestrained(phaseOf(player));
        boolean moved = applyTransition(player, to);
        if (!moved) {
            return false;
        }
        boolean nowRestrained = ArrestPhases.isRestrained(phaseOf(player));
        if (wasRestrained != nowRestrained) {
            // The single place a restraint can begin or end, so the single place its consequences
            // belong. Applying the movement penalty at the call sites instead was how a jailed player
            // kept it for the whole sentence: the escort completed, the phase moved to JAILED, and
            // nothing on that path remembered to take it off again. A modifier that leaks is permanent,
            // so the removal has to live where it cannot be forgotten.
            if (nowRestrained) {
                RestraintHandlers.onRestrained(player);
            } else {
                RestraintHandlers.onReleased(player);
            }
            RestraintSync.broadcast(player);
        }
        return moved;
    }

    private static boolean applyTransition(ServerPlayer player, ArrestPhase to) {
        return CrimeCapabilities.get(player).map(data -> {
            ArrestState state = data.getArrest();
            ArrestPhase from = state == null ? ArrestPhase.NONE : state.getPhase();
            if (!ArrestPhases.allows(from, to)) {
                McaCrime.LOGGER.debug("MCA: Crime refused an illegal arrest transition {} -> {} for {}",
                        from, to, player.getGameProfile().getName());
                return false;
            }
            if (to == ArrestPhase.NONE) {
                data.setArrest(null);
                return true;
            }
            if (state == null) {
                state = new ArrestState();
                data.setArrest(state);
            }
            state.setPhase(to);
            return true;
        }).orElse(false);
    }

    /**
     * Opens an arrest at {@code CONFRONTED}, recording who is asking and which encounter this is.
     *
     * <p>The encounter id is stored so a replayed or forged challenge response cannot re-enter the
     * surrender path against an encounter that has already been answered.
     */
    public static boolean begin(ServerPlayer player, @Nullable UUID guard, @Nullable UUID encounterId) {
        if (!transition(player, ArrestPhase.CONFRONTED)) {
            return false;
        }
        ArrestState state = of(player);
        if (state != null) {
            state.setGuard(guard);
            state.setEncounterId(encounterId);
        }
        return true;
    }

    /**
     * Enters {@code SURRENDERED}: the player has been taken, and the arrest owns them from here.
     *
     * <p>Named for the path that reaches it in practice, but it is the entry point for a
     * guard-initiated arrest too -- the phase means "the arrest has begun", not literally "they
     * clicked surrender". Writing it before the encounter closes is the whole point: it is what stops
     * a failing arrest falling back into a state the enforcement scan reads as a fresh suspect.
     */
    public static boolean surrendered(ServerPlayer player, @Nullable UUID guard, @Nullable UUID encounterId) {
        boolean had = of(player) != null;
        if (!transition(player, ArrestPhase.SURRENDERED)) {
            return false;
        }
        ArrestState state = of(player);
        if (state != null && (!had || state.getGuard() == null)) {
            state.setGuard(guard);
            state.setEncounterId(encounterId);
        }
        return true;
    }

    /**
     * Arms a surrendered player for the walk: destination, sentence, and the escort deadline.
     *
     * <p>The sentence is captured here rather than recomputed on arrival, because Heat decays while
     * the guard walks — recomputing would make a slow escort a discount.
     */
    public static void arm(ServerPlayer player, @Nullable JailAnchor anchor, long sentenceTicks,
                           long escortTimeoutTicks) {
        CrimeCapabilities.get(player).ifPresent(data -> {
            ArrestState state = data.getArrest();
            if (state == null) {
                return;
            }
            state.setAnchor(anchor);
            state.setSentenceTicks(sentenceTicks);
            state.setDeadlineOnlineTick(escortTimeoutTicks <= 0L
                    ? 0L
                    : data.getOnlineTicksLived() + escortTimeoutTicks);
            state.setBestAnchorDistanceSqr(Double.MAX_VALUE);
            state.setStuckStrikes(0);
            state.setLastSeenPos(player.blockPosition());
        });
    }

    /** Hands the escort to a different responder after the first one died, unloaded, or vanished. */
    public static void reassignGuard(ServerPlayer player, @Nullable UUID guard) {
        ArrestState state = of(player);
        if (state != null) {
            state.setGuard(guard);
            // The lead is drawn to whoever holds it, so a handover is a visual change even though the
            // restraint itself never lapsed.
            RestraintSync.broadcast(player);
            // A new escort gets a fresh run at the destination; keeping the old strikes would have the
            // replacement inherit its predecessor's failure and give up almost immediately.
            state.setBestAnchorDistanceSqr(Double.MAX_VALUE);
            state.setStuckStrikes(0);
        }
    }

    /** Drops the arrest entirely: the player is no longer the law's business. */
    public static void clear(ServerPlayer player) {
        transition(player, ArrestPhase.NONE);
    }

    /**
     * Ends a failed arrest in {@code RECOVERY} for {@code expiryOnlineTicks}.
     *
     * <p>The window exists so a guard that just failed to arrest somebody does not immediately open a
     * fresh screen and fail again. It is not a cooldown on the trigger: the phase is a state the
     * arrest genuinely reached, and it ends by transition when the window lapses.
     */
    public static void recover(ServerPlayer player, long expiryOnlineTicks) {
        if (!transition(player, ArrestPhase.RECOVERY)) {
            return;
        }
        CrimeCapabilities.get(player).ifPresent(data -> {
            ArrestState state = data.getArrest();
            if (state == null) {
                return;
            }
            state.setGuard(null);
            state.setEncounterId(null);
            state.setDeadlineOnlineTick(data.getOnlineTicksLived() + Math.max(1L, expiryOnlineTicks));
        });
    }

    /**
     * Expires a lapsed {@code RECOVERY}, edge-triggered from the decay handler.
     *
     * <p>Modelled on {@code CrimeState.tickResistingArrest}: the same clock, the same shape, and the
     * same reason for riding an existing per-player pump instead of adding a ticker.
     */
    public static void tickRecovery(ServerPlayer player, PlayerCrimeData data) {
        ArrestState state = data.getArrest();
        if (state == null || state.getPhase() != ArrestPhase.RECOVERY) {
            return;
        }
        if (state.expired(data.getOnlineTicksLived())) {
            data.setArrest(null);
        }
    }
}
