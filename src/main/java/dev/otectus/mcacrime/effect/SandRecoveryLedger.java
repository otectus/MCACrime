package dev.otectus.mcacrime.effect;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Where a target's sand timings live between the effect landing and the recovery window closing
 * (0.7.2 §13.6).
 *
 * <p>Two stores, deliberately. The in-memory map is authoritative while the server runs and is
 * dropped on shutdown, because a cache that outlives the world it describes is exactly the "stale
 * controller state" SAND-10 asks about. The entity's own persistent data is the lazy copy: it is
 * written whenever the state changes and read back the first time a target is asked about after a
 * load, so a restart inside a recovery window still refuses the next bottle instead of forgetting.
 *
 * <p>The clock is always the overworld's game time. Every other dimension ticks its own, and a target
 * that changes dimension mid-effect would otherwise land on an unrelated tick count and either
 * recover instantly or never.
 */
public final class SandRecoveryLedger {

    /** NeoForge keeps this subtag on the entity's own NBT, so it survives save/load and logout. */
    private static final String ROOT = "mcacrime_sand";
    private static final String ACTIVE_UNTIL = "active_until";
    private static final String RECOVERY_UNTIL = "recovery_until";
    private static final int MAX_TRACKED = 4096;

    private static final Map<UUID, SandRecovery.State> CACHE = new LinkedHashMap<>();

    private SandRecoveryLedger() {
    }

    /** The one clock every sand timestamp is measured on. */
    public static long clock(@Nullable MinecraftServer server) {
        if (server == null) {
            return 0L;
        }
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld == null ? server.overworld().getGameTime() : overworld.getGameTime();
    }

    /** The same clock, reached from any entity. */
    public static long clock(@Nullable LivingEntity entity) {
        return entity == null || entity.level().getServer() == null ? 0L : clock(entity.level().getServer());
    }

    /** The reconciled state of one target, reading through to its saved copy exactly once. */
    public static synchronized SandRecovery.State stateOf(LivingEntity entity, long now) {
        if (entity == null) {
            return SandRecovery.State.CLEAR;
        }
        SandRecovery.State cached = CACHE.get(entity.getUUID());
        if (cached == null) {
            cached = read(entity);
        }
        SandRecovery.State reconciled = SandRecovery.reconcile(cached, now);
        if (reconciled.isClear()) {
            CACHE.remove(entity.getUUID());
        } else {
            remember(entity.getUUID(), reconciled);
        }
        return reconciled;
    }

    /** Whether sand may take hold on this target right now, against every thrower alike. */
    public static boolean canApply(LivingEntity entity, long now) {
        return SandRecovery.canApply(stateOf(entity, now), now);
    }

    /** Records an application and the recovery window that follows it. */
    public static synchronized void applied(LivingEntity entity, long now, int durationTicks, int recoveryTicks) {
        write(entity, SandRecovery.applied(now, durationTicks, recoveryTicks));
    }

    /**
     * Records that the effect is gone — expired, cured, or removed by something else. The recovery
     * window runs from this moment, so an early cure buys sight back but not a second bottle.
     */
    public static synchronized void cured(LivingEntity entity, long now, int recoveryTicks) {
        write(entity, SandRecovery.cured(now, recoveryTicks));
    }

    /** Drops every transient record. Called when the server stops; the saved copies are unaffected. */
    public static synchronized void clearAll() {
        CACHE.clear();
    }

    private static void write(LivingEntity entity, SandRecovery.State state) {
        if (entity == null) {
            return;
        }
        remember(entity.getUUID(), state);
        CompoundTag root = new CompoundTag();
        root.putLong(ACTIVE_UNTIL, state.activeUntil());
        root.putLong(RECOVERY_UNTIL, state.recoveryUntil());
        entity.getPersistentData().put(ROOT, root);
    }

    private static SandRecovery.State read(LivingEntity entity) {
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(ROOT, CompoundTag.TAG_COMPOUND)) {
            return SandRecovery.State.CLEAR;
        }
        CompoundTag root = data.getCompound(ROOT);
        return new SandRecovery.State(root.getLong(ACTIVE_UNTIL), root.getLong(RECOVERY_UNTIL));
    }

    private static void remember(UUID id, SandRecovery.State state) {
        if (CACHE.size() >= MAX_TRACKED && !CACHE.containsKey(id)) {
            CACHE.remove(CACHE.keySet().iterator().next());
        }
        CACHE.put(id, state);
    }
}
