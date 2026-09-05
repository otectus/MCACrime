package dev.otectus.mcacrime.detect;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.CrimeCommittedEvent;
import dev.otectus.mcacrime.api.event.CrimeWitnessedEvent;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.KarmaSource;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.crime.type.CrimeType;
import dev.otectus.mcacrime.crime.type.CrimeTypeRegistry;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.integration.CrimeIntegrationHooks;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.ledger.CrimeFlag;
import dev.otectus.mcacrime.ledger.CrimeLedger;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.mug.MuggingService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.MinecraftForge;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
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
        Optional<ServerPlayer> offenderOpt = CrimeGate.resolveOffender(victim, source, level, true);
        if (offenderOpt.isEmpty()) {
            return;
        }
        ServerPlayer offender = offenderOpt.get();
        // A villager killed while being mugged is the heavier mugging_murder, not a plain kill (§8.6).
        ResourceLocation crimeId = MuggingService.wasMugging(offender.getUUID(), victim.getUUID(), level.getGameTime())
                ? CrimeIds.MUGGING_MURDER
                : CrimeClassifier.classifyKill(victim);
        commit(offender, victim, crimeId, level);
    }

    // ------------------------------------------------------------------ commit

    private static void commit(ServerPlayer offender, LivingEntity victim, ResourceLocation crimeId, ServerLevel level) {
        commitDirect(offender, crimeId, victim, level, WitnessChecker.resolve(level, victim), "direct");
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
        Optional<CrimeType> typeOpt = CrimeTypeRegistry.getOrBuiltin(crimeId);
        if (typeOpt.isEmpty()) {
            McaCrime.LOGGER.debug("No crime type (or builtin) for '{}'; skipping", crimeId);
            return Optional.empty();
        }
        CrimeType type = typeOpt.get();
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        boolean witnessed = witnesses.witnessed();

        long karmaApplied = karmaFor(type, witnessed, c.unwitnessedKarmaFactor.get());
        long heatApplied = heatFor(type, witnessed, c.requireWitnessForHeat.get());
        if (karmaApplied != 0L) {
            CrimeState.addKarma(offender, karmaApplied, KarmaSource.CRIME);
        }
        if (heatApplied != 0L) {
            CrimeState.addHeat(offender, heatApplied, crimeId, "");
        }

        UUID recordId = UUID.randomUUID();
        UUID victimId = victim == null ? null : victim.getUUID();
        OptionalInt villageId = victim == null ? OptionalInt.empty() : McaCompat.getHomeVillageId(victim);
        CrimeCommunityKey community = CrimeCommunityResolver.resolve(victim, level).orElse(null);
        CrimeRecord record = new CrimeRecord(recordId, offender.getUUID(), victimId, crimeId,
                villageId, community, witnessed, witnesses.witnessIds(), level.getGameTime(),
                heatApplied, karmaApplied, 0L, 0L, Resolution.UNRESOLVED, 0L, java.util.List.of(), null,
                buildContext(victim, witnesses, detection));

        MinecraftServer server = offender.getServer();
        if (server != null) {
            CrimeLedger.record(server, record);
            // Queued in the same dirty cycle as the record itself, so a crash cannot leave the case
            // written but the companion mod never told about it.
            CrimeIntegrationHooks.onCommitted(server, record.view());
        }

        // Identity-carrying observations (§12), recorded once the incident has an id to hang them on.
        // This is additive to the witness set above and never contradicts it: the eyewitness identities
        // are the same ones, reused rather than rescanned.
        if (server != null) {
            dev.otectus.mcacrime.memory.ObservationService.record(level, offender, victim, crimeId,
                    recordId, witnesses);
        }

        CrimeRecordView view = record.view();
        if (witnessed) {
            MinecraftForge.EVENT_BUS.post(new CrimeWitnessedEvent(offender, crimeId, victimId,
                    witnesses.totalWitnesses(), witnesses.witnessIds()));
        }
        MinecraftForge.EVENT_BUS.post(new CrimeCommittedEvent(offender, crimeId, victimId, witnessed,
                karmaApplied, heatApplied, recordId, view));
        return Optional.of(view);
    }

    /**
     * The NPC counterpart of {@link #commitDirect}: writes the record for a crime a villager
     * committed (0.5.1).
     *
     * <p>Three things are deliberately missing relative to the player path, and all three are missing
     * because the offender is not a player. Karma and Heat live in a player capability, so there is
     * nothing to add them to; {@code CrimeCommittedEvent} and {@code CrimeWitnessedEvent} both name a
     * {@code ServerPlayer} offender in their public API and cannot describe a thief. The caller posts
     * {@code NpcCrimeCommittedEvent} instead, which is that API's counterpart.
     *
     * <p>Everything else is the same tail: the same record shape, the same ledger, the same companion
     * hook, the same observations. A mugging by a villager is a case in the same book as a mugging by
     * a player, which is the point of the release.
     *
     * @param flags     circumstances stamped into the record context, where the guard challenge and
     *                  the custody branch read them back
     * @param detection how it came to light, exactly as {@code commitDirect} means it
     * @return the committed case, or empty when the crime type is unknown and nothing was written
     */
    public static Optional<CrimeRecordView> commitNpc(LivingEntity offender, ResourceLocation crimeId,
                                                      @Nullable LivingEntity victim, ServerLevel level,
                                                      WitnessResult witnesses, String detection,
                                                      Set<CrimeFlag> flags) {
        if (offender == null || level == null) {
            return Optional.empty();
        }
        if (CrimeTypeRegistry.getOrBuiltin(crimeId).isEmpty()) {
            McaCrime.LOGGER.debug("No crime type (or builtin) for '{}'; skipping", crimeId);
            return Optional.empty();
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return Optional.empty();
        }

        // The offender is a villager standing next to its own crime, so the witness scan finds it.
        // A thief is not a witness to a mugging it is committing: leaving it in would let it observe,
        // and then report, itself.
        WitnessResult effective = withoutOffender(witnesses, offender.getUUID());

        UUID recordId = UUID.randomUUID();
        UUID victimId = victim == null ? null : victim.getUUID();
        OptionalInt villageId = McaCompat.getHomeVillageId(offender);
        CrimeCommunityKey community = CrimeCommunityResolver.resolve(offender, level).orElse(null);
        Map<String, String> context = buildContext(victim, effective, detection);
        context.put(CrimeContext.OFFENDER_KIND, "npc");
        context.put(CrimeContext.FLAGS, CrimeFlag.encode(flags == null || flags.isEmpty()
                ? EnumSet.noneOf(CrimeFlag.class) : EnumSet.copyOf(flags)));

        CrimeRecord record = new CrimeRecord(recordId, offender.getUUID(), victimId, crimeId,
                villageId, community, effective.witnessed(), effective.witnessIds(), level.getGameTime(),
                0L, 0L, 0L, 0L, Resolution.UNRESOLVED, 0L, java.util.List.of(), null, context);

        CrimeLedger.record(server, record);
        CrimeIntegrationHooks.onCommitted(server, record.view());
        dev.otectus.mcacrime.memory.ObservationService.record(level, offender, victim, crimeId,
                recordId, effective);
        return Optional.of(record.view());
    }

    /** The witness set with one identity removed, counts kept honest. */
    private static WitnessResult withoutOffender(WitnessResult witnesses, UUID offender) {
        if (witnesses == null) {
            return WitnessResult.none();
        }
        if (!witnesses.witnessIds().contains(offender)) {
            return witnesses;
        }
        Set<UUID> ids = new LinkedHashSet<>(witnesses.witnessIds());
        ids.remove(offender);
        return WitnessResult.of(ids, Math.max(0, witnesses.scannedCandidates() - 1),
                Math.max(ids.size(), witnesses.totalWitnesses() - 1));
    }

    /**
     * The allowlisted context snapshot (spec §6.8): a handful of stable facts a later screen or
     * companion mod would otherwise have to reconstruct from an entity that has long since unloaded.
     * Deliberately not a dumping ground — no entity NBT, no chat, no player-supplied strings.
     */
    private static Map<String, String> buildContext(@Nullable LivingEntity victim, WitnessResult witnesses,
                                                    String detection) {
        Map<String, String> context = new LinkedHashMap<>();
        context.put(CrimeContext.DETECTION, detection);
        if (witnesses.truncated()) {
            // The crowd was bigger than we stored. Keeping the real number lets a later line say "and
            // a dozen others saw it" without pretending to know who they were.
            context.put(CrimeContext.WITNESS_COUNT_TOTAL, Integer.toString(witnesses.totalWitnesses()));
        }
        if (victim != null) {
            String name = McaCompat.getVillagerDisplayName(victim).getString();
            if (!name.isEmpty()) {
                context.put(CrimeContext.VICTIM_NAME, name);
            }
            context.put(CrimeContext.VICTIM_ROLE, victimRole(victim));
        }
        return context;
    }

    private static String victimRole(LivingEntity victim) {
        if (McaCompat.isGuard(victim)) {
            return "guard";
        }
        return McaCompat.isAdult(victim) ? "villager" : "child";
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
