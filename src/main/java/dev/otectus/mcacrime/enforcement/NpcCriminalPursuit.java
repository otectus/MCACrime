package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.ai.ArmedResolver;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.mug.npc.NpcMugAbortReason;
import dev.otectus.mcacrime.mug.npc.NpcMuggingService;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A guard chasing a criminal villager (spec §"Guards and thief arrests").
 *
 * <p>The chase is non-lethal by default, and that is the whole design. MCA's guard brain kills what
 * it is given as an attack target, so handing it the thief would end every intervention in a corpse —
 * and a dead thief is exactly the outcome the stolen-goods return exists to stop being the best one.
 * A pursued thief is walked down with plain navigation and arrested on contact. {@code
 * setGuardTarget} is reached for only when the thief is armed and {@code guardsUseForceOnArmedThieves}
 * is on, because an armed criminal that cannot be fought is one a guard can never take.
 *
 * <p>Bounded three ways: one pursuit per guard, a re-path every ten ticks rather than every tick, and
 * two independent give-up conditions ({@code guardThiefPursuitTimeoutTicks} and getting further away
 * than {@code guardAggroRadius}). Memory-only — a restart that lost a chase lost a chase.
 */
public final class NpcCriminalPursuit {

    /** How often the guard's path is re-issued. MCA re-paths underneath us on its own schedule. */
    private static final int REPATH_INTERVAL_TICKS = 10;
    /** Squared distance at which the guard has the thief. Two blocks. */
    private static final double ARREST_REACH_SQR = 4.0D;
    private static final double PURSUE_SPEED = 1.15D;

    /** guard id -> what it is chasing. One guard chases one thief. */
    private static final Map<UUID, Pursuit> ACTIVE = new ConcurrentHashMap<>();

    private record Pursuit(UUID thiefId, ResourceLocation dimension, long startedAt, long nextThinkAt,
                           @Nullable ActiveIncidentRegistry.ActiveIncident incident) {

        Pursuit scheduled(long next) {
            return new Pursuit(thiefId, dimension, startedAt, next, incident);
        }
    }

    private NpcCriminalPursuit() {
    }

    /**
     * Puts this guard onto this offender. Idempotent per guard: a guard already chasing somebody is
     * left alone rather than being re-pointed every scan.
     */
    public static void engage(ServerLevel level, LivingEntity guard, ActiveIncidentRegistry.ActiveIncident incident) {
        if (level == null || guard == null || incident == null || !guard.isAlive()) {
            return;
        }
        if (guard.getUUID().equals(incident.offenderId())) {
            return; // a guard is not sent after itself
        }
        long now = level.getGameTime();
        Pursuit existing = ACTIVE.get(guard.getUUID());
        if (existing != null && existing.thiefId().equals(incident.offenderId())) {
            return;
        }
        if (existing != null) {
            return; // busy with somebody else; the other guards in range can take this one
        }
        ACTIVE.put(guard.getUUID(), new Pursuit(incident.offenderId(), level.dimension().location(),
                now, 0L, incident));
        CrimeDebug.crime("guard {} is pursuing criminal villager {}", guard.getUUID(), incident.offenderId());

        // Detection, not contact, is what saves the victim (spec §"Player mugging interaction"): the
        // moment a guard has the thief in its sights the mugging is over, and the chase that follows is
        // about catching a thief rather than about beating it to the theft.
        if (ActiveIncidentRegistry.get(incident.offenderId()).isPresent()) {
            NpcMuggingService.sessionForThief(incident.offenderId()).ifPresent(session ->
                    NpcMuggingService.abort(session.victimId(), NpcMugAbortReason.GUARD_INTERVENTION));
        }
    }

    /** Whether anybody is already chasing this offender, so a scan does not pile guards onto one thief. */
    public static boolean isPursued(UUID offender) {
        if (offender == null) {
            return false;
        }
        for (Pursuit pursuit : ACTIVE.values()) {
            if (pursuit.thiefId().equals(offender)) {
                return true;
            }
        }
        return false;
    }

    public static int activeCount() {
        return ACTIVE.size();
    }

    /** Drops every chase. Called on server stop so a restart never inherits one. */
    public static void clearAll() {
        ACTIVE.clear();
    }

    /** Advances every chase. One bounded map walk, and nothing at all when nobody is chasing. */
    public static void tick(MinecraftServer server) {
        if (server == null || ACTIVE.isEmpty()) {
            return;
        }
        int timeout = McaCrimeConfig.COMMON.guardThiefPursuitTimeoutTicks.get();
        double leash = McaCrimeConfig.COMMON.guardAggroRadius.get();
        boolean force = McaCrimeConfig.COMMON.guardsUseForceOnArmedThieves.get();

        for (Map.Entry<UUID, Pursuit> entry : List.copyOf(ACTIVE.entrySet())) {
            UUID guardId = entry.getKey();
            Pursuit pursuit = entry.getValue();
            ServerLevel level = levelOf(server, pursuit.dimension());
            if (level == null) {
                ACTIVE.remove(guardId);
                continue;
            }
            if (!(level.getEntity(guardId) instanceof LivingEntity guard) || !guard.isAlive()) {
                ACTIVE.remove(guardId);
                continue;
            }
            if (!(level.getEntity(pursuit.thiefId()) instanceof LivingEntity thief) || !thief.isAlive()) {
                giveUp(guardId, guard, null, "gone");
                continue;
            }
            if (CustodyRegistry.isCaptive(server, pursuit.thiefId())) {
                // Somebody else got there first, or this is a second pass after the arrest.
                giveUp(guardId, guard, thief, "already in custody");
                continue;
            }

            long now = level.getGameTime();
            if (now - pursuit.startedAt() > timeout) {
                giveUp(guardId, guard, thief, "timed out");
                continue;
            }
            double distanceSqr = guard.distanceToSqr(thief);
            if (distanceSqr > leash * leash) {
                giveUp(guardId, guard, thief, "out of range");
                continue;
            }
            if (distanceSqr <= ARREST_REACH_SQR) {
                ACTIVE.remove(guardId);
                McaCompat.clearGuardTarget(guard, thief);
                NpcArrestService.arrest(level, thief, guard, pursuit.incident());
                continue;
            }
            if (now < pursuit.nextThinkAt()) {
                continue;
            }
            ACTIVE.replace(guardId, pursuit, pursuit.scheduled(now + REPATH_INTERVAL_TICKS));
            McaCompat.faceEntity(guard, thief);
            McaCompat.moveVillagerTo(guard, thief.getX(), thief.getY(), thief.getZ(), PURSUE_SPEED);
            if (force && ArmedResolver.classify(thief).armed()) {
                McaCompat.setGuardTarget(guard, thief);
            }
        }
    }

    private static void giveUp(UUID guardId, LivingEntity guard, @Nullable LivingEntity thief, String why) {
        ACTIVE.remove(guardId);
        McaCompat.clearGuardTarget(guard, thief);
        McaCompat.releaseVillagerControl(guard);
        CrimeDebug.crime("guard {} broke off a pursuit ({})", guardId, why);
    }

    @Nullable
    private static ServerLevel levelOf(MinecraftServer server, ResourceLocation dimension) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
    }
}
