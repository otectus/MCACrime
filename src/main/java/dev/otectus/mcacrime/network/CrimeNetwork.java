package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.enforcement.OutlawResolver;
import dev.otectus.mcacrime.enforcement.RestraintVisualState;
import dev.otectus.mcacrime.item.weapon.WeaponPolicySnapshot;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Our own Forge {@link SimpleChannel} (independent of MCA's network, spec §13/§18). All sync is
 * server→client and <b>display-only</b>: the client never sends state changes, and the server never
 * trusts a client packet to mutate Karma/Heat. Registered during common setup.
 */
public final class CrimeNetwork {

    // 8: every message is now registered with an explicit direction, counts are rejected rather than
    // clamped, and the guard response travels as a name instead of an ordinal. A 7 client would send an
    // ordinal that decodes as a string of the wrong length, which is a dropped connection at best.
    private static final String PROTOCOL_VERSION = "8";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(McaCrime.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int nextId = 0;

    private CrimeNetwork() {
    }

    public static void register() {
        toClient(SelfStatusS2CPacket.class,
                SelfStatusS2CPacket::encode, SelfStatusS2CPacket::decode, SelfStatusS2CPacket::handle);
        toClient(BandSyncS2CPacket.class,
                BandSyncS2CPacket::encode, BandSyncS2CPacket::decode, BandSyncS2CPacket::handle);
        toClient(BandBulkSyncS2CPacket.class,
                BandBulkSyncS2CPacket::encode, BandBulkSyncS2CPacket::decode, BandBulkSyncS2CPacket::handle);
        toClient(CaptiveStatusS2CPacket.class,
                CaptiveStatusS2CPacket::encode, CaptiveStatusS2CPacket::decode, CaptiveStatusS2CPacket::handle);
        toServer(RequestActionMenuC2SPacket.class,
                RequestActionMenuC2SPacket::encode, RequestActionMenuC2SPacket::decode, RequestActionMenuC2SPacket::handle);
        toClient(ActionMenuS2CPacket.class,
                ActionMenuS2CPacket::encode, ActionMenuS2CPacket::decode, ActionMenuS2CPacket::handle);
        toServer(StartActionC2SPacket.class,
                StartActionC2SPacket::encode, StartActionC2SPacket::decode, StartActionC2SPacket::handle);
        toClient(ActionProgressS2CPacket.class,
                ActionProgressS2CPacket::encode, ActionProgressS2CPacket::decode, ActionProgressS2CPacket::handle);
        toServer(RequestSelfMenuC2SPacket.class,
                RequestSelfMenuC2SPacket::encode, RequestSelfMenuC2SPacket::decode, RequestSelfMenuC2SPacket::handle);
        toClient(GuardChallengeS2CPacket.class,
                GuardChallengeS2CPacket::encode, GuardChallengeS2CPacket::decode, GuardChallengeS2CPacket::handle);
        toServer(GuardChallengeResponseC2SPacket.class,
                GuardChallengeResponseC2SPacket::encode, GuardChallengeResponseC2SPacket::decode,
                GuardChallengeResponseC2SPacket::handle);
        toServer(RequestCaseLedgerC2SPacket.class,
                RequestCaseLedgerC2SPacket::encode, RequestCaseLedgerC2SPacket::decode,
                RequestCaseLedgerC2SPacket::handle);
        toClient(CaseLedgerS2CPacket.class,
                CaseLedgerS2CPacket::encode, CaseLedgerS2CPacket::decode, CaseLedgerS2CPacket::handle);
        // Appended, never inserted: ids are the registration order, so reordering this list would make
        // two builds that differ only in packet order silently misread each other.
        toClient(RestraintSyncS2CPacket.class,
                RestraintSyncS2CPacket::encode, RestraintSyncS2CPacket::decode,
                RestraintSyncS2CPacket::handle);
        toClient(RestraintBulkSyncS2CPacket.class,
                RestraintBulkSyncS2CPacket::encode, RestraintBulkSyncS2CPacket::decode,
                RestraintBulkSyncS2CPacket::handle);
        toClient(WeaponPolicyS2CPacket.class,
                WeaponPolicyS2CPacket::encode, WeaponPolicyS2CPacket::decode,
                WeaponPolicyS2CPacket::handle);
        toClient(CriminalJobSyncS2CPacket.class,
                CriminalJobSyncS2CPacket::encode, CriminalJobSyncS2CPacket::decode,
                CriminalJobSyncS2CPacket::handle);
    }

    /**
     * Registers one server-bound message. The direction is the point: the five-argument
     * {@code registerMessage} registers a message in <em>both</em> directions, so a client-to-server
     * packet stays decodable and dispatchable on a client — on an integrated server that means the
     * host can be made to run a C2S handler against their own world. There is one helper per direction
     * so the argument cannot be left off again.
     */
    private static <T> void toServer(Class<T> type, BiConsumer<T, FriendlyByteBuf> encoder,
                                     Function<FriendlyByteBuf, T> decoder,
                                     BiConsumer<T, Supplier<NetworkEvent.Context>> handler) {
        CHANNEL.registerMessage(nextId++, type, encoder, decoder, handler,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    /** Registers one client-bound message. See {@link #toServer}. */
    private static <T> void toClient(Class<T> type, BiConsumer<T, FriendlyByteBuf> encoder,
                                     Function<FriendlyByteBuf, T> decoder,
                                     BiConsumer<T, Supplier<NetworkEvent.Context>> handler) {
        CHANNEL.registerMessage(nextId++, type, encoder, decoder, handler,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** Pushes a guard challenge, or its closure, to the challenged player alone. */
    public static void sendGuardChallenge(ServerPlayer player, GuardChallengeS2CPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** Answers a player's own dossier request. Never sent unsolicited, and never about anybody else. */
    public static void sendCaseLedger(ServerPlayer player, CaseLedgerS2CPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /**
     * Pushes one action-channel update to the actor. Sent only to the player performing the action:
     * nobody else needs to know how far along somebody's mugging is, and broadcasting it would leak
     * exactly the timing information the interruption rules depend on staying private.
     */
    public static void sendActionProgress(ServerPlayer player, ActionProgressS2CPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** Pushes the player's own card data (karma/heat/band/wanted/jail/legal-target) to their client. */
    public static void sendSelfStatus(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SelfStatusS2CPacket(
                CrimeState.getKarma(player),
                CrimeState.getHeat(player),
                CrimeState.getBand(player),
                CrimeState.isWanted(player),
                JailService.remainingTicks(player),
                OutlawResolver.resolve(player).lawfulCombatTarget()));
    }

    /** Pushes the player's own captivity status (held? lawful? captor? cap remaining?) to their client. */
    public static void sendCaptiveStatus(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        boolean captive = false;
        boolean lawful = false;
        String captor = "";
        long capRemaining = 0L;
        if (server != null) {
            CustodyRecord record = CrimeWorldData.get(server).getCustody(player.getUUID());
            if (record != null) {
                captive = true;
                lawful = record.isLawful();
                long capTicks = (long) McaCrimeConfig.COMMON.maxCaptivityRealMinutes.get() * 1200L;
                capRemaining = Math.max(0L, capTicks - record.getRealTicksHeld());
                UUID owner = record.getOwner().ownerUuid().orElse(null);
                ServerPlayer captorPlayer = owner == null ? null : server.getPlayerList().getPlayer(owner);
                if (captorPlayer != null) {
                    captor = captorPlayer.getGameProfile().getName();
                }
            }
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new CaptiveStatusS2CPacket(captive, lawful, captor, capRemaining));
    }

    /** Broadcasts one player's band to every client (for nameplate coloring). */
    public static void broadcastBand(UUID subject, Band band) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), new BandSyncS2CPacket(subject, band));
    }

    /** Sends a full snapshot of every online player's band to one joining client. */
    public static void sendBandBulk(ServerPlayer to, Map<UUID, Band> bands) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> to), new BandBulkSyncS2CPacket(bands));
    }

    /**
     * Broadcasts one player's restraint state, so everybody who can see the arrest draws it.
     *
     * <p>Broadcast rather than sent to the subject, because cuffs and a lead are things other people
     * look at. Display-only, like every other sync on this channel.
     */
    public static void broadcastRestraint(UUID subject, RestraintVisualState state) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), RestraintSyncS2CPacket.of(subject, state));
    }

    /**
     * Sends one subject's restraint state to a single client.
     *
     * <p>What a client that has just started tracking an entity needs: it missed the broadcast, and
     * telling everybody again for its benefit would be a broadcast per chunk load.
     */
    public static void sendRestraintTo(ServerPlayer to, UUID subject, RestraintVisualState state) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> to), RestraintSyncS2CPacket.of(subject, state));
    }

    /**
     * Tells one client which weapons the server counts as drawn.
     *
     * <p>Sent on login, because the client's own COMMON file is not the one being gated on and a
     * button that disagrees with the server is worse than no button.
     */
    public static void sendWeaponPolicy(ServerPlayer to) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> to),
                new WeaponPolicyS2CPacket(WeaponPolicySnapshot.fromConfig()));
    }

    /** Re-sends the policy to everybody after a config reload, so no client keeps the old lists. */
    public static void broadcastWeaponPolicy(MinecraftServer server) {
        if (server == null) {
            return;
        }
        WeaponPolicyS2CPacket packet = new WeaponPolicyS2CPacket(WeaponPolicySnapshot.fromConfig());
        CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
    }

    /** Tells one tracking client that this villager has a criminal job. */
    public static void sendCriminalJob(ServerPlayer to, UUID villager, CriminalJob job) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> to), new CriminalJobSyncS2CPacket(villager, job));
    }

    /**
     * Tells everybody tracking this villager what its criminal job now is.
     *
     * <p>Tracking rather than everybody, because the client only needs the answer for a villager it
     * can see and could otherwise be handed a map of every fence on the server. {@link CriminalJob#NONE}
     * is a real message here: it is how a client is told to forget one.
     */
    public static void broadcastCriminalJob(Entity villager, CriminalJob job) {
        if (villager == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> villager),
                new CriminalJobSyncS2CPacket(villager.getUUID(), job));
    }

    /** Sends every currently restrained subject to one joining client. */
    public static void sendRestraintBulk(ServerPlayer to, Map<UUID, RestraintVisualState> restrained) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> to), new RestraintBulkSyncS2CPacket(restrained));
    }
}
