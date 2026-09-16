package dev.otectus.mcacrime.effect;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.ai.CrimeEntityTags;
import dev.otectus.mcacrime.detect.CrimeClassifier;
import dev.otectus.mcacrime.detect.DamageIncidentService;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.entity.SandBottleProjectile;
import dev.otectus.mcacrime.incident.ObservationSnapshot;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.mug.npc.NpcMuggingService;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What happens where a Sand Bottle lands (0.7.2 §13.3, §13.7, §14).
 *
 * <p>One impact runs through here once, in a fixed order that the rest of the feature depends on:
 *
 * <ol>
 *   <li>collect a <em>bounded</em> candidate set — the ceiling is passed to the collector, not
 *       applied to an unlimited list afterwards;</li>
 *   <li>reduce every candidate to plain facts and let {@link SandExposurePolicy} choose the victims;</li>
 *   <li>capture the self-defence context <b>before</b> any effect is applied, because applying the
 *       effect is what aborts the mugging that proves the throw was defensive (§14.2);</li>
 *   <li>apply the effects, recording for each victim whether it actually took hold;</li>
 *   <li>commit one incident per genuinely blinded, genuinely hostile victim, against launch-time
 *       observations rather than whatever is true now (§14.3).</li>
 * </ol>
 *
 * <p>No reputation call is made here, and no damage event is faked. The single commit per victim goes
 * through {@code IncidentService}, which already owns the integration hooks — emitting a standing
 * change here as well is precisely the double penalty §14.5 forbids.
 */
public final class SandExposureService {

    private SandExposureService() {
    }

    /** Runs the whole impact. Safe to call only once per projectile; the projectile enforces that. */
    public static void burst(ServerLevel level, SandBottleProjectile bottle, HitResult result) {
        Vec3 impact = result.getLocation();
        Entity struck = result instanceof EntityHitResult hit ? hit.getEntity() : null;
        Entity owner = bottle.getOwner();
        ServerPlayer thrower = owner instanceof ServerPlayer player ? player : null;
        long clock = SandRecoveryLedger.clock(level.getServer());

        SandExposurePolicy.Settings settings = settings(level);
        List<LivingEntity> nearby = collect(level, impact, settings, bottle);
        Map<UUID, LivingEntity> byId = new HashMap<>();
        List<SandExposurePolicy.Candidate> candidates = new ArrayList<>(nearby.size() + 1);
        if (struck instanceof LivingEntity directTarget) {
            byId.put(directTarget.getUUID(), directTarget);
            candidates.add(candidate(level, impact, directTarget, thrower, clock, true));
        }
        for (LivingEntity entity : nearby) {
            if (byId.containsKey(entity.getUUID())) {
                continue;
            }
            byId.put(entity.getUUID(), entity);
            candidates.add(candidate(level, impact, entity, thrower, clock, false));
        }

        List<SandExposurePolicy.Application> applications =
                SandExposurePolicy.select(candidates, settings);
        if (applications.isEmpty()) {
            return;
        }

        // Step 3. Before a single effect is applied: who among these victims is, right now, mugging
        // the thrower? One tick later the answer is gone, because the blindness ends the mugging.
        Map<UUID, Boolean> defensive = new LinkedHashMap<>();
        for (SandExposurePolicy.Application application : applications) {
            defensive.put(application.target(), isMuggingThrower(application.target(), thrower));
        }

        int recovery = McaCrimeConfig.COMMON.sandRecoveryTicks.get();
        List<SandIncidentPolicy.Exposure> exposures = new ArrayList<>(applications.size());
        for (SandExposurePolicy.Application application : applications) {
            LivingEntity victim = byId.get(application.target());
            boolean applied = victim != null && apply(victim, application, owner, clock, recovery);
            exposures.add(new SandIncidentPolicy.Exposure(application.target(), applied,
                    application.selfExposure(), Boolean.TRUE.equals(defensive.get(application.target()))));
        }

        SandIncidentPolicy.LaunchSnapshot launch = bottle.launchSnapshot();
        if (thrower == null || launch == null) {
            // A bottle with no identifiable owner blinds exactly as well and charges nobody. Inventing
            // an attacker for it is the one thing §13.6 says not to do.
            return;
        }
        ObservationSnapshot observations =
                new ObservationSnapshot(bottle.launchWitnesses(), launch.maskedAtLaunch(), launch.launchTick());
        for (SandIncidentPolicy.Charge charge : SandIncidentPolicy.charges(launch, exposures)) {
            LivingEntity victim = byId.get(charge.victim());
            if (victim == null) {
                continue;
            }
            Map<String, String> context = new LinkedHashMap<>();
            context.put(CrimeContext.DAMAGE_ATTRIBUTION, "projectile_or_indirect");
            DamageIncidentService.completeNonDamage(level, thrower, victim, charge.incidentId(),
                    CrimeClassifier.classifyHarm(victim), "sand_bottle", context, observations);
        }
    }

    /** The tuning for one burst: the operator's numbers, plus the server's own PvP rule. */
    private static SandExposurePolicy.Settings settings(ServerLevel level) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        return new SandExposurePolicy.Settings(c.sandRadius.get(), c.sandDirectDurationTicks.get(),
                c.sandSplashDurationTicks.get(), SandExposurePolicy.MIN_APPLICATION_TICKS,
                SandExposurePolicy.MAX_AFFECTED, SandExposurePolicy.MAX_CANDIDATES,
                c.sandAffectsPlayers.get(),
                level.getServer() != null && level.getServer().isPvpAllowed());
    }

    /**
     * The bounded spatial query.
     *
     * <p>The limit goes into {@code getEntities}, which stops walking sections once it has that many.
     * Building the whole list and trimming it would be the same answer at a very different cost in the
     * crowd this bound exists for.
     */
    private static List<LivingEntity> collect(ServerLevel level, Vec3 impact,
                                              SandExposurePolicy.Settings settings, Entity bottle) {
        List<LivingEntity> found = new ArrayList<>();
        AABB box = new AABB(impact, impact).inflate(Math.max(0.0D, settings.radius()));
        level.getEntities(EntityTypeTest.forClass(LivingEntity.class), box,
                entity -> entity != bottle && entity.isAlive() && !entity.isRemoved(),
                found, settings.maxCandidates());
        return found;
    }

    /** One entity, reduced to the facts {@link SandExposurePolicy} is written against. */
    private static SandExposurePolicy.Candidate candidate(ServerLevel level, Vec3 impact, LivingEntity entity,
                                                          @Nullable ServerPlayer thrower, long clock,
                                                          boolean direct) {
        boolean player = entity instanceof ServerPlayer;
        boolean isThrower = thrower != null && entity.getUUID().equals(thrower.getUUID());
        boolean creativeOrSpectator = entity instanceof Player p && (p.isSpectator() || p.isCreative());
        boolean teamProtected = player && thrower != null && !isThrower
                && !thrower.canHarmPlayer((Player) entity);
        double distance = Math.sqrt(entity.getBoundingBox().distanceToSqr(impact));
        return new SandExposurePolicy.Candidate(entity.getUUID(), distance, direct, player, isThrower,
                entity.getType().is(CrimeEntityTags.SAND_IMMUNE),
                // Deliberately the configured-extras half, not isProtected: every MCA villager is a
                // protected *victim*, and treating that as immunity would make the item inert.
                EntitySelectors.isConfiguredProtected(entity),
                creativeOrSpectator,
                occluded(level, impact, entity),
                teamProtected,
                SandBlindness.isBlinded(entity),
                !SandRecoveryLedger.canApply(entity, clock));
    }

    /** A wall between the burst and a pair of eyes stops the sand, rather than blinding through it. */
    private static boolean occluded(ServerLevel level, Vec3 impact, LivingEntity entity) {
        return level.clip(new ClipContext(impact, entity.getEyePosition(), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, entity)).getType() != HitResult.Type.MISS;
    }

    /**
     * Applies one effect, and reports whether it genuinely took hold.
     *
     * <p>{@code addEffect} is the veto point: NeoForge's applicability event and the entity's own
     * immunity both answer here, and a false is a victim that was never blinded. Recording that
     * honestly is what stops a vetoed target being reported as a completed blinding (SAND-17).
     */
    private static boolean apply(LivingEntity victim, SandExposurePolicy.Application application,
                                 @Nullable Entity source, long clock, int recoveryTicks) {
        MobEffectInstance instance = new MobEffectInstance(CrimeEffects.SAND_BLINDED,
                application.durationTicks(), 0, false, true, true);
        if (!victim.addEffect(instance, source)) {
            return false;
        }
        SandRecoveryLedger.applied(victim, clock, application.durationTicks(), recoveryTicks);
        CrimeDebug.crime("sand blinded {} for {} ticks ({})", victim.getUUID(), application.durationTicks(),
                application.direct() ? "direct" : "splash");
        return true;
    }

    /**
     * Whether this victim is mugging the thrower at this instant (§14.2).
     *
     * <p>Read before the effects land. A blinded thief's mugging aborts on the next tick, and by then
     * the session that made the throw defensive no longer exists to be consulted.
     */
    private static boolean isMuggingThrower(UUID victimId, @Nullable ServerPlayer thrower) {
        if (thrower == null) {
            return false;
        }
        return NpcMuggingService.sessionForThief(victimId)
                .map(session -> thrower.getUUID().equals(session.victimId()))
                .orElse(false);
    }

}
