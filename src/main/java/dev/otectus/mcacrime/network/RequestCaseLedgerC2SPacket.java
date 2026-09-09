package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.economy.SettlementPolicy;
import dev.otectus.mcacrime.economy.SettlementQuote;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * "Show me my record." Carries no arguments on purpose.
 *
 * <p>A request that named a subject would be a client-supplied lookup key into the ledger, and there
 * is no version of that which is not eventually used to read somebody else's record. The server
 * answers about the sender and only the sender, so there is nothing to validate and nothing to forge.
 *
 * <p>Rate limiting lives in {@link RequestBudget}, whose DOSSIER category is one request per ten
 * ticks with no burst. That is the cooldown this payload used to keep for itself, in the one place
 * that is emptied when a player logs out.
 */
public record RequestCaseLedgerC2SPacket() implements CustomPacketPayload {

    public static final Type<RequestCaseLedgerC2SPacket> TYPE =
            new Type<>(McaCrime.id("request_case_ledger"));

    /** No fields, so nothing goes on the wire but the payload id itself. */
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestCaseLedgerC2SPacket> STREAM_CODEC =
            StreamCodec.unit(new RequestCaseLedgerC2SPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Answers one request. The rate is {@link CrimeNetwork}'s budget check, not this method's. */
    static void respond(ServerPlayer sender) {
        CrimeNetwork.sendCaseLedger(sender, build(sender));
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
