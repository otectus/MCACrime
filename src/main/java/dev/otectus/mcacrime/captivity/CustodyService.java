package dev.otectus.mcacrime.captivity;

import dev.otectus.mcacrime.enforcement.OutlawResolver;
import dev.otectus.mcacrime.enforcement.RestraintHandlers;
import dev.otectus.mcacrime.restraint.EscapeService;
import dev.otectus.mcacrime.restraint.LegacyRestraintProjection;
import dev.otectus.mcacrime.restraint.SessionCancelCause;
import dev.otectus.mcacrime.restraint.SessionRegistry;
import dev.otectus.mcacrime.restraint.RestraintService;
import dev.otectus.mcacrime.restraint.RestraintSyncService;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.ActionSessionManager;
import dev.otectus.mcacrime.activity.CrimeActivityRegistry;
import dev.otectus.mcacrime.activity.CrimeActivityView;
import dev.otectus.mcacrime.action.CancelReason;
import dev.otectus.mcacrime.api.event.EntityKidnappedEvent;
import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.api.event.EntityReleasedFromCaptivityEvent;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.CrimeDetector;
import dev.otectus.mcacrime.detect.WitnessChecker;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.ledger.SentenceAssignmentService;
import dev.otectus.mcacrime.network.ActionProgressS2CPacket;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.CrimeCapabilities;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * The server-authoritative, idempotent custody state machine for <b>unlawful</b> captivity — kidnapping
 * (spec §8). The structural twin of {@link JailService}: the sole writer of kidnapping {@link
 * CustodyRecord}s and of the {@code heldCaptiveRef}/{@code heldByRef} player pointers, with a guarded
 * {@link #release} (double-release is a no-op) and the pure {@link #advanceTick} real-time cap that makes a
 * softlock unreachable — an online captive is always freed within {@code maxCaptivityRealMinutes}, and
 * admin {@link #release} always works.
 *
 * <p>Lawful jail stays owned by {@code JailService}; the two never conflate (spec §1.4). Only the {@code
 * lawful} flag distinguishes them, and escaping kidnapping here is never a crime (contrast {@code
 * JailConfine}, which commits {@code jailbreak} on a physical jail escape — spec §8.1).
 */
public final class CustodyService {

    private CustodyService() {
    }

    // ------------------------------------------------------------------ queries

    public static boolean isCaptive(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server != null && CustodyRegistry.isCaptive(server, player.getUUID());
    }

    // ------------------------------------------------------------------ capture

    /**
     * Takes {@code captiveEntity} into unlawful captivity by {@code captor}. Idempotent: a named refusal
     * if the target is already held or is the captor. Commits the {@code kidnap} crime against the captor
     * (the victim is never penalised), sets the held refs, claims the villager's activity, fires {@link
     * EntityKidnappedEvent}, and syncs. <b>Server side only.</b>
     *
     * <p>Legal only, as of 0.7.5. The gear is already on the subject by the time this runs -- an
     * application is a physical event with its own transaction, and this is the paperwork that follows
     * it. Nothing here puts a restraint on anybody, and nothing here leashes a villager: an NPC captive
     * is held by the {@code CUSTODY} activity claim below, not by a vanilla lead (§3.3).
     */
    public static CaptureCommitResult capture(ServerPlayer captor, LivingEntity captiveEntity) {
        MinecraftServer server = captor.getServer();
        if (server == null || !(captiveEntity.level() instanceof ServerLevel level)) {
            return CaptureCommitResult.TARGET_INVALID;
        }
        UUID captiveUuid = captiveEntity.getUUID();
        if (captiveUuid.equals(captor.getUUID())) {
            return CaptureCommitResult.TARGET_INVALID; // no self-capture
        }
        if (CustodyRegistry.isCaptive(server, captiveUuid)) {
            return CaptureCommitResult.ALREADY_HELD; // no double-capture
        }
        long alreadyHeld = CustodyRegistry.byOwner(server, captor.getUUID()).stream()
                .filter(existing -> !existing.isLawful()).count();
        if (alreadyHeld >= McaCrimeConfig.COMMON.maxUnlawfulCaptivesPerCaptor.get()) {
            return CaptureCommitResult.QUOTA_FULL; // commit-time invariant: a race cannot bypass the start check
        }
        boolean captiveIsPlayer = captiveEntity instanceof ServerPlayer;

        // A citizen's arrest is not a kidnapping (0.5.1). Restraining somebody the law already
        // authorises force against, while a price stands on their head, is the thing the whole alive
        // route exists to make possible -- and charging the hunter with kidnapping for it is exactly
        // the "hunter punished for lawful force" bug this release was opened to kill. Routed to the
        // lawful path instead, which writes no kidnap record, posts no EntityKidnappedEvent, and does
        // not count against the captor's unlawful-captive allowance.
        if (captiveIsPlayer && McaCrimeConfig.COMMON.payForAliveCapture.get()
                && OutlawResolver.resolve((ServerPlayer) captiveEntity).bountyEligible()) {
            CaptureCommitResult taken = captureLawful(server, (ServerPlayer) captiveEntity,
                    CustodyOwner.bountyHunter(captor.getUUID()), captiveEntity.blockPosition(),
                    level.dimension().location());
            if (taken.ok()) {
                // Whatever the hunter actually put on them is already worn and already published; this
                // path adds the legal half only. A hunter's tape restrains exactly as much as a guard's
                // cuffs do, so the physical consequences are re-derived here.
                RestraintHandlers.onRestrained((ServerPlayer) captiveEntity);
                captor.sendSystemMessage(Component.translatable("mcacrime.bounty.citizens_arrest"));
            }
            return taken;
        }

        long start = CrimeCapabilities.get(captor).map(PlayerCrimeData::getOnlineTicksLived).orElse(0L);
        CaptureCommitResult committed = capture(CrimeWorldData.get(server), captor.getUUID(), captiveUuid,
                captiveIsPlayer, start, captiveEntity.blockPosition(),
                level.dimension().location(), McaCrimeConfig.COMMON.maxUnlawfulCaptivesPerCaptor.get());
        if (!committed.ok()) {
            return committed;
        }

        CrimeCapabilities.get(captor).ifPresent(d -> d.setHeldCaptiveRef(captiveUuid));
        ServerPlayer captivePlayer = captiveIsPlayer ? (ServerPlayer) captiveEntity : null;
        if (captivePlayer != null) {
            CrimeCapabilities.get(captivePlayer).ifPresent(d -> d.setHeldByRef(captor.getUUID()));
            // Being tied up is being tied up. The movement penalty and the suppressed interactions
            // belong to the restraint, not to the paperwork that put it there.
            RestraintHandlers.onRestrained(captivePlayer);
        } else {
            // The hold, and the only one: a claim of Kind.CUSTODY, which every ordinary villager
            // behaviour yields to. No vanilla lead, and never setNoAi (§3.3).
            assertCustodyClaim(captiveEntity);
        }
        // Gear is drawn from a client cache, and the cache only learns about a villager when somebody
        // says so. A capture is exactly such a moment.
        RestraintSyncService.broadcastDelta(captiveEntity, CrimeWorldData.get(server));

        // The captor is the criminal: commit the kidnap crime (Karma/Heat + ledger + witnessed events).
        CrimeDetector.commitDirect(captor, CrimeIds.KIDNAP, captiveEntity, level,
                WitnessChecker.resolve(level, captor, captiveEntity), "custody");

        MinecraftForge.EVENT_BUS.post(new EntityKidnappedEvent(captiveUuid, captiveIsPlayer, captor.getUUID(),
                true, LegacyRestraintProjection.of(CrimeWorldData.get(server).physicalRestraint(captiveUuid)),
                captivePlayer, captor));

        CrimeNetwork.sendSelfStatus(captor); // captor is now an active kidnapper -> Legal Target
        captor.sendSystemMessage(Component.translatable("mcacrime.kidnap.holding",
                McaCompat.getVillagerDisplayName(captiveEntity)));
        if (captivePlayer != null) {
            CrimeNetwork.sendSelfStatus(captivePlayer);
            CrimeNetwork.sendCaptiveStatus(captivePlayer);
            captivePlayer.sendSystemMessage(Component.translatable("mcacrime.kidnap.taken", captor.getDisplayName()));
        }
        return CaptureCommitResult.CAPTURED;
    }

    /**
     * Writes the unlawful custody record itself, against a ledger rather than a server.
     *
     * <p>This is the part of {@link #capture} that decides whether a capture may stand: not the
     * captor, not already held, not over the captor's allowance. Everything the server overload does
     * around it — the kidnap charge, the leash, the events, the messages — is consequence, and none
     * of it is safe to run when this returns false. Splitting them means the invariant can be asserted
     * against two captors racing for one victim without a level to hold either of them.
     *
     * @param maxUnlawfulPerCaptor the captor's allowance, read from config by the caller
     * @return why the capture may not stand, in which case nothing was written, or {@link
     *         CaptureCommitResult#CAPTURED}
     */
    public static CaptureCommitResult capture(CrimeWorldData data, UUID captorUuid, UUID captiveUuid,
                                              boolean captiveIsPlayer,
                                              long startTick, BlockPos holdPos, ResourceLocation holdDim,
                                              int maxUnlawfulPerCaptor) {
        if (data == null || captorUuid == null || captiveUuid == null || captorUuid.equals(captiveUuid)) {
            return CaptureCommitResult.TARGET_INVALID; // no self-capture
        }
        if (!ServerMutationGate.allows(data)) {
            return CaptureCommitResult.GATED;
        }
        if (data.isCaptive(captiveUuid)) {
            return CaptureCommitResult.ALREADY_HELD; // no double-capture
        }
        long alreadyHeld = data.custodyRecords().stream()
                .filter(existing -> !existing.isLawful())
                .filter(existing -> existing.getOwner().isKidnapper(captorUuid))
                .count();
        if (alreadyHeld >= maxUnlawfulPerCaptor) {
            return CaptureCommitResult.QUOTA_FULL; // commit-time invariant: a race cannot bypass the start check
        }
        data.putCustody(new CustodyRecord(captiveUuid, captiveIsPlayer, false,
                CustodyOwner.kidnapper(captorUuid), startTick, holdPos, holdDim));
        return CaptureCommitResult.CAPTURED;
    }

    /**
     * The lawful twin of {@link #capture(CrimeWorldData, UUID, UUID, boolean, long,
     * BlockPos, ResourceLocation, int)}: the record write and the one check that guards it, with no
     * capability, packet or sound attached.
     *
     * @return {@link CaptureCommitResult#ALREADY_HELD} when the captive is already held by anybody,
     *         lawfully or not
     */
    public static CaptureCommitResult captureLawful(CrimeWorldData data, UUID captiveUuid,
                                                    boolean captiveIsPlayer, CustodyOwner owner,
                                                    long startTick,
                                                    @Nullable BlockPos holdPos,
                                                    @Nullable ResourceLocation holdDim) {
        if (data == null || captiveUuid == null || owner == null) {
            return CaptureCommitResult.TARGET_INVALID;
        }
        if (!ServerMutationGate.allows(data)) {
            return CaptureCommitResult.GATED;
        }
        if (data.isCaptive(captiveUuid)) {
            return CaptureCommitResult.ALREADY_HELD;
        }
        data.putCustody(new CustodyRecord(captiveUuid, captiveIsPlayer, true, owner, startTick,
                holdPos, holdDim));
        return CaptureCommitResult.CAPTURED;
    }

    /**
     * Takes a player into <b>lawful</b> custody on behalf of an authority (spec §2.3).
     *
     * <p>Separate from {@link #capture} rather than a flag on it, because almost everything that method
     * does is wrong for an arrest. {@code capture} commits the {@code kidnap} crime <em>against the
     * captor</em>, counts against {@code maxUnlawfulCaptivesPerCaptor}, and leashes the captive — a
     * guard performing a lawful arrest must do none of those things, and a boolean parameter guarding
     * four unrelated behaviours would be a worse contract than two methods.
     *
     * <p>What the two share is the record: {@link CustodyOwner#guard} and {@link CustodyOwner#jail}
     * have existed since the custody table was written and until now were constructed only by tests,
     * because nothing in the mod ever took somebody into custody lawfully. This is what makes them
     * real.
     *
     * <p>The per-tick cost of a lawful record is a map lookup: {@code CrimeDecayHandler} enters
     * {@code CustodyService.tick} because {@code heldByRef} is set, and it returns immediately on
     * {@code record.isLawful()}. A lawful captive is not a kidnapping victim
     * and must not be subject to the escape work, the tether, or the real-time captivity cap; their
     * clock is the sentence.
     *
     * @return {@link CaptureCommitResult#ALREADY_HELD} when the player is already held by anybody,
     *         lawfully or not
     */
    public static CaptureCommitResult captureLawful(MinecraftServer server, ServerPlayer captive,
                                                    CustodyOwner owner, BlockPos holdPos,
                                                    ResourceLocation holdDim) {
        if (server == null || captive == null || owner == null) {
            return CaptureCommitResult.TARGET_INVALID;
        }
        UUID captiveUuid = captive.getUUID();
        long start = CrimeCapabilities.get(captive).map(PlayerCrimeData::getOnlineTicksLived).orElse(0L);
        CaptureCommitResult committed = captureLawful(CrimeWorldData.get(server), captiveUuid, true, owner,
                start, holdPos, holdDim);
        if (!committed.ok()) {
            return committed;
        }
        CrimeCapabilities.get(captive).ifPresent(d -> d.setHeldByRef(
                owner.ownerUuid().orElse(captiveUuid)));
        // The record carries no gear of its own: what the client draws is whatever is physically on
        // the prisoner, which is published here in case this is the first anyone has heard of them.
        RestraintSyncService.broadcastDelta(captive, CrimeWorldData.get(server));
        CrimeNetwork.sendSelfStatus(captive);
        CrimeNetwork.sendCaptiveStatus(captive);
        return CaptureCommitResult.CAPTURED;
    }

    /**
     * Takes a villager into <b>lawful</b> custody on behalf of a guard or a jail (0.5.1).
     *
     * <p>The NPC twin of {@link #captureLawful}, and separate from it for the same reason that method
     * is separate from {@link #capture}: almost everything the player path does is about a player.
     * There is no capability to write a {@code heldByRef} into, no self-status packet to send, and no
     * captive screen to open — what a restrained villager needs is the record, the restraint, and a
     * client that knows to draw it.
     *
     * <p>It deliberately does <em>not</em> post {@link EntityKidnappedEvent}. That event's contract is
     * unlawful captivity, and an arrest firing it would tell every listener — including any companion
     * mod reading it as evidence of a crime — that the guard had just kidnapped somebody.
     *
     * @return {@link CaptureCommitResult#ALREADY_HELD} when the villager is already held by anybody,
     *         lawfully or not
     */
    public static CaptureCommitResult captureNpcLawful(MinecraftServer server, LivingEntity captive,
                                                       CustodyOwner owner,
                                                       @Nullable BlockPos holdPos,
                                                       @Nullable ResourceLocation holdDim) {
        if (server == null || captive == null || owner == null || captive instanceof ServerPlayer) {
            return CaptureCommitResult.TARGET_INVALID;
        }
        UUID captiveUuid = captive.getUUID();
        CaptureCommitResult committed = captureLawful(CrimeWorldData.get(server), captiveUuid, false, owner,
                0L, holdPos, holdDim);
        if (!committed.ok()) {
            return committed;
        }
        // Same reason as the kidnapping path: gear is drawn from a client cache that only learns
        // about a villager when somebody tells it.
        RestraintSyncService.broadcastDelta(captive, CrimeWorldData.get(server));
        assertCustodyClaim(captive);
        return CaptureCommitResult.CAPTURED;
    }

    /**
     * Rewrites who holds a lawful captive, without releasing and re-taking them.
     *
     * <p>Used at the end of an escort: custody passes from the arresting guard to the jail itself, and
     * a release-then-capture would fire the public events twice and momentarily leave the player free.
     */
    public static boolean transferLawfulCustody(MinecraftServer server, UUID captiveUuid, CustodyOwner owner) {
        if (server == null || owner == null || !ServerMutationGate.allows(server)) {
            return false;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        CustodyRecord record = data.getCustody(captiveUuid);
        if (record == null || !record.isLawful()) {
            return false;
        }
        record.setOwner(owner);
        // The same captivity under new management: the id is kept, the generation advances, and every
        // packet and session the previous holder was issued stops being accepted (0.7.5 §6.2).
        record.bumpGeneration();
        data.putCustody(record);
        return true;
    }

    /**
     * Re-asserts the activity claim that says this villager is not free.
     *
     * <p>Custody outlasts any lease, so it is re-asserted from the sweeps that already walk the custody
     * records rather than taken once and given a deadline nobody would renew. Idempotent: the owner
     * token and kind are constant, so a re-assertion renews the same claim and keeps its generation,
     * which is what lets the release below be generation-safe.
     *
     * <p>{@link CrimeActivityView.Kind#CUSTODY} is the strongest authority there is, so a claim taken
     * here is never pre-empted by a guard hold, a challenge or a reaction. That is the point: a
     * prisoner does not stop being a prisoner because somebody shouted at them.
     */
    public static void assertCustodyClaim(@Nullable Entity captive) {
        if (captive == null || captive instanceof ServerPlayer || captive.level() == null) {
            return;
        }
        try {
            CrimeActivityRegistry.claim(captive.getUUID(), captive.level().dimension().location(),
                    CrimeActivityView.Kind.CUSTODY, "custody", captive.level().getGameTime());
        } catch (Throwable t) {
            // Coordination is an improvement on top of custody, never a precondition for it.
        }
    }

    // ------------------------------------------------------------------ release (idempotent)

    public static void release(MinecraftServer server, UUID captiveUuid, CustodyReleaseReason reason) {
        CrimeWorldData data = CrimeWorldData.get(server);
        CustodyRecord record = data.getCustody(captiveUuid);
        if (record == null) {
            return; // the guard that makes double-release a no-op
        }
        ServerPlayer captivePlayer = record.isCaptivePlayer()
                ? server.getPlayerList().getPlayer(captiveUuid) : null;
        data.removeCustody(captiveUuid);
        // The authoritative episode is gone before respawn restoration is considered. An offline
        // player retains the persisted snapshot for login reconciliation; a jail sentence that is
        // still active keeps owning it until its own release event.
        dev.otectus.mcacrime.detention.BunkRespawnPolicy.release(captivePlayer);
        // Unconditional rather than generation-scoped, and one of only two places that is right: the
        // custody record is gone, so every claim that existed because of it is meaningless, whoever
        // took it.
        CrimeActivityRegistry.forget(captiveUuid);
        // The same reasoning for the two 0.7.3 registries: a cell slot held for a captivity that has
        // ended can never be spent, and a care interval for a released prisoner names nobody.
        CustodyCareService.forget(captiveUuid);
        dev.otectus.mcacrime.facility.CrimeFacilityService.releaseFor(server, captiveUuid);
        dev.otectus.mcacrime.enforcement.JailEscortNavigation.forget(captiveUuid);
        data.removeRansom(captiveUuid);
        ActionSessionManager.clearFor(captiveUuid, CancelReason.TARGET_GONE);
        // The gear this captivity put on comes off with it, and only that gear: a subject's own
        // restraints are theirs. System-issued and legacy-converted instances return no item, so a
        // release can never mint a pair of cuffs (§5.4).
        RestraintService.releaseFor(server, captiveUuid, record.getCustodyId());
        // And the claims this custody owned rather than merely coexisted with: the escort that existed
        // only because somebody was being walked somewhere, and any execution order naming them
        // (§3.19 -- a pardon, a ransom or a served sentence all clear it). A chain somebody paid for
        // and a device somebody locked are independent physical facts and are deliberately left alone,
        // for the same reason removing one restraint is not a release.
        dev.otectus.mcacrime.restraint.CustodyTransitionService.clearPhysicalClaims(server, captiveUuid,
                record.getCustodyId());
        UUID formerCaptor = record.getOwner().ownerUuid().orElse(null);

        if (formerCaptor != null) {
            ServerPlayer captorPlayer = server.getPlayerList().getPlayer(formerCaptor);
            if (captorPlayer != null) {
                CrimeCapabilities.get(captorPlayer).ifPresent(d -> {
                    if (captiveUuid.equals(d.getHeldCaptiveRef())) {
                        d.setHeldCaptiveRef(null);
                    }
                });
                CrimeNetwork.sendSelfStatus(captorPlayer);
            }
        }

        if (record.isLawful() && (!record.isCaptivePlayer()
                || (captivePlayer != null && !JailService.isJailed(captivePlayer)))) {
            SentenceAssignmentService.cancel(data, captiveUuid, record.getSentenceId());
        }
        if (captivePlayer != null) {
            CrimeCapabilities.get(captivePlayer).ifPresent(d -> d.setHeldByRef(null));
            // Released from somebody's custody is the same vulnerable moment as released from a cell,
            // and a player let go of by a kidnapper is owed the same grace (0.7.0).
            dev.otectus.mcacrime.mug.npc.MugProtection.grant(captivePlayer,
                    dev.otectus.mcacrime.McaCrimeConfig.COMMON.releaseMugProtectionTicks.get());
            // Re-derived rather than simply lifted: a player released from a kidnapper's tape straight
            // into an arrest is still restrained, and taking the penalty off here would hand them a
            // free sprint the moment a guard reached them.
            if (RestraintHandlers.policy(captivePlayer).unrestrictedPolicy()) {
                RestraintHandlers.onReleased(captivePlayer);
            } else {
                RestraintHandlers.refresh(captivePlayer);
            }
            RestraintSyncService.broadcastDelta(captivePlayer, data);
            CrimeNetwork.sendSelfStatus(captivePlayer);
            CrimeNetwork.sendCaptiveStatus(captivePlayer); // record already removed -> clears the client
            captivePlayer.sendSystemMessage(Component.translatable(releaseKey(reason)));
        }
        if (!record.isCaptivePlayer()) {
            // Three things have to come off together, in this order, and none of them is removal. The
            // lead first, then MCA's own control of the villager -- released rather than merely cleared,
            // because an arrest pointed their brain at a destination and leaving it there is how a freed
            // villager walks back to the jail on its own. The distraction last: a relative let out of a
            // cell is not still making a scene, and an effect that outlived its owner's custody would go
            // on shrinking somebody's witness set with nobody left to blame for it.
            Entity freed = resolveEntity(server, record.getHoldDim(), captiveUuid);
            NpcReleaseEffects.apply(new NpcReleaseEffects.Effects() {
                @Override
                public void clearLeash() {
                    // Both, and in this order. The tether is the live hold as of 0.7.5 M4.3; the
                    // vanilla lead is only ever a leftover from a world upgraded from before it, and
                    // leaving one on would be a second hold nothing owns.
                    dev.otectus.mcacrime.tether.TetherService.detachAll(server, captiveUuid,
                            dev.otectus.mcacrime.tether.TetherService.DetachReason.ADMINISTRATIVE);
                    McaCompat.clearLeash(freed);
                }

                @Override
                public void releaseControl() {
                    McaCompat.releaseVillagerControl(freed);
                }

                @Override
                public void clearDistraction() {
                    dev.otectus.mcacrime.detect.WitnessModifiers.remove(captiveUuid,
                            dev.otectus.mcacrime.detect.WitnessModifiers.Kind.DISTRACTION);
                }

                @Override
                public void notifyFamily() {
                    dev.otectus.mcacrime.enforcement.AccompliceService.notifyFamily(server, captiveUuid,
                            "mcacrime.msg.family.released");
                }
            }, freed != null, record.isLawful());
        }
        if (!record.isCaptivePlayer()) {
            // By id, not by entity: the record may outlive the loaded villager, and a client that saw
            // the cuffs must be told they are gone even when the chunk is asleep.
            Entity loaded = resolveEntity(server, record.getHoldDim(), captiveUuid);
            if (loaded != null) {
                RestraintSyncService.broadcastDelta(loaded, data);
            } else {
                RestraintSyncService.broadcastRemoval(server, captiveUuid);
            }
        }

        if (captivePlayer != null) {
            CrimeSounds.restraintRemoved(captivePlayer);
        }
        MinecraftForge.EVENT_BUS.post(new EntityReleasedFromCaptivityEvent(captiveUuid, record.isCaptivePlayer(),
                captivePlayer, formerCaptor, reason));
    }

    private static String releaseKey(CustodyReleaseReason reason) {
        return switch (reason) {
            case RESCUED -> "mcacrime.kidnap.released.rescued";
            case ESCAPED -> "mcacrime.kidnap.released.escaped";
            case CAPTOR_GONE -> "mcacrime.kidnap.released.captorgone";
            case CAPTIVITY_CAP -> "mcacrime.kidnap.released.cap";
            case ADMIN -> "mcacrime.kidnap.released.admin";
            case RELEASED_BY_CAPTOR -> "mcacrime.kidnap.released.captor";
            case RANSOM_PAID -> "mcacrime.kidnap.released.ransom";
            case SENTENCE_SERVED -> "mcacrime.kidnap.released.served";
            case CAPTIVE_DIED -> "mcacrime.kidnap.released.died";
        };
    }

    // ------------------------------------------------------------------ escape (kidnapping only; never a crime)

    /**
     * A captive's request to work their way out of whatever is physically on them.
     *
     * <p>0.7.5 turned this from a timed roll into a physical act (§5.4). There is no captivity-wide
     * escape chance any more and no hidden die: a subject strains against one worn restraint at a
     * time, the server decides whether that input counted, and the restraint comes off when its own
     * durability runs out. Escaping kidnapping is still never a crime, and that is now a consequence
     * of {@code RestraintService} owning gear and this class owning paperwork rather than a rule
     * written here.
     *
     * <p>Held lawfully or not makes no difference to the gear. What differs is what the escape
     * <em>means</em>, and that is filed where it always was: {@code JailService} on a jail break, and
     * nothing at all on a kidnapping.
     *
     * @return true when the request was accepted as work, whether or not anything came off
     */
    public static boolean attemptEscape(ServerPlayer captive) {
        if (captive == null || captive.getServer() == null) {
            return false;
        }
        EscapeService.Attempt attempt = EscapeService.struggleOnce(captive);
        switch (attempt.outcome()) {
            case NOTHING_WORN -> {
                captive.sendSystemMessage(Component.translatable("mcacrime.captive.escape.not_held"));
                return false;
            }
            case NOT_BREAKABLE -> {
                captive.sendSystemMessage(Component.translatable("mcacrime.captive.escape.locked"));
                return false;
            }
            case REFUSED -> {
                captive.sendSystemMessage(Component.translatable("mcacrime.captive.escape.cooldown", 0));
                return false;
            }
            case BROKEN -> {
                captive.sendSystemMessage(Component.translatable("mcacrime.captive.escape.broke_free"));
                return true;
            }
            default -> {
                captive.sendSystemMessage(Component.translatable("mcacrime.captive.escape.strained",
                        attempt.remainingDurability()));
                return true;
            }
        }
    }

    // ------------------------------------------------------------------ per-tick cap (from CrimeDecayHandler)

    /** Per-tick cap accounting for an online PLAYER kidnapping captive; releases at the real-time cap (§7.2). */
    public static void tick(ServerPlayer captive) {
        MinecraftServer server = captive.getServer();
        if (server == null) {
            return;
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        CustodyRecord record = world.getCustody(captive.getUUID());
        if (record == null || (record.isLawful() && record.getOwner() != null
                && record.getOwner().type() == CustodyOwnerType.JAIL)) {
            // A jail's clock is the sentence, and that is JailService's job. A hunter's is not: nobody
            // else measures how long a player has been sitting in a bounty hunter's rope, so the
            // real-time cap has to, or the hold is unbounded.
            return;
        }
        UUID captorId = record.getOwner().ownerUuid().orElse(null);
        if (record.isCaptivePlayer() && captorId != null
                && (record.getOwner().type() == CustodyOwnerType.KIDNAPPER
                || record.getOwner().type() == CustodyOwnerType.BOUNTY_HUNTER)) {
            ServerPlayer captor = server.getPlayerList().getPlayer(captorId);
            long now = captive.level().getGameTime();
            if (captor != null) {
                if (record.getCaptorDisconnectedAt() != 0L) record.setCaptorDisconnectedAt(0L);
            } else {
                if (record.getCaptorDisconnectedAt() == 0L) record.setCaptorDisconnectedAt(now);
                long grace = McaCrimeConfig.COMMON.captorDisconnectGraceTicks.get();
                if (grace == 0L || now - record.getCaptorDisconnectedAt() >= grace) {
                    release(server, captive.getUUID(), CustodyReleaseReason.CAPTOR_GONE);
                    return;
                }
            }
        }
        long capTicks = (long) McaCrimeConfig.COMMON.maxCaptivityRealMinutes.get() * 1200L;
        CustodyReleaseReason due = advanceTick(record, capTicks);
        world.setDirty();
        if (due != null) {
            release(server, captive.getUUID(), due);
        }
    }

    /**
     * Pure one-tick advance: accumulates real held time and returns {@link CustodyReleaseReason#CAPTIVITY_CAP}
     * when the cap is reached, else null. No game/config deps, so the cap backstop is unit-testable.
     */
    public static CustodyReleaseReason advanceTick(CustodyRecord record, long capTicks) {
        record.setRealTicksHeld(record.getRealTicksHeld() + 1L);
        if (capTicks > 0L && record.getRealTicksHeld() >= capTicks) {
            return CustodyReleaseReason.CAPTIVITY_CAP;
        }
        return null;
    }

    // ------------------------------------------------------------------ login reconcile (no softlock)

    /**
     * On login, reconcile a player's captive/captor pointers against the authoritative table and guarantee
     * no softlock: a captive whose captor reference is gone is freed (§8.4); the real-time cap and admin
     * release remain the backstops for an offline-but-valid captor. A captor's dangling held-ref is cleared.
     */
    public static void reconcileOnLogin(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        Optional<PlayerCrimeData> dataOpt = CrimeCapabilities.get(player);
        CustodyRecord asCaptive = world.getCustody(player.getUUID());
        if (asCaptive != null && !asCaptive.isLawful()) {
            UUID captor = asCaptive.getOwner().ownerUuid().orElse(null);
            if (captor == null) {
                release(server, player.getUUID(), CustodyReleaseReason.CAPTOR_GONE);
            } else {
                dataOpt.ifPresent(d -> d.setHeldByRef(captor)); // table is authoritative
            }
        } else {
            dataOpt.ifPresent(d -> {
                if (d.getHeldByRef() != null) {
                    d.setHeldByRef(null); // no longer a kidnapping captive
                }
            });
        }
        java.util.List<CustodyRecord> owned = CustodyRegistry.byOwner(server, player.getUUID()).stream()
                .filter(record -> !record.isLawful()).toList();
        dataOpt.ifPresent(d -> d.setHeldCaptiveRef(owned.isEmpty() ? null : owned.get(0).getCaptive()));
        // Legacy builds could orphan several records behind one scalar cache. Keep the oldest/first
        // authoritative record and release the ambiguous extras instead of hiding them forever.
        for (int i = 1; i < owned.size(); i++) {
            release(server, owned.get(i).getCaptive(), CustodyReleaseReason.ADMIN);
        }
        // If this player is a returning captor, clear their disconnect stamps.
        for (CustodyRecord held : CustodyRegistry.byOwner(server, player.getUUID())) {
            if (held.getCaptorDisconnectedAt() != 0L) {
                held.setCaptorDisconnectedAt(0L);
                world.setDirty();
            }
        }
    }

    public static void onCaptorLogout(ServerPlayer captor) {
        MinecraftServer server = captor.getServer();
        if (server == null) return;
        CrimeWorldData world = CrimeWorldData.get(server);
        long now = captor.level().getGameTime();
        for (CustodyRecord held : CustodyRegistry.byOwner(server, captor.getUUID())) {
            if (!held.isLawful() && held.isCaptivePlayer() && held.getCaptorDisconnectedAt() == 0L) {
                held.setCaptorDisconnectedAt(now);
                world.setDirty();
            }
        }
    }

    /**
     * Stops whatever escape work a subject had going, because something happened to them.
     *
     * <p>A session rather than a flag on the record now: struggle progress is durability on the worn
     * instance and a live {@code WorkSession}, so interrupting one is cancelling the session. The
     * durability already spent is kept -- being hit while working at a pair of cuffs should not undo
     * the work, only the run of it.
     */
    public static void interruptEscape(UUID captive, MinecraftServer server) {
        if (server == null || captive == null) {
            return;
        }
        SessionRegistry.server().cancelForActor(captive, SessionCancelCause.DAMAGE);
    }

    @Nullable
    // ------------------------------------------------------------------ NPC captives (§14.5 virtualization)

    /**
     * Advances every NPC captivity, and implements {@code npcCaptiveVirtualizeWhenUnloaded}.
     *
     * <p>This closes a real hole rather than adding a feature. Only <em>player</em> captives were ever
     * ticked, through {@code CrimeDecayHandler}, so a kidnapped villager's real-time cap never advanced
     * at all: the captivity that was supposed to end within {@code maxCaptivityRealMinutes} instead
     * lasted until somebody released it, and if the captor walked away and the chunk unloaded, it
     * lasted forever as an invisible row in world data.
     *
     * <p>The setting decides what happens when the captive's chunk is not loaded. On, the record is
     * marked virtual and keeps counting — captivity continues while nobody is looking, which is what
     * the key describes. Off, the captivity ends, because §13.3 is explicit that the alternative must
     * never be an invisible permanent custody record.
     *
     * @param elapsedTicks how many ticks to credit; the caller batches these rather than running every tick
     */
    public static void tickNpcCaptives(MinecraftServer server, long elapsedTicks) {
        if (server == null || elapsedTicks <= 0L) {
            return;
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        boolean virtualize = McaCrimeConfig.COMMON.npcCaptiveVirtualizeWhenUnloaded.get();
        long capTicks = (long) McaCrimeConfig.COMMON.maxCaptivityRealMinutes.get() * 1200L;
        boolean dirty = false;

        for (CustodyRecord record : world.custodyRecords()) {
            if (record.isCaptivePlayer() || record.isLawful()) {
                continue; // player captives tick with their own player; lawful custody is JailService's
            }
            Entity npc = resolveEntity(server, record.getHoldDim(), record.getCaptive());
            if (npc == null) {
                if (!virtualize) {
                    release(server, record.getCaptive(), CustodyReleaseReason.CAPTOR_GONE);
                    continue;
                }
                if (!record.isVirtual()) {
                    record.setVirtual(true);
                    dirty = true;
                }
            } else if (record.isVirtual()) {
                // Back in a loaded chunk. The hold is the activity claim re-asserted below, not a lead:
                // world data survived the unload and the gear on them survived with it.
                record.setVirtual(false);
                dirty = true;
                // Re-announce it. Clients tracking a captive that was virtual while they were watching
                // have nothing in their cache to draw from.
                RestraintSyncService.broadcastDelta(npc, world);
            }

            assertCustodyClaim(npc);
            record.setRealTicksHeld(record.getRealTicksHeld() + elapsedTicks);
            dirty = true;
            if (capTicks > 0L && record.getRealTicksHeld() >= capTicks) {
                release(server, record.getCaptive(), CustodyReleaseReason.CAPTIVITY_CAP);
            }
        }
        if (dirty) {
            world.setDirty();
        }
    }

    private static Entity resolveEntity(MinecraftServer server, @Nullable ResourceLocation dim, UUID uuid) {
        ServerLevel level = JailService.resolveLevel(server, dim);
        return level == null ? null : level.getEntity(uuid);
    }
}
