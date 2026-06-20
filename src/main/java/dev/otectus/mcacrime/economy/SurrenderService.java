package dev.otectus.mcacrime.economy;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.Band;
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

/**
 * {@code /crime surrender} (spec §6.3): near a guard, jail, or Blue player, a Red/Wanted player can
 * surrender to drop Heat (so they become finable), shorten an active sentence, and stop resisting (clears
 * the escaped/Legal-Target flag). All Heat changes route through the {@link CrimeState} chokepoint.
 */
public final class SurrenderService {

    private SurrenderService() {
    }

    public static int surrender(ServerPlayer player) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        if (!isNearAuthority(player, level, c.surrenderNearRadius.get())) {
            player.sendSystemMessage(Component.translatable("mcacrime.surrender.noauthority"));
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
                double pct = Math.max(0.0, Math.min(1.0, c.surrenderSentenceReductionPct.get() / 100.0));
                long cut = (long) (jail.getRemainingOnlineTicks() * (1.0 - pct));
                jail.setRemainingOnlineTicks(Math.max(1L, cut));
            }
        });

        CrimeNetwork.sendSelfStatus(player);
        player.sendSystemMessage(Component.translatable("mcacrime.surrender.done"));
        return 1;
    }

    private static boolean isNearAuthority(ServerPlayer player, ServerLevel level, double radius) {
        AABB box = player.getBoundingBox().inflate(radius);
        // (a) an MCA guard
        if (!level.getEntitiesOfClass(LivingEntity.class, box, McaCompat::isGuard).isEmpty()) {
            return true;
        }
        // (b) a Blue player
        double r2 = radius * radius;
        for (ServerPlayer other : level.players()) {
            if (other != player && other.distanceToSqr(player) <= r2 && CrimeState.getBand(other) == Band.BLUE) {
                return true;
            }
        }
        // (c) inside (or near) a jail region
        ResourceLocation dim = level.dimension().location();
        if (player.getServer() != null) {
            for (JailAnchor anchor : JailRegistry.all(player.getServer())) {
                int r = Math.max(anchor.radius(), (int) Math.ceil(radius));
                if (JailRegion.contains(anchor.pos(), r, anchor.dim(), player.blockPosition(), dim)) {
                    return true;
                }
            }
        }
        return false;
    }
}
