package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.economy.FineCalculator;
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
import java.util.OptionalLong;

/**
 * "Show me my record." Carries no arguments on purpose.
 *
 * <p>A request that named a subject would be a client-supplied lookup key into the ledger, and there
 * is no version of that which is not eventually used to read somebody else's record. The server
 * answers about the sender and only the sender, so there is nothing to validate and nothing to forge.
 *
 * <p>Rate limiting is by cooldown rather than by refusal: spamming the key rebuilds a bounded list
 * from an in-memory index, which is cheap, but there is no reason to do it sixty times a second.
 */
public record RequestCaseLedgerC2SPacket() implements CustomPacketPayload {

    public static final Type<RequestCaseLedgerC2SPacket> TYPE =
            new Type<>(McaCrime.id("request_case_ledger"));

    /** No fields, so nothing goes on the wire but the payload id itself. */
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestCaseLedgerC2SPacket> STREAM_CODEC =
            StreamCodec.unit(new RequestCaseLedgerC2SPacket());

    /** Minimum ticks between one player's dossier builds. */
    private static final long COOLDOWN_TICKS = 10L;

    private static final java.util.Map<java.util.UUID, Long> LAST_REQUEST = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Answers one request, subject to the per-player cooldown. Called from {@link CrimeNetwork}. */
    static void respond(ServerPlayer sender) {
        long now = sender.level().getGameTime();
        Long last = LAST_REQUEST.get(sender.getUUID());
        if (last != null && now - last < COOLDOWN_TICKS) {
            return;
        }
        LAST_REQUEST.put(sender.getUUID(), now);
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
        OptionalLong due = FineCalculator.fineFor(CrimeState.getHeat(player), CrimeState.getBand(player),
                McaCrimeConfig.COMMON.fineBase.get(), McaCrimeConfig.COMMON.finePerHeat.get(),
                McaCrimeConfig.COMMON.jailableHeatThreshold.get(),
                McaCrimeConfig.COMMON.blueFineMultiplier.get(), McaCrimeConfig.COMMON.redCanPayFine.get());
        return new CaseLedgerS2CPacket(rows, open, due.orElse(0L));
    }

    /** Drops a player's cooldown stamp on logout, so the map cannot grow for the life of the server. */
    public static void forget(java.util.UUID player) {
        LAST_REQUEST.remove(player);
    }
}
