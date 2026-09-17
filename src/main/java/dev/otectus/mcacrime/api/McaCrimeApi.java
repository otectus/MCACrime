package dev.otectus.mcacrime.api;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimePlayerSnapshot;
import dev.otectus.mcacrime.api.model.CrimePublicView;
import dev.otectus.mcacrime.api.model.CrimeRecordQuery;
import dev.otectus.mcacrime.api.model.CrimeRecordSelector;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.api.model.CivicContractView;
import dev.otectus.mcacrime.api.model.ServiceRefusalView;
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
import dev.otectus.mcacrime.state.CrimeAttachments;
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

    /**
     * Registers a currency MCA: Crime can charge fines, bail, ransom, theft and bounties in.
     *
     * <p>Call during common setup, <b>before</b> the first config reload: the active currency is
     * resolved from {@code integrations.currencyId} at that point, and an id registered afterwards is
     * only picked up by the next reload.
     *
     * <p>An implementation whose balance is virtual — a bank account, a purse, a database row — must
     * return {@code hasItemForm() == false}, so nothing tries to drop it on the ground and lose it.
     *
     * <p>Not a version bump: this class versions on breaking changes to existing signatures, and an
     * added static method is not one. A companion written for v1 keeps working untouched.
     */
    public static void registerCurrency(dev.otectus.mcacrime.economy.Currency currency) {
        try { dev.otectus.mcacrime.economy.Currencies.register(currency); }
        catch (RuntimeException ignored) { /* nothing here throws at an integration */ }
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
            return Optional.ofNullable(CrimeAttachments.get(player).getJail())
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

    /**
     * This player's standing with a community, from MCA: Crime's own store.
     *
     * <p>Named for what it actually reads, and deliberately left that way. It has always answered from
     * this mod's fallback table, and a companion that treated it as "the reputation mod's number" was
     * reading something else than it thought (reference §11.6). Changing it to consult MCA: Reputation
     * would silently move the ground under every existing caller, so the companion-aware answer is a
     * second method instead — see {@link #effectiveStanding}.
     */
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

    /**
     * The standing a settlement should actually act on: MCA: Reputation's, when it is keeping it.
     *
     * <p>The two stores are not redundant copies, they are two different claims. When MCA: Reputation is
     * installed and MCA: Crime has handed it the authority for these deeds, <em>its</em> number is the
     * one the deeds were filed against and this mod's fallback table has stopped being updated for
     * them; reading the fallback then would report standing frozen at whenever the companion arrived.
     * With no companion, the fallback is the only number there is and is exactly right.
     *
     * <p>Falls back rather than failing on every uncertainty — companion absent, integration switched
     * off, a score it does not hold — because a neutral-but-stale answer is a village that is slightly
     * behind, and a thrown exception is a dialogue that does not open.
     */
    public static int effectiveStanding(MinecraftServer server, UUID playerId, CrimeCommunityKey community) {
        if (server == null || playerId == null || community == null) {
            return 0;
        }
        try {
            java.util.OptionalInt companion = dev.otectus.mcacrime.compat.ReputationBridge.ops()
                    .map(ops -> ops.score(server, playerId, community))
                    .orElse(java.util.OptionalInt.empty());
            if (companion.isPresent()) {
                return companion.getAsInt();
            }
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — effective standing lookup failed; using the local store", t);
        }
        return communityStanding(server, playerId, community);
    }

    // ------------------------------------------------------------------ public projections

    /**
     * What one community may know about one person.
     *
     * <p>The method to call from a settlement reaction, a dialogue condition or anything that draws a
     * status where other people can see it. {@link #selectRecords} is the player's own file and is
     * scoped to them for exactly that reason; this is the village's knowledge, and the difference is
     * every unwitnessed crime, every crime in another village, and every crime nobody has yet reported
     * (reference §11.1). A companion that populated a village alert from the record list would be
     * broadcasting things nobody saw.
     *
     * <p>The knowledge rule lives in {@link CrimePublicView#isPublic} and is the same one the civic
     * incident filing uses, so what a village reacts to and what MCA: Reputation recorded cannot drift
     * apart.
     */
    public static Optional<CrimePublicView> publicView(MinecraftServer server, CrimeCommunityKey community,
                                                       UUID subject) {
        if (server == null || community == null || subject == null) {
            return Optional.empty();
        }
        try {
            CrimeWorldData data = CrimeWorldData.get(server);
            List<CrimeRecordView> views = new ArrayList<>();
            data.recordsForOffender(subject).forEach(record -> views.add(record.view()));

            boolean observations = dev.otectus.mcacrime.McaCrimeConfig.COMMON.enableObservations.get();
            double confidence =
                    dev.otectus.mcacrime.McaCrimeConfig.COMMON.reportConfidenceThreshold.get();
            // Built once from the report index rather than re-scanned per case: the projection is asked
            // for on a dialogue open and on a reaction, and a scan per case would be quadratic in a
            // long-running world.
            Set<UUID> reported = new java.util.HashSet<>();
            data.reportsAgainst(subject).stream()
                    .filter(report -> report.supportsArrest(confidence))
                    .forEach(report -> reported.add(report.incidentId()));

            ServerPlayer online = server.getPlayerList().getPlayer(subject);
            Band band = online == null
                    ? Band.fromKarma(0L)
                    : CrimeState.getBand(online);
            boolean wanted = online != null
                    ? CrimeState.isWanted(online)
                    : data.warrant(subject) != null;
            long bounty = wanted ? dev.otectus.mcacrime.bounty.BountyService.price(server, subject) : 0L;

            return Optional.of(CrimePublicView.of(community, subject, band, wanted,
                    effectiveStanding(server, subject, community), bounty, views, observations,
                    reported::contains));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — public view failed; returning empty", t);
            return Optional.empty();
        }
    }

    /** The same, for a player who is online, where band and wanted status are read directly. */
    public static Optional<CrimePublicView> publicView(ServerPlayer player, CrimeCommunityKey community) {
        return player == null || player.getServer() == null
                ? Optional.empty()
                : publicView(player.getServer(), community, player.getUUID());
    }

    // ------------------------------------------------------------------ civic layer

    /**
     * Whether one villager will serve one person, and what they say if not (reference §11.5).
     *
     * <p>Published so a settlement companion can ask MCA: Crime's question instead of building its own
     * answer out of a band and a wanted flag. The rule has one exception that must never be got wrong —
     * food, shelter and care are never refused — and a second implementation of it is a second chance
     * to strand a player with no route back.
     *
     * <p>Answers "served" for everything when {@code townstead.serviceRestrictions} is off, which is
     * the default, and for any service kind it does not recognise. Never throws.
     *
     * @param provider    the villager being asked; their own memory is what makes a refusal personal
     * @param subject     who is asking
     * @param serviceKind one of {@code essential_food}, {@code essential_shelter}, {@code trade},
     *                    {@code luxury}, {@code fence}
     */
    public static ServiceRefusalView serviceRefusal(net.minecraft.server.level.ServerLevel level,
                                                    net.minecraft.world.entity.Entity provider,
                                                    UUID subject, String serviceKind) {
        try {
            dev.otectus.mcacrime.civic.ServiceKind kind =
                    dev.otectus.mcacrime.civic.ServiceKind.parse(serviceKind).orElse(null);
            if (kind == null) {
                return ServiceRefusalView.allowed(serviceKind == null ? "" : serviceKind);
            }
            dev.otectus.mcacrime.civic.ServiceRestrictionPolicy.Decision decision =
                    dev.otectus.mcacrime.civic.ServiceRestrictions.decide(level, provider, subject, kind);
            return new ServiceRefusalView(decision.refused(), kind.id(), decision.reasonKey(),
                    decision.repairKey());
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — service refusal query failed; serving", t);
            return ServiceRefusalView.allowed(serviceKind == null ? "" : serviceKind);
        }
    }

    /**
     * Every civic service contract this offender has, newest last (reference §12.1).
     *
     * <p>Read-only, and there is deliberately no companion method to accept or advance one. Work is
     * credited only from transitions MCA: Crime observed itself, so a presentation layer can show a
     * contract and cannot complete one — which is what "Crime retains the case and completion
     * authority" has to mean in code rather than in a comment.
     */
    public static List<CivicContractView> civicContracts(MinecraftServer server, UUID offender) {
        if (server == null || offender == null) {
            return List.of();
        }
        try {
            List<CivicContractView> views = new ArrayList<>();
            for (dev.otectus.mcacrime.civic.ServiceContract contract
                    : CrimeWorldData.get(server).serviceContractsFor(offender)) {
                views.add(new CivicContractView(contract.contractId(), contract.caseId(),
                        contract.offender(), contract.community().asString(), contract.task().id(),
                        contract.requiredUnits(), contract.completedUnits(), contract.deadline(),
                        contract.state().id()));
            }
            return List.copyOf(views);
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — civic contract query failed; returning empty", t);
            return List.of();
        }
    }
}
