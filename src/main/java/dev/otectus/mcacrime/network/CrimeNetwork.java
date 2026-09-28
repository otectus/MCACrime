package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.enforcement.OutlawResolver;
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

    // 17 added bounded player-report menus, submission and private receipt status; 18 drops the
    // warden-guide and identity-projection packets, which shifts every later message index.
    private static final String PROTOCOL_VERSION = "18";

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
        toClient(WeaponPolicyS2CPacket.class,
                WeaponPolicyS2CPacket::encode, WeaponPolicyS2CPacket::decode,
                WeaponPolicyS2CPacket::handle);
        toClient(CriminalJobSyncS2CPacket.class,
                CriminalJobSyncS2CPacket::encode, CriminalJobSyncS2CPacket::decode,
                CriminalJobSyncS2CPacket::handle);
        toServer(GuardChallengeDisplayedC2SPacket.class,
                GuardChallengeDisplayedC2SPacket::encode, GuardChallengeDisplayedC2SPacket::decode,
                GuardChallengeDisplayedC2SPacket::handle);
        toClient(BailQuoteS2CPacket.class,
                BailQuoteS2CPacket::encode, BailQuoteS2CPacket::decode, BailQuoteS2CPacket::handle);
        toServer(SelectMaskRecipeC2SPacket.class,
                SelectMaskRecipeC2SPacket::encode, SelectMaskRecipeC2SPacket::decode,
                SelectMaskRecipeC2SPacket::handle);
        toClient(MaskSelectionS2CPacket.class,
                MaskSelectionS2CPacket::encode, MaskSelectionS2CPacket::decode,
                MaskSelectionS2CPacket::handle);
        toClient(RestraintRigSyncS2CPacket.class,
                RestraintRigSyncS2CPacket::encode, RestraintRigSyncS2CPacket::decode,
                RestraintRigSyncS2CPacket::handle);
        toServer(RequestVillageSecurityC2SPacket.class,
                RequestVillageSecurityC2SPacket::encode, RequestVillageSecurityC2SPacket::decode,
                RequestVillageSecurityC2SPacket::handle);
        toClient(VillageSecurityS2CPacket.class,
                VillageSecurityS2CPacket::encode, VillageSecurityS2CPacket::decode,
                VillageSecurityS2CPacket::handle);
        toClient(PhysicalStateS2CPacket.class,
                PhysicalStateS2CPacket::encode, PhysicalStateS2CPacket::decode,
                PhysicalStateS2CPacket::handle);
        toClient(PhysicalStateDeltaS2CPacket.class,
                PhysicalStateDeltaS2CPacket::encode, PhysicalStateDeltaS2CPacket::decode,
                PhysicalStateDeltaS2CPacket::handle);
        toClient(PhysicalStateRemoveS2CPacket.class,
                PhysicalStateRemoveS2CPacket::encode, PhysicalStateRemoveS2CPacket::decode,
                PhysicalStateRemoveS2CPacket::handle);
        toServer(RestraintStruggleC2SPacket.class,
                RestraintStruggleC2SPacket::encode, RestraintStruggleC2SPacket::decode,
                RestraintStruggleC2SPacket::handle);
        toServer(SelfRestraintC2SPacket.class,
                SelfRestraintC2SPacket::encode, SelfRestraintC2SPacket::decode,
                SelfRestraintC2SPacket::handle);
        toClient(LockpickBeginS2CPacket.class,
                LockpickBeginS2CPacket::encode, LockpickBeginS2CPacket::decode,
                LockpickBeginS2CPacket::handle);
        toClient(LockpickPhaseS2CPacket.class,
                LockpickPhaseS2CPacket::encode, LockpickPhaseS2CPacket::decode,
                LockpickPhaseS2CPacket::handle);
        toClient(LockpickResultS2CPacket.class,
                LockpickResultS2CPacket::encode, LockpickResultS2CPacket::decode,
                LockpickResultS2CPacket::handle);
        toServer(LockpickAttemptC2SPacket.class,
                LockpickAttemptC2SPacket::encode, LockpickAttemptC2SPacket::decode,
                LockpickAttemptC2SPacket::handle);
        toServer(LockpickCancelC2SPacket.class,
                LockpickCancelC2SPacket::encode, LockpickCancelC2SPacket::decode,
                LockpickCancelC2SPacket::handle);
        toClient(FriskSnapshotS2CPacket.class,
                FriskSnapshotS2CPacket::encode, FriskSnapshotS2CPacket::decode,
                FriskSnapshotS2CPacket::handle);
        toServer(FriskTransferC2SPacket.class,
                FriskTransferC2SPacket::encode, FriskTransferC2SPacket::decode,
                FriskTransferC2SPacket::handle);
        toClient(RestraintProfileSyncS2CPacket.class,
                RestraintProfileSyncS2CPacket::encode, RestraintProfileSyncS2CPacket::decode,
                RestraintProfileSyncS2CPacket::handle);
        toClient(ReportMenuS2CPacket.class,
                ReportMenuS2CPacket::encode, ReportMenuS2CPacket::decode, ReportMenuS2CPacket::handle);
        toServer(SubmitPlayerReportC2SPacket.class,
                SubmitPlayerReportC2SPacket::encode, SubmitPlayerReportC2SPacket::decode,
                SubmitPlayerReportC2SPacket::handle);
        toServer(RequestPlayerReportsC2SPacket.class,
                RequestPlayerReportsC2SPacket::encode, RequestPlayerReportsC2SPacket::decode,
                RequestPlayerReportsC2SPacket::handle);
        toClient(PlayerReportsS2CPacket.class,
                PlayerReportsS2CPacket::encode, PlayerReportsS2CPacket::decode,
                PlayerReportsS2CPacket::handle);
    }

    public static void sendReportMenu(ServerPlayer player,
                                      dev.otectus.mcacrime.report.PlayerCrimeReportService.Menu menu) {
        if (player != null && menu != null) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ReportMenuS2CPacket(menu));
    }

    public static void sendPlayerReports(ServerPlayer player, PlayerReportsS2CPacket packet) {
        if (player != null && packet != null) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /**
     * Sends the {@code restraint_profiles} datapack layer to one player (plan §3.13).
     *
     * <p>Sent even when the list is empty, which is how a client learns that the last server's
     * numbers no longer apply.
     */
    public static void sendRestraintProfiles(ServerPlayer player,
            java.util.List<dev.otectus.mcacrime.restraint.RestraintProfile> profiles) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RestraintProfileSyncS2CPacket(profiles));
    }

    /** Opens one picker's dial. To that player alone: a lock is nobody else's business. */
    public static void sendLockpickBegin(ServerPlayer player, LockpickBeginS2CPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** Tells one picker the phase moved on, and where the server's meter really is. */
    public static void sendLockpickPhase(ServerPlayer player, LockpickPhaseS2CPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** Closes one picker's dial with the outcome the server already carried out. */
    public static void sendLockpickResult(ServerPlayer player, LockpickResultS2CPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /**
     * Gives one searcher the revisions their open frisking screen has to quote back (M5.2).
     *
     * <p>To that player alone. Only the authorised searcher ever receives a searchable projection,
     * which is the §1.6 rule about never sending another player's inventory to anybody else.
     */
    public static void sendFriskSnapshot(ServerPlayer player, FriskSnapshotS2CPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** Sends one alignment attempt. Intent only: the server scores it. */
    public static void sendLockpickAttempt(LockpickAttemptC2SPacket packet) {
        CHANNEL.sendToServer(packet);
    }

    /** Asks the server to end the sender's own lockpick session. */
    public static void sendLockpickCancel(LockpickCancelC2SPacket packet) {
        CHANNEL.sendToServer(packet);
    }

    /** Asks the server to put the held restraint on the sender's own chosen slot. */
    public static void sendSelfRestraint(SelfRestraintC2SPacket packet) {
        CHANNEL.sendToServer(packet);
    }

    /** Sends one struggle input. Intent only: the server owns durability and the outcome. */
    public static void sendStruggle(RestraintStruggleC2SPacket packet) {
        CHANNEL.sendToServer(packet);
    }

    /** Sends one client the whole physical-restraint picture: login, respawn, dimension change. */
    public static void sendPhysicalState(ServerPlayer to, PhysicalStateS2CPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> to), packet);
    }

    /** Sends one client one subject's physical state, for the moment it starts tracking them. */
    public static void sendPhysicalStateDelta(ServerPlayer to, PhysicalStateDeltaS2CPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> to), packet);
    }

    /**
     * Tells everybody tracking this subject what changed about them.
     *
     * <p>Tracking rather than everybody: gear is drawn by the people who can see the person wearing
     * it, and a server-wide broadcast would hand every client the whole prison roster.
     */
    public static void broadcastPhysicalStateDelta(Entity subject, PhysicalStateDeltaS2CPacket packet) {
        if (subject == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> subject), packet);
    }

    /** Sends one client a removal for a subject nobody can track any more. */
    public static void sendPhysicalStateRemoval(ServerPlayer to, PhysicalStateRemoveS2CPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> to), packet);
    }

    /** Tells everybody tracking this subject to forget their physical state. */
    public static void broadcastPhysicalStateRemoval(Entity subject, PhysicalStateRemoveS2CPacket packet) {
        if (subject == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> subject), packet);
    }

    /**
     * Tells one client which body a restrained subject is being drawn on.
     *
     * <p>To the people who can see them rather than to everybody: the rig only matters because cuffs
     * are being drawn on it, and a broadcast would be a packet per restrained villager per client that
     * will never render one.
     */
    public static void sendRestraintRig(ServerPlayer to, UUID subject, boolean humanoid, String rig) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> to),
                new RestraintRigSyncS2CPacket(subject, humanoid, rig));
    }

    /** Tells everybody tracking this subject what body their restraint is being drawn on. */
    public static void broadcastRestraintRig(Entity subject, boolean humanoid, String rig) {
        if (subject == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> subject),
                new RestraintRigSyncS2CPacket(subject.getUUID(), humanoid, rig));
    }

    /** Answers a player's own settlement-safety enquiry. Never sent unsolicited. */
    public static void sendVillageSecurity(ServerPlayer player, VillageSecurityS2CPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /**
     * Tells one viewer which style their own Mask Station has selected.
     *
     * <p>To that player alone: two people at one station have two private sessions, and telling either
     * about the other's choice would be the first crack in that separation (0.7.2 §6.2).
     */
    public static void sendMaskSelection(ServerPlayer player, MaskSelectionS2CPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** Answers a player's own bail enquiry. Never sent unsolicited, and never about anybody else. */
    public static void sendBailQuote(ServerPlayer player, BailQuoteS2CPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
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

}
