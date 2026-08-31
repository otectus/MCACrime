package dev.otectus.mcacrime.economy;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.FinePaidEvent;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.ledger.CrimeCaseService;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.relationship.RelationshipConsequences;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
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
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        long heat = CrimeState.getHeat(player);

        if (!c.enableFines.get()) {
            return refused(player, heat, "mcacrime.fine.disabled");
        }
        Band band = CrimeState.getBand(player);
        // Reuse the existing pure gate: it decides whether a fine is available at all (jailable Heat,
        // barred Red) and produces the whole-Heat price under the same rules as before.
        OptionalLong fineOpt = FineCalculator.fineFor(heat, band, c.fineBase.get(), c.finePerHeat.get(),
                c.jailableHeatThreshold.get(), c.blueFineMultiplier.get(), c.redCanPayFine.get());
        if (fineOpt.isEmpty()) {
            String key = heat >= c.jailableHeatThreshold.get()
                    ? "mcacrime.fine.notfinable"
                    : "mcacrime.fine.barred";
            return refused(player, heat, key);
        }

        MinecraftServer server = player.getServer();
        List<FineAllocation.FineCase> actionable = actionableCases(server, player, requestedCaseIds);
        double bandMultiplier = band == Band.BLUE ? c.blueFineMultiplier.get() : 1.0D;
        FineAllocation.Allocation allocation = FineAllocation.allocate(actionable, heat,
                c.fineBase.get(), c.finePerHeat.get(), bandMultiplier,
                c.maxCasesPerFinePayment.get(), payAll);

        long cost = allocation.caseIds().isEmpty() && payAll ? fineOpt.getAsLong() : allocation.totalCost();
        if (cost <= 0L) {
            return refused(player, heat, "mcacrime.fine.nothing");
        }
        if (!EmeraldCurrency.INSTANCE.tryCharge(player, cost)) {
            player.sendSystemMessage(Component.translatable("mcacrime.fine.need", cost));
            return new Payment(false, null, List.of(), cost, heat, heat, "mcacrime.fine.need");
        }

        // Charged exactly once, above. Everything below is bookkeeping on state we now own.
        UUID transactionId = UUID.randomUUID();
        List<UUID> settled = settleCases(server, allocation.caseIds(), transactionId);

        long newHeat = Math.max(0L, heat - allocation.heatCleared());
        CrimeState.setHeat(player, newHeat, McaCrime.id("fine"), "fine:" + transactionId);
        RelationshipConsequences.applyRestitution(player); // §11.3: a fine repairs some community standing

        MinecraftForge.EVENT_BUS.post(new FinePaidEvent(player, transactionId, settled, cost, heat, newHeat));
        // One merged message, not one per case: the player performed one act.
        player.sendSystemMessage(settled.isEmpty()
                ? Component.translatable("mcacrime.fine.paid", cost)
                : Component.translatable("mcacrime.fine.paid_cases", cost, settled.size()));
        return new Payment(true, transactionId, settled, cost, heat, newHeat, "mcacrime.fine.paid");
    }

    // ------------------------------------------------------------------ internals

    private static Payment refused(ServerPlayer player, long heat, String messageKey) {
        player.sendSystemMessage(Component.translatable(messageKey));
        return new Payment(false, null, List.of(), 0L, heat, heat, messageKey);
    }

    /**
     * The cases this payment may settle: either exactly the ones asked for, or every open case
     * oldest-first. A requested id that is not the player's own, or is already settled, is silently
     * dropped rather than honoured — a caller must never be able to resolve somebody else's case.
     */
    private static List<FineAllocation.FineCase> actionableCases(MinecraftServer server, ServerPlayer player,
                                                                 List<UUID> requestedCaseIds) {
        if (server == null) {
            return List.of();
        }
        List<CrimeRecord> open = CrimeWorldData.get(server).actionableFor(player.getUUID());
        List<FineAllocation.FineCase> out = new ArrayList<>(open.size());
        for (CrimeRecord record : open) {
            if (!requestedCaseIds.isEmpty() && !requestedCaseIds.contains(record.id())) {
                continue;
            }
            out.add(new FineAllocation.FineCase(record.id(), record.heatGenerated(),
                    record.fineAmount(), record.timeCommitted()));
        }
        return out;
    }

    /** Marks each allocated case {@code FINED} and stamps the payment that settled it. */
    private static List<UUID> settleCases(MinecraftServer server, List<UUID> caseIds, UUID transactionId) {
        if (server == null || caseIds.isEmpty()) {
            return List.of();
        }
        List<UUID> settled = new ArrayList<>(caseIds.size());
        for (UUID caseId : caseIds) {
            CrimeCaseService.Result result = CrimeCaseService.resolve(server, caseId, Resolution.FINED,
                    McaCrime.id("fine"), "fine:" + transactionId, null,
                    Map.of(CrimeContext.FINE_TRANSACTION, transactionId.toString()), false);
            if (result.successful()) {
                settled.add(caseId);
            }
        }
        return settled;
    }
}
