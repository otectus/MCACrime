package dev.otectus.mcacrime.economy;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.api.event.FinePaidEvent;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.ledger.CrimeCaseService;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.relationship.RelationshipConsequences;
import dev.otectus.mcacrime.economy.account.EconomicTransactionService;
import dev.otectus.mcacrime.economy.account.PurseAccess;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /crime payfine} (spec §6.1): clears Wanted status by paying an emerald fine. Server-authoritative
 * and atomic — the charge either fully succeeds (then Heat is cleared via the {@link CrimeState} chokepoint)
 * or nothing is taken. Severe (jailable) Heat and barred Red players are rejected with a clear message.
 *
 * <p>Payment now also <b>settles specific cases</b>. The money moving was never the interesting part;
 * what makes a fine mean something is that the ledger afterwards says which offences were answered
 * for. Without that, a player who paid still had every case open against them and no companion mod
 * could tell restitution from a coincidence.
 */
public final class FineService {

    private FineService() {
    }

    /** The outcome of a payment, for the typed API and for the command wrapper alike. */
    public record Payment(boolean paid, UUID transactionId, List<UUID> settledCaseIds,
                          long amount, long oldHeat, long newHeat, String messageKey) {
    }

    /**
     * The command entry point. Behaviour is unchanged from the player's point of view: one payment,
     * all Heat cleared, the same messages.
     */
    public static int payFine(ServerPlayer player) {
        Payment payment = pay(player, List.of(), true);
        return payment.paid() ? 1 : 0;
    }

    /**
     * Pays a fine, settling either the given cases or the oldest actionable ones.
     *
     * @param requestedCaseIds exact cases to settle, or empty to let the server choose oldest-first
     * @param payAll           settle everything outstanding and clear all Heat
     */
    public static Payment pay(ServerPlayer player, List<UUID> requestedCaseIds, boolean payAll) {
        MinecraftServer server = player.getServer();
        CrimeWorldData data = server == null ? null : CrimeWorldData.get(server);
        long now = server == null ? 0L : server.overworld().getGameTime();
        if (data != null && !ServerMutationGate.allows(data)) {
            player.sendSystemMessage(Component.translatable("mcacrime.readonly"));
            return refused(CrimeState.getHeat(player), "mcacrime.readonly");
        }
        // Minted before the charge, not after, because the receipt the charge writes has to name it.
        UUID transactionId = UUID.randomUUID();
        Payment payment = pay(data, now,
                SettlementPolicy.quote(data, player.getUUID(), CrimeState.getHeat(player),
                        CrimeState.getBand(player), requestedCaseIds, payAll, now),
                amount -> charge(data, transactionId, player, amount, now),
                CrimeCaseService.ResolutionGate.ALLOW_ALL, transactionId,
                CrimeCaseService.ResolutionSink.forServer(server));
        if (!payment.paid()) {
            // The amount is only interesting on the one refusal that quotes a price back at the player.
            player.sendSystemMessage("mcacrime.fine.need".equals(payment.messageKey())
                    ? Component.translatable(payment.messageKey(), payment.amount())
                    : Component.translatable(payment.messageKey()));
            return payment;
        }
        CrimeState.setHeat(player, payment.newHeat(), McaCrime.id("fine"), "fine:" + payment.transactionId());
        RelationshipConsequences.applyRestitution(player, payment.amount()); // §11.3: a fine repairs some community standing

        NeoForge.EVENT_BUS.post(new FinePaidEvent(player, payment.transactionId(),
                payment.settledCaseIds(), payment.amount(), payment.oldHeat(), payment.newHeat()));
        // One merged message, not one per case: the player performed one act.
        player.sendSystemMessage(payment.settledCaseIds().isEmpty()
                ? Component.translatable("mcacrime.fine.paid", payment.amount())
                : Component.translatable("mcacrime.fine.paid_cases", payment.amount(),
                        payment.settledCaseIds().size()));
        return payment;
    }

    /**
     * Where the money goes when a fine is charged. The player overload binds it to the active
     * currency; a test binds it to a counter, which is the only way to assert that nothing is debited
     * on a refusal without standing a server up to hold the emeralds.
     */
    @FunctionalInterface
    public interface Purse {
        /** Takes {@code amount} in full, or takes nothing and returns false. */
        boolean charge(long amount);
    }

    /**
     * The payment itself: eligibility, allocation, the charge, and the cases it settles.
     *
     * <p>Everything a {@link ServerPlayer} is needed for — writing Heat back through the
     * {@link CrimeState} chokepoint, the restitution nudge, the event, the messages — is deliberately
     * outside this method and applied by the caller from the {@link Payment} it returns. What is left
     * is the part that decides how much is owed and which charges it answers, which is the part worth
     * asserting on.
     *
     * @param heat  the offender's Heat at the moment of payment; the caller owns the capability
     * @param purse the debit, performed exactly once and only after the price is settled
     */
    public static Payment pay(CrimeWorldData data, long now, UUID offender, long heat, Band band,
                              List<UUID> requestedCaseIds, boolean payAll, Purse purse,
                              CrimeCaseService.ResolutionGate gate) {
        return pay(data, now, SettlementPolicy.quote(data, offender, heat, band, requestedCaseIds,
                payAll, now), purse, gate);
    }

    /**
     * Charges a quote.
     *
     * <p>The order below is the point of the method. The quote is checked for age and for cases that
     * moved under it, then every case is put to the {@link CrimeCaseService.ResolutionGate} while
     * nothing has been taken yet, and only then is the purse debited. Doing the preflight after the
     * charge — which is what "debit, then resolve, and hope" amounts to — means a refusal leaves the
     * player poorer and the case open, and there is no way back from that inside one tick.
     *
     * <p>Nothing here reads config: the price and the eligibility both arrived in the quote.
     */
    public static Payment pay(CrimeWorldData data, long now, SettlementQuote quote, Purse purse,
                              CrimeCaseService.ResolutionGate gate) {
        return pay(data, now, quote, purse, gate, UUID.randomUUID());
    }

    /** The same, under an id the caller has already minted so a receipt can name it. */
    public static Payment pay(CrimeWorldData data, long now, SettlementQuote quote, Purse purse,
                              CrimeCaseService.ResolutionGate gate, UUID transactionId) {
        return pay(data, now, quote, purse, gate, transactionId, CrimeCaseService.ResolutionSink.NONE);
    }

    /**
     * The same again, announcing each case the payment settles through {@code sink}.
     *
     * <p>Settling a case is not only a write. It queues the companion mod's incident update and posts
     * {@code CrimeRecordResolvedEvent}, which is what makes paying a fine read publicly as making good
     * — and for one release, routing the payment through the ledger overload dropped both on the floor
     * without dropping the settlement, so the cases closed and nobody was told.
     */
    public static Payment pay(CrimeWorldData data, long now, SettlementQuote quote, Purse purse,
                              CrimeCaseService.ResolutionGate gate, UUID transactionId,
                              CrimeCaseService.ResolutionSink sink) {
        if (quote == null) {
            return refused(0L, SettlementQuote.RejectReason.NOTHING_OWED.messageKey());
        }
        if (quote.reject().isPresent()) {
            return refused(quote.heat(), quote.reject().get().messageKey());
        }
        if (quote.expired(now) || !current(data, quote)) {
            return refused(quote.heat(), SettlementQuote.RejectReason.STALE.messageKey());
        }
        for (SettlementQuote.CaseRef ref : quote.cases()) {
            CrimeRecord record = data == null ? null : data.recordById(ref.id()).orElse(null);
            if (record != null && gate != null && !gate.allow(record, Resolution.FINED)) {
                // A veto is not a failed payment, because no payment happened: this is the one exit
                // that has to come before the purse is touched.
                return refused(quote.heat(), "mcacrime.fine.notfinable");
            }
        }
        if (!purse.charge(quote.amount())) {
            return new Payment(false, null, List.of(), quote.amount(), quote.heat(), quote.heat(),
                    "mcacrime.fine.need");
        }

        // Charged exactly once, above. Everything below is bookkeeping on state we now own.
        List<UUID> settled = settleCases(data, now, quote.caseIds(), transactionId, gate, sink);

        long newHeat = Math.max(0L, quote.heat() - quote.heatCleared());
        return new Payment(true, transactionId, settled, quote.amount(), quote.heat(), newHeat,
                "mcacrime.fine.paid");
    }

    // ------------------------------------------------------------------ internals

    /**
     * The debit, through the receipt ledger so the money leaving is durable before anything else.
     *
     * <p>A fine has no recipient -- the emeralds are destroyed rather than paid to a village -- so the
     * credit half of the transfer is trivially successful. What the receipt buys is the debit side: a
     * crash between the charge and the settlement leaves a row saying the player paid.
     */
    private static boolean charge(CrimeWorldData data, UUID transactionId, ServerPlayer player,
                                  long amount, long now) {
        if (data == null) {
            return Currencies.active().tryCharge(player, amount, TransactionReason.FINE);
        }
        return EconomicTransactionService.transfer(data, transactionId, TransactionReason.FINE,
                Currencies.active().id().toString(), new PurseAccess() {
                    @Override
                    public long available() {
                        return Currencies.active().balance(player);
                    }

                    @Override
                    public long debit(long requested, TransactionReason reason) {
                        return Currencies.active().tryCharge(player, requested, reason) ? requested : 0L;
                    }

                    @Override
                    public boolean credit(long credited, TransactionReason reason) {
                        return true; // nobody is paid a fine; the debit is the whole transfer
                    }
                }, amount, false, player.getUUID(), null, now).delivered();
    }

    private static Payment refused(long heat, String messageKey) {
        return new Payment(false, null, List.of(), 0L, heat, heat, messageKey);
    }

    /**
     * Whether every case in the quote still stands where it stood when it was priced.
     *
     * <p>{@code resolutionRevision} moves on every disposition change, so this catches the case that
     * was pardoned, served, or settled by another payment between the offer and the click without
     * needing a lock over the ledger.
     */
    private static boolean current(CrimeWorldData data, SettlementQuote quote) {
        if (quote.cases().isEmpty()) {
            return true; // a Heat-only settlement prices no case and so has nothing to go stale
        }
        if (data == null) {
            return false;
        }
        for (SettlementQuote.CaseRef ref : quote.cases()) {
            CrimeRecord record = data.recordById(ref.id()).orElse(null);
            if (record == null || !record.actionable() || record.resolutionRevision() != ref.revision()) {
                return false;
            }
        }
        return true;
    }

    /** Marks each allocated case {@code FINED} and stamps the payment that settled it. */
    private static List<UUID> settleCases(CrimeWorldData data, long now, List<UUID> caseIds,
                                          UUID transactionId, CrimeCaseService.ResolutionGate gate,
                                          CrimeCaseService.ResolutionSink sink) {
        if (data == null || caseIds.isEmpty()) {
            return List.of();
        }
        List<UUID> settled = new ArrayList<>(caseIds.size());
        for (UUID caseId : caseIds) {
            CrimeCaseService.Result result = CrimeCaseService.resolve(data, now, caseId, Resolution.FINED,
                    McaCrime.id("fine"), "fine:" + transactionId, null,
                    Map.of(CrimeContext.FINE_TRANSACTION, transactionId.toString()), false, gate, sink);
            if (result.successful()) {
                settled.add(caseId);
            }
        }
        return settled;
    }
}
