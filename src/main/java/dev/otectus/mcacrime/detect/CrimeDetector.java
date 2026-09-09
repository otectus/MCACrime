package dev.otectus.mcacrime.detect;

import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.crime.type.CrimeType;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.ledger.CrimeFlag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Compatibility facade over incident commits and server-owned damage reconciliation. */
public final class CrimeDetector {

    private CrimeDetector() {}

    /** Compatibility adapter; the Forge handler passes the original event to retain final cancellation. */
    public static void onHarm(LivingEntity victim, DamageSource source, float amount, ServerLevel level) {
        DamageIncidentService.damage(new net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Pre(victim,
                new net.neoforged.neoforge.common.damagesource.DamageContainer(source, amount)), level);
    }

    public static void onKill(LivingEntity victim, DamageSource source, ServerLevel level) {
        DamageIncidentService.death(new net.neoforged.neoforge.event.entity.living.LivingDeathEvent(victim, source), level);
    }
    /**
     * Shared commit tail (spec §3.5, §2.2): apply Karma/Heat via {@link CrimeState}, write the ledger, fire
     * {@code CrimeWitnessed}/{@code CrimeCommitted}. {@code victim} may be null for victimless crimes (e.g.
     * a {@code jailbreak}, which is inherently witnessed by the law). Fail-safe: unknown crime id → no-op.
     *
     * @deprecated prefer {@link #commitDirect(ServerPlayer, ResourceLocation, LivingEntity, ServerLevel,
     *         WitnessResult, String)}, which records who saw it rather than only how many.
     */
    @Deprecated
    public static void commitDirect(ServerPlayer offender, ResourceLocation crimeId, @Nullable LivingEntity victim,
                                    ServerLevel level, boolean witnessed, int witnessCount) {
        commitDirect(offender, crimeId, victim, level, WitnessResult.legacy(witnessed, witnessCount), "direct");
    }

    /**
     * Shared commit tail, carrying the full witness snapshot.
     *
     * <p>{@code detection} records how the crime came to light — {@code direct}, {@code custody},
     * {@code jailbreak}, {@code command} — because the same crime type means something different when a
     * guard caught it in the act than when it was recorded by an operator.
     *
     * @return the committed case, or empty when the crime type is unknown and nothing was written
     */
    public static Optional<CrimeRecordView> commitDirect(ServerPlayer offender, ResourceLocation crimeId,
                                                         @Nullable LivingEntity victim, ServerLevel level,
                                                         WitnessResult witnesses, String detection) {
        return dev.otectus.mcacrime.incident.IncidentService.commitDirect(offender, crimeId, victim,
                level, witnesses, detection);
    }

    public static Optional<CrimeRecordView> commitNpc(LivingEntity offender, ResourceLocation crimeId,
                                                      @Nullable LivingEntity victim, ServerLevel level,
                                                      WitnessResult witnesses, String detection, Set<CrimeFlag> flags) {
        return dev.otectus.mcacrime.incident.IncidentService.commitNpc(offender, crimeId, victim,
                level, witnesses, detection, flags);
    }
    // ------------------------------------------------------------------ pure application math (testable)

    /** Karma to apply: full (×witnessedMultiplier) when witnessed, else scaled by unwitnessedKarmaFactor (§3.5). */
    public static long karmaFor(CrimeType type, boolean witnessed, double unwitnessedKarmaFactor) {
        double multiplier = witnessed ? type.witnessedMultiplier() : unwitnessedKarmaFactor;
        return Math.round(type.karmaDelta() * multiplier);
    }

    /** Heat to apply: only when witnessed (×witnessedMultiplier), else 0 unless requireWitnessForHeat is off (§3.5). */
    public static long heatFor(CrimeType type, boolean witnessed, boolean requireWitnessForHeat) {
        if (witnessed) {
            return Math.round(type.heatDelta() * type.witnessedMultiplier());
        }
        return requireWitnessForHeat ? 0L : type.heatDelta();
    }

    public static void clearVictim(UUID victim) { DamageIncidentService.forgetAcrossServers(victim); }
    public static void clearAttacker(UUID attacker) { DamageIncidentService.forgetAcrossServers(attacker); }
}
