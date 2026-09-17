package dev.otectus.mcacrime.civic;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.activity.CrimeActivityRegistry;
import dev.otectus.mcacrime.activity.CrimeActivityView;
import dev.otectus.mcacrime.ai.NpcAwareness;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.compat.TownsteadBridge;
import dev.otectus.mcacrime.compat.TownsteadReactionEvent;
import dev.otectus.mcacrime.compat.TownsteadScheduleView;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.economy.FineService;
import dev.otectus.mcacrime.economy.SettlementPolicy;
import dev.otectus.mcacrime.economy.SettlementQuote;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.facility.FacilityAssignment;
import dev.otectus.mcacrime.integration.TownsteadReactions;
import dev.otectus.mcacrime.ledger.CrimeCaseService;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.property.PropertyRegistry;
import dev.otectus.mcacrime.relationship.RelationshipConsequences;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Community service: settling an eligible minor case by doing something useful instead of paying
 * (reference §12.1).
 *
 * <h2>The shape §12.1 asks for, in order</h2>
 *
 * <ol>
 *   <li><b>Offered only for configured minor, public, unresolved cases.</b> {@link #offer} refuses
 *       anything the ordinary settlement path would not price: a case flagged mandatory-custody, a
 *       case already bound to a sentence, a case that is not this offender's, and a case nobody in the
 *       settlement knows about. A contract for a crime the village never heard of would be a village
 *       assigning penance for a secret.</li>
 *   <li><b>A server-issued contract naming exact case IDs and obligations.</b> {@link ServiceContract}
 *       is that contract, and it is durable.</li>
 *   <li><b>Narrowly scoped work permissions.</b> The only permission a contract grants is an activity
 *       claim on an NPC offender, which stops their settlement work shift starting while they are on
 *       duty. It grants no access to anything, which is why it can be safe at all.</li>
 *   <li><b>Work measured by actual accepted outputs.</b> {@link #credit} is the single entry point and
 *       it is only ever called from an event MCA: Crime already observes — see {@link CivicTask}. It
 *       is never a scan, a poll, or a proximity check.</li>
 *   <li><b>Completion resolves only the agreed fine/restitution component.</b> {@link #complete} puts
 *       the contract's case through the ordinary settlement path with the work standing in for the
 *       money. Nothing else about the offender's record is touched.</li>
 *   <li><b>The incident and victim memory remain.</b> Nothing here writes to {@code memory/}.</li>
 * </ol>
 *
 * <h2>Why the fine is never reduced up front</h2>
 *
 * <p>A contract that discounted the fine when it was accepted would need the discount unwound on
 * failure, and unwinding a settlement is exactly the operation the ledger has no safe form of. So an
 * accepted contract changes nothing: the case stays open, the fine stays payable, and the only thing
 * that settles anything is completion. Failure therefore restores the original sentence by having
 * never departed from it — which is the strongest form of "falls back" available.
 *
 * <h2>Inert when off</h2>
 *
 * <p>{@link #enabled()} is one boolean read and every entry point asks it first, so a server with
 * {@code townstead.communityService} off does no work here at all.
 */
public final class CivicWorkService {

    /** The owner token an activity claim is taken under. */
    public static final String CLAIM_OWNER = "civic-service";

    /** The namespace civic idempotency keys live in, so a derived id cannot collide with anything. */
    private static final String KEY_NAMESPACE = "mcacrime:civic-service:";

    /** How long a contract runs before it lapses: three in-game days. */
    public static final long DEFAULT_TERM_TICKS = 72_000L;

    /** Why an offer was refused. Each one is a sentence an operator or a player can act on. */
    public enum Refusal {
        /** {@code townstead.communityService} is off. */
        DISABLED("community service is switched off on this server"),
        /** The store is read-only this session. */
        READ_ONLY("the crime store is read-only this session"),
        /** No case was named, or the one named is not this offender's open case. */
        NO_CASE("there is no open case of theirs to settle this way"),
        /** The case cannot be settled by a fine, so it cannot be settled by work either. */
        NOT_SETTLEABLE("that case cannot be bought off, so it cannot be worked off either"),
        /** Nobody in the settlement knows about the case. */
        NOT_PUBLIC("the settlement does not know about that case"),
        /** The case names no settlement, so there is nobody to do the work for. */
        NO_COMMUNITY("that case names no settlement, so there is nobody to work for"),
        /** They already owe work. */
        ALREADY_CONTRACTED("they already have a civic contract outstanding"),
        /** Nothing could credit this task on this server. */
        SIGNAL_UNAVAILABLE("nothing on this server can credit that task's work"),
        /** The contract table is full. */
        TABLE_FULL("the civic contract table is full");

        private final String reason;

        Refusal(String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** An offer, or the reason there is not one. */
    public record Offer(@Nullable ServiceContract contract, @Nullable Refusal refusal, long fineAvoided) {

        public static Offer of(ServiceContract contract, long fineAvoided) {
            return new Offer(contract, null, Math.max(0L, fineAvoided));
        }

        public static Offer refused(Refusal refusal) {
            return new Offer(null, refusal, 0L);
        }

        public boolean made() {
            return contract != null;
        }
    }

    private CivicWorkService() {
    }

    // ------------------------------------------------------------------ switch

    /**
     * Whether community service is switched on.
     *
     * <p>Any throw reads as off, for the same reason every other Townstead-adjacent gate does: this is
     * asked from event handlers that can run before the config is loaded, and an exception there would
     * become somebody else's crash.
     */
    public static boolean enabled() {
        try {
            return McaCrimeConfig.COMMON.townsteadEnabled.get()
                    && McaCrimeConfig.COMMON.townsteadCommunityService.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Whether anything on this server could credit work of this kind.
     *
     * <p>Asked before an offer is made rather than discovered afterwards: a contract whose progress
     * signal does not exist can only ever fail, and offering one would be worse than offering nothing.
     */
    public static boolean creditable(@Nullable CivicTask task) {
        return task != null && (!task.requiresPropertyLaw() || PropertyRegistry.enabled());
    }

    // ------------------------------------------------------------------ offering

    /**
     * Prices the alternative: what this case would cost in money, if it can be settled at all.
     *
     * <p>Community service is offered exactly where the existing settlement path already offers a
     * choice, and this is how that is decided rather than by a second eligibility ladder. If
     * {@code SettlementPolicy} refuses to quote — jailable Heat, mandatory custody, a case bound to a
     * sentence, an outlaw who must surrender first — then there is no alternative to be an alternative
     * to.
     */
    public static SettlementQuote quoteFor(@Nullable CrimeWorldData data, UUID offender, long heat,
                                           Band band, UUID caseId, long now) {
        if (data == null || offender == null || caseId == null) {
            // Priced through the ordinary path even for the nothing case, so there is exactly one
            // producer of a refusal and no second vocabulary of rejection reasons to keep in step.
            return SettlementPolicy.quote(data, offender, heat, band, List.of(), false, now);
        }
        return SettlementPolicy.quote(data, offender, heat, band, List.of(caseId), false, now);
    }

    /** A case a contract could be written against, or the reason there is not one. */
    public record Eligibility(@Nullable UUID caseId, @Nullable CrimeCommunityKey community,
                              long fineAvoided, @Nullable Refusal refusal) {

        public boolean eligible() {
            return caseId != null && community != null;
        }

        static Eligibility refused(Refusal refusal) {
            return new Eligibility(null, null, 0L, refusal);
        }
    }

    /**
     * Whether a contract <em>could</em> be written, without writing one.
     *
     * <p>Split from {@link #offer} because the action menu asks this question every time it is drawn,
     * and a query that minted a durable contract as a side effect of being asked would fill a world's
     * table with offers nobody ever saw.
     */
    public static Eligibility eligibility(@Nullable MinecraftServer server, @Nullable UUID offender,
                                          @Nullable CivicTask task, @Nullable UUID caseId) {
        if (!enabled()) {
            return Eligibility.refused(Refusal.DISABLED);
        }
        if (server == null || offender == null) {
            return Eligibility.refused(Refusal.NO_CASE);
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        if (!ServerMutationGate.allows(data)) {
            return Eligibility.refused(Refusal.READ_ONLY);
        }
        if (task != null && !creditable(task)) {
            return Eligibility.refused(Refusal.SIGNAL_UNAVAILABLE);
        }
        if (data.openServiceContractFor(offender) != null) {
            return Eligibility.refused(Refusal.ALREADY_CONTRACTED);
        }
        long now = server.overworld().getGameTime();
        ServerPlayer player = server.getPlayerList().getPlayer(offender);
        long heat = player == null ? 0L : CrimeState.getHeat(player);
        Band band = player == null ? Band.GREY : CrimeState.getBand(player);

        List<CrimeRecord> candidates = new ArrayList<>();
        if (caseId == null) {
            candidates.addAll(data.actionableFor(offender));
        } else {
            data.recordById(caseId).ifPresent(candidates::add);
        }
        if (candidates.isEmpty()) {
            return Eligibility.refused(Refusal.NO_CASE);
        }

        Refusal lastRefusal = Refusal.NO_CASE;
        for (CrimeRecord record : candidates) {
            if (!offender.equals(record.offender()) || !record.actionable()) {
                lastRefusal = Refusal.NO_CASE;
                continue;
            }
            CrimeCommunityKey community = record.communityKey().orElse(null);
            if (community == null) {
                lastRefusal = Refusal.NO_COMMUNITY;
                continue;
            }
            if (!record.witnessed()) {
                // §12.1's "public" gate. A case nobody saw is still a real case with real Heat, and it
                // is settled with money in private; a village cannot assign penance for a secret it
                // does not have.
                lastRefusal = Refusal.NOT_PUBLIC;
                continue;
            }
            SettlementQuote quote = quoteFor(data, offender, heat, band, record.id(), now);
            if (quote.reject().isPresent()) {
                lastRefusal = Refusal.NOT_SETTLEABLE;
                continue;
            }
            return new Eligibility(record.id(), community, quote.amount(), null);
        }
        return Eligibility.refused(lastRefusal);
    }

    /**
     * The task a case is offered as when nobody named one.
     *
     * <p>Returning what the offender can actually do something about: goods still outstanding under
     * property law are put back, and everything else is answered to the people who remember it.
     */
    public static CivicTask defaultTask(@Nullable MinecraftServer server, @Nullable UUID offender) {
        if (server != null && offender != null && creditable(CivicTask.RESTITUTION_DELIVERY)
                && !CrimeWorldData.get(server).outstandingPropertyReceipts(offender, null, null)
                        .isEmpty()) {
            return CivicTask.RESTITUTION_DELIVERY;
        }
        return CivicTask.VICTIM_AMENDS;
    }

    /**
     * Offers a contract for one case.
     *
     * @param task   the work to ask for, or null to let the server choose from what this offender can
     *               actually be credited for
     * @param caseId the case to settle, or null to take the offender's oldest eligible open case
     */
    public static Offer offer(@Nullable MinecraftServer server, @Nullable UUID offender,
                              boolean offenderIsPlayer, @Nullable CivicTask task,
                              @Nullable UUID caseId, @Nullable UUID facilityId) {
        CivicTask chosen = task == null ? defaultTask(server, offender) : task;
        Eligibility eligibility = eligibility(server, offender, chosen, caseId);
        if (!eligibility.eligible()) {
            return Offer.refused(eligibility.refusal() == null ? Refusal.NO_CASE
                    : eligibility.refusal());
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        long now = server.overworld().getGameTime();
        ServiceContract contract = ServiceContract.offered(UUID.randomUUID(), eligibility.caseId(),
                offender, offenderIsPlayer, eligibility.community(), chosen, chosen.defaultUnits(),
                now, now + DEFAULT_TERM_TICKS, facilityId);
        if (!data.putServiceContract(contract)) {
            return Offer.refused(Refusal.TABLE_FULL);
        }
        McaCrime.LOGGER.debug("MCA: Crime offered civic contract {} to {} for case {} in {}",
                contract.contractId(), offender, contract.caseId(),
                eligibility.community().asString());
        return Offer.of(contract, eligibility.fineAvoided());
    }

    /** The contract this offender has been offered or has taken, or null. */
    @Nullable
    public static ServiceContract openFor(@Nullable MinecraftServer server, @Nullable UUID offender) {
        return server == null || !enabled() ? null
                : CrimeWorldData.get(server).openServiceContractFor(offender);
    }

    /**
     * Takes up an offer.
     *
     * <p>Idempotent: accepting an already-active contract returns it unchanged, which is what a
     * re-sent packet or a reconnect looks like.
     */
    public static Optional<ServiceContract> accept(@Nullable MinecraftServer server,
                                                   @Nullable UUID offender,
                                                   @Nullable UUID contractId) {
        if (!enabled() || server == null || offender == null) {
            return Optional.empty();
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        ServiceContract contract = contractId == null
                ? data.openServiceContractFor(offender)
                : data.serviceContract(contractId);
        if (contract == null || !offender.equals(contract.offender()) || !contract.open()) {
            return Optional.empty();
        }
        long now = server.overworld().getGameTime();
        if (contract.expired(now)) {
            fail(server, contract, now);
            return Optional.empty();
        }
        ServiceContract accepted = contract.accepted(now);
        if (accepted != contract && !data.putServiceContract(accepted)) {
            return Optional.empty();
        }
        return Optional.of(accepted);
    }

    /** Withdraws a contract. The original fine or sentence stands, because it never left. */
    public static boolean cancel(@Nullable MinecraftServer server, @Nullable UUID contractId) {
        if (server == null || contractId == null) {
            return false;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        ServiceContract contract = data.serviceContract(contractId);
        if (contract == null || !contract.open()) {
            return false;
        }
        long now = server.overworld().getGameTime();
        ServiceContract cancelled = contract.cancelled(now);
        boolean stored = data.putServiceContract(cancelled);
        releaseClaim(cancelled);
        return stored;
    }

    // ------------------------------------------------------------------ progress

    /**
     * Credits work against this offender's active contract, if the task matches.
     *
     * <p>The single entry point, and the reason it takes a {@code dedupeKey} rather than just a count:
     * §12.1 point 7 asks for duplicate output submissions and repeated credits for the same
     * transferred items to be prevented, and the durable one-shot receipt ledger is where that is
     * enforced. The key is whatever identifies the thing that happened — a bounty claim key, a
     * property transfer id, a memory key — so the same event replayed after a crash credits nothing.
     *
     * @return the contract after the credit, or empty when nothing was credited
     */
    public static Optional<ServiceContract> credit(@Nullable MinecraftServer server,
                                                   @Nullable UUID offender, @Nullable CivicTask task,
                                                   @Nullable String dedupeKey, int units) {
        if (!enabled() || server == null || offender == null || task == null || units <= 0) {
            return Optional.empty();
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        if (!ServerMutationGate.allows(data)) {
            return Optional.empty();
        }
        ServiceContract contract = data.openServiceContractFor(offender);
        if (contract == null || !contract.active() || contract.task() != task) {
            return Optional.empty();
        }
        long now = server.overworld().getGameTime();
        if (contract.expired(now)) {
            fail(server, contract, now);
            return Optional.empty();
        }
        UUID receipt = receiptFor(contract.contractId(), dedupeKey);
        if (data.hasOneShotReceipt(receipt)) {
            return Optional.empty(); // this output has already been counted once
        }
        ServiceContract progressed = contract.progressed(units, now);
        if (progressed == contract) {
            return Optional.empty();
        }
        data.recordOneShotReceipt(receipt);
        if (!data.putServiceContract(progressed)) {
            return Optional.empty();
        }
        if (progressed.satisfied()) {
            return Optional.of(complete(server, progressed, now));
        }
        return Optional.of(progressed);
    }

    /**
     * Settles the contract's case, exactly once, and tells the settlement about it.
     *
     * <p>The settlement itself goes through {@code FineService} with a purse that always pays: the
     * work is the payment, so the price is re-quoted now, the case is put to the same resolution gate
     * a paid fine faces, and the ledger records the same disposition. Nothing mints currency and
     * nothing else about the offender's record is touched.
     */
    private static ServiceContract complete(MinecraftServer server, ServiceContract contract, long now) {
        CrimeWorldData data = CrimeWorldData.get(server);
        ServiceContract completed = contract.completed(now);
        if (completed == contract) {
            return contract; // not satisfied, or already completed: nothing is settled twice
        }
        if (!data.putServiceContract(completed)) {
            return contract;
        }
        releaseClaim(completed);

        ServerPlayer player = server.getPlayerList().getPlayer(completed.offender());
        long heat = player == null ? 0L : CrimeState.getHeat(player);
        Band band = player == null ? Band.GREY : CrimeState.getBand(player);
        SettlementQuote quote = quoteFor(data, completed.offender(), heat, band, completed.caseId(), now);
        if (quote.reject().isPresent()) {
            // The case moved under the contract -- pardoned, served, settled by a payment. The work was
            // still done, so the contract stands completed; there is simply nothing left to settle.
            McaCrime.LOGGER.info("MCA: Crime — civic contract {} finished, but case {} is no longer "
                    + "settleable ({}); nothing was resolved twice.", completed.contractId(),
                    completed.caseId(), quote.reject().get().name().toLowerCase(Locale.ROOT));
            notify(player, "mcacrime.civic.completed_nothing");
            return completed;
        }
        UUID transactionId = receiptFor(completed.contractId(), "settlement");
        FineService.Payment payment = FineService.pay(data, now, quote, amount -> true,
                CrimeCaseService.ResolutionGate.ALLOW_ALL, transactionId,
                CrimeCaseService.ResolutionSink.forServer(server));
        if (!payment.paid()) {
            McaCrime.LOGGER.info("MCA: Crime — civic contract {} finished but its case could not be "
                    + "settled ({}); the original sentence stands.", completed.contractId(),
                    payment.messageKey());
            notify(player, "mcacrime.civic.completed_nothing");
            return completed;
        }
        if (player != null) {
            CrimeState.setHeat(player, payment.newHeat(), McaCrime.id("civic_service"),
                    "civic:" + completed.contractId());
            RelationshipConsequences.applyRestitution(player, payment.amount());
            player.sendSystemMessage(Component.translatable("mcacrime.civic.completed",
                    Component.translatable(completed.task().labelKey())));
        }
        // The property half of restitution, and only when property law is actually on: a settled case
        // resolves the losses recorded against it exactly as a paid fine does (§10.6).
        if (PropertyRegistry.enabled()) {
            dev.otectus.mcacrime.property.PropertyTheftService.restoreForCases(server,
                    payment.settledCaseIds(), now);
        }
        announce(server, completed);
        return completed;
    }

    /**
     * The one durable civic effect a completed contract produces.
     *
     * <p>Exactly one, and through the existing reaction path rather than a second one: the contract id
     * is the idempotency key, so a replayed completion finds the receipt already written and enqueues
     * nothing. {@code RESTITUTION_COMPLETED} is the right event because that is what happened — a debt
     * to this settlement was settled — and using the arrest or release events instead would have the
     * village react as though somebody had been jailed.
     */
    private static void announce(MinecraftServer server, ServiceContract contract) {
        siteOf(server, contract).ifPresent(site -> TownsteadReactions.enqueue(server,
                TownsteadReactionEvent.RESTITUTION_COMPLETED, contract.community(), site.level(),
                site.pos(), contract.contractId()));
    }

    /** Where the settlement should be seen to happen: the offender, or the facility they worked at. */
    private static Optional<Site> siteOf(MinecraftServer server, ServiceContract contract) {
        ServerPlayer player = server.getPlayerList().getPlayer(contract.offender());
        if (player != null && player.level() instanceof ServerLevel level) {
            return Optional.of(new Site(level, player.blockPosition()));
        }
        FacilityAssignment facility = contract.facilityId() == null ? null
                : CrimeWorldData.get(server).facility(contract.facilityId());
        if (facility != null) {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.dimension().location().equals(facility.ref().dimension())) {
                    return Optional.of(new Site(level, facility.anchor()));
                }
            }
        }
        // No place to put an audience. A reaction needs somewhere for residents to be near, and the
        // world origin is not it, so nothing is queued.
        return Optional.empty();
    }

    private record Site(ServerLevel level, BlockPos pos) {
    }

    /** Marks a contract failed. The original sentence stands because nothing was ever taken off it. */
    public static ServiceContract fail(MinecraftServer server, ServiceContract contract, long now) {
        ServiceContract failed = contract.failed(now);
        if (failed != contract) {
            CrimeWorldData.get(server).putServiceContract(failed);
            releaseClaim(failed);
            ServerPlayer player = server.getPlayerList().getPlayer(failed.offender());
            notify(player, "mcacrime.civic.failed");
            McaCrime.LOGGER.debug("MCA: Crime — civic contract {} lapsed; case {} is untouched.",
                    failed.contractId(), failed.caseId());
        }
        return failed;
    }

    // ------------------------------------------------------------------ upkeep

    /**
     * Fails lapsed contracts and keeps the NPC claims alive.
     *
     * <p>Called from the civic tick, not from a per-entity scan: the loop is over contracts, of which
     * there are at most a few hundred, and it touches the world only to look up the villagers that
     * actually have one.
     *
     * @return how many contracts lapsed
     */
    public static int sweep(@Nullable MinecraftServer server) {
        if (!enabled() || server == null) {
            return 0;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        if (!ServerMutationGate.allows(data)) {
            return 0;
        }
        long now = server.overworld().getGameTime();
        int lapsed = 0;
        for (ServiceContract contract : data.serviceContracts()) {
            if (!contract.open()) {
                continue;
            }
            if (contract.expired(now)) {
                fail(server, contract, now);
                lapsed++;
                continue;
            }
            if (contract.active() && !contract.offenderIsPlayer()) {
                holdClaim(server, contract, now);
            }
        }
        return lapsed;
    }

    /**
     * Takes or renews the activity claim on an NPC working off a contract.
     *
     * <p>Three things can stop it, and each one is the same rule the rest of the mod already follows.
     * A villager Townstead has floored is incapable, so the claim is dropped rather than holding a
     * collapsed worker to a job ({@code respectIncapacity}). A villager whose Townstead schedule says
     * rest is off duty, and civic work does not run through somebody's night. And a stronger claim —
     * an arrest, an escort, custody — simply refuses this one, which is what the authority ordering is
     * for: community service must never outrank the law.
     */
    private static void holdClaim(MinecraftServer server, ServiceContract contract, long now) {
        Entity villager = findEntity(server, contract);
        if (villager == null) {
            return; // unloaded: the lease lapses on its own, which is what leases are for
        }
        if (!claimable(NpcAwareness.canNavigate(villager), resting(villager))) {
            CrimeActivityRegistry.releaseOwned(contract.offender(),
                    CrimeActivityView.Kind.CIVIC_SERVICE, CLAIM_OWNER);
            return;
        }
        CrimeActivityRegistry.claim(villager, CrimeActivityView.Kind.CIVIC_SERVICE, CLAIM_OWNER, now);
    }

    /**
     * The claim rule on its own: capable, and not on their rest block.
     *
     * <p>Split out because it is the one part of {@link #holdClaim} worth asserting and the rest needs
     * a server, a level and a loaded villager to say anything at all.
     */
    static boolean claimable(boolean canNavigate, boolean resting) {
        return canNavigate && !resting;
    }

    /** Whether Townstead says this villager is on their rest block. Unknown reads as "not resting". */
    private static boolean resting(Entity villager) {
        TownsteadScheduleView schedule = TownsteadBridge.schedule(villager).orElse(null);
        return schedule != null && schedule.known() && schedule.resting();
    }

    @Nullable
    private static Entity findEntity(MinecraftServer server, ServiceContract contract) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(contract.offender());
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    /** Drops the claim a contract was holding, if it was holding one. */
    static void releaseClaim(ServiceContract contract) {
        if (!contract.offenderIsPlayer()) {
            CrimeActivityRegistry.releaseOwned(contract.offender(),
                    CrimeActivityView.Kind.CIVIC_SERVICE, CLAIM_OWNER);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The one-shot receipt id for one contract and one credited output.
     *
     * <p>Derived rather than random, which is the whole anti-farming mechanism: the same output always
     * produces the same id, so the second credit for it finds the receipt already written.
     */
    public static UUID receiptFor(UUID contractId, @Nullable String dedupeKey) {
        String key = KEY_NAMESPACE + contractId + ":" + (dedupeKey == null ? "" : dedupeKey);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private static void notify(@Nullable ServerPlayer player, String key) {
        if (player != null) {
            player.sendSystemMessage(Component.translatable(key));
        }
    }
}
