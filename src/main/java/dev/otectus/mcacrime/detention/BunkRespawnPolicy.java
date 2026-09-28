package dev.otectus.mcacrime.detention;

import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import java.util.Map;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who owns a prisoner's respawn point while they are sleeping in a bunk (0.7.5 M4.7).
 *
 * <p>The rule the specification asks for in one sentence: a forced custody bunk may set the
 * prisoner's respawn point, and must give the old one back on release <b>only if this system still
 * owns the override</b>. A player who has since slept in their own bed, or touched a respawn anchor,
 * has chosen a newer spawn, and restoring a snapshot over the top of that would be this mod undoing
 * somebody's deliberate decision.
 *
 * <p>{@link #restores} is that rule as a pure function, and it is the only thing the acceptance test
 * needs: ownership is decided by comparing what we set against what the player has now, never by a
 * flag we set ourselves and hope is still true.
 *
 * <p>The snapshots are memory-only. A restart loses them, and the consequence is the safe one: the
 * prisoner keeps the bunk as their spawn until they choose another, rather than being moved to a
 * position this mod half-remembers.
 */
public final class BunkRespawnPolicy {

    /**
     * Somebody's respawn point before a bunk touched it.
     *
     * @param dimension  the dimension key as a string; kept as a string so the record needs no
     *                   registry to exist in a test
     * @param pos        the point itself, or null when they had none
     * @param angle      the facing that goes with it
     * @param forced     whether it was a forced spawn
     * @param bunkPos    the bunk we set instead, which is what ownership is checked against
     */
    public record Snapshot(@Nullable String dimension, @Nullable BlockPos pos, float angle,
                           boolean forced, BlockPos bunkPos) {

        public Snapshot {
            pos = pos == null ? null : pos.immutable();
            bunkPos = bunkPos == null ? BlockPos.ZERO : bunkPos.immutable();
            angle = Float.isFinite(angle) ? angle : 0.0F;
        }
    }

    private static final Map<UUID, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private BunkRespawnPolicy() {
    }

    /** {@code detention.bunkSetsRespawn}, defaulting to the documented true. */
    public static boolean setsRespawn() {
        try {
            return McaCrimeConfig.COMMON.bunkSetsRespawn.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return true;
        }
    }

    /**
     * Whether release should put the old respawn point back.
     *
     * <p>Pure. True only when the player's current respawn is still the bunk this system set: any
     * other value means they have chosen something newer, and the snapshot is discarded rather than
     * applied.
     *
     * @param currentPos       where the player respawns now
     * @param currentDimension the dimension they respawn in now
     * @param snapshot         what we took and what we set
     */
    public static boolean restores(@Nullable BlockPos currentPos, @Nullable String currentDimension,
                                   @Nullable Snapshot snapshot) {
        // Ownership is one comparison: is the player's respawn still the bunk we set? A newer bed, a
        // respawn anchor, another mod's spawn point or a cleared spawn all answer no, and all of them
        // are decisions this mod has no business reversing. The dimension is carried for the restore
        // itself and deliberately not part of the test -- a bunk position that matches by coordinate
        // in another dimension is not a position the player chose either.
        return snapshot != null && currentPos != null && currentPos.equals(snapshot.bunkPos());
    }

    /** Remembers what {@code player} had, and records which bunk replaced it. */
    public static void take(@Nullable ServerPlayer player, @Nullable BlockPos bunkPos) {
        if (player == null || bunkPos == null) {
            return;
        }
        ResourceKey<Level> dimension = player.getRespawnDimension();
        SNAPSHOTS.put(player.getUUID(), new Snapshot(
                dimension == null ? null : dimension.location().toString(),
                player.getRespawnPosition(), player.getRespawnAngle(), player.isRespawnForced(),
                bunkPos));
    }

    /** What we took from {@code player}, if anything. */
    public static Optional<Snapshot> snapshot(@Nullable UUID player) {
        return Optional.ofNullable(player == null ? null : SNAPSHOTS.get(player));
    }

    /**
     * Gives the old respawn point back, if this system still owns the override.
     *
     * <p>The snapshot is consumed either way: whether it was applied or discarded, it has been
     * answered, and keeping it would mean a later release applying a stale one.
     *
     * @return true when the old point was actually restored
     */
    public static boolean release(@Nullable ServerPlayer player) {
        if (player == null) {
            return false;
        }
        Snapshot snapshot = SNAPSHOTS.remove(player.getUUID());
        ResourceKey<Level> dimension = player.getRespawnDimension();
        String current = dimension == null ? null : dimension.location().toString();
        if (!restores(player.getRespawnPosition(), current, snapshot)) {
            return false;
        }
        if (snapshot.pos() == null) {
            player.setRespawnPosition(Level.OVERWORLD, null, 0.0F, false, false);
            return true;
        }
        // 1.21.1: ResourceLocation's constructor is private, and a snapshot string that is not a valid
        // id resolves to the overworld rather than throwing inside a release path.
        net.minecraft.resources.ResourceLocation parsed = snapshot.dimension() == null ? null
                : net.minecraft.resources.ResourceLocation.tryParse(snapshot.dimension());
        ResourceKey<Level> restored = parsed == null ? Level.OVERWORLD
                : ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, parsed);
        player.setRespawnPosition(restored, snapshot.pos(), snapshot.angle(), snapshot.forced(), false);
        return true;
    }

    /** Forgets a player's snapshot without applying it: they logged out, or a test is resetting. */
    public static void forget(@Nullable UUID player) {
        if (player != null) {
            SNAPSHOTS.remove(player);
        }
    }

    /** Forgets everything. Server stop. */
    public static void clearAll() {
        SNAPSHOTS.clear();
    }
}
