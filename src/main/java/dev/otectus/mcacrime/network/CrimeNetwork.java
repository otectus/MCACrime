package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.CrimeActionService;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.enforcement.GuardChallengeService;
import dev.otectus.mcacrime.enforcement.OutlawResolver;
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

    // 15 added the 0.7.5 physical-restraint messages: a full snapshot, a per-subject delta and an
    // explicit removal; 16 drops the warden-guide payload; 17 adds the paused-sentence flag to the self
    // status. Update clients and server together.
    private static final String PROTOCOL_VERSION = "17";

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

        registrar.playToServer(GuardChallengeDisplayedC2SPacket.TYPE, GuardChallengeDisplayedC2SPacket.STREAM_CODEC,
                CrimeNetwork::handleGuardChallengeDisplayed);
        // The station's own budget and sender checks live in ServerPacketGuard, which the handler uses.
        registrar.playToServer(SelectMaskRecipeC2SPacket.TYPE, SelectMaskRecipeC2SPacket.STREAM_CODEC,
                SelectMaskRecipeC2SPacket::handle);

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
        registrar.playToClient(WeaponPolicyS2CPacket.TYPE, WeaponPolicyS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleWeaponPolicy);
        registrar.playToClient(CriminalJobSyncS2CPacket.TYPE, CriminalJobSyncS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleCriminalJob);
        registrar.playToClient(BailQuoteS2CPacket.TYPE, BailQuoteS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleBailQuote);
        registrar.playToClient(MaskSelectionS2CPacket.TYPE, MaskSelectionS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleMaskSelection);
        registrar.playToClient(RestraintRigSyncS2CPacket.TYPE, RestraintRigSyncS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleRestraintRig);
        // The request's own sender, direction and budget checks live in ServerPacketGuard, which the
        // handler uses, so this registration looks like the mask station's rather than like the five
        // above it.
        registrar.playToServer(RequestVillageSecurityC2SPacket.TYPE,
                RequestVillageSecurityC2SPacket.STREAM_CODEC, RequestVillageSecurityC2SPacket::handle);
        registrar.playToClient(VillageSecurityS2CPacket.TYPE, VillageSecurityS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleVillageSecurity);

        registrar.playToClient(PhysicalStateS2CPacket.TYPE, PhysicalStateS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handlePhysicalState);
        registrar.playToClient(PhysicalStateDeltaS2CPacket.TYPE, PhysicalStateDeltaS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handlePhysicalStateDelta);
        registrar.playToClient(PhysicalStateRemoveS2CPacket.TYPE, PhysicalStateRemoveS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handlePhysicalStateRemove);
        registrar.playToServer(RestraintStruggleC2SPacket.TYPE, RestraintStruggleC2SPacket.STREAM_CODEC,
                RestraintStruggleC2SPacket::handle);
        registrar.playToServer(SelfRestraintC2SPacket.TYPE, SelfRestraintC2SPacket.STREAM_CODEC,
                SelfRestraintC2SPacket::handle);

        // Lockpicking (0.7.5 M3.3). Two client messages, both intent only, and three server ones that
        // are display: there is deliberately no success packet, because nothing a client says can
        // open a lock.
        registrar.playToClient(LockpickBeginS2CPacket.TYPE, LockpickBeginS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleLockpickBegin);
        registrar.playToClient(LockpickPhaseS2CPacket.TYPE, LockpickPhaseS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleLockpickPhase);
        registrar.playToClient(LockpickResultS2CPacket.TYPE, LockpickResultS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleLockpickResult);
        registrar.playToServer(LockpickAttemptC2SPacket.TYPE, LockpickAttemptC2SPacket.STREAM_CODEC,
                LockpickAttemptC2SPacket::handle);
        registrar.playToServer(LockpickCancelC2SPacket.TYPE, LockpickCancelC2SPacket.STREAM_CODEC,
                LockpickCancelC2SPacket::handle);

        // 0.7.5 M5.2: the frisking projection's two payloads.
        registrar.playToClient(FriskSnapshotS2CPacket.TYPE, FriskSnapshotS2CPacket.STREAM_CODEC,
                CrimeClientPayloadRouter::handleFriskSnapshot);
        registrar.playToServer(FriskTransferC2SPacket.TYPE, FriskTransferC2SPacket.STREAM_CODEC,
                FriskTransferC2SPacket::handle);

        // 0.7.5 §3.13: the restraint_profiles datapack layer, sent on login and after every reload.
        registrar.playToClient(RestraintProfileSyncS2CPacket.TYPE,
                RestraintProfileSyncS2CPacket.STREAM_CODEC, RestraintProfileSyncS2CPacket::handle);
    }

    /**
     * Sends the {@code restraint_profiles} datapack layer to one player (plan §3.13).
     *
     * <p>Sent even when the list is empty, which is how a client learns that the last server's
     * numbers no longer apply.
     */
    public static void sendRestraintProfiles(ServerPlayer player,
            java.util.List<dev.otectus.mcacrime.restraint.RestraintProfile> profiles) {
        if (player != null) {
            PacketDistributor.sendToPlayer(player, new RestraintProfileSyncS2CPacket(profiles));
        }
    }

    /**
     * Sends the searcher the session id and the per-slot revisions their screen has to echo back.
     *
     * <p>To that player alone. Only the authorised searcher ever receives a searchable projection,
     * which is the §1.6 rule about never sending another player's inventory to anybody else.
     */
    public static void sendFriskSnapshot(ServerPlayer player, FriskSnapshotS2CPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }

    /** Opens one picker's dial. One player only: nobody else has any business seeing it. */
    public static void sendLockpickBegin(ServerPlayer to, LockpickBeginS2CPacket packet) {
        PacketDistributor.sendToPlayer(to, packet);
    }

    /** The next phase and the server's own meter, after every scored attempt. */
    public static void sendLockpickPhase(ServerPlayer to, LockpickPhaseS2CPacket packet) {
        PacketDistributor.sendToPlayer(to, packet);
    }

    /** How it ended. Sent after the outcome has already been applied on the server. */
    public static void sendLockpickResult(ServerPlayer to, LockpickResultS2CPacket packet) {
        PacketDistributor.sendToPlayer(to, packet);
    }

    /** Sends one alignment attempt. Intent only: the server scores it. */
    public static void sendLockpickAttempt(LockpickAttemptC2SPacket packet) {
        PacketDistributor.sendToServer(packet);
    }

    /** Asks the server to end this picker's own session. */
    public static void sendLockpickCancel(LockpickCancelC2SPacket packet) {
        PacketDistributor.sendToServer(packet);
    }

    /** Asks the server to put the held restraint on the sender's own chosen slot. */
    public static void sendSelfRestraint(SelfRestraintC2SPacket packet) {
        PacketDistributor.sendToServer(packet);
    }

    /** Sends one struggle input. Intent only: the server owns durability and the outcome. */
    public static void sendStruggle(RestraintStruggleC2SPacket packet) {
        PacketDistributor.sendToServer(packet);
    }

    /** Sends one client the whole physical-restraint picture: login, respawn, dimension change. */
    public static void sendPhysicalState(ServerPlayer to, PhysicalStateS2CPacket packet) {
        PacketDistributor.sendToPlayer(to, packet);
    }

    /** Sends one client one subject's physical state, for the moment it starts tracking them. */
    public static void sendPhysicalStateDelta(ServerPlayer to, PhysicalStateDeltaS2CPacket packet) {
        PacketDistributor.sendToPlayer(to, packet);
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
        PacketDistributor.sendToPlayersTrackingEntity(subject, packet);
    }

    /** Sends one client a removal for a subject nobody can track any more. */
    public static void sendPhysicalStateRemoval(ServerPlayer to, PhysicalStateRemoveS2CPacket packet) {
        PacketDistributor.sendToPlayer(to, packet);
    }

    /** Tells everybody tracking this subject to forget their physical state. */
    public static void broadcastPhysicalStateRemoval(Entity subject, PhysicalStateRemoveS2CPacket packet) {
        if (subject == null) {
            return;
        }
        PacketDistributor.sendToPlayersTrackingEntity(subject, packet);
    }

    /**
     * Tells one client which body a restrained subject is being drawn on.
     *
     * <p>To the people who can see them rather than to everybody: the rig only matters because cuffs
     * are being drawn on it, and a broadcast would be a packet per restrained villager per client that
     * will never render one.
     */
    public static void sendRestraintRig(ServerPlayer to, UUID subject, boolean humanoid, String rig) {
        PacketDistributor.sendToPlayer(to, new RestraintRigSyncS2CPacket(subject, humanoid, rig));
    }

    /** Tells everybody tracking this subject what body their restraint is being drawn on. */
    public static void broadcastRestraintRig(Entity subject, boolean humanoid, String rig) {
        if (subject == null) {
            return;
        }
        PacketDistributor.sendToPlayersTrackingEntity(subject,
                new RestraintRigSyncS2CPacket(subject.getUUID(), humanoid, rig));
    }

    /** Answers a player's own settlement-safety enquiry. Never sent unsolicited. */
    public static void sendVillageSecurity(ServerPlayer player, VillageSecurityS2CPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
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
        GuardChallengeService.respond(sp, payload.encounterId(), payload.revision(), payload.response());
    }

    private static void handleGuardChallengeDisplayed(GuardChallengeDisplayedC2SPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        if (!RequestBudget.allow(sp.getUUID(), RequestBudget.Category.CHALLENGE)) return;
        GuardChallengeService.menuDisplayed(sp, payload.encounterId());
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

    /**
     * Tells one viewer which mask style their own station has selected (0.7.2 §8.1).
     *
     * <p>To that player alone: a Mask Station session is private to the viewer who opened it, and a
     * second player standing at the same block has a menu of their own with its own selection.
     */
    public static void sendMaskSelection(ServerPlayer player, MaskSelectionS2CPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }

    /** Answers a player's own bail enquiry. Never sent unsolicited, and never about anybody else. */
    public static void sendBailQuote(ServerPlayer player, BailQuoteS2CPacket packet) {
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
                JailService.sentencePaused(player),
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
