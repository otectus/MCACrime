package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.PlayerJailedEvent;
import dev.otectus.mcacrime.api.event.PlayerReleasedFromJailEvent;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.CrimeAttachments;
import dev.otectus.mcacrime.ledger.SentenceResolutionService;
import dev.otectus.mcacrime.ledger.SentenceAssignmentService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.util.TickFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;

import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The server-authoritative, idempotent jail state machine (spec §7). The only writer of {@code JailState}.
 * Every release routes through {@link #release}, which is guarded so a double-release is a harmless no-op
 * (no double event, no double teleport). The real-time captivity cap in {@link #advanceTick} is the
 * universal backstop that makes a softlock unreachable: it needs no anchor, dimension, or chunk, so an
 * online player is always freed within {@code maxCaptivityRealMinutes}, and admin {@link #release} always works.
 */
public final class JailService {

    /**
     * How often, in online ticks, a serving prisoner's remaining sentence is pushed to their client.
     *
     * <p>Deliberately a constant rather than a config key. The client runs its own local countdown
     * between resyncs (see {@code ClientSelfData.tick}), so this value decides only how quickly drift
     * is corrected — it is invisible in play, and a knob nobody can perceive the effect of is a knob
     * that only exists to be set wrong. Two seconds is short enough that a lagged or late-joining
     * client is never visibly out, and long enough that a full jail costs nothing measurable.
     */
    private static final int RESYNC_TICKS = 40;

    private JailService() {
    }

    // ------------------------------------------------------------------ queries

    public static boolean isJailed(ServerPlayer player) {
        return CrimeAttachments.get(player).isJailed();
    }

    public static long remainingTicks(ServerPlayer player) {
        JailState jail = CrimeAttachments.get(player).getJail();
        return jail == null ? 0L : jail.getRemainingOnlineTicks();
    }

    // ------------------------------------------------------------------ jail

    /**
     * Jails the player for {@code ticks} online ticks. Returns false (refused, never softlock) when no jail
     * anchor can be resolved (none assigned + no fallback) — the caller reports it. Re-jailing an
     * already-jailed player only extends the sentence (max) and fires no duplicate event.
     */
    public static boolean jail(ServerPlayer player, long ticks, @Nullable JailAnchor explicit) {
        return jail(player, ticks, explicit, null, false);
    }

    /**
     * Jails the player, optionally allowing an existing sentence to be <em>shortened</em>.
     *
     * <p>{@code allowReduce} exists for the one caller that legitimately produces a shorter number than
     * the sentence already running: a voluntary surrender, whose waiver is baked into the sentence by
     * {@code SentenceCalculator.afterSurrender}. Every other caller keeps the max-only behaviour, so
     * {@code /crime jail} and a completed escort cannot accidentally cut a term short.
     *
     * @param sentenceId the id minted at the start of the arrest, so the holding cell and the sentence
     *                   can be matched; null mints a fresh one
     */
    public static boolean jail(ServerPlayer player, long ticks, @Nullable JailAnchor explicit,
                               @Nullable UUID sentenceId, boolean allowReduce) {
        if (player == null || !ServerMutationGate.allows(player.getServer())) return false;
        Optional<PlayerCrimeData> opt = Optional.of(CrimeAttachments.get(player));
        if (opt.isEmpty()) {
            return false;
        }
        PlayerCrimeData data = opt.get();
        long clamped = Math.max(1L, Math.min(ticks, McaCrimeConfig.COMMON.maxJailCommandTicks.get()));

        if (data.isJailed()) {
            JailState existing = data.getJail();
            existing.setRemainingOnlineTicks(
                    mergeSentence(existing.getRemainingOnlineTicks(), clamped, allowReduce));
            // An extension is time added for charges that were not part of the original term, so those
            // charges join it. Without this they would be served alongside it and settled by nothing.
            CrimeNetwork.sendSelfStatus(player);
            return true; // sentence update; no duplicate PlayerJailedEvent
        }

        JailAnchor anchor = resolveAnchor(player, explicit).orElse(null);
        if (anchor == null) {
            return false; // no jail assigned and no fallback -> refuse, don't create a stuck state (§7.4)
        }
        JailContainmentMode mode = McaCrimeConfig.COMMON.jailContainmentMode.get();
        var custody = CrimeWorldData.get(player.getServer()).getCustody(player.getUUID());
        UUID intakeId = SentenceAssignmentService.intakeId(sentenceId,
                custody == null ? null : custody.getSentenceId()).orElse(null);
        if (intakeId == null) return false;
        JailState jail = new JailState(clamped, anchor.pos(), anchor.dim(), anchor.radius(), mode);
        jail.setSentenceId(intakeId);
        var arrestAtIntake = dev.otectus.mcacrime.enforcement.ArrestStates.of(player);
        if (arrestAtIntake != null) jail.setSurrenderCredited(arrestAtIntake.isSurrenderCredited());
        // The move happens before the sentence is written, and a refusal ends the whole thing. A
        // sentence persisted around a prisoner still standing where they were arrested is a player
        // marked as jailed and confined to a jail they are not in -- which is worse than not jailing
        // them, because every containment rule then fires on somebody standing in a field.
        if (!teleportToAnchor(player, jail)) {
            return false;
        }
        data.setJail(jail);
        // Arrest membership is already frozen. A direct administrative jailing assesses here once.
        bindSentence(player, jail.getSentenceId());
        jail.setLegacyBound(true); // an empty assessment is final too; never infer later crimes on login
        // Also true for a sentence handed down by /crime jail with no arrest behind it: a prisoner is a
        // prisoner, and a guard has no business opening a confrontation screen through the bars.
        dev.otectus.mcacrime.enforcement.ArrestStates.transition(
                player, dev.otectus.mcacrime.enforcement.ArrestPhase.JAILED);
        dev.otectus.mcacrime.audio.CrimeSounds.jailed(player);
        NeoForge.EVENT_BUS.post(new PlayerJailedEvent(player, clamped, anchor.pos()));
        CrimeNetwork.sendSelfStatus(player);
        player.sendSystemMessage(Component.translatable("mcacrime.jail.jailed", TickFormat.compact(clamped)));
        return true;
    }

    /** Administrative jailing assesses once; an arrest has already frozen its own membership. */
    private static void bindSentence(ServerPlayer player, UUID sentenceId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        var custody = world.getCustody(player.getUUID());
        if (custody != null && custody.getSentenceId() != null) return;
        world.bindSentence(player.getUUID(), sentenceId, server.overworld().getGameTime());
    }

    private static Optional<JailAnchor> resolveAnchor(ServerPlayer player, @Nullable JailAnchor explicit) {
        if (explicit != null) {
            return Optional.of(explicit);
        }
        Optional<JailAnchor> nearest = JailRegistry.nearestTo(player);
        if (nearest.isPresent()) {
            return nearest;
        }
        return configFallbackAnchor();
    }

    /**
     * How a proposed sentence combines with one already running.
     *
     * <p>Pure, because the asymmetry is the interesting part: a surrender may shorten but never
     * lengthen, and everything else may lengthen but never shorten. Either rule applied in both
     * directions produces a way to game a sentence.
     */
    public static long mergeSentence(long existing, long proposed, boolean allowReduce) {
        return allowReduce
                ? Math.max(1L, Math.min(existing, proposed))
                : Math.max(existing, proposed);
    }

    /**
     * The configured fallback destination, or empty when disabled or malformed.
     *
     * <p>Public because the arrest path needs it too. It used to be private, so
     * {@code ArrestService.resolveDestination} could not consult it: with {@code buildHoldingCell} off
     * and {@code jailFallbackEnabled} on, an arrest was refused for want of a cell while
     * {@code /crime jail} on the same player succeeded against the very anchor the arrest could not see.
     */
    public static Optional<JailAnchor> configFallbackAnchor() {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        if (!c.jailFallbackEnabled.get()) {
            return Optional.empty();
        }
        List<? extends Integer> xyz = c.jailFallbackPos.get();
        ResourceLocation dim = ResourceLocation.tryParse(c.jailFallbackDim.get());
        if (xyz.size() < 3 || dim == null) {
            return Optional.empty();
        }
        return Optional.of(new JailAnchor(new BlockPos(xyz.get(0), xyz.get(1), xyz.get(2)), dim, c.jailRadiusDefault.get()));
    }

    // ------------------------------------------------------------------ release (idempotent)

    public static void release(ServerPlayer player, ReleaseReason reason) {
        PlayerCrimeData data = CrimeAttachments.get(player);
        if (!data.isJailed()) {
            return; // the single guard that makes double-release a no-op
        }
        JailState finished = data.getJail();
        data.setJail(null);
        // Serving the sentence is what closes the cases. Without this a player could be jailed, serve
        // the whole term, walk out, and still have every charge open and actionable against them --
        // which made a sentence cost time and change nothing. Only an actually-served sentence counts:
        // an administrative release, a pardon, or a broken jail anchor settle nothing.
        if (reason == ReleaseReason.SENTENCE_SERVED && player.getServer() != null && finished != null) {
            SentenceResolutionService.markServed(player.getServer(), player.getUUID(), finished.getSentenceId());
        }
        dev.otectus.mcacrime.audio.CrimeSounds.released(player);
        NeoForge.EVENT_BUS.post(new PlayerReleasedFromJailEvent(player, reason));
        CrimeNetwork.sendSelfStatus(player);
        player.sendSystemMessage(Component.translatable(releaseKey(reason)));
    }

    private static String releaseKey(ReleaseReason reason) {
        return switch (reason) {
            case SENTENCE_SERVED -> "mcacrime.jail.released.served";
            case CAPTIVITY_CAP -> "mcacrime.jail.released.cap";
            case ADMIN -> "mcacrime.jail.released.admin";
            case PARDON -> "mcacrime.jail.released.pardon";
            case BAILED -> "mcacrime.jail.released.bailed";
            case INVALID_JAIL -> "mcacrime.jail.released.invalid";
        };
    }

    /** On login, free a player whose jail dimension vanished and has no fallback (avoids a softlock, §7.4). */
    public static void reconcileOnLogin(ServerPlayer player) {
        JailState jail = CrimeAttachments.get(player).getJail();
        if (jail == null) {
            return;
        }
        if (jail.hasValidAnchor()
                && resolveLevel(player.getServer(), jail.getJailDim()) == null
                && configFallbackAnchor().isEmpty()) {
            release(player, ReleaseReason.INVALID_JAIL);
            return;
        }
        inferLegacySentenceMembership(player, jail);
    }

    /**
     * Adopts the charges of a sentence handed down before cases could name one.
     *
     * <p>{@code JailState} has never persisted a start tick, so there is nothing in a 0.5.1 save that
     * says which cases a running sentence was for. The choices were to settle nothing on release —
     * which turns every in-flight sentence into time served for no reason — or to assume it covers
     * what is standing against the prisoner. This takes the second, once, at the first login after the
     * upgrade, and stamps {@link dev.otectus.mcacrime.ledger.CrimeContext#LEGACY_SENTENCE_INFERRED} on
     * every case it takes so the ledger never claims the binding was a fact.
     *
     * <p>A sentence that already binds cases is left alone, which is what makes this run once.
     */
    private static void inferLegacySentenceMembership(ServerPlayer player, JailState jail) {
        MinecraftServer server = player.getServer();
        if (server == null || jail.isLegacyBound()) {
            return;
        }
        dev.otectus.mcacrime.state.world.CrimeWorldData world =
                dev.otectus.mcacrime.state.world.CrimeWorldData.get(server);
        if (!world.casesForSentence(player.getUUID(), jail.getSentenceId()).isEmpty()) {
            jail.setLegacyBound(true);
            return;
        }
        long now = server.overworld().getGameTime();
        List<UUID> bound = world.bindLegacySentence(player.getUUID(), jail.getSentenceId(), now);
        // Stamped whether or not anything was bound. A legacy sentence with nothing left standing
        // against it looks identical to one that has never been inferred, and re-running the guess on
        // every login would keep adopting crimes committed since.
        jail.setLegacyBound(true);
        if (!bound.isEmpty()) {
            McaCrime.LOGGER.info("Bound {} pre-0.6.0 case(s) to the sentence {} is serving", bound.size(),
                    player.getGameProfile().getName());
        }
    }

    // ------------------------------------------------------------------ per-tick (from CrimeDecayHandler)

    /** Per-tick countdown + cap; releases when due. Called every tick while jailed. */
    public static void tick(ServerPlayer player, PlayerCrimeData data) {
        JailState jail = data.getJail();
        if (jail == null) {
            return;
        }
        if (jail.getModeSnapshot() == JailContainmentMode.PHYSICAL) {
            // Account for a breakout before decrementing, rather than waiting for the confine scan.
            JailConfine.tick(player, data);
        }
        long capTicks = (long) McaCrimeConfig.COMMON.maxCaptivityRealMinutes.get() * 1200L;
        ReleaseReason due = advanceTick(jail, capTicks);
        if (due != null) {
            release(player, due);
            return;
        }
        // The client's sentence clock had exactly one writer -- the status packet -- and that packet is
        // only sent on discrete events, none of which happen while a sentence is simply running. The
        // HUD therefore froze on the value captured at jailing and stayed there for the whole term.
        // Pushing on a slow cadence and letting the client tick between pushes keeps the server the
        // authority without spending a packet per tick per prisoner.
        if (shouldResync(jail.getRemainingOnlineTicks(), RESYNC_TICKS)) {
            CrimeNetwork.sendSelfStatus(player);
        }
    }

    /**
     * Whether this tick is a resync tick. Pure, so the cadence is testable without a network stack.
     *
     * <p>An interval of zero or less disables resyncing rather than dividing by zero.
     */
    public static boolean shouldResync(long remainingTicks, int intervalTicks) {
        return intervalTicks > 0 && remainingTicks >= 0L && remainingTicks % intervalTicks == 0L;
    }

    /**
     * Pure one-tick advance: decrements the sentence, accumulates served time, and returns the release
     * reason if the sentence is finished or the real-time cap is hit — else null. No game/config deps, so
     * the online-tick accounting (incl. the cap backstop) is unit-testable.
     */
    public static ReleaseReason advanceTick(JailState jail, long capTicks) {
        if (jail.isEscaped()) return null;
        jail.setRemainingOnlineTicks(Math.max(0L, jail.getRemainingOnlineTicks() - 1L));
        if (jail.getRealOnlineTicksServed() < Long.MAX_VALUE)
            jail.setRealOnlineTicksServed(jail.getRealOnlineTicksServed() + 1L);
        if (jail.getRemainingOnlineTicks() <= 0L) {
            return ReleaseReason.SENTENCE_SERVED;
        }
        if (capTicks > 0L && jail.getRealOnlineTicksServed() >= capTicks) {
            return ReleaseReason.CAPTIVITY_CAP;
        }
        return null;
    }

    /** Preserve the sentence while a successful cuff escape ends physical custody. */
    public static void escapeCuffs(ServerPlayer player) {
        if (!ServerMutationGate.allows(player.getServer())) return;
        var data = Optional.of(CrimeAttachments.get(player)).orElse(null);
        if (data == null) return;
        var arrest = dev.otectus.mcacrime.enforcement.ArrestStates.of(player);
        JailState jail = data.getJail();
        if (jail == null && arrest != null && arrest.anchor() != null && arrest.getSentenceTicks() > 0) {
            // Preserve an assessed escort sentence without teleporting the escaping player into jail.
            JailAnchor anchor = arrest.anchor();
            jail = new JailState(arrest.getSentenceTicks(), anchor.pos(), anchor.dim(), anchor.radius(),
                    McaCrimeConfig.COMMON.jailContainmentMode.get());
            jail.setSentenceId(arrest.getSentenceId());
            jail.setLegacyBound(true);
            jail.setSurrenderCredited(arrest.isSurrenderCredited());
            data.setJail(jail);
        }
        if (jail != null) {
            jail.escapeCuffs();
            SentenceResolutionService.markEscaped(player.getServer(), player.getUUID(), jail.getSentenceId());
        }
        UUID guard = dev.otectus.mcacrime.enforcement.ArrestStates.owningGuard(player);
        dev.otectus.mcacrime.enforcement.EscortService.forget(player.getUUID());
        if (guard != null) dev.otectus.mcacrime.enforcement.LawHold.clear(guard);
        dev.otectus.mcacrime.enforcement.ArrestStates.clear(player);
        dev.otectus.mcacrime.detect.CrimeDetector.commitDirect(player,
                dev.otectus.mcacrime.crime.type.CrimeIds.JAILBREAK, null, player.serverLevel(),
                dev.otectus.mcacrime.detect.WitnessResult.official(), "cuff_escape");
        CrimeNetwork.sendSelfStatus(player);
    }

    /** Recapture resumes the existing term without granting another surrender discount. */
    public static boolean recapture(ServerPlayer player) {
        if (!ServerMutationGate.allows(player.getServer())) return false;
        JailState jail = Optional.of(CrimeAttachments.get(player)).map(PlayerCrimeData::getJail).orElse(null);
        if (jail == null) return false;
        if (!jail.isEscaped()) return true;
        if (!teleportToAnchor(player, jail)) return false;
        jail.setEscaped(false);
        CrimeNetwork.sendSelfStatus(player);
        return true;
    }

    // ------------------------------------------------------------------ teleport / confinement helpers

    /** Resolves the jail's dimension to a live {@link ServerLevel}, or null if it no longer exists. */
    @Nullable
    public static ServerLevel resolveLevel(@Nullable MinecraftServer server, @Nullable ResourceLocation dim) {
        if (server == null || dim == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
    }

    /**
     * Teleports the player to a safe spot at the jail anchor. Best-effort: returns false (caller tolerates)
     * if the dimension is gone or no safe footing can be found in a loaded region. Never teleports into
     * suffocation; never force-loads for an offline player (only the online player triggers a load by moving).
     */
    public static boolean teleportToAnchor(ServerPlayer player, JailState jail) {
        if (!jail.hasValidAnchor()) {
            return false;
        }
        ServerLevel level = resolveLevel(player.getServer(), jail.getJailDim());
        if (level == null) {
            return false;
        }
        // No anchor.above() fallback any more. That branch ran precisely when the chunk was not
        // loaded -- when the server knew least about what was there -- and put prisoners inside walls,
        // in lava and in the void. An unknown destination is now a refusal.
        BlockPos target = SafeCustodyDestination.validate(level, jail.getJailAnchor(),
                Math.max(2, jail.getJailRadius())).orElse(null);
        if (target == null) {
            return false;
        }
        // The overload taking a relative-movement set returns whether the move happened; the one
        // without it is void and cannot report a refusal, which is why a failed teleport used to look
        // like a success.
        return player.teleportTo(level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                java.util.Set.of(), player.getYRot(), player.getXRot());
    }

    /**
     * Finds a 2-high air gap with footing within {@code range} vertical blocks of the anchor; null if none.
     *
     * <p>Public because release has to use it too. A mod-built holding cell is demolished when the
     * sentence ends, and demolition restores the floor and roof courses to whatever was there before —
     * which, for a prisoner still standing inside, means solid blocks appearing where their head is.
     * The release path finds a stand outside the cell with this and moves them there first.
     */
    @Nullable
    public static BlockPos findSafeStand(ServerLevel level, BlockPos anchor, int range) {
        return SafeCustodyDestination.validate(level, anchor, range).orElse(null);
    }

    /** Whether one position is safe to put somebody in. Delegates to the shared rules. */
    public static boolean isSafeStand(ServerLevel level, BlockPos feet) {
        return SafeCustodyDestination.isSafeStand(level, feet);
    }
}
