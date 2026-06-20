package dev.otectus.mcacrime.detect;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.raid.Raid;
import net.minecraftforge.common.util.FakePlayer;

import java.util.Optional;

/**
 * The false-positive gate (spec §5.2): before any crime registers, every check below must pass. Ordered
 * cheapest/most-decisive first — the victim-type filter eliminates almost every {@code LivingHurtEvent}
 * in the game at near-zero cost. Returns the resolved offender, or empty meaning "not a crime".
 */
public final class CrimeGate {

    private CrimeGate() {
    }

    public static Optional<ServerPlayer> resolveOffender(LivingEntity victim, DamageSource source, ServerLevel level) {
        // 1. Victim must be a protected MCA entity (kills the overwhelming majority of hurt events cheaply).
        if (!McaCompat.isMcaVillager(victim)) {
            return Optional.empty();
        }
        // 2. Resolve the true attacker (arrow -> shooter). No responsible entity => environmental/indirect
        //    (lava, dispenser, fall, suffocation, mob-knockback-into-hazard) => not a crime.
        Entity responsible = source.getEntity();
        if (responsible == null) {
            return Optional.empty();
        }
        // 3. The attacker must be a real player.
        if (!(responsible instanceof ServerPlayer player)) {
            return Optional.empty();
        }
        // 4. FakePlayer filter — MUST follow the ServerPlayer cast (FakePlayer extends ServerPlayer).
        //    Excludes automation/machines (the headline anti-farm guard, spec §20).
        if (player instanceof FakePlayer) {
            return Optional.empty();
        }
        // 5. PvP hook: victims here are villagers, never players, so pvpCountsAsCrime never triggers.
        //    Left as a documented seam so PvP crimes slot in without reordering.
        // 6. Raid grace: an accidental cleave on a villager mid-raid is not a crime.
        if (McaCrimeConfig.COMMON.raidGrace.get()) {
            Raid raid = level.getRaidAt(victim.blockPosition());
            if (raid != null && raid.isActive()) {
                return Optional.empty();
            }
        }
        // 7. Self-defense: if the villager is already targeting the attacker, retaliation is lawful.
        if (McaCompat.getMcaTarget(victim).map(target -> target == player).orElse(false)) {
            return Optional.empty();
        }
        // 8. Legal-Target / owner / faction checks are future seams (Phase 3/5/7) — inert here.
        return Optional.of(player);
    }
}
