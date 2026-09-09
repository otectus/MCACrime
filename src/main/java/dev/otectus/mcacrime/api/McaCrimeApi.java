package dev.otectus.mcacrime.api;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimePlayerSnapshot;
import dev.otectus.mcacrime.api.model.CrimeRecordQuery;
import dev.otectus.mcacrime.api.model.CrimeRecordSelector;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.api.model.CustodyView;
import dev.otectus.mcacrime.api.model.JailSentenceView;
import dev.otectus.mcacrime.api.model.OutlawStatusView;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.enforcement.LegalTarget;
import dev.otectus.mcacrime.enforcement.OutlawResolver;
import dev.otectus.mcacrime.enforcement.OutlawStatus;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.jail.JailState;
import dev.otectus.mcacrime.ledger.CrimeCaseService;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.state.CrimeCapabilities;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stable public surface for MCA: Crime (spec §16). Other mods can read a player's standing and case
 * history without touching internals; all <em>mutation</em> stays server-internal through
 * {@link CrimeState} and the case service. To react to changes, subscribe to the events in
 * {@code dev.otectus.mcacrime.api.event} on the Forge event bus.
 *
 * <h2>Contracts every method here honours</h2>
 *
 * <ul>
 *   <li><b>Nothing throws at an integration.</b> A dialogue evaluation or a quest condition must never
 *       crash because of this mod; failures come back as an empty result or a neutral value.</li>
 *   <li><b>Only immutable views cross the boundary.</b> Never a {@code CrimeRecord}, never a
 *       capability, never the {@code SavedData}.</li>
 *   <li><b>Reads are scoped to the player asked about.</b> There is no "any player" query, which is
 *       what structurally prevents one player enumerating another's history.</li>
 * </ul>
 *
 * <h2>Versioning</h2>
 *
 * <p>{@link #getApiVersion()} lets a bridge refuse an incompatible future version instead of dying on
 * a {@code NoSuchMethodError}. The version is deliberately <b>not</b> a public constant: {@code javac}
 * copies a {@code public static final int} straight into the consumer's own constant pool, so a
 * companion compiled against v1 would keep reading 1 forever, and the handshake it was written to
 * perform would silently never fire.
 */
public final class McaCrimeApi {

    /** Incremented only on a breaking change to this class's signatures. */
    private static final int API_VERSION = 1;

    private McaCrimeApi() {
    }

    /** The binary API generation. Bridges should refuse anything they were not written against. */
    public static int getApiVersion() {
        return API_VERSION;
    }

    /** Memory-sensitive dialogue and quest context for this villager/player pair only. */
    public static List<dev.otectus.mcacrime.api.model.VictimMemoryView> victimMemories(
            MinecraftServer server, UUID villager, UUID player) {
        try { return dev.otectus.mcacrime.memory.VictimMemoryService.memories(server, villager, player); }
        catch (RuntimeException e) { return List.of(); }
    }

    // ------------------------------------------------------------------ existing surface (unchanged)

    public static long getKarma(ServerPlayer player) {
        return CrimeState.getKarma(player);
    }

    public static long getHeat(ServerPlayer player) {
        return CrimeState.getHeat(player);
    }

    public static Band getBand(ServerPlayer player) {
        return CrimeState.getBand(player);
    }

    public static boolean isWanted(ServerPlayer player) {
        return CrimeState.isWanted(player);
    }

    // ------------------------------------------------------------------ snapshots

    /**
     * Everything about a player's legal standing at one instant.
     *
     * <p>One object rather than six calls, so a caller cannot end up holding a karma reading from
     * before a change and a jail state from after it.
     */
    public static Optional<CrimePlayerSnapshot> snapshot(ServerPlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        try {
            MinecraftServer server = player.getServer();
            long outstandingFines = 0L;
            int unresolved = 0;
            if (server != null) {
                for (CrimeRecord record : CrimeWorldData.get(server).actionableFor(player.getUUID())) {
                    unresolved++;
                    outstandingFines += Math.max(0L, record.fineAmount());
                }
            }
            return Optional.of(new CrimePlayerSnapshot(
                    player.getUUID(),
                    CrimeState.getKarma(player),
                    CrimeState.getHeat(player),
                    CrimeState.getBand(player),
                    CrimeState.isWanted(player),
                    OutlawResolver.resolve(player).lawfulCombatTarget(),
                    JailService.isJailed(player),
                    JailService.remainingTicks(player),
                    CustodyService.isCaptive(player),
                    LegalTarget.isHoldingCaptive(player),
                    unresolved,
                    outstandingFines));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — snapshot failed; returning empty", t);
            return Optional.empty();
        }
    }

    /**
     * Whether force against this player is lawful right now, and why (0.5.1).
     *
     * <p>Separate from {@link #snapshot} rather than folded into it: a companion that only wants to
     * know whether its own NPC may attack somebody should not have to pay for a ledger walk, and
     * {@code CrimePlayerSnapshot} is a published shape that widening would break.
     */
    public static Optional<OutlawStatusView> outlawStatus(ServerPlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        try {
            OutlawStatus status = OutlawResolver.resolve(player);
            return Optional.of(new OutlawStatusView(
                    status.lawfulCombatTarget(),
                    status.lethalForceLawful(),
                    status.bountyEligible(),
                    status.basis().reasonKey(),
                    status.heat(),
                    status.karma(),
                    status.band()));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — outlaw status failed; returning empty", t);
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ records

    /** One case by its exact id, or empty. */
    public static Optional<CrimeRecordView> record(MinecraftServer server, UUID recordId) {
        try {
            return CrimeCaseService.view(server, recordId);
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — record lookup failed; returning empty", t);
            return Optional.empty();
        }
    }

    /**
     * This player's cases matching {@code query}, bounded and deterministically ordered.
     *
     * <p>Scoped to {@code player} as the offender, with no way to ask about anyone else. Administrative
     * cross-player reads go through {@code /crime ledger}, which has its own permission check.
     */
    public static List<CrimeRecordView> selectRecords(ServerPlayer player, CrimeRecordQuery query) {
        if (player == null || player.getServer() == null) {
            return List.of();
        }
        try {
            CrimeRecordQuery effective = query == null ? CrimeRecordQuery.ANY : query;
            MinecraftServer server = player.getServer();
            List<CrimeRecord> records = CrimeWorldData.get(server).recordsForOffender(player.getUUID());
            List<CrimeRecordView> views = new ArrayList<>(records.size());
            records.forEach(record -> views.add(record.view()));
            return CrimeRecordSelector.select(views, effective, server.overworld().getGameTime());
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — record selection failed; returning empty", t);
            return List.of();
        }
    }

    /** How many of this player's cases are still actionable, without materialising them. */
    public static int unresolvedCaseCount(ServerPlayer player) {
        if (player == null || player.getServer() == null) {
            return 0;
        }
        try {
            return CrimeWorldData.get(player.getServer()).actionableFor(player.getUUID()).size();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ custody and sentences

    /** Who is holding this entity, if anyone. Works for players and villagers alike. */
    public static Optional<CustodyView> custody(MinecraftServer server, UUID entityId) {
        if (server == null || entityId == null) {
            return Optional.empty();
        }
        try {
            CustodyRecord record = CrimeWorldData.get(server).getCustody(entityId);
            if (record == null) {
                return Optional.empty();
            }
            return Optional.of(new CustodyView(
                    record.getCaptive(),
                    record.isCaptivePlayer(),
                    record.isLawful(),
                    record.getOwner() == null ? Optional.empty() : record.getOwner().ownerUuid(),
                    record.getRestraint(),
                    record.getRealTicksHeld(),
                    record.getRemainingJailTicks(),
                    Optional.ofNullable(record.getHoldDim()),
                    Optional.empty(),
                    Optional.empty()));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — custody lookup failed; returning empty", t);
            return Optional.empty();
        }
    }

    /** This player's current sentence, or empty when they are not serving one. */
    public static Optional<JailSentenceView> sentence(ServerPlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        try {
            return CrimeCapabilities.get(player)
                    .map(PlayerCrimeData::getJail)
                    .filter(jail -> jail != null)
                    .map(jail -> toView(jail, CrimeWorldData.get(player.getServer()), player.getUUID()));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — sentence lookup failed; returning empty", t);
            return Optional.empty();
        }
    }

    static JailSentenceView toView(JailState jail, CrimeWorldData world, UUID offender) {
        return new JailSentenceView(
                Optional.of(jail.getSentenceId()),
                jail.getRemainingOnlineTicks(),
                jail.getRealOnlineTicksServed(),
                Optional.ofNullable(jail.getJailDim()),
                jail.isEscaped(),
                jail.getModeSnapshot(),
                world.casesForSentence(offender, jail.getSentenceId()).stream()
                        .map(dev.otectus.mcacrime.ledger.CrimeRecord::id)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    // ------------------------------------------------------------------ communities

    /** This player's standing with a community, from whichever store is authoritative. */
    public static int communityStanding(MinecraftServer server, UUID playerId, CrimeCommunityKey community) {
        if (server == null || playerId == null || community == null) {
            return 0;
        }
        try {
            return CrimeWorldData.get(server).reputation(community, playerId);
        } catch (Throwable t) {
            return 0;
        }
    }
}
