package dev.otectus.mcacrime.economy;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.enforcement.ArrestPhase;
import dev.otectus.mcacrime.enforcement.ArrestService;
import dev.otectus.mcacrime.enforcement.ArrestStates;
import dev.otectus.mcacrime.enforcement.GuardChallengeService;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.jail.JailRegion;
import dev.otectus.mcacrime.jail.JailRegistry;
import dev.otectus.mcacrime.jail.JailState;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.CrimeAttachments;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import org.jetbrains.annotations.Nullable;

import java.util.OptionalLong;

/**
 * {@code /crime surrender} (spec §6.3): near an authority, a Red/Wanted player can give themselves up.
 *
 * <p>Until 0.4.0 that sentence was a lie by omission. Surrendering dropped thirty Heat, shortened a
 * sentence the player was already serving, and cleared the escaped flag — and then nothing else
 * happened. It never called {@link ArrestService} or {@code JailService}, so the guard stood down, the
 * cases stayed open, and roughly ten ticks later a fresh challenge opened. Surrender was a Heat
 * discount you could collect by standing next to a friend.
 *
 * <p>It now hands the player to the law: an arrest takes them into custody and puts them in a cell for
 * a sentence worked out from what they actually did, and only then — once the law has accepted them
 * — is the Heat discount written. An arrest that refuses leaves the player exactly as it found them.
 *
 * <p>All Heat changes route through the {@link CrimeState} chokepoint.
 */
public final class SurrenderService {

    private SurrenderService() {
    }

    /**
     * Who actually takes the player in. Bound to {@link ArrestService#arrest} in play; a test binds it
     * to a lambda that reports an outcome, which is what makes the ordering around it — arrest first,
     * Heat second — assertable without a jail, a guard, and a world to put them in.
     */
    @FunctionalInterface
    public interface Arrester {
        ArrestService.Outcome arrest(ServerPlayer player, @Nullable LivingEntity responder,
                                     ArrestService.Cause cause, OptionalLong sentencingHeat);
    }

    /**
     * Everything a surrender reads and, if it succeeds, writes back. A record rather than a player, so
     * the ordering below can be asserted without a world, a guard, and a cell to put them in.
     */
    public record SurrenderState(long heat, boolean jailed, boolean escaped, boolean surrenderCredited,
                                 long lastSurrenderTick) {
    }

    /** What was decided before anybody was taken into custody. */
    public record Decision(boolean proceed, long sentencingHeat, String messageKey) {
    }

    /** What was written afterwards, if anything. */
    public record Commit(boolean applied, SurrenderState state, String messageKey) {
    }

    /**
     * Whether a surrender may proceed, and the Heat the sentence should be worked out from.
     *
     * <p>The reduced Heat is computed here and <em>not</em> written. That is the whole reorder: the old
     * code dropped thirty Heat, cleared the escaped flag and told the player they had surrendered
     * before the arrest was attempted, so an arrest that then refused — no cell, dead, spectating —
     * left a discount collected and nothing given for it.
     */
    public static Decision decide(SurrenderState before, boolean hasAuthority, long heatReduction,
                                  long jailableThreshold) {
        if (!hasAuthority) {
            return new Decision(false, before.heat(), "mcacrime.surrender.noauthority");
        }
        if (before.jailed()) {
            // Already serving. Surrendering again is not a second act of contrition, it is a second
            // discount on one sentence, which is what made surrender a Heat tap in the first place.
            return new Decision(false, before.heat(), "mcacrime.surrender.already_serving");
        }
        long reduced = Math.max(0L, before.heat() - heatReduction);
        // Ensure surrender drops Heat below the jailable threshold so the player becomes finable.
        long finableCeiling = Math.max(0L, jailableThreshold - 1L);
        return new Decision(true, Math.min(reduced, finableCeiling), "mcacrime.surrender.done");
    }

    /**
     * The state after the arrest answered. A refused arrest writes nothing at all — not the Heat, not
     * the surrender tick, not the escaped flag — because a surrender nobody accepted did not happen.
     */
    public static Commit commit(SurrenderState before, Decision decision, boolean arrestSucceeded,
                                long now) {
        if (!decision.proceed()) {
            return new Commit(false, before, decision.messageKey());
        }
        if (!arrestSucceeded) {
            return new Commit(false, before, "mcacrime.surrender.failed");
        }
        return new Commit(true, new SurrenderState(decision.sentencingHeat(), before.jailed(), false,
                true, now), "mcacrime.surrender.done");
    }

    /**
     * Which arrest outcomes count as the law having taken the player.
     *
     * <p>{@code NO_SENTENCE} counts: there was nothing to serve, the player walks, and the surrender
     * still happened. {@code REFUSED} and {@code NO_CELL} do not, and those are exactly the paths that
     * used to leave a discount behind.
     */
    public static boolean accepted(ArrestService.Outcome outcome) {
        return outcome == ArrestService.Outcome.ARRESTED
                || outcome == ArrestService.Outcome.ALREADY_SERVING
                || outcome == ArrestService.Outcome.NO_SENTENCE;
    }

    public static int surrender(ServerPlayer player) {
        return surrender(player, ArrestService::arrest);
    }

    /** The same surrender, with the arrest it ends in supplied rather than looked up. */
    public static int surrender(ServerPlayer player, Arrester arrester) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        if (!ServerMutationGate.allows(player.getServer())) {
            player.sendSystemMessage(Component.translatable("mcacrime.readonly"));
            return 0;
        }
        Authority authority = findAuthority(player, level, c.surrenderNearRadius.get());
        SurrenderState before = read(player);
        if (before.jailed() && before.escaped() && authority.present()) {
            // Return to the original sentence, without another heat reduction or sentence waiver.
            ArrestService.Outcome resumed = arrester.arrest(player, authority.responder(),
                    ArrestService.Cause.VOLUNTARY_SURRENDER, OptionalLong.empty());
            if (resumed == ArrestService.Outcome.ALREADY_SERVING) {
                GuardChallengeService.standDown(player);
                player.sendSystemMessage(Component.translatable("mcacrime.surrender.done"));
                return 1;
            }
            player.sendSystemMessage(Component.translatable("mcacrime.surrender.failed"));
            return 0;
        }
        Decision decision = decide(before, authority.present(), c.surrenderHeatReduction.get(),
                c.jailableHeatThreshold.get());
        if (!decision.proceed()) {
            if (!authority.present() && ArrestStates.phaseOf(player) == ArrestPhase.SURRENDERED) {
                // Nobody to surrender to, and a challenge screen put us in SURRENDERED on the way here
                // -- the guard died, despawned, or walked off between the click and this line. The
                // phase has to be handed back, or the player is left marked as surrendering to nobody
                // while the scan waits for an arrest that will never start.
                GuardChallengeService.standDownAndRecover(player, decision.messageKey());
            } else {
                player.sendSystemMessage(Component.translatable(decision.messageKey()));
            }
            return 0;
        }

        // The sentence is worked out from post-surrender Heat, so giving yourself up genuinely shortens
        // the term -- but the Heat itself is not written until the arrest has succeeded, so the number
        // travels as an argument rather than as a mutation somebody has to undo on failure.
        ArrestService.Outcome outcome = arrester.arrest(player, authority.responder(),
                ArrestService.Cause.VOLUNTARY_SURRENDER, OptionalLong.of(decision.sentencingHeat()));
        PlayerCrimeData data = CrimeAttachments.get(player);
        Commit commit = commit(before, decision, accepted(outcome), data.getOnlineTicksLived());
        if (!commit.applied()) {
            player.sendSystemMessage(Component.translatable(commit.messageKey()));
            return 0;
        }

        CrimeState.setHeat(player, commit.state().heat());
        data.setLastSurrenderTick(commit.state().lastSurrenderTick()); // a transient capture vulnerability (§8.2)
        JailState surrenderJail = data.getJail();
        if (surrenderJail != null) {
            surrenderJail.setEscaped(false); // stop resisting arrest
            surrenderJail.setSurrenderCredited(true);
        }
        // The sentence waiver is folded into the sentence itself by SentenceCalculator.afterSurrender
        // before the sentence is ever stored, so this service has stopped being a second writer of a
        // record JailService owns.

        CrimeNetwork.sendSelfStatus(player);
        player.sendSystemMessage(Component.translatable(commit.messageKey()));
        return 1;
    }

    /** The player's side of the surrender, read once so the decision above has one snapshot. */
    private static SurrenderState read(ServerPlayer player) {
        PlayerCrimeData data = CrimeAttachments.get(player);
        JailState jail = data.getJail();
        return new SurrenderState(CrimeState.getHeat(player), jail != null,
                jail != null && jail.isEscaped(), jail != null && jail.isSurrenderCredited(),
                data.getOnlineTicksLived());
    }

    /**
     * Who the player is surrendering to.
     *
     * <p>{@code responder} is null for a surrender at a jail with nobody around, which is still a valid
     * surrender — there is simply no escort. The distinction matters because an arrest needs to know
     * who made it.
     */
    private record Authority(@Nullable LivingEntity responder, boolean atJail) {
        boolean present() {
            return responder != null || atJail;
        }
    }

    /**
     * Finds the authority within {@code radius}, so the arrest that follows knows who made it rather
     * than re-scanning for one.
     *
     * <p>The nearby-Blue-player branch that used to be here is gone, as spec §13.3 asks in as many
     * words. A Blue player is not an authority: they cannot take custody, cannot escort anybody
     * anywhere, and cannot hold a sentence. All the branch did was let a Wanted player collect the
     * surrender Heat discount by standing next to a well-liked friend, repeatedly, with no arrest ever
     * following — which is exactly the loophole that made surrender look like it did nothing.
     *
     * <p>Responders are matched with {@link EntitySelectors#isResponder} rather than
     * {@code McaCompat.isGuard}, so a modded law entity an operator added through
     * {@code responderEntities} can be surrendered to as well as fled from.
     */
    private static Authority findAuthority(ServerPlayer player, ServerLevel level, double radius) {
        ArrestPhase phase = ArrestStates.phaseOf(player);
        java.util.UUID encounterGuard = phase == ArrestPhase.CONFRONTED || phase == ArrestPhase.SURRENDERED
                ? ArrestStates.owningGuard(player) : null;
        AABB box = player.getBoundingBox().inflate(radius);
        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box,
                EntitySelectors::isAvailableResponder)) {
            // Answering a challenge must not silently select a closer guard from another jurisdiction.
            if (encounterGuard != null && !encounterGuard.equals(candidate.getUUID())) continue;
            double distance = candidate.distanceToSqr(player);
            if (!candidate.isAlive() || !candidate.hasLineOfSight(player) || distance > radius * radius
                    || dev.otectus.mcacrime.enforcement.ResponderAssignments.isEscorting(
                    level.getServer(), candidate.getUUID(), player.getUUID())
                    || dev.otectus.mcacrime.enforcement.NpcCriminalPursuit.isAssignedElsewhere(
                    candidate.getUUID(), player.getUUID())) continue;
            if (distance < best) {
                best = distance;
                nearest = candidate;
            }
        }
        if (nearest != null) {
            return new Authority(nearest, false);
        }
        if (encounterGuard != null) return new Authority(null, false);
        ResourceLocation dim = level.dimension().location();
        if (player.getServer() != null) {
            for (JailAnchor anchor : JailRegistry.all(player.getServer())) {
                int r = Math.max(anchor.radius(), (int) Math.ceil(radius));
                if (JailRegion.contains(anchor.pos(), r, anchor.dim(), player.blockPosition(), dim)) {
                    return new Authority(null, true);
                }
            }
        }
        return new Authority(null, false);
    }
}
