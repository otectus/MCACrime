package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.bounty.BountyService;
import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyOwnerType;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.economy.SentenceCalculator;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.SentenceAssignmentService;
import dev.otectus.mcacrime.memory.ReportService;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.jail.HoldingCell;
import dev.otectus.mcacrime.jail.HoldingCellService;
import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.jail.JailRegistry;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.state.CrimeAttachments;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import org.jetbrains.annotations.Nullable;
import java.util.OptionalLong;
import java.util.List;
import java.util.UUID;

/**
 * Turns a surrender into an actual arrest (spec §6.3, §7).
 *
 * <p>This is the piece that was missing. Surrendering to a guard reduced Heat by thirty and did nothing
 * else: {@code JailService.jail} had exactly one caller in the whole mod, the operator command, so no
 * sequence of in-game actions could ever put a player in a cell. A player who surrendered watched their
 * Heat drop, the guard stand down, and — because the surrender had not resolved anything — a fresh
 * challenge open ten ticks later.
 *
 * <p>An arrest here is four things in order: a sentence worked out from what the player actually did,
 * somewhere to serve it, lawful custody transferring the player to the law, and an escort to the cell.
 * Each step can decline, and declining is always better than a stuck state — the outcomes below are
 * reported rather than swallowed, because "there is nowhere to hold you" is a thing a player needs to
 * be told rather than a silence to interpret.
 */
public final class ArrestService {

    /** Why an arrest is happening, which decides how much proof it needs. */
    public enum Cause {
        /** The player asked. No warrant required: turning yourself in is always allowed. */
        VOLUNTARY_SURRENDER,
        /** A guard took them. Requires a basis, because taking somebody's liberty is the higher bar. */
        GUARD_INITIATED,
        /**
         * A bounty hunter brought them in (0.5.1).
         *
         * <p>No fresh basis is demanded, for the same reason a surrender does not need one: the
         * hunter already established it when they took the outlaw into custody, and re-deriving it at
         * the guard's feet would let an outlaw whose Heat decayed during the walk simply be let go
         * with the hunter unpaid.
         */
        DELIVERED
    }

    /** What came of it. Every value is a thing the caller can say out loud. */
    public enum Outcome {
        ARRESTED,
        /** Nothing to serve. The Heat reduction stands; the player simply walks. */
        NO_SENTENCE,
        /** No jail, no buildable ground. Refused rather than left half-applied. */
        NO_CELL,
        /** Already serving; the sentence was extended instead. */
        ALREADY_SERVING,
        /**
         * Custody could not be installed — somebody else holds them, or the hand-over from the hunter
         * found no record to hand over. Refused rather than arrested on paper only.
         */
        NO_CUSTODY,
        /** Being kidnapped, dead, spectating, or otherwise not arrestable right now. */
        REFUSED
    }

    private ArrestService() {
    }

    /**
     * Arrests {@code player}, if everything an arrest needs is available.
     *
     * <p>Every precondition is re-derived here. The challenge screen is a way of asking, never a source
     * of authority, so "the client said surrender" still has to pass every check the command would.
     */
    public static Outcome arrest(ServerPlayer player, @Nullable LivingEntity arrestingResponder, Cause cause) {
        return arrest(player, arrestingResponder, cause, OptionalLong.empty());
    }

    /**
     * The same arrest, sentenced against a Heat figure the caller has decided but not yet written.
     *
     * <p>{@link Cause#VOLUNTARY_SURRENDER} is the reason this exists. A surrender only writes its Heat
     * reduction once the arrest has succeeded, so by the time this runs the attachment still holds the
     * pre-surrender number — and sentencing from that would quietly delete the entire mechanical
     * payoff for giving yourself up. Passing the figure keeps the ordering safe and the sentence
     * correct at the same time.
     *
     * @param sentencingHeat the Heat to sentence from, or empty to read the player's current Heat
     */
    public static Outcome arrest(ServerPlayer player, @Nullable LivingEntity arrestingResponder,
                                 Cause cause, OptionalLong sentencingHeat) {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null || !(player.level() instanceof ServerLevel level)
                || !player.isAlive() || player.isSpectator() || !ServerMutationGate.allows(server)) {
            return Outcome.REFUSED;
        }
        double authorityReach = cause == Cause.VOLUNTARY_SURRENDER
                ? McaCrimeConfig.COMMON.surrenderNearRadius.get() : McaCrimeConfig.COMMON.guardChallengeRadius.get();
        if (arrestingResponder != null && (!dev.otectus.mcacrime.ai.NpcAwareness.isAwake(arrestingResponder)
                || arrestingResponder.level() != level || !EntitySelectors.isResponder(arrestingResponder)
                || !arrestingResponder.hasLineOfSight(player)
                || arrestingResponder.distanceToSqr(player) > authorityReach * authorityReach)) return Outcome.REFUSED;
        if (cause == Cause.GUARD_INITIATED && arrestingResponder == null) return Outcome.REFUSED;
        if (arrestingResponder != null && (ResponderAssignments.isEscorting(server,
                arrestingResponder.getUUID(), player.getUUID()) || NpcCriminalPursuit.isAssignedElsewhere(
                arrestingResponder.getUUID(), player.getUUID()))) return Outcome.REFUSED;
        CustodyRecord held = CrimeWorldData.get(server).getCustody(player.getUUID());
        if (held != null && !held.isLawful()) {
            // Being kidnapped is not a state you can be arrested out of. Somebody else is holding them
            // against the law, and the answer to that is a rescue, not a second set of chains.
            return Outcome.REFUSED;
        }
        if (held != null && held.getOwner().type() != CustodyOwnerType.BOUNTY_HUNTER
                && !JailService.isJailed(player)) return Outcome.REFUSED;

        List<CrimeRecord> assessed = CrimeWorldData.get(server).actionableFor(player.getUUID());
        if (arrestingResponder != null) {
            assessed = dev.otectus.mcacrime.justice.JusticeService.forGuard(level, arrestingResponder, player).cases();
        }
        List<UUID> assessedCaseIds = assessed.stream().map(CrimeRecord::id).toList();
        int charges = assessedCaseIds.size();
        if (cause == Cause.GUARD_INITIATED && !hasBasis(server, level, player, arrestingResponder, charges)) {
            return abort(player, Outcome.REFUSED, null);
        }
        // Who, if anybody, is owed a bounty for this arrest -- read before anything below can rewrite
        // the custody record out from under the answer.
        UUID hunter = held != null && held.getOwner() != null
                && held.getOwner().type() == CustodyOwnerType.BOUNTY_HUNTER
                ? held.getOwner().ownerUuid().orElse(null)
                : null;

        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        long heat = sentencingHeat.orElseGet(() -> CrimeState.getHeat(player));
        long sentence = SentenceCalculator.sentenceFor(heat, CrimeState.getBand(player),
                charges, c.sentenceBaseTicks.get(), c.sentenceTicksPerHeat.get(),
                c.sentenceTicksPerCharge.get(), c.blueFineMultiplier.get(), c.maxJailCommandTicks.get());
        boolean voluntary = cause == Cause.VOLUNTARY_SURRENDER;
        if (voluntary) {
            // The waiver belongs in the number, not in an edit applied to a sentence afterwards.
            sentence = SentenceCalculator.afterSurrender(sentence, c.surrenderSentenceReductionPct.get());
        }
        if (JailService.isJailed(player)) {
            // Recapture resumes the assessed sentence. Repeated arrest never re-prices or expands it.
            if (!JailService.recapture(player)) {
                return abort(player, Outcome.NO_CELL, "mcacrime.arrest.no_cell");
            }
            return Outcome.ALREADY_SERVING;
        }
        if (charges <= 0 && heat <= 0L) {
            return abort(player, Outcome.NO_SENTENCE, null);
        }

        // Record the arrest before anything can fail, so no failure path can leave the player marked as
        // surrendering with nothing behind it -- the hole the confrontation screen used to pour into.
        ArrestStates.surrendered(player, arrestingResponder == null ? null : arrestingResponder.getUUID(),
                null);
        ArrestState state = ArrestStates.of(player);
        if (state != null) state.setSurrenderCredited(voluntary);
        UUID sentenceId = state == null ? UUID.randomUUID() : state.getSentenceId();

        JailAnchor destination = resolveDestination(server, level, player, arrestingResponder, sentenceId);
        if (destination == null) {
            return abort(player, Outcome.NO_CELL, "mcacrime.arrest.no_cell");
        }

        UUID custodian = arrestingResponder == null ? player.getUUID() : arrestingResponder.getUUID();
        boolean inCustody;
        if (hunter != null) {
            // Already in lawful custody, so captureLawful would refuse. Custody passes from the hunter
            // to the law in place, exactly as it does at the end of an escort.
            inCustody = CustodyService.transferLawfulCustody(server, player.getUUID(),
                    CustodyOwner.guard(custodian));
        } else {
            inCustody = CustodyService.captureLawful(server, player, CustodyOwner.guard(custodian),
                    player.blockPosition(), level.dimension().location()).ok();
        }
        if (!inCustody) {
            // The custody record is what the sentence, the escort and the release all hang off. An
            // arrest that carried on without one used to arm the escort anyway, and the discarded
            // refusal became a player walking to a cell nothing believed they were being taken to.
            HoldingCellService.releaseAndDismantle(server, player.getUUID());
            return abort(player, Outcome.NO_CUSTODY, "mcacrime.arrest.no_custody");
        }

        if (!SentenceAssignmentService.assign(CrimeWorldData.get(server), player.getUUID(),
                sentenceId, assessedCaseIds, level.getGameTime())) {
            CustodyService.release(server, player.getUUID(),
                    dev.otectus.mcacrime.captivity.CustodyReleaseReason.ADMIN);
            HoldingCellService.releaseAndDismantle(server, player.getUUID());
            return abort(player, Outcome.NO_CUSTODY, "mcacrime.arrest.no_custody");
        }
        // Surrendering ends the resistance. Whatever the player did a moment ago, they are complying now.
        CrimeState.setResistingArrest(player, false);

        ArrestStates.arm(player, destination, sentence, c.arrestEscortTimeoutTicks.get());
        ArrestStates.transition(player, ArrestPhase.RESTRAINED);

        if (!EscortService.beginChecked(player, arrestingResponder, destination, sentence)) {
            return abort(player, Outcome.NO_CELL, "mcacrime.arrest.no_cell");
        }
        player.sendSystemMessage(Component.translatable("mcacrime.arrest.taken"));
        if (hunter != null) {
            // The delivery is only real once the arrest is: a hunter who walks an outlaw past a guard
            // and keeps going has delivered nothing.
            BountyService.resolveCapture(server, hunter, player);
        }
        return Outcome.ARRESTED;
    }

    /**
     * Ends an arrest that could not proceed, guaranteeing the player is not left restrained.
     *
     * <p>Every non-arresting outcome comes through here, and that is the point. The outcomes existed
     * before and both callers discarded them, so "there is nowhere to hold you" was a value returned
     * into a void while the player stood cuffed and the guard re-opened its screen ten ticks later.
     * Recovering here rather than in the callers means a future caller cannot forget to.
     */
    private static Outcome abort(ServerPlayer player, Outcome outcome, @Nullable String reasonKey) {
        if (ArrestStates.isRestrained(player)) {
            GuardChallengeService.standDownAndRecover(player, reasonKey);
        } else if (reasonKey != null) {
            player.sendSystemMessage(Component.translatable(reasonKey));
        }
        return outcome;
    }

    /**
     * Where this arrest is going, in strict priority order.
     *
     * <p>An operator-assigned jail always wins: somebody built it and pointed the mod at it, and
     * raising a cage next to it would be the mod overruling a decision that was already made. Only when
     * there is no jail at all does the mod build, and only then does it fall back to the config
     * coordinate — which is disabled by default and exists for servers that want a fixed destination
     * without a structure.
     */
    @Nullable
    private static JailAnchor resolveDestination(MinecraftServer server, ServerLevel level,
                                                 ServerPlayer player, @Nullable LivingEntity responder,
                                                 UUID sentenceId) {
        JailAnchor assigned = JailRegistry
                .nearestTo(player, McaCrimeConfig.COMMON.jailAssignedMaxDistance.get())
                .orElse(null);
        if (assigned != null) {
            return assigned;
        }
        HoldingCell existing = HoldingCellService.existingFor(server, player.getUUID());
        if (existing != null) {
            return existing.toAnchor();
        }
        // The sentence id is threaded through rather than minted here, so the cell and the sentence it
        // was built for finally carry the same one and an orphan is detectable across a restart.
        HoldingCell built = HoldingCellService.provision(level,
                responder == null ? player.blockPosition() : responder.blockPosition(),
                player.getUUID(), sentenceId);
        if (built != null) {
            return built.toAnchor();
        }
        // Last: the configured fixed destination. This branch is why an arrest used to be refused on a
        // server that had jailFallbackEnabled on and buildHoldingCell off -- /crime jail could reach it
        // and the arrest path could not, so surrender did nothing while the command worked.
        return JailService.configFallbackAnchor().orElse(null);
    }

    /**
     * Whether a guard may take this player without being asked.
     *
     * <p>The same test the challenge uses, so a guard cannot arrest somebody they would not have been
     * allowed to stop. {@code warrantExists} finally does something here: it is the reason
     * {@code reportConfidenceThreshold} exists, and until now nothing called it.
     */
    private static boolean hasBasis(MinecraftServer server, ServerLevel level, ServerPlayer player,
                                    @Nullable LivingEntity responder, int charges) {
        // The assessment already checked testimony against live cases. A stale report is not a charge.
        return ChallengeBasis.hasBasis(charges, false,
                LegalTarget.isEscapedPrisoner(player), LegalTarget.isHoldingCaptive(player),
                CrimeState.isWanted(player), LegalTarget.isResistingArrest(player));
    }

    /**
     * Finishes an arrest that a restart, a relog, or a death interrupted.
     *
     * <p>A lawful custody record with no sentence behind it means the escort was in flight when the
     * world stopped. The escort itself is memory-only and deliberately not resumed: replaying a walk
     * that began before a restart is theatre nobody is present to watch, and the sentence is the part
     * that matters. Completing immediately is both the smaller amount of code and the more honest
     * outcome.
     */
    public static void reconcileOnLogin(ServerPlayer player) {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null) {
            return;
        }
        ArrestState state = ArrestStates.of(player);
        ArrestPhase stored = state == null ? ArrestPhase.NONE : state.getPhase();
        boolean jailed = JailService.isJailed(player);
        CustodyRecord record = CrimeWorldData.get(server).getCustody(player.getUUID());
        boolean lawful = record != null && record.isLawful();

        if (stored == ArrestPhase.NONE && !lawful) {
            return;
        }
        // Prefer the destination the arrest recorded; only fall back to re-resolving when a pre-0.4.0
        // save, or a crash before arming, left none.
        JailAnchor anchor = state == null ? null : state.anchor();
        if (anchor == null) {
            HoldingCell cell = HoldingCellService.existingFor(server, player.getUUID());
            anchor = cell != null ? cell.toAnchor()
                    : JailRegistry.nearestTo(player, McaCrimeConfig.COMMON.jailAssignedMaxDistance.get())
                            .orElse(JailService.configFallbackAnchor().orElse(null));
        }
        boolean anchorResolves = anchor != null
                && JailService.resolveLevel(server, anchor.dim()) != null;
        long online = CrimeAttachments.get(player).getOnlineTicksLived();
        boolean expired = state != null && state.expired(online);
        boolean guardPresent = state != null && state.getGuard() != null
                && player.level() instanceof ServerLevel level
                && level.getEntity(state.getGuard()) != null;

        switch (ArrestReconcile.decide(stored, jailed, lawful, anchorResolves, guardPresent, expired)) {
            case CLEAR -> {
                if (jailed) {
                    ArrestStates.transition(player, ArrestPhase.JAILED);
                } else {
                    ArrestStates.clear(player);
                    if (lawful) {
                        CustodyService.release(server, player.getUUID(),
                                dev.otectus.mcacrime.captivity.CustodyReleaseReason.ADMIN);
                    }
                }
            }
            case RESUME_ESCORT -> EscortService.resume(player);
            case COMPLETE_NOW -> {
                // Deliberately not a replayed walk: the escort is memory-only and theatre nobody was
                // present to watch, while the sentence is the part that matters.
                long sentence = state != null && state.getSentenceTicks() > 0L
                        ? state.getSentenceTicks()
                        : assessedSentence(server, player);
                EscortService.complete(player, anchor, sentence, null);
            }
            case RECOVER -> {
                if (lawful) {
                    CustodyService.release(server, player.getUUID(),
                            dev.otectus.mcacrime.captivity.CustodyReleaseReason.ADMIN);
                }
                GuardChallengeService.standDownAndRecover(player, "mcacrime.arrest.recovery");
            }
        }
    }

    /** The sentence this player would be given right now, for a save that recorded none. */
    private static long assessedSentence(MinecraftServer server, ServerPlayer player) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        int charges = CrimeWorldData.get(server).actionableFor(player.getUUID()).size();
        return SentenceCalculator.sentenceFor(CrimeState.getHeat(player), CrimeState.getBand(player),
                charges, c.sentenceBaseTicks.get(), c.sentenceTicksPerHeat.get(),
                c.sentenceTicksPerCharge.get(), c.blueFineMultiplier.get(), c.maxJailCommandTicks.get());
    }

    /** The guard's display name, or an empty component when the responder has gone. */
    static Component nameOf(@Nullable LivingEntity responder) {
        return responder == null ? Component.empty() : McaCompat.getVillagerDisplayName(responder);
    }

    /** True when this player is being held lawfully right now. */
    public static boolean inLawfulCustody(MinecraftServer server, UUID player) {
        CustodyRecord record = server == null ? null : CrimeWorldData.get(server).getCustody(player);
        return record != null && record.isLawful();
    }

    /** Whether anybody at all holds this player, for callers that must not double-hold. */
    public static boolean isHeld(MinecraftServer server, UUID player) {
        return server != null && CustodyRegistry.isCaptive(server, player);
    }
}
