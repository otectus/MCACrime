package dev.otectus.mcacrime.detect;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.relationship.FamilyGraph;
import dev.otectus.mcacrime.relationship.FamilyLoyalty;
import dev.otectus.mcacrime.relationship.FamilyTier;
import dev.otectus.mcacrime.engine.CrimeState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Decides who witnessed a crime (spec §3.5): any MCA villager/guard (responder) within
 * {@code witnessRadius} that has line of sight to the victim. Event-driven and bounded — called once
 * per detected crime, never per tick (spec §20). Mirrors vanilla gossip's line-of-sight rule.
 *
 * <p>The scan now captures <em>identities</em>, not just a count, because who saw a crime decides who
 * may later speak about it. The identities are snapshotted here, once, and stored on the record; they
 * are never re-derived, since a villager who wandered past afterwards did not witness anything.
 */
public final class WitnessChecker {

    private WitnessChecker() {
    }

    /**
     * Number of responder NPCs that can see the victim within the witness radius (the victim excluded).
     *
     * @deprecated use {@link #resolve(ServerLevel, LivingEntity)}, which also captures who they were.
     *         Kept so existing callers keep working; it simply discards the identities.
     */
    @Deprecated
    public static int countWitnesses(ServerLevel level, LivingEntity victim) {
        return resolve(level, victim).count();
    }

    /**
     * The witnesses to a crime against {@code victim}: identities, whether it was witnessed at all,
     * and the true crowd size even when the stored set is capped.
     *
     * <p>Spectators are excluded explicitly. An MCA villager is never in spectator mode today, but
     * the filter costs nothing and stops a future or modded case from producing a witness who cannot
     * physically be there.
     */
    public static WitnessResult resolve(ServerLevel level, LivingEntity victim) {
        return resolve(level, null, victim);
    }

    /**
     * The same scan with the offender known, which is what family loyalty needs: a relative can only
     * decline to report somebody, and the two-argument form has nobody to be related to.
     */
    public static WitnessResult resolve(ServerLevel level, @Nullable LivingEntity offender, LivingEntity victim) {
        if (level == null || victim == null) {
            return WitnessResult.none();
        }
        double r = McaCrimeConfig.COMMON.witnessRadius.get() * lookoutMultiplier(level, offender);
        AABB box = victim.getBoundingBox().inflate(r);
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != victim && dev.otectus.mcacrime.ai.NpcAwareness.isAwake(e) && !e.isSpectator() && McaCompat.isMcaVillager(e));

        List<WitnessSelection.Candidate> candidates = new ArrayList<>(nearby.size());
        for (LivingEntity witness : nearby) {
            if (witness.hasLineOfSight(victim)) {
                candidates.add(new WitnessSelection.Candidate(
                        witness.getUUID(), witness.distanceToSqr(victim)));
            }
        }
        return finish(level, offender, victim, candidates, nearby.size());
    }

    /** Per-crime radius, spherical bounds, and sight of the act without assumed identity. */
    public static WitnessResult resolve(ServerLevel level, LivingEntity actor, LivingEntity victim,
                                        dev.otectus.mcacrime.crime.type.CrimeAwareness awareness) {
        if (!McaCrimeConfig.COMMON.enableWitnessSystem.get()) return resolve(level, victim == null ? actor : victim);
        LivingEntity center = victim == null ? actor : victim;
        double radius = awareness.visualRadius() * McaCrimeConfig.COMMON.visualWitnessRadiusMultiplier.get()
                * lookoutMultiplier(level, actor);
        List<WitnessSelection.Candidate> candidates = new ArrayList<>();
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, center.getBoundingBox().inflate(radius),
                e -> e != actor && e != victim && dev.otectus.mcacrime.ai.NpcAwareness.isAwake(e) && !e.isSpectator() && McaCompat.isMcaVillager(e));
        for (LivingEntity witness : nearby) {
            if (perceive(witness, actor, center, awareness).sawAct()) {
                candidates.add(new WitnessSelection.Candidate(witness.getUUID(), witness.distanceToSqr(center)));
            }
        }
        return finish(level, actor, victim, candidates, nearby.size());
    }

    /**
     * The shared tail of both scans: apply the witness modifiers, split the offender's family off the
     * candidate list, and select only from the ones who will actually report.
     *
     * <p>Loyalty belongs here rather than downstream because {@link WitnessResult#witnessIds()} is the
     * single input to Heat, community standing, family heart loss and the observation set. Filtering
     * once is what makes all four agree that a crime only the offender's sister saw was never
     * witnessed.
     */
    private static WitnessResult finish(ServerLevel level, @Nullable LivingEntity offender,
                                        @Nullable LivingEntity victim,
                                        List<WitnessSelection.Candidate> candidates, int scanned) {
        List<WitnessSelection.Candidate> considered = applyModifiers(level, offender, candidates);
        WitnessLoyaltyFilter.Partition partition =
                WitnessLoyaltyFilter.partition(considered, loyaltyPredicate(level, offender, victim));
        WitnessResult selected = WitnessSelection.select(partition.reporting(),
                McaCrimeConfig.COMMON.maxStoredWitnesses.get(), scanned);
        if (partition.loyal().isEmpty()) {
            return selected;
        }
        return WitnessResult.of(selected.witnessIds(), partition.loyal(),
                selected.scannedCandidates(), selected.totalWitnesses());
    }

    /**
     * What a posted lookout multiplies the collection radius by, or 1.0 when nobody is watching.
     *
     * <p>The lookout is applied <em>here</em>, to the radius, rather than as a per-candidate exemption
     * further down. Somebody watching the street does not make individual villagers blind; it lets the
     * offender pick a moment when the street is emptier, and the honest model of that is a smaller
     * search. Doing it the other way would leave the crowd size and the scanned count describing a
     * scene the witness set disagrees with.
     */
    private static double lookoutMultiplier(ServerLevel level, @Nullable LivingEntity offender) {
        if (offender == null || level == null || !McaCrimeConfig.COMMON.enableAccomplices.get()) {
            return 1.0D;
        }
        return WitnessModifiers.witnessRadiusMultiplier(offender.getUUID(), level.getGameTime());
    }

    /**
     * Drops the candidates a relative is currently holding the attention of.
     *
     * <p>Civilians only, and that is not a balance decision: a guard who can be drawn off a crime by a
     * villager making a scene is a guard who cannot do the one job the enforcement system gives them.
     * The geometry lives here because it needs loaded entities; the rule itself is
     * {@link WitnessModifiers#distracts}, which does not.
     */
    private static List<WitnessSelection.Candidate> applyModifiers(ServerLevel level,
                                                                   @Nullable LivingEntity offender,
                                                                   List<WitnessSelection.Candidate> candidates) {
        if (offender == null || level == null || candidates.isEmpty()
                || !McaCrimeConfig.COMMON.enableAccomplices.get()) {
            return candidates;
        }
        List<WitnessModifiers.Modifier> distractions =
                WitnessModifiers.distractions(offender.getUUID(), level.getGameTime());
        if (distractions.isEmpty()) {
            return candidates;
        }
        List<WitnessSelection.Candidate> kept = new ArrayList<>(candidates.size());
        for (WitnessSelection.Candidate candidate : candidates) {
            if (!distracted(level, candidate.id(), distractions)) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    /** Whether any active distraction covers this candidate. */
    private static boolean distracted(ServerLevel level, UUID candidateId,
                                      List<WitnessModifiers.Modifier> distractions) {
        if (!(level.getEntity(candidateId) instanceof LivingEntity candidate)) {
            return false;
        }
        boolean responder = EntitySelectors.isResponder(candidate);
        for (WitnessModifiers.Modifier modifier : distractions) {
            if (modifier.accomplice().equals(candidateId)) {
                // The relative making the scene is not a witness to it.
                return true;
            }
            if (!(level.getEntity(modifier.accomplice()) instanceof LivingEntity source)) {
                continue;
            }
            if (WitnessModifiers.distracts(modifier, candidate.distanceToSqr(source), responder)) {
                return true;
            }
        }
        return false;
    }

    /**
     * "Will this candidate keep quiet?", or null when nobody can — the feature is off, MCA has no
     * relationship data, the offender is unknown, or none of the candidates is family.
     *
     * <p>The offender's relative map is built once per crime, not once per candidate. The per-candidate
     * work left is one entity lookup and one relationship read, which is why this stays affordable on
     * an event-driven path that already resolves every witness it stores.
     */
    @Nullable
    public static Predicate<UUID> loyaltyPredicate(ServerLevel level, @Nullable LivingEntity offender,
                                                   @Nullable LivingEntity victim) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        if (offender == null || !c.enableFamilyLoyalty.get() || !McaCompat.isRelationshipApiAvailable()) {
            return null;
        }
        Set<FamilyTier> scope = scope(c.familyLoyaltyScope.get());
        if (scope.isEmpty()) {
            return null;
        }
        int generations = c.familyLoyaltyGenerations.get();
        Map<UUID, FamilyTier> relatives = FamilyGraph.relativesOf(offender, scope, generations);
        if (relatives.isEmpty()) {
            return null;
        }
        FamilyLoyalty.Settings settings = settings(c);
        ServerPlayer offendingPlayer = offender instanceof ServerPlayer player ? player : null;
        // Hearts and Heat are both player-side facts. A villager offender has neither, so its family
        // are judged on tier and personality alone rather than on a relationship MCA never recorded.
        long heat = offendingPlayer == null ? 0L : CrimeState.getHeat(offendingPlayer);
        UUID victimId = victim == null ? null : victim.getUUID();
        return id -> {
            FamilyTier tier = relatives.get(id);
            if (tier == null || !(level.getEntity(id) instanceof LivingEntity witness)) {
                return false;
            }
            int hearts = offendingPlayer == null ? 0 : McaCompat.getHearts(offendingPlayer, witness);
            boolean victimIsRelative = victimId != null
                    && FamilyGraph.relativesOf(witness, EnumSet.allOf(FamilyTier.class), generations)
                            .containsKey(victimId);
            FamilyLoyalty.Input input = new FamilyLoyalty.Input(tier, hearts,
                    McaCompat.getPersonalityName(witness), heat,
                    victimId != null && victimId.equals(id), victimIsRelative,
                    EntitySelectors.isResponder(witness), McaCompat.isAdult(witness), true);
            return FamilyLoyalty.evaluate(input, settings).loyal();
        };
    }

    /** The configured scope, with unknown names dropped ({@code ConfigValidator} reports them). */
    private static Set<FamilyTier> scope(List<? extends String> names) {
        Set<FamilyTier> tiers = EnumSet.noneOf(FamilyTier.class);
        for (String name : names) {
            FamilyTier.parse(name).ifPresent(tiers::add);
        }
        return tiers;
    }

    private static FamilyLoyalty.Settings settings(McaCrimeConfig.Common c) {
        return new FamilyLoyalty.Settings(c.loyaltyHeartsWeight.get(), c.loyaltyThreshold.get(),
                c.loyaltyTierBonusSpouse.get(), c.loyaltyTierBonusImmediate.get(),
                c.loyaltyTierBonusExtended.get(), names(c.loyalPersonalities.get()),
                names(c.lawfulPersonalities.get()), c.personalityLoyaltyBonus.get(),
                c.personalityLoyaltyPenalty.get(), c.loyaltyMaxCrimeHeat.get());
    }

    private static Set<String> names(List<? extends String> configured) {
        return new LinkedHashSet<>(configured);
    }

    public static PerceptionRules.Result perceive(LivingEntity observer, LivingEntity actor, LivingEntity center,
                                                   dev.otectus.mcacrime.crime.type.CrimeAwareness awareness) {
        if (!dev.otectus.mcacrime.ai.NpcAwareness.isAwake(observer)) return new PerceptionRules.Result(false, false, 0);
        var toward = center.getEyePosition().subtract(observer.getEyePosition()).normalize();
        boolean seesActor = observer.hasLineOfSight(actor);
        boolean seesAct = seesActor || observer.hasLineOfSight(center);
        return PerceptionRules.evaluate(new PerceptionRules.Input(Math.sqrt(observer.distanceToSqr(center)),
                awareness.visualRadius() * McaCrimeConfig.COMMON.visualWitnessRadiusMultiplier.get(),
                awareness.soundRadius() * McaCrimeConfig.COMMON.auditoryWitnessRadiusMultiplier.get(),
                seesAct, seesActor, observer.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS),
                McaCompat.isVillagerSleeping(observer), actor.isInvisible(), actor.isCrouching(), !seesAct,
                observer.getLookAngle().dot(toward), actor.level().getMaxLocalRawBrightness(actor.blockPosition()) / 15.0,
                actor.level().isRainingAt(actor.blockPosition())));
    }
}
