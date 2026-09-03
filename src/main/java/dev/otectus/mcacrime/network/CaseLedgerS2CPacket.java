package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.ledger.Resolution;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

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
public record CaseLedgerS2CPacket(List<Row> rows, int totalOpen,
                                  long totalDue) implements CustomPacketPayload {

    /** Row cap. A dossier is a summary; a player with more open cases than this has a bigger problem. */
    public static final int MAX_ROWS = 64;

    public static final Type<CaseLedgerS2CPacket> TYPE = new Type<>(McaCrime.id("case_ledger"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CaseLedgerS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    Row.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ROWS)), CaseLedgerS2CPacket::rows,
                    ByteBufCodecs.VAR_INT, CaseLedgerS2CPacket::totalOpen,
                    ByteBufCodecs.VAR_LONG, CaseLedgerS2CPacket::totalDue,
                    CaseLedgerS2CPacket::new);

    /**
     * One case as the screen needs it.
     *
     * @param witnessed whether anybody saw it — the single fact that most changes what a player can
     *                  expect to happen next, and the reason it is on the row rather than in a tooltip
     */
    public record Row(UUID caseId, ResourceLocation crimeType, Resolution resolution,
                      long committedAt, long fine, boolean witnessed, String community) {

        /** A community label, not the key's own string form; a village name never needs more. */
        public static final int MAX_COMMUNITY_LENGTH = 128;

        /** Seven fields, one past what {@code StreamCodec.composite} carries, so written by hand. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Row> STREAM_CODEC =
                StreamCodec.of(Row::write, Row::read);

        public Row {
            resolution = resolution == null ? Resolution.UNRESOLVED : resolution;
            community = community == null ? "" : community;
            fine = Math.max(0L, fine);
        }

        private static void write(RegistryFriendlyByteBuf buf, Row row) {
            buf.writeUUID(row.caseId());
            buf.writeResourceLocation(row.crimeType());
            buf.writeVarInt(row.resolution().ordinal());
            buf.writeVarLong(row.committedAt());
            buf.writeVarLong(row.fine());
            buf.writeBoolean(row.witnessed());
            buf.writeUtf(row.community(), MAX_COMMUNITY_LENGTH);
        }

        private static Row read(RegistryFriendlyByteBuf buf) {
            UUID caseId = buf.readUUID();
            ResourceLocation type = buf.readResourceLocation();
            int ordinal = buf.readVarInt();
            Resolution[] resolutions = Resolution.values();
            if (ordinal < 0 || ordinal >= resolutions.length) {
                throw new DecoderException("mcacrime: case resolution ordinal " + ordinal
                        + " outside [0, " + (resolutions.length - 1) + "]");
            }
            return new Row(caseId, type, resolutions[ordinal], buf.readVarLong(), buf.readVarLong(),
                    buf.readBoolean(), buf.readUtf(MAX_COMMUNITY_LENGTH));
        }
    }

    public CaseLedgerS2CPacket {
        rows = rows == null ? List.of() : List.copyOf(rows).stream().limit(MAX_ROWS).toList();
        totalOpen = Math.max(0, totalOpen);
        totalDue = Math.max(0L, totalDue);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
