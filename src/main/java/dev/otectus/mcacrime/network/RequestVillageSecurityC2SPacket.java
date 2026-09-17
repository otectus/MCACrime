package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.civic.VillageSecurityService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * "How safe is the settlement I am standing in?" Carries no arguments, on purpose.
 *
 * <h2>Why there is nothing to send</h2>
 *
 * <p>The obvious shape is a community key in the packet, and it is the wrong one. A client-supplied
 * key is a lookup into a server-side table, and there is no version of that which is not eventually
 * used to read the security of a village on the other side of the map — or to enumerate every village
 * on the server by counting up from zero. So the server answers about where the sender actually is and
 * only about that, which leaves nothing to validate and nothing to forge. It is the same reasoning
 * {@code RequestCaseLedgerC2SPacket} uses for the dossier, and it is the same answer.
 *
 * <p>Rate limited on {@link RequestBudget.Category#DOSSIER}: the answer is a bounded ledger walk and a
 * guard scan, and it is for a panel rather than for an input, so once every ten ticks is generous.
 *
 * <p>Not in a settlement is a real answer, sent as {@link VillageSecurityS2CPacket#none()}, so a client
 * that opened a panel is never left waiting on a reply that is not coming.
 */
public record RequestVillageSecurityC2SPacket() {

    public static void encode(RequestVillageSecurityC2SPacket msg, FriendlyByteBuf buf) {
    }

    public static RequestVillageSecurityC2SPacket decode(FriendlyByteBuf buf) {
        return new RequestVillageSecurityC2SPacket();
    }

    public static void handle(RequestVillageSecurityC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.DOSSIER,
                sender -> CrimeNetwork.sendVillageSecurity(sender, build(sender)));
    }

    private static VillageSecurityS2CPacket build(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return VillageSecurityS2CPacket.none();
        }
        return VillageSecurityService.communityNear(level, player.blockPosition())
                .flatMap(community -> VillageSecurityService.of(level, community))
                .map(VillageSecurityS2CPacket::of)
                .orElseGet(VillageSecurityS2CPacket::none);
    }
}
