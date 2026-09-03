package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.enforcement.LegalTarget;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Map;
import java.util.UUID;

/**
 * Our own Forge {@link SimpleChannel} (independent of MCA's network, spec §13/§18). All sync is
 * server→client and <b>display-only</b>: the client never sends state changes, and the server never
 * trusts a client packet to mutate Karma/Heat. Registered during common setup.
 */
public final class CrimeNetwork {

    // 6: added the restraint sync pair. A client that does not know how to draw an arrest would
    // otherwise sit silently mismatched against a server that expects it to.
    private static final String PROTOCOL_VERSION = "6";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(McaCrime.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int nextId = 0;

    private CrimeNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(nextId++, SelfStatusS2CPacket.class,
                SelfStatusS2CPacket::encode, SelfStatusS2CPacket::decode, SelfStatusS2CPacket::handle);
        CHANNEL.registerMessage(nextId++, BandSyncS2CPacket.class,
                BandSyncS2CPacket::encode, BandSyncS2CPacket::decode, BandSyncS2CPacket::handle);
        CHANNEL.registerMessage(nextId++, BandBulkSyncS2CPacket.class,
                BandBulkSyncS2CPacket::encode, BandBulkSyncS2CPacket::decode, BandBulkSyncS2CPacket::handle);
        CHANNEL.registerMessage(nextId++, CaptiveStatusS2CPacket.class,
                CaptiveStatusS2CPacket::encode, CaptiveStatusS2CPacket::decode, CaptiveStatusS2CPacket::handle);
        CHANNEL.registerMessage(nextId++, RequestActionMenuC2SPacket.class,
                RequestActionMenuC2SPacket::encode, RequestActionMenuC2SPacket::decode, RequestActionMenuC2SPacket::handle);
        CHANNEL.registerMessage(nextId++, ActionMenuS2CPacket.class,
                ActionMenuS2CPacket::encode, ActionMenuS2CPacket::decode, ActionMenuS2CPacket::handle);
        CHANNEL.registerMessage(nextId++, StartActionC2SPacket.class,
                StartActionC2SPacket::encode, StartActionC2SPacket::decode, StartActionC2SPacket::handle);
        CHANNEL.registerMessage(nextId++, ActionProgressS2CPacket.class,
                ActionProgressS2CPacket::encode, ActionProgressS2CPacket::decode, ActionProgressS2CPacket::handle);
        CHANNEL.registerMessage(nextId++, RequestSelfMenuC2SPacket.class,
                RequestSelfMenuC2SPacket::encode, RequestSelfMenuC2SPacket::decode, RequestSelfMenuC2SPacket::handle);
        CHANNEL.registerMessage(nextId++, GuardChallengeS2CPacket.class,
                GuardChallengeS2CPacket::encode, GuardChallengeS2CPacket::decode, GuardChallengeS2CPacket::handle);
        CHANNEL.registerMessage(nextId++, GuardChallengeResponseC2SPacket.class,
                GuardChallengeResponseC2SPacket::encode, GuardChallengeResponseC2SPacket::decode,
                GuardChallengeResponseC2SPacket::handle);
        CHANNEL.registerMessage(nextId++, RequestCaseLedgerC2SPacket.class,
                RequestCaseLedgerC2SPacket::encode, RequestCaseLedgerC2SPacket::decode,
                RequestCaseLedgerC2SPacket::handle);
        CHANNEL.registerMessage(nextId++, CaseLedgerS2CPacket.class,
                CaseLedgerS2CPacket::encode, CaseLedgerS2CPacket::decode, CaseLedgerS2CPacket::handle);
        // Appended, never inserted: ids are the registration order, so reordering this list would make
        // two builds that differ only in packet order silently misread each other.
        CHANNEL.registerMessage(nextId++, RestraintSyncS2CPacket.class,
                RestraintSyncS2CPacket::encode, RestraintSyncS2CPacket::decode,
                RestraintSyncS2CPacket::handle);
        CHANNEL.registerMessage(nextId++, RestraintBulkSyncS2CPacket.class,
                RestraintBulkSyncS2CPacket::encode, RestraintBulkSyncS2CPacket::decode,
                RestraintBulkSyncS2CPacket::handle);
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
                LegalTarget.isLegalTarget(player)));
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
    public static void broadcastRestraint(UUID subject, boolean restrained, int guardEntityId) {
        CHANNEL.send(PacketDistributor.ALL.noArg(),
                new RestraintSyncS2CPacket(subject, restrained, guardEntityId));
    }

    /** Sends every currently restrained player to one joining client. */
    public static void sendRestraintBulk(ServerPlayer to, Map<UUID, Integer> restrained) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> to), new RestraintBulkSyncS2CPacket(restrained));
    }
}
