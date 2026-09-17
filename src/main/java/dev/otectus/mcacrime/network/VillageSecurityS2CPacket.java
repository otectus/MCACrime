package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.civic.VillageSecurityView;
import dev.otectus.mcacrime.client.CrimeClientHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: how safe the settlement the player is standing in currently is.
 *
 * <h2>What is deliberately not in here</h2>
 *
 * <p>Reference §13.3 is blunt about it: a new public UI gets its own authorised projection, not a
 * convenient existing snapshot. So this carries counts and a score and nothing that identifies anybody
 * — no case ids, no offender names, no victims, no witnesses, no facility positions. Everything in it
 * is derived from {@link VillageSecurityView}, which is itself built only from incidents that have
 * already passed the public-knowledge rule, so there is no path from this packet back to a crime
 * nobody saw.
 *
 * <p>The counts are what a player could establish by standing in the village and paying attention:
 * how many guards there are, how many are on their feet, that there is a jail, that something has been
 * happening. The score is this mod's own summary of exactly those facts.
 *
 * <p>Sent only in answer to a request, never unsolicited, and only about the settlement the requester
 * is actually in.
 *
 * @param village        the community key's string form, for the client to tell two answers apart
 * @param score          0-100, as {@link VillageSecurityView} computes it
 * @param rating         the ordinal of {@link VillageSecurityView.Rating}, so the client shows a word
 * @param knownIncidents publicly known incidents inside the decay window
 * @param openIncidents  how many of those are unsettled
 * @param guards         law found among the loaded residents
 * @param guardsOnDuty   how many of them could answer a report right now
 * @param cells          assigned jail cells here
 * @param careRooms      assigned care rooms here
 */
public record VillageSecurityS2CPacket(String village, int score, int rating, int knownIncidents,
                                       int openIncidents, int guards, int guardsOnDuty, int cells,
                                       int careRooms) {

    /** Long enough for {@code minecraft:the_nether/1234567}, short enough to be a bound. */
    public static final int MAX_VILLAGE_LENGTH = PacketBounds.MAX_ID_LENGTH;

    /** The answer for "you are not in a settlement", which is a real answer and not a missing one. */
    public static VillageSecurityS2CPacket none() {
        return new VillageSecurityS2CPacket("", 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static VillageSecurityS2CPacket of(VillageSecurityView view) {
        return new VillageSecurityS2CPacket(view.village(), view.score(), view.rating().ordinal(),
                view.knownIncidents(), view.openIncidents(), view.guards(), view.guardsOnDuty(),
                view.cells(), view.careRooms());
    }

    public VillageSecurityS2CPacket {
        village = village == null ? "" : village;
    }

    /** Whether the server found a settlement at all. */
    public boolean present() {
        return !village.isEmpty();
    }

    /**
     * The rating word, resolved defensively.
     *
     * <p>An ordinal out of range is clamped rather than thrown: a client one version behind a server
     * that added a rating should show the nearest word it knows, not disconnect over a status icon.
     */
    public VillageSecurityView.Rating ratingValue() {
        VillageSecurityView.Rating[] values = VillageSecurityView.Rating.values();
        return values[Math.max(0, Math.min(values.length - 1, rating))];
    }

    public static void encode(VillageSecurityS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.village, MAX_VILLAGE_LENGTH);
        buf.writeVarInt(msg.score);
        buf.writeVarInt(msg.rating);
        buf.writeVarInt(msg.knownIncidents);
        buf.writeVarInt(msg.openIncidents);
        buf.writeVarInt(msg.guards);
        buf.writeVarInt(msg.guardsOnDuty);
        buf.writeVarInt(msg.cells);
        buf.writeVarInt(msg.careRooms);
    }

    public static VillageSecurityS2CPacket decode(FriendlyByteBuf buf) {
        return new VillageSecurityS2CPacket(
                buf.readUtf(MAX_VILLAGE_LENGTH),
                PacketBounds.readCount(buf, VillageSecurityView.MAX_SCORE),
                PacketBounds.readCount(buf, VillageSecurityView.Rating.values().length - 1),
                PacketBounds.readCount(buf, Integer.MAX_VALUE),
                PacketBounds.readCount(buf, Integer.MAX_VALUE),
                PacketBounds.readCount(buf, Integer.MAX_VALUE),
                PacketBounds.readCount(buf, Integer.MAX_VALUE),
                PacketBounds.readCount(buf, Integer.MAX_VALUE),
                PacketBounds.readCount(buf, Integer.MAX_VALUE));
    }

    public static void handle(VillageSecurityS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (!context.getDirection().getReceptionSide().isClient()) {
            return;
        }
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.onVillageSecurity(msg)));
    }
}
