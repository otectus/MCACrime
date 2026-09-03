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
import dev.otectus.mcacrime.state.CrimeCapabilities;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import org.jetbrains.annotations.Nullable;

/**
 * {@code /crime surrender} (spec §6.3): near an authority, a Red/Wanted player can give themselves up.
 *
 * <p>Until 0.4.0 that sentence was a lie by omission. Surrendering dropped thirty Heat, shortened a
 * sentence the player was already serving, and cleared the escaped flag — and then nothing else
 * happened. It never called {@link ArrestService} or {@code JailService}, so the guard stood down, the
 * cases stayed open, and roughly ten ticks later a fresh challenge opened. Surrender was a Heat
 * discount you could collect by standing next to a friend.
 *
 * <p>It now hands the player to the law: Heat drops, and then an arrest takes them into custody and
 * puts them in a cell for a sentence worked out from what they actually did.
 *
 * <p>All Heat changes route through the {@link CrimeState} chokepoint.
 */
public final class SurrenderService {

    private SurrenderService() {
    }

    public static int surrender(ServerPlayer player) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        Authority authority = findAuthority(player, level, c.surrenderNearRadius.get());
        if (!authority.present()) {
            // Nobody to surrender to. If a challenge screen put us in SURRENDERED on the way here --
            // the guard died, despawned, or walked off between the click and this line -- the phase has
            // to be handed back, or the player is left marked as surrendering to nobody while the scan
            // waits for an arrest that will never start.
            if (ArrestStates.phaseOf(player) == ArrestPhase.SURRENDERED) {
                GuardChallengeService.standDownAndRecover(player, "mcacrime.surrender.noauthority");
            } else {
                player.sendSystemMessage(Component.translatable("mcacrime.surrender.noauthority"));
            }
            return 0;
        }

        long heat = CrimeState.getHeat(player);
        long reduced = Math.max(0L, heat - c.surrenderHeatReduction.get());
        // Ensure surrender drops Heat below the jailable threshold so the player becomes finable.
        long finableCeiling = Math.max(0L, c.jailableHeatThreshold.get() - 1L);
        CrimeState.setHeat(player, Math.min(reduced, finableCeiling));

        CrimeCapabilities.get(player).ifPresent(data -> {
            data.setLastSurrenderTick(data.getOnlineTicksLived()); // a transient capture vulnerability (§8.2)
            JailState jail = data.getJail();
            if (jail != null) {
                jail.setEscaped(false); // stop resisting arrest
            }
        });
        // The sentence waiver used to be applied here, by writing straight into the live JailState --
        // and then the arrest below recomputed the sentence from full charges and took the maximum of
        // the two, which put the whole quarter back. It is now folded into the sentence itself by
        // SentenceCalculator.afterSurrender before the sentence is ever stored, so this service has
        // stopped being a second writer of a record JailService owns.

        CrimeNetwork.sendSelfStatus(player);
        player.sendSystemMessage(Component.translatable("mcacrime.surrender.done"));

        // Deliberately after the Heat reduction: the sentence is calculated from post-surrender Heat,
        // so giving yourself up genuinely shortens the term. That is the whole mechanical payoff for
        // surrendering rather than running, and reordering these two lines would silently remove it.
        ArrestService.arrest(player, authority.responder(), ArrestService.Cause.VOLUNTARY_SURRENDER);
        return 1;
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
        AABB box = player.getBoundingBox().inflate(radius);
        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box,
                EntitySelectors::isResponder)) {
            double distance = candidate.distanceToSqr(player);
            if (distance < best) {
                best = distance;
                nearest = candidate;
            }
        }
        if (nearest != null) {
            return new Authority(nearest, false);
        }
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
