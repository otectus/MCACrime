package dev.otectus.mcacrime.mug;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.CrimeDetector;
import dev.otectus.mcacrime.detect.WitnessChecker;
import dev.otectus.mcacrime.economy.EmeraldCurrency;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mugging / robbery-over-murder (spec §8.6): a successful mug makes the villager "pay" (loot) for a moderate
 * theft-tier crime; if the villager is instead killed while being mugged, it is reclassified to the heavier
 * {@code mugging_murder} (no bonus loot, bigger Karma/Heat, bounty-eligible) so the default favors robbery
 * over murder. The "being mugged" marker is transient (non-persistent, swept) — same rationale as the harm
 * cooldown in {@code CrimeDetector}.
 */
public final class MuggingService {

    private record MugKey(UUID mugger, UUID victim) {
    }

    /** How long after a mug the victim's death still counts as a mugging-murder. */
    private static final long MUG_WINDOW_TICKS = 200L;
    private static final double MUG_REACH = 4.0;

    private static final Map<MugKey, Long> RECENT = new ConcurrentHashMap<>();

    private MuggingService() {
    }

    /** Attempts to mug the villager the player is looking at within reach. Returns 1 on success, 0 on a refusal. */
    public static int mug(ServerPlayer player) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        if (!c.enableMugging.get()) {
            return refuse(player, "mcacrime.mug.disabled");
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        LivingEntity target = nearestVillager(player, level);
        if (target == null) {
            return refuse(player, "mcacrime.mug.notarget");
        }
        long now = level.getGameTime();
        sweep(now);
        RECENT.put(new MugKey(player.getUUID(), target.getUUID()), now);

        int witnesses = WitnessChecker.countWitnesses(level, target);
        CrimeDetector.commitDirect(player, CrimeIds.THEFT, target, level, witnesses > 0, witnesses);
        EmeraldCurrency.INSTANCE.grant(player, c.muggingBaseLoot.get()); // the NPC "pays"
        player.sendSystemMessage(Component.translatable("mcacrime.mug.success", c.muggingBaseLoot.get()));
        return 1;
    }

    /**
     * True (consuming the marker) when {@code killer} was mid-mugging {@code victim} within the window — so
     * {@code CrimeDetector.onKill} reclassifies the death as {@code mugging_murder} (spec §8.6).
     */
    public static boolean wasMugging(UUID killer, UUID victim, long now) {
        Long stamp = RECENT.remove(new MugKey(killer, victim));
        return stamp != null && now - stamp < MUG_WINDOW_TICKS;
    }

    public static void onLogout(UUID player) {
        RECENT.keySet().removeIf(k -> k.mugger().equals(player));
    }

    @Nullable
    private static LivingEntity nearestVillager(ServerPlayer player, ServerLevel level) {
        AABB box = player.getBoundingBox().inflate(MUG_REACH);
        LivingEntity best = null;
        double bestSqr = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, McaCompat::isMcaVillager)) {
            double d = e.distanceToSqr(player);
            if (d < bestSqr && player.hasLineOfSight(e)) {
                bestSqr = d;
                best = e;
            }
        }
        return best;
    }

    private static void sweep(long now) {
        RECENT.entrySet().removeIf(entry -> now - entry.getValue() > MUG_WINDOW_TICKS * 4L);
    }

    private static int refuse(ServerPlayer player, String key) {
        player.sendSystemMessage(Component.translatable(key));
        return 0;
    }
}
