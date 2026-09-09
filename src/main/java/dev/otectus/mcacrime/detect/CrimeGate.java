package dev.otectus.mcacrime.detect;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.enforcement.OutlawResolver;
import dev.otectus.mcacrime.enforcement.OutlawStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.util.FakePlayer;

import java.util.Optional;

/**
 * The false-positive gate (spec §5.2): before any crime registers, every check below must pass. Ordered
 * cheapest/most-decisive first — the victim-type filter eliminates almost every {@code LivingHurtEvent}
 * in the game at near-zero cost. Returns the resolved offender, or empty meaning "not a crime".
 *
 * <h2>Two settings that used to be comments</h2>
 *
 * <p>{@code pvpCountsAsCrime} shipped in 0.1.0 with a comment in this file saying it could never fire,
 * because the victim filter admitted only villagers. It now admits players too when the setting is on,
 * which is what the key always claimed to do.
 *
 * <p>{@code protectedEntities} was validated by {@code /crime validate} and read by nothing. The victim
 * filter now goes through {@link EntitySelectors}, so an entity a server owner adds to that list is
 * genuinely protected rather than merely accepted by the config parser.
 */
public final class CrimeGate {

    private CrimeGate() {
    }

    public static Optional<ServerPlayer> resolveOffender(LivingEntity victim, DamageSource source, ServerLevel level) {
        return resolveOffender(victim, source, level, false);
    }

    /**
     * @param lethal whether this blow kills the victim. It only matters for a player victim: harming a
     *               Red player and killing one are separately configurable, and collapsing them would
     *               make {@code allowKillingRed} unreachable.
     */
    public static Optional<ServerPlayer> resolveOffender(LivingEntity victim, DamageSource source,
                                                         ServerLevel level, boolean lethal) {
        // 1. Victim must be protected: an MCA villager, a configured extra, or -- when PvP crime is
        //    enabled -- a real player. This kills the overwhelming majority of hurt events cheaply.
        boolean playerVictim = victim instanceof ServerPlayer && !(victim instanceof FakePlayer);
        boolean pvp = playerVictim && McaCrimeConfig.COMMON.pvpCountsAsCrime.get();
        if (!pvp && !EntitySelectors.isProtected(victim)) {
            return Optional.empty();
        }
        // 2. Resolve the true attacker (arrow -> shooter). No responsible entity => environmental/indirect
        //    (lava, dispenser, fall, suffocation, mob-knockback-into-hazard) => not a crime.
        Entity responsible = responsibleActor(source);
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
        // 5. Nobody commits a crime against themselves.
        if (victim == player) {
            return Optional.empty();
        }
        // 6. PvP: force against a Legal Target is lawful, so attacking a Wanted player is not itself a
        //    crime. Killing one is a separate permission, because "you may stop them" and "you may end
        //    them" are different grants and a server should be able to give the first without the second.
        if (playerVictim) {
            ServerPlayer victimPlayer = (ServerPlayer) victim;
            OutlawStatus status = OutlawResolver.resolve(victimPlayer);
            if (status.lawfulCombatTarget() && (!lethal || status.lethalForceLawful())) {
                return Optional.empty();
            }
        }
        // Combat provenance and limited raid grace are evaluated after damage finality.
        // An AI attack target is never evidence that its opponent did not provoke it.
        return Optional.of(player);
    }

    /** Projectile owners are supplied by DamageSource; a loaded tame animal can name its owner. */
    public static Entity responsibleActor(DamageSource source) {
        Entity actor = source.getEntity();
        if (actor instanceof net.minecraft.world.entity.TamableAnimal pet
                && pet.getOwner() instanceof ServerPlayer owner) return owner;
        return actor;
    }
}
