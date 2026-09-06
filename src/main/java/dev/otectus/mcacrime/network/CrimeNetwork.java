package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.CrimeActionService;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.enforcement.GuardChallengeService;
import dev.otectus.mcacrime.enforcement.OutlawResolver;
import dev.otectus.mcacrime.enforcement.RestraintVisualState;
import dev.otectus.mcacrime.item.weapon.WeaponPolicySnapshot;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.Map;
import java.util.UUID;

/**
 * Our own payload set (independent of MCA's network, spec §13/§18). All sync is server→client and
 * <b>display-only</b>: the client never sends state changes, and the server never trusts a client
 * payload to mutate Karma/Heat.
 *
 * <p>The Forge build ran these fifteen messages over a {@code SimpleChannel} with numeric
 * discriminators, so packet <em>order</em> in this file was the wire format and reordering it made two
 * builds silently misread each other. Under the payload API each message carries its own namespaced
 * id, and this list is just a list.
 *
 * <p>Registration happens from common code, so nothing here may name a client class; the twelve S2C
 * handlers go through {@link CrimeClientPayloadRouter}, whose implementation the client entrypoint
 * installs (spec §9.4).
 */
public final class CrimeNetwork {

    /**
     * Bumped from the Forge channel's {@code "6"}: named payloads, a different framing and a different
     * component encoding. Nothing on protocol 6 could talk to this, so it does not claim to.
     *
     * <p>7 to 8 in 0.5.1: both restraint payloads changed shape, and the added field sits between two
     * that were already on the wire. A reader and a writer that disagree there do not throw, they
     * produce a plausible wrong answer, so the mismatch is refused at handshake instead.
     */
    private static final String PROTOCOL_VERSION = "8";

    private CrimeNetwork() {
    }

    /** Registers every payload. A mod-bus listener; registering a payload later throws. */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        registrar.playToServer(RequestActionMenuC2SPacket.TYPE, RequestActionMenuC2SPacket.STREAM_CODEC,
                CrimeNetwork::handleRequestActionMenu);
        registrar.playToServer(StartActionC2SPacket.TYPE, StartActionC2SPacket.STREAM_CODEC,
                CrimeNetwork::handleStartAction);
        registrar.playToServer(RequestSelfMenuC2SPacket.TYPE, RequestSelfMenuC2SPacket.STREAM_CODEC,
                CrimeNetwork::handleRequestSelfMenu);
        registrar.playToServer(GuardChallengeResponseC2SPacket.TYPE,
                GuardChallengeResponseC2SPacket.STREAM_CODEC, CrimeNetwork::handleGuardChallengeResponse);
        registrar.playToServer(RequestCaseLedgerC2SPacket.TYPE, RequestCaseLedgerC2SPacket.STREAM_CODEC,
                CrimeNetwork::handleRequestCaseLedger);

        registrar.playToClient(SelfStatusS2CPacket.TYPE, SelfStatusS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleSelfStatus);
        registrar.playToClient(BandSyncS2CPacket.TYPE, BandSyncS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleBandSync);
        registrar.playToClient(BandBulkSyncS2CPacket.TYPE, BandBulkSyncS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleBandBulkSync);
        registrar.playToClient(CaptiveStatusS2CPacket.TYPE, CaptiveStatusS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleCaptiveStatus);
        registrar.playToClient(ActionMenuS2CPacket.TYPE, ActionMenuS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleActionMenu);
        registrar.playToClient(ActionProgressS2CPacket.TYPE, ActionProgressS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleActionProgress);
        registrar.playToClient(GuardChallengeS2CPacket.TYPE, GuardChallengeS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleGuardChallenge);
        registrar.playToClient(CaseLedgerS2CPacket.TYPE, CaseLedgerS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleCaseLedger);
        registrar.playToClient(RestraintSyncS2CPacket.TYPE, RestraintSyncS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleRestraintSync);
        registrar.playToClient(RestraintBulkSyncS2CPacket.TYPE, RestraintBulkSyncS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleRestraintBulkSync);
        registrar.playToClient(WeaponPolicyS2CPacket.TYPE, WeaponPolicyS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleWeaponPolicy);
        registrar.playToClient(CriminalJobSyncS2CPacket.TYPE, CriminalJobSyncS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleCriminalJob);
    }

    // The registrar runs the server-bound handlers on the main thread, which is the same guarantee the
    // old ctx.enqueueWork(...) provided, so none of them enqueues anything itself.
    // What the registrar does not provide is a rate: every one of these does real work on request, so
    // each spends a token from the sender's RequestBudget before doing any of it.

    private static void handleRequestActionMenu(RequestActionMenuC2SPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        if (!RequestBudget.allow(sp.getUUID(), RequestBudget.Category.MENU)) return;
        CrimeActionService.openMenu(sp, payload.targetId());
    }

    private static void handleStartAction(StartActionC2SPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        if (!RequestBudget.allow(sp.getUUID(), RequestBudget.Category.ACTION)) return;
        CrimeActionService.startFromMenu(sp, payload);
    }

    private static void handleRequestSelfMenu(RequestSelfMenuC2SPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        if (!RequestBudget.allow(sp.getUUID(), RequestBudget.Category.MENU)) return;
        CrimeActionService.openSelfMenu(sp, payload.kind());
    }

    private static void handleGuardChallengeResponse(GuardChallengeResponseC2SPacket payload,
                                                     IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        if (!RequestBudget.allow(sp.getUUID(), RequestBudget.Category.CHALLENGE)) return;
        GuardChallengeService.respond(sp, payload.encounterId(), payload.response());
    }

    private static void handleRequestCaseLedger(RequestCaseLedgerC2SPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        if (!RequestBudget.allow(sp.getUUID(), RequestBudget.Category.DOSSIER)) return;
        RequestCaseLedgerC2SPacket.respond(sp);
    }

    /** The one send the client makes, so screens and keybinds do not name {@code PacketDistributor}. */
    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    /** Pushes a guard challenge, or its closure, to the challenged player alone. */
    public static void sendGuardChallenge(ServerPlayer player, GuardChallengeS2CPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }

    /** Answers a player's own dossier request. Never sent unsolicited, and never about anybody else. */
    public static void sendCaseLedger(ServerPlayer player, CaseLedgerS2CPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }

    /**
     * Pushes one action-channel update to the actor. Sent only to the player performing the action:
     * nobody else needs to know how far along somebody's mugging is, and broadcasting it would leak
     * exactly the timing information the interruption rules depend on staying private.
     */
    public static void sendActionProgress(ServerPlayer player, ActionProgressS2CPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }

    /** Pushes the player's own card data (karma/heat/band/wanted/jail/legal-target) to their client. */
    public static void sendSelfStatus(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new SelfStatusS2CPacket(
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
        PacketDistributor.sendToPlayer(player,
                new CaptiveStatusS2CPacket(captive, lawful, captor, capRemaining));
    }

    /** Broadcasts one player's band to every client (for nameplate coloring). */
    public static void broadcastBand(UUID subject, Band band) {
        PacketDistributor.sendToAllPlayers(new BandSyncS2CPacket(subject, band));
    }

    /** Sends a full snapshot of every online player's band to one joining client. */
    public static void sendBandBulk(ServerPlayer to, Map<UUID, Band> bands) {
        PacketDistributor.sendToPlayer(to, new BandBulkSyncS2CPacket(bands));
    }

    /**
     * Broadcasts one subject's restraint state, so everybody who can see the arrest draws it.
     *
     * <p>Broadcast rather than sent to the subject, because cuffs and a lead are things other people
     * look at. Display-only, like every other sync on this channel.
     */
    public static void broadcastRestraint(UUID subject, RestraintVisualState state) {
        PacketDistributor.sendToAllPlayers(RestraintSyncS2CPacket.of(subject, state));
    }

    /**
     * Tells one client about one subject: the answer to "you just started tracking somebody, are they
     * in cuffs?", which is a question only that client asked.
     */
    public static void sendRestraintTo(ServerPlayer to, UUID subject, RestraintVisualState state) {
        PacketDistributor.sendToPlayer(to, RestraintSyncS2CPacket.of(subject, state));
    }

    /** Sends every currently restrained subject to one joining client. */
    public static void sendRestraintBulk(ServerPlayer to, Map<UUID, RestraintVisualState> restrained) {
        PacketDistributor.sendToPlayer(to, new RestraintBulkSyncS2CPacket(restrained));
    }

    /**
     * Tells one client what the server's weapon rules actually are, on login.
     *
     * <p>The client has a COMMON config file of its own, and on a multiplayer server it is not the one
     * the gate is evaluated against. Sending the policy is what keeps a greyed-out Crime button and a
     * refused menu request meaning the same thing.
     */
    public static void sendWeaponPolicy(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new WeaponPolicyS2CPacket(WeaponPolicySnapshot.fromConfig()));
    }

    /** Re-sends the policy to everybody after a config reload, rather than at their next login. */
    public static void broadcastWeaponPolicy(MinecraftServer server) {
        if (server == null) {
            return;
        }
        PacketDistributor.sendToAllPlayers(new WeaponPolicyS2CPacket(WeaponPolicySnapshot.fromConfig()));
    }

    /** Tells one client about one villager's criminal job, when that client starts tracking them. */
    public static void sendCriminalJob(ServerPlayer to, UUID villager, CriminalJob job) {
        PacketDistributor.sendToPlayer(to, new CriminalJobSyncS2CPacket(villager, job));
    }

    /**
     * Tells everybody who can see this villager that their job changed.
     *
     * <p>To the trackers rather than to all players: a job is only ever read to decide what a button
     * does about the villager in front of you, so a client that cannot see them has no use for it.
     */
    public static void broadcastCriminalJob(Entity villager, CriminalJob job) {
        if (villager == null) {
            return;
        }
        PacketDistributor.sendToPlayersTrackingEntity(villager,
                new CriminalJobSyncS2CPacket(villager.getUUID(), job));
    }
}
