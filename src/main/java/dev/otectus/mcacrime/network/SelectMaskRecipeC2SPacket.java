package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.menu.MaskSelectionOutcome;
import dev.otectus.mcacrime.menu.MaskStationMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * "Make that one." The only thing a client is allowed to say about a mask (0.7.2 §8.1).
 *
 * <p>Three numbers and a name: which menu the sender thinks they have open, which style they picked,
 * and which recipe generation their grid was built from. No item, no count, no NBT, no price — the
 * server holds all of those, and re-decides every one of them when the output is taken. The worst a
 * forged packet can do is select a style the sender could have selected by clicking (invariant 6).
 *
 * <p>The id is read as a bounded {@link ResourceLocation}, so an oversized or malformed name is a
 * protocol error on that connection rather than an allocation or a lookup key. Everything past the
 * decoder runs on the server thread through {@link ServerPacketGuard}, which also supplies the sender
 * and the per-player budget; nothing here reads a position, so no client can make the server touch a
 * chunk (§8.4).
 */
public record SelectMaskRecipeC2SPacket(int containerId, ResourceLocation recipeId, int generation) {

    /** Ticks between refusal warnings about one player, however many they sent in between. */
    private static final long WARN_INTERVAL_MILLIS = 60_000L;
    private static final int WARN_MAP_LIMIT = 256;
    private static final Map<UUID, Long> LAST_WARN = new ConcurrentHashMap<>();

    public static void encode(SelectMaskRecipeC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.containerId);
        buf.writeUtf(msg.recipeId.toString(), PacketBounds.MAX_ID_LENGTH);
        buf.writeVarInt(msg.generation);
    }

    public static SelectMaskRecipeC2SPacket decode(FriendlyByteBuf buf) {
        return new SelectMaskRecipeC2SPacket(buf.readVarInt(), PacketBounds.readResourceLocation(buf),
                buf.readVarInt());
    }

    public static void handle(SelectMaskRecipeC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.MENU, sender -> {
            if (!(sender.containerMenu instanceof MaskStationMenu menu)) {
                warn(sender, MaskSelectionOutcome.WRONG_CONTAINER);
                return;
            }
            MaskSelectionOutcome outcome =
                    menu.select(sender, msg.containerId, msg.recipeId, msg.generation);
            if (!outcome.accepted()) {
                warn(sender, outcome);
            }
        });
    }

    /**
     * Records a refusal, at most once a minute per player.
     *
     * <p>One line per refused packet would hand a spammer the server log, which is the same denial of
     * service by a quieter route — the same reasoning as {@link RequestBudget}'s own warnings.
     */
    private static void warn(ServerPlayer sender, MaskSelectionOutcome outcome) {
        long now = System.currentTimeMillis();
        Long last = LAST_WARN.get(sender.getUUID());
        if (last != null && now - last < WARN_INTERVAL_MILLIS) {
            return;
        }
        if (LAST_WARN.size() > WARN_MAP_LIMIT) {
            LAST_WARN.values().removeIf(stamp -> now - stamp > WARN_INTERVAL_MILLIS);
        }
        LAST_WARN.put(sender.getUUID(), now);
        McaCrime.LOGGER.warn("Refused mask style selection from {}: {}",
                sender.getGameProfile().getName(), outcome);
    }

    /** Drops this player's warning stamp. Called on logout, like the request budget's. */
    public static void forget(UUID player) {
        if (player != null) {
            LAST_WARN.remove(player);
        }
    }
}
