package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import dev.otectus.mcacrime.ledger.Resolution;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The player's own case file, for the dossier screen.
 *
 * <p>Scoped to the requesting player and nobody else. A screen that could ask about another player's
 * record would be a way of reading the ledger through the client, so the request carries no subject at
 * all — the server answers about whoever sent it, which is the only subject it will answer about.
 *
 * <p>Rows carry a crime <em>type id</em> rather than rendered text, so the dossier localises like
 * everything else and a resource pack can rename a crime without the server knowing.
 */
public record CaseLedgerS2CPacket(List<Row> rows, int totalOpen, long totalDue) {

    /** Row cap. A dossier is a summary; a player with more open cases than this has a bigger problem. */
    public static final int MAX_ROWS = 64;

    private static final Resolution[] RESOLUTIONS = Resolution.values();

    /**
     * One case as the screen needs it.
     *
     * @param witnessed whether anybody saw it — the single fact that most changes what a player can
     *                  expect to happen next, and the reason it is on the row rather than in a tooltip
     */
    public record Row(UUID caseId, ResourceLocation crimeType, Resolution resolution,
                      long committedAt, long fine, boolean witnessed, String community) {
        public Row {
            resolution = resolution == null ? Resolution.UNRESOLVED : resolution;
            community = community == null ? "" : community;
            fine = Math.max(0L, fine);
        }
    }

    public CaseLedgerS2CPacket {
        rows = rows == null ? List.of() : List.copyOf(rows);
        totalOpen = Math.max(0, totalOpen);
        totalDue = Math.max(0L, totalDue);
    }

    public static void encode(CaseLedgerS2CPacket msg, FriendlyByteBuf buf) {
        List<Row> rows = msg.rows.stream().limit(MAX_ROWS).toList();
        buf.writeVarInt(rows.size());
        for (Row row : rows) {
            buf.writeUUID(row.caseId());
            buf.writeResourceLocation(row.crimeType());
            buf.writeVarInt(row.resolution().ordinal());
            buf.writeVarLong(row.committedAt());
            buf.writeVarLong(row.fine());
            buf.writeBoolean(row.witnessed());
            buf.writeUtf(row.community(), 128);
        }
        buf.writeVarInt(msg.totalOpen);
        buf.writeVarLong(msg.totalDue);
    }

    public static CaseLedgerS2CPacket decode(FriendlyByteBuf buf) {
        int count = Math.min(MAX_ROWS, Math.max(0, buf.readVarInt()));
        List<Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID caseId = buf.readUUID();
            ResourceLocation type = buf.readResourceLocation();
            int ordinal = buf.readVarInt();
            Resolution resolution = ordinal >= 0 && ordinal < RESOLUTIONS.length
                    ? RESOLUTIONS[ordinal] : Resolution.UNRESOLVED;
            rows.add(new Row(caseId, type, resolution, buf.readVarLong(), buf.readVarLong(),
                    buf.readBoolean(), buf.readUtf(128)));
        }
        return new CaseLedgerS2CPacket(rows, buf.readVarInt(), buf.readVarLong());
    }

    public static void handle(CaseLedgerS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CrimeClientHandlers.onCaseLedger(msg)));
        context.setPacketHandled(true);
    }
}
