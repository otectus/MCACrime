package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.dialogue.CrimeDialogueService;
import dev.otectus.mcacrime.dialogue.DialogueEvents;
import dev.otectus.mcacrime.economy.FineCalculator;
import dev.otectus.mcacrime.economy.FineService;
import dev.otectus.mcacrime.economy.SurrenderService;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.memory.ReportService;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.GuardChallengeS2CPacket;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens, answers, and closes guard challenges (spec §13.2).
 *
 * <p>This is what replaces "a guard sees a Wanted player and swings". The plan is explicit that a
 * default guard should not attack on sight when a safe arrest is possible, and the mechanism that
 * makes an arrest possible is being given the chance to comply. A challenge states the charge, offers
 * the ways out that actually apply, and only escalates when the player refuses or lets the window run
 * out — refusing is a decision the player made, not one the mod made for them.
 *
 * <p>Challenges are keyed by player, one at a time. Two guards converging on the same suspect is a
 * common situation and it must not produce two competing screens; the first guard owns the encounter
 * until it closes.
 */
public final class GuardChallengeService {

    private static final Map<UUID, GuardChallenge> OPEN = new ConcurrentHashMap<>();

    private GuardChallengeService() {
    }

    // ------------------------------------------------------------------ queries

    @Nullable
    public static GuardChallenge open(UUID player) {
        return OPEN.get(player);
    }

    /**
     * Whether guards may currently use force on this player.
     *
     * <p>An open challenge suppresses force outright — that is the whole point of issuing one. Force
     * is unlocked by a refusal, by the window expiring, or by challenges being switched off entirely,
     * in which case behaviour reverts to the pre-challenge model.
     *
     * <p>The refusal is read from the player's persisted state rather than from a map here. It used to
     * live in a static {@code REFUSED} map that {@link #tick} pruned for anybody who was not already a
     * Legal Target — which is to say, for precisely the players whose refusal was the only thing making
     * force lawful. Refusing put them in the map, the next scan ten ticks later took them out, and
     * {@link #challenge} promptly opened a new window. Refusing a guard produced an endless queue of
     * identical panels and no consequence whatsoever.
     */
    public static boolean forcePermitted(ServerPlayer player, long now) {
        boolean challenges = McaCrimeConfig.COMMON.enableGuardChallenge.get();
        // The phase is consulted before the config, and deliberately so: an arrest already under way
        // suppresses force whatever the challenge setting says. Switching challenges off is a decision
        // about how guards start an encounter, never a licence to swing at somebody already in custody.
        if (!ArrestPhases.forcePermitted(ArrestStates.phaseOf(player), challenges,
                CrimeState.isResistingArrest(player))) {
            return false;
        }
        // The open map stays the fast index it already was: an unanswered screen suppresses force for
        // as long as it is on screen.
        return !challenges || !OPEN.containsKey(player.getUUID());
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Issues a challenge if one is warranted and none is open.
     *
     * @return true when a challenge is now open for this player, whether this call opened it or found
     *         one already standing
     */
    public static boolean challenge(ServerLevel level, LivingEntity guard, ServerPlayer player) {
        if (!McaCrimeConfig.COMMON.enableGuardChallenge.get()) {
            return false;
        }
        MinecraftServer server = level.getServer();
        if (server == null || player.isSpectator() || !player.isAlive()) {
            return false;
        }
        long now = level.getGameTime();
        GuardChallenge existing = OPEN.get(player.getUUID());
        if (existing != null) {
            return !existing.expired(now);
        }
        if (!ArrestPhases.canOpenChallenge(ArrestStates.phaseOf(player))) {
            // Already confronted, already being arrested, or standing down after an arrest that could
            // not be completed. This is the check the old code had no way to make: Heat and charges
            // both survive an arrest, so a player being walked to a cell stayed a Legal Target for the
            // whole walk and was handed a fresh screen every ten ticks -- including after they had
            // already surrendered, which is the bug players actually saw.
            return false;
        }
        if (CrimeState.isResistingArrest(player)) {
            return false; // already answered, and answered no; escalation owns this player now
        }

        List<CrimeRecord> open = CrimeWorldData.get(server).actionableFor(player.getUUID());
        CrimeCommunityKey jurisdiction = ReportService.jurisdictionOf(level, guard);
        // A guard with nothing to say does not open a screen. Without this a challenge could be issued
        // on proximity alone, producing a panel that read "0 charge(s) outstanding" and an answer of
        // "the guard checks, and finds nothing against you" -- while the window kept running, and
        // letting it expire still counted as refusing. Being confronted about nothing and then
        // penalised for not answering is the worst possible version of this feature.
        if (!ChallengeBasis.hasBasis(open.size(),
                ReportService.warrantExists(server, player.getUUID(), jurisdiction, now),
                LegalTarget.isEscapedPrisoner(player), LegalTarget.isHoldingCaptive(player))) {
            return false;
        }
        OptionalLong fine = FineCalculator.fineFor(CrimeState.getHeat(player), CrimeState.getBand(player),
                McaCrimeConfig.COMMON.fineBase.get(), McaCrimeConfig.COMMON.finePerHeat.get(),
                McaCrimeConfig.COMMON.jailableHeatThreshold.get(),
                McaCrimeConfig.COMMON.blueFineMultiplier.get(), McaCrimeConfig.COMMON.redCanPayFine.get());
        boolean finable = McaCrimeConfig.COMMON.enableFines.get() && fine.isPresent();

        GuardChallenge challenge = new GuardChallenge(UUID.randomUUID(), guard.getUUID(), player.getUUID(),
                jurisdiction, open.size(), fine.orElse(0L), finable, now,
                now + McaCrimeConfig.COMMON.guardChallengeWindowTicks.get());
        OPEN.put(player.getUUID(), challenge);
        ArrestStates.begin(player, guard.getUUID(), challenge.encounterId());

        CrimeSounds.guardChallenge(player);
        CrimeDialogueService.speak(guard, player, DialogueEvents.GUARD_CHALLENGE,
                CrimeDialogueService.context(level, guard, player, challenge.encounterId(),
                        DialogueEvents.GUARD_CHALLENGE));
        CrimeNetwork.sendGuardChallenge(player, GuardChallengeS2CPacket.open(challenge, now,
                McaCompat.getVillagerDisplayName(guard), Jurisdictions.label(level, jurisdiction)));
        return true;
    }

    /**
     * Applies a player's answer. Every path re-derives its own preconditions: the screen is a way of
     * asking, never a source of authority, so "the client said surrender" still has to pass every
     * check {@code /crime surrender} would.
     */
    public static void respond(ServerPlayer player, UUID encounterId, ChallengeResponse response) {
        if (ArrestStates.phaseOf(player) != ArrestPhase.CONFRONTED) {
            // The encounter has already been answered, or was never this player's to answer. A replayed
            // or forged response must not be able to re-enter the surrender path against an arrest that
            // has already moved on.
            return;
        }
        GuardChallenge challenge = OPEN.get(player.getUUID());
        if (challenge == null || !challenge.encounterId().equals(encounterId)) {
            // A stale screen, a replay, or an encounter that already closed. Silently ignored: telling
            // the player their click missed would only invite them to click again faster.
            return;
        }
        long now = player.level().getGameTime();
        if (challenge.expired(now)) {
            close(player, ChallengeResponse.REFUSE);
            return;
        }
        switch (response) {
            case ASK_CHARGES -> sendCharges(player, challenge);
            case SURRENDER -> {
                // Order is the whole fix. close() used to run first, clearing both the open encounter
                // and the resisting flag; if the surrender then failed for want of an authority, a
                // cell, or a sentence, the player was left with charges, not resisting, and nothing on
                // record -- which the next scan read as a fresh suspect and challenged again. Writing
                // the phase first means the failure paths land in RECOVERY instead of in a hole.
                ArrestStates.surrendered(player, challenge.guardId(), challenge.encounterId());
                close(player, ChallengeResponse.SURRENDER);
                SurrenderService.surrender(player);
                standDownNear(player);
            }
            case PAY_FINE -> {
                if (!challenge.canPay()) {
                    player.sendSystemMessage(Component.translatable("mcacrime.challenge.not_finable"));
                    return; // the window stays open: being told no is not an answer
                }
                FineService.Payment payment = FineService.pay(player, List.of(), true);
                if (payment.paid()) {
                    close(player, ChallengeResponse.PAY_FINE);
                    CrimeSounds.paid(player);
                    standDownNear(player);
                } else {
                    // Could not pay after all. That is a refusal in effect, and pretending otherwise
                    // would let an empty purse be a way of ending challenges for free.
                    close(player, ChallengeResponse.REFUSE);
                }
            }
            case REFUSE -> close(player, ChallengeResponse.REFUSE);
        }
    }

    /**
     * Ends the encounter, recording a refusal so guards may escalate.
     *
     * <p><b>Every</b> terminal branch comes through here, and that is the fix for a leak rather than
     * tidiness. Surrender and a successful fine used to remove the encounter from {@code OPEN}
     * directly, so the closure packet was never sent; {@code ClientChallengeData.current} is only ever
     * nulled by that packet, so {@code active()} stayed true for the rest of the session and the reopen
     * keybind would resurrect a screen for an encounter that had been settled minutes earlier.
     */
    private static void close(ServerPlayer player, ChallengeResponse outcome) {
        OPEN.remove(player.getUUID());
        if (outcome == ChallengeResponse.REFUSE) {
            CrimeState.setResistingArrest(player, true);
            player.sendSystemMessage(Component.translatable("mcacrime.challenge.refused"));
        } else {
            CrimeState.setResistingArrest(player, false);
        }
        // Surrender has already written SURRENDERED and the arrest owns the phase from here. Every
        // other outcome ends the encounter outright.
        if (outcome != ChallengeResponse.SURRENDER
                && ArrestStates.phaseOf(player) == ArrestPhase.CONFRONTED) {
            ArrestStates.clear(player);
        }
        CrimeNetwork.sendGuardChallenge(player, GuardChallengeS2CPacket.closed());
    }

    /**
     * Ends an arrest that could not be completed, and holds the guards off for a moment.
     *
     * <p>The counterpart to {@link #standDown}, for the paths that fail rather than settle. An arrest
     * can be refused for reasons the player did nothing to cause -- no buildable ground, no assigned
     * jail, temporary jails switched off -- and the honest response is to say so and stand down, not to
     * try again immediately. The recovery window is a property of a state the arrest genuinely reached,
     * which is what separates it from a cooldown bolted onto the confrontation trigger.
     */
    public static void standDownAndRecover(ServerPlayer player, @Nullable String reasonKey) {
        OPEN.remove(player.getUUID());
        CrimeState.setResistingArrest(player, false);
        ArrestStates.recover(player, McaCrimeConfig.COMMON.arrestRecoveryTicks.get());
        if (reasonKey != null && !reasonKey.isEmpty()) {
            player.sendSystemMessage(Component.translatable(reasonKey));
        }
        CrimeNetwork.sendGuardChallenge(player, GuardChallengeS2CPacket.closed());
        standDownNear(player);
    }

    /** Lists what the player is actually accused of, without closing the window. */
    private static void sendCharges(ServerPlayer player, GuardChallenge challenge) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        List<CrimeRecord> open = CrimeWorldData.get(server).actionableFor(player.getUUID());
        if (open.isEmpty()) {
            player.sendSystemMessage(Component.translatable("mcacrime.challenge.no_charges"));
            return;
        }
        player.sendSystemMessage(Component.translatable("mcacrime.challenge.charges_header", open.size()));
        for (CrimeRecord record : open.stream().limit(8).toList()) {
            player.sendSystemMessage(Component.translatable("mcacrime.challenge.charge_row",
                    Component.translatable("crime.mcacrime." + record.type().getPath())));
        }
        if (challenge.canPay()) {
            player.sendSystemMessage(Component.translatable("mcacrime.challenge.total_due",
                    challenge.assessedFine()));
        }
    }

    /**
     * Expires open challenges. Called from the enforcement scan, which already runs on a throttled
     * interval.
     *
     * <p>There is deliberately no refusal sweep here any more. The one that used to live at the bottom
     * of this method dropped a refusal for anybody who was not already a Legal Target, and since a
     * refusal is itself what makes somebody a Legal Target, it erased exactly the refusals that
     * mattered — every scan, ten ticks after they were recorded. A refusal now lapses on its own clock
     * in {@code CrimeDecayHandler}, and is cleared by the events that should clear it: surrendering,
     * paying, being jailed, or a stand-down.
     */
    public static void tick(MinecraftServer server) {
        if (OPEN.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        for (GuardChallenge challenge : List.copyOf(OPEN.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(challenge.playerId());
            if (player == null) {
                // Logged out mid-challenge. Dropped rather than held: the window is a conversation,
                // and there is nobody on the other side of it any more.
                OPEN.remove(challenge.playerId());
                continue;
            }
            if (challenge.expired(player.level().getGameTime())) {
                close(player, ChallengeResponse.REFUSE);
            }
        }
    }

    /**
     * Clears everything for one player. Called on stand-down: payment, surrender, sentence completion,
     * pardon, or losing the legal basis (spec §13.5).
     */
    public static void standDown(ServerPlayer player) {
        boolean had = OPEN.remove(player.getUUID()) != null;
        CrimeState.setResistingArrest(player, false);
        // Settling with the law ends the arrest as well as the encounter -- unless the way it was
        // settled was being put in a cell. This is called from the end of a successful escort, one line
        // after the phase becomes JAILED, so clearing unconditionally would drop a serving prisoner back
        // to NONE and let a guard open a confrontation screen through the bars.
        if (ArrestStates.phaseOf(player) != ArrestPhase.JAILED) {
            ArrestStates.clear(player);
        }
        if (had) {
            CrimeNetwork.sendGuardChallenge(player, GuardChallengeS2CPacket.closed());
        }
        standDownNear(player);
    }

    /** Drops nearby guards' targets on this player and says so once, not every scan. */
    private static void standDownNear(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        double radius = McaCrimeConfig.COMMON.guardAggroRadius.get();
        // isResponder, not isGuard: the enforcement scan targets everything the responder selector
        // matches, so standing down anything narrower would leave a configured non-MCA responder
        // permanently hostile to somebody who had already settled with the law.
        for (LivingEntity guard : level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(radius), EntitySelectors::isResponder)) {
            McaCompat.clearGuardTarget(guard, player);
            LawHold.clear(guard.getUUID());
        }
        CrimeSounds.standDown(player);
    }

    /** Drops every encounter. Called on server stop. */
    public static void clearAll() {
        OPEN.clear();
    }

    /**
     * Forgets one player's open encounter, on logout.
     *
     * <p>Only the encounter. Resisting arrest is persistent by design: it is a consequence the player
     * chose, and logging out for a minute must not be the way to clear it.
     */
    public static void forget(UUID player) {
        OPEN.remove(player);
    }

    /** Exposed for {@code /crime debug}: how many encounters are open right now. */
    public static int openCount() {
        return OPEN.size();
    }

    /** The band a challenge is being issued against, for dialogue context. */
    static Band bandOf(ServerPlayer player) {
        return CrimeState.getBand(player);
    }
}
