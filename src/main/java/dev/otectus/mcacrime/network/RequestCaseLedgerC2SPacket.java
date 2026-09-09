package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.economy.SettlementPolicy;
import dev.otectus.mcacrime.economy.SettlementQuote;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * "Show me my record." Carries no arguments on purpose.
 *
 * <p>A request that named a subject would be a client-supplied lookup key into the ledger, and there
 * is no version of that which is not eventually used to read somebody else's record. The server
 * answers about the sender and only the sender, so there is nothing to validate and nothing to forge.
 *
 * <p>Rate limiting is {@link RequestBudget.Category#DOSSIER}, shared with every other C2S packet
 * rather than kept in a private map here: spamming the key rebuilds a bounded list from an in-memory
 * index, which is cheap, but there is no reason to do it sixty times a second.
 */
public record RequestCaseLedgerC2SPacket() {

    public static void encode(RequestCaseLedgerC2SPacket msg, FriendlyByteBuf buf) {
    }

    public static RequestCaseLedgerC2SPacket decode(FriendlyByteBuf buf) {
        return new RequestCaseLedgerC2SPacket();
    }

    public static void handle(RequestCaseLedgerC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.DOSSIER,
                sender -> CrimeNetwork.sendCaseLedger(sender, build(sender)));
    }

    /** Builds the dossier: newest cases first, with the current assessed total. */
    private static CaseLedgerS2CPacket build(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return new CaseLedgerS2CPacket(List.of(), 0, 0L);
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        List<CrimeRecord> records = data.recordsForOffender(player.getUUID());
        List<CaseLedgerS2CPacket.Row> rows = new ArrayList<>(Math.min(records.size(), CaseLedgerS2CPacket.MAX_ROWS));
        int open = 0;
        for (CrimeRecord record : records) {
            if (record.actionable()) {
                open++;
            }
            if (rows.size() < CaseLedgerS2CPacket.MAX_ROWS) {
                rows.add(new CaseLedgerS2CPacket.Row(record.id(), record.type(), record.resolution(),
                        record.timeCommitted(), record.fineAmount(), record.witnessed(),
                        record.communityKey().map(key -> key.asString()).orElse("")));
            }
        }
        // The same quote the guard screen shows and the payment charges. The dossier used to print
        // FineCalculator's whole-Heat figure, which was not what /crime payfine took off the player.
        var challenge = dev.otectus.mcacrime.enforcement.GuardChallengeService.open(player.getUUID());
        SettlementQuote quote = challenge != null && challenge.offer() != null ? challenge.offer().quote()
                : SettlementPolicy.quote(data, player.getUUID(), CrimeState.getHeat(player),
                        CrimeState.getBand(player), server.overworld().getGameTime());
        return new CaseLedgerS2CPacket(rows, open, quote.amount());
    }
}
