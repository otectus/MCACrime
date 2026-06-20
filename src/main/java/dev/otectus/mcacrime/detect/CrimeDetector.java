package dev.otectus.mcacrime.detect;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.CrimeCommittedEvent;
import dev.otectus.mcacrime.api.event.CrimeWitnessedEvent;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.KarmaSource;
import dev.otectus.mcacrime.crime.type.CrimeType;
import dev.otectus.mcacrime.crime.type.CrimeTypeRegistry;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.ledger.CrimeLedger;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.MinecraftForge;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates crime detection (spec §5): gate → classify → resolve type → witness → apply → ledger →
 * events. Holds the transient per-(attacker, victim) harm cooldown that collapses a melee flurry into one
 * crime; that map is intentionally non-persistent (a cooldown must not survive restart — spec §18) and is
 * keyed on world game time (a throwaway anti-spam window, never the online-tick sentence clock).
 */
public final class CrimeDetector {

    private record HarmKey(UUID attacker, UUID victim) {
    }

    private static final Map<HarmKey, Long> LAST_HARM = new ConcurrentHashMap<>();
    private static volatile long lastSweep;

    private CrimeDetector() {
    }

    // ------------------------------------------------------------------ entry points

    public static void onHarm(LivingEntity victim, DamageSource source, float amount, ServerLevel level) {
        Optional<ServerPlayer> offenderOpt = CrimeGate.resolveOffender(victim, source, level);
        if (offenderOpt.isEmpty()) {
            return;
        }
        // Lethal blow: skip harm and let LivingDeathEvent record the single kill_villager (no double-count).
        if (amount >= victim.getHealth()) {
            return;
        }
        ServerPlayer offender = offenderOpt.get();
        long now = level.getGameTime();
        int cooldown = McaCrimeConfig.COMMON.harmCooldownTicks.get();
        maybeSweep(now, cooldown);
        HarmKey key = new HarmKey(offender.getUUID(), victim.getUUID());
        Long last = LAST_HARM.get(key);
        if (cooldown > 0 && last != null && now - last < cooldown) {
            return; // within the anti-spam window
        }
        LAST_HARM.put(key, now);
        commit(offender, victim, CrimeClassifier.classifyHarm(victim), level);
    }

    public static void onKill(LivingEntity victim, DamageSource source, ServerLevel level) {
        clearVictim(victim.getUUID()); // the victim is gone; drop its cooldown entries
        Optional<ServerPlayer> offenderOpt = CrimeGate.resolveOffender(victim, source, level);
        if (offenderOpt.isEmpty()) {
            return;
        }
        commit(offenderOpt.get(), victim, CrimeClassifier.classifyKill(victim), level);
    }

    // ------------------------------------------------------------------ commit

    private static void commit(ServerPlayer offender, LivingEntity victim, ResourceLocation crimeId, ServerLevel level) {
        Optional<CrimeType> typeOpt = CrimeTypeRegistry.getOrBuiltin(crimeId);
        if (typeOpt.isEmpty()) {
            McaCrime.LOGGER.debug("No crime type (or builtin) for '{}'; skipping detection", crimeId);
            return;
        }
        CrimeType type = typeOpt.get();
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;

        int witnessCount = WitnessChecker.countWitnesses(level, victim);
        boolean witnessed = witnessCount > 0;

        long karmaApplied = karmaFor(type, witnessed, c.unwitnessedKarmaFactor.get());
        long heatApplied = heatFor(type, witnessed, c.requireWitnessForHeat.get());

        if (karmaApplied != 0L) {
            CrimeState.addKarma(offender, karmaApplied, KarmaSource.CRIME);
        }
        if (heatApplied != 0L) {
            CrimeState.addHeat(offender, heatApplied);
        }

        UUID recordId = UUID.randomUUID();
        UUID victimId = victim.getUUID();
        OptionalInt villageId = McaCompat.getHomeVillageId(victim);
        MinecraftServer server = offender.getServer();
        if (server != null) {
            CrimeLedger.record(server, new CrimeRecord(recordId, offender.getUUID(), victimId, crimeId,
                    villageId, witnessed, level.getGameTime(), heatApplied, karmaApplied,
                    0L, 0L, Resolution.UNRESOLVED));
        }

        if (witnessed) {
            MinecraftForge.EVENT_BUS.post(new CrimeWitnessedEvent(offender, crimeId, victimId, witnessCount));
        }
        MinecraftForge.EVENT_BUS.post(new CrimeCommittedEvent(offender, crimeId, victimId, witnessed,
                karmaApplied, heatApplied, recordId));
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

    // ------------------------------------------------------------------ cooldown hygiene

    public static void clearVictim(UUID victim) {
        LAST_HARM.keySet().removeIf(key -> key.victim().equals(victim));
    }

    public static void clearAttacker(UUID attacker) {
        LAST_HARM.keySet().removeIf(key -> key.attacker().equals(attacker));
    }

    private static void maybeSweep(long now, int cooldown) {
        if (now - lastSweep < 200L) {
            return;
        }
        lastSweep = now;
        long cutoff = now - Math.max(cooldown, 1) * 4L;
        LAST_HARM.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }
}
