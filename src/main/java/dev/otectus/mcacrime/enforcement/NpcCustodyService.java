package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.ai.CrimeReactionService;
import dev.otectus.mcacrime.ai.thief.ThiefBehaviorService;
import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyOwnerType;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.jail.HoldingCell;
import dev.otectus.mcacrime.jail.HoldingCellService;
import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.ledger.SentenceResolutionService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sentence of an arrested villager: escort, cell, clock, release (spec §"Guards and thief arrests").
 *
 * <p>{@code CustodyService.tickNpcCaptives} deliberately skips lawful records — it is the kidnapping
 * ticker, and its real-time cap, escape work and tether are all wrong for a prisoner. This is the
 * other half: the same table, the records that one leaves alone.
 *
 * <p>It is a separate service rather than an extension of {@code EscortService} and {@code JailService}
 * for the reason those two are the way they are. Both are {@code ServerPlayer}-typed through and
 * through and store their state in a player capability, so an arrested villager cannot be represented
 * in either without a parallel non-player path inside each of them. The escort here is a much smaller
 * thing anyway: a guard, a destination, and a leash.
 *
 * <p>The orphan rules are what stop an arrest from becoming permanent scenery. A guard that dies or
 * wanders off hands its prisoner to any other responder standing nearby; if none arrives within
 * {@code npcEscortOrphanTicks}, the thief is jailed where it stands rather than left cuffed in a field
 * for the rest of the save.
 */
public final class NpcCustodyService {

    /** How close to the anchor counts as arrived. Three blocks. */
    private static final double ARRIVAL_REACH_SQR = 9.0D;
    /** How far a replacement escort may be found when the original guard is gone. */
    private static final double REPLACEMENT_RADIUS = 16.0D;

    /** captive id -> live escort bookkeeping. Memory-only; the custody record is what persists. */
    private static final Map<UUID, Escort> ESCORTS = new ConcurrentHashMap<>();

    private static final class Escort {
        private long startedAt;
        private long nextNavAt;
        /** When the escort lost its guard, or 0 while it still has one. */
        private long orphanSince;

        private Escort(long startedAt) {
            this.startedAt = startedAt;
        }
    }

    private NpcCustodyService() {
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the walk to jail. Called by {@link NpcArrestService} the moment custody is written. */
    public static void beginEscort(ServerLevel level, LivingEntity thief, LivingEntity guard) {
        if (level == null || thief == null || guard == null) {
            return;
        }
        ESCORTS.put(thief.getUUID(), new Escort(level.getGameTime()));
    }

    /**
     * Picks up every lawful NPC custody record a restart inherited.
     *
     * <p>Only the memory-side bookkeeping is rebuilt; the record itself already says who holds the
     * prisoner and how much of the sentence is left. An escort that was halfway to a cell before the
     * shutdown starts its timeout again from here, which is the forgiving reading — the alternative
     * is a prisoner instantly declared orphaned by a clock that ran while the server was down.
     */
    public static void reconcile(MinecraftServer server) {
        ESCORTS.clear();
        if (server == null) {
            return;
        }
        int found = 0;
        for (CustodyRecord record : CrimeWorldData.get(server).custodyRecords()) {
            if (record.isCaptivePlayer() || !record.isLawful()) {
                continue;
            }
            found++;
            if (record.getOwner().type() == CustodyOwnerType.GUARD) {
                ESCORTS.put(record.getCaptive(), new Escort(server.overworld().getGameTime()));
            }
            inferLegacySentenceMembership(server, record.getCaptive());
        }
        if (found > 0) {
            CrimeDebug.crime("reconciled {} lawful NPC custody record(s) on server start", found);
        }
    }

    /**
     * The NPC half of the login-time inference {@code JailService} does for players.
     *
     * <p>A villager sentence handed down before 0.6.0 has a cell and a clock and nothing linking it to
     * a case, so the release below would settle nothing at all. Binding what stands against the thief
     * to the sentence it is already serving is the same assumption, made in the same words, and it runs
     * here because a server start is the only moment an NPC sentence is looked at as a whole.
     */
    private static void inferLegacySentenceMembership(MinecraftServer server, UUID captiveId) {
        HoldingCell cell = HoldingCellService.existingFor(server, captiveId);
        if (cell == null || cell.sentenceId() == null || cell.isLegacyBound()) {
            return; // an escort still on the road; the cell and its id are minted on arrival
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        if (!data.casesForSentence(captiveId, cell.sentenceId()).isEmpty()) {
            return;
        }
        List<UUID> bound = data.bindLegacySentence(captiveId, cell.sentenceId(),
                server.overworld().getGameTime());
        // Stamped whether or not anything was bound, and stamped on the cell because a villager has no
        // capability of its own. Same rule as the player side: a one-time upgrade guess runs once.
        cell.setLegacyBound(true);
        data.putHoldingCell(cell);
        if (!bound.isEmpty()) {
            CrimeDebug.crime("bound {} pre-0.6.0 case(s) to the sentence {} is serving", bound.size(),
                    captiveId);
        }
    }

    public static void clearAll() {
        ESCORTS.clear();
    }

    public static int activeCount() {
        return ESCORTS.size();
    }

    // ------------------------------------------------------------------ ticking

    /**
     * Advances every lawful NPC custody record.
     *
     * @param elapsedTicks ticks since the last call; this rides the throttled guard scan rather than
     *                     the raw tick, so a sentence is served in scan-sized steps
     */
    public static void tick(MinecraftServer server, long elapsedTicks) {
        if (server == null || elapsedTicks <= 0L) {
            return;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        for (CustodyRecord record : List.copyOf(data.custodyRecords())) {
            if (record.isCaptivePlayer() || !record.isLawful()) {
                continue; // players are JailService's, unlawful captives are CustodyService's
            }
            ServerLevel level = JailService.resolveLevel(server, record.getHoldDim());
            if (level == null) {
                continue;
            }
            switch (record.getOwner().type()) {
                case GUARD -> tickEscort(server, data, level, record);
                case JAIL -> tickSentence(server, data, level, record, elapsedTicks);
                default -> {
                    // AUTHORITY / NONE: nothing is walking anywhere and no clock is running. Left
                    // alone rather than guessed at; an operator put it there.
                }
            }
        }
    }

    /** The walk to the cell, and everything that can go wrong on the way. */
    private static void tickEscort(MinecraftServer server, CrimeWorldData data, ServerLevel level,
                                   CustodyRecord record) {
        UUID captiveId = record.getCaptive();
        if (!(level.getEntity(captiveId) instanceof LivingEntity thief) || !thief.isAlive()) {
            return; // unloaded or dead; the record waits, and death releases it elsewhere
        }
        long now = level.getGameTime();
        Escort escort = ESCORTS.computeIfAbsent(captiveId, id -> new Escort(now));

        UUID guardId = record.getOwner().ownerUuid().orElse(null);
        LivingEntity guard = guardId != null && level.getEntity(guardId) instanceof LivingEntity found
                && found.isAlive() ? found : null;
        int timeout = McaCrimeConfig.COMMON.arrestEscortTimeoutTicks.get();
        boolean overdue = timeout > 0 && now - escort.startedAt > timeout;
        if (guard == null || overdue) {
            handleOrphan(server, data, level, record, thief, escort, now);
            return;
        }
        escort.orphanSince = 0L;

        JailAnchor anchor = nearestAnchor(data, level, thief.blockPosition());
        if (anchor == null) {
            // No jail anywhere in this dimension. The cell is built where the arrest happened rather
            // than marching the pair toward a destination that does not exist.
            commit(server, data, level, record, thief, guard, thief.blockPosition());
            return;
        }
        BlockPos target = anchor.pos();
        if (thief.distanceToSqr(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D) <= ARRIVAL_REACH_SQR) {
            commit(server, data, level, record, thief, guard, target);
            return;
        }
        if (now < escort.nextNavAt) {
            return;
        }
        escort.nextNavAt = now + Math.max(1, McaCrimeConfig.COMMON.escortNavigationIntervalTicks.get());
        McaCompat.moveVillagerTo(guard, target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
                McaCrimeConfig.COMMON.escortWalkSpeed.get());
        McaCompat.leashTo(thief, guard); // re-secured: a leash does not survive a chunk round-trip
    }

    /** Nobody is holding this prisoner any more. Find a replacement, or stop pretending. */
    private static void handleOrphan(MinecraftServer server, CrimeWorldData data, ServerLevel level,
                                     CustodyRecord record, LivingEntity thief, Escort escort, long now) {
        LivingEntity replacement = nearestResponder(level, thief);
        if (replacement != null) {
            CustodyService.transferLawfulCustody(server, record.getCaptive(),
                    CustodyOwner.guard(replacement.getUUID()));
            McaCompat.leashTo(thief, replacement);
            escort.startedAt = now;
            escort.nextNavAt = 0L;
            escort.orphanSince = 0L;
            CrimeDebug.crime("escort of {} was handed to guard {}", record.getCaptive(),
                    replacement.getUUID());
            return;
        }
        if (escort.orphanSince == 0L) {
            escort.orphanSince = now;
            return;
        }
        if (now - escort.orphanSince < McaCrimeConfig.COMMON.npcEscortOrphanTicks.get()) {
            return;
        }
        JailAnchor anchor = nearestAnchor(data, level, thief.blockPosition());
        BlockPos near = anchor == null ? thief.blockPosition() : anchor.pos();
        CrimeDebug.crime("escort of {} was orphaned; jailing in place", record.getCaptive());
        commit(server, data, level, record, thief, null, near);
    }

    /**
     * Arrival: the cell goes up, custody passes from the guard to the jail, and the sentence starts.
     *
     * <p>Custody is <em>transferred</em> rather than released and re-taken, for the same reason the
     * player escort does it: a release would fire the public events and leave the prisoner briefly
     * free, in a cell, with nothing holding them.
     */
    private static void commit(MinecraftServer server, CrimeWorldData data, ServerLevel level,
                               CustodyRecord record, LivingEntity thief, @Nullable LivingEntity guard,
                               BlockPos near) {
        UUID captiveId = record.getCaptive();
        // The cell and the sentence share one id, so the cases charged under it can be found again at
        // release from the only thing that persists about an NPC sentence: the cell it is served in.
        UUID sentenceId = UUID.randomUUID();
        HoldingCell cell = HoldingCellService.provision(level, near, captiveId, sentenceId);
        BlockPos hold = cell == null ? near : cell.anchor();
        data.bindSentence(captiveId, sentenceId, level.getGameTime());
        OptionalInt village = guard == null ? McaCompat.getHomeVillageId(thief)
                : McaCompat.getHomeVillageId(guard);
        CustodyService.transferLawfulCustody(server, captiveId,
                CustodyOwner.jail(village.orElse(-1), hold, level.dimension().location()));
        McaCompat.clearLeash(thief);
        McaCompat.stopModNavigation(thief);
        thief.teleportTo(hold.getX() + 0.5D, hold.getY(), hold.getZ() + 0.5D);
        record.setHoldPos(hold);
        data.setDirty();
        RestraintSync.broadcast(thief);
        ESCORTS.remove(captiveId);
        CrimeDebug.crime("thief {} is serving {} ticks at {}", captiveId, record.getRemainingJailTicks(), hold);
    }

    /** The sentence clock, and the release at the end of it. */
    private static void tickSentence(MinecraftServer server, CrimeWorldData data, ServerLevel level,
                                     CustodyRecord record, long elapsedTicks) {
        long remaining = record.getRemainingJailTicks() - elapsedTicks;
        record.setRemainingJailTicks(Math.max(0L, remaining));
        data.setDirty();
        if (remaining > 0L) {
            return;
        }
        UUID captiveId = record.getCaptive();
        // Read before the cell comes down: dismantling it is what destroys the only record of which
        // sentence this thief was serving.
        HoldingCell cell = HoldingCellService.existingFor(server, captiveId);
        if (cell != null && cell.sentenceId() != null) {
            SentenceResolutionService.markServed(server, captiveId, cell.sentenceId());
        }
        CustodyService.release(server, captiveId, CustodyReleaseReason.SENTENCE_SERVED);
        HoldingCellService.releaseAndDismantle(server, captiveId);
        CrimeReactionService.endCaptive(level, captiveId);
        // The criminal job survives the sentence (spec §"Guards and thief arrests"): a thief comes out
        // of jail still a thief, and goes back to work after its ordinary cooldown.
        ThiefBehaviorService.markReleased(captiveId);
        ESCORTS.remove(captiveId);
        CrimeDebug.crime("thief {} served its sentence and was released", captiveId);
    }

    // ------------------------------------------------------------------ helpers

    @Nullable
    private static JailAnchor nearestAnchor(CrimeWorldData data, ServerLevel level, BlockPos from) {
        JailAnchor best = null;
        double bestDistance = Double.MAX_VALUE;
        for (JailAnchor anchor : data.jailAnchors()) {
            if (!anchor.dim().equals(level.dimension().location())) {
                continue;
            }
            double distance = anchor.pos().distSqr(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = anchor;
            }
        }
        return best;
    }

    @Nullable
    private static LivingEntity nearestResponder(ServerLevel level, LivingEntity thief) {
        AABB box = thief.getBoundingBox().inflate(REPLACEMENT_RADIUS);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box,
                entity -> entity != thief && entity.isAlive() && EntitySelectors.isResponder(entity))) {
            double distance = candidate.distanceToSqr(thief);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
