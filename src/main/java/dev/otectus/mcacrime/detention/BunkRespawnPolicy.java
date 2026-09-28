package dev.otectus.mcacrime.detention;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.PlayerReleasedFromJailEvent;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.state.CrimeCapabilities;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owns and restores the temporary respawn point set by a custody bunk. */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class BunkRespawnPolicy {

    /** A respawn point captured immediately before {@code startSleepInBed}. */
    public record RespawnState(@Nullable String dimension, @Nullable BlockPos pos, float angle,
                               boolean forced) {
        public RespawnState {
            pos = pos == null ? null : pos.immutable();
            angle = Float.isFinite(angle) ? angle : 0.0F;
        }
    }

    /**
     * The first home displaced during one custody episode and the latest bunk owned by that episode.
     * Custody and sentence ids make an old release unable to consume a newer episode's snapshot.
     */
    public record Snapshot(@Nullable String dimension, @Nullable BlockPos pos, float angle,
                           boolean forced, @Nullable String bunkDimension, BlockPos bunkPos,
                           @Nullable UUID custodyId, @Nullable UUID sentenceId) {

        public Snapshot {
            pos = pos == null ? null : pos.immutable();
            bunkPos = bunkPos == null ? BlockPos.ZERO : bunkPos.immutable();
            angle = Float.isFinite(angle) ? angle : 0.0F;
        }

        /** Compatibility constructor for the original pure ownership tests. */
        public Snapshot(@Nullable String dimension, @Nullable BlockPos pos, float angle,
                        boolean forced, @Nullable BlockPos bunkPos) {
            this(dimension, pos, angle, forced, dimension, bunkPos, null, null);
        }

        Snapshot withBunk(String dimension, BlockPos pos, @Nullable UUID custody,
                          @Nullable UUID sentence) {
            return new Snapshot(this.dimension, this.pos, angle, forced, dimension, pos,
                    custody, sentence);
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            if (dimension != null) tag.putString("homeDimension", dimension);
            if (pos != null) tag.putLong("homePos", pos.asLong());
            tag.putFloat("homeAngle", angle);
            tag.putBoolean("homeForced", forced);
            if (bunkDimension != null) tag.putString("bunkDimension", bunkDimension);
            tag.putLong("bunkPos", bunkPos.asLong());
            if (custodyId != null) tag.putUUID("custodyId", custodyId);
            if (sentenceId != null) tag.putUUID("sentenceId", sentenceId);
            return tag;
        }

        public static Optional<Snapshot> load(@Nullable CompoundTag tag) {
            if (tag == null || !tag.contains("bunkPos")) {
                return Optional.empty();
            }
            String bunkDimension = tag.contains("bunkDimension")
                    ? tag.getString("bunkDimension") : null;
            if (bunkDimension == null || ResourceLocation.tryParse(bunkDimension) == null) {
                return Optional.empty();
            }
            String homeDimension = tag.contains("homeDimension")
                    ? tag.getString("homeDimension") : null;
            if (homeDimension != null && ResourceLocation.tryParse(homeDimension) == null) {
                homeDimension = null;
            }
            return Optional.of(new Snapshot(homeDimension,
                    tag.contains("homePos") ? BlockPos.of(tag.getLong("homePos")) : null,
                    tag.getFloat("homeAngle"), tag.getBoolean("homeForced"), bunkDimension,
                    BlockPos.of(tag.getLong("bunkPos")),
                    tag.hasUUID("custodyId") ? tag.getUUID("custodyId") : null,
                    tag.hasUUID("sentenceId") ? tag.getUUID("sentenceId") : null));
        }
    }

    private record Episode(@Nullable UUID custodyId, @Nullable UUID sentenceId) {
        boolean present() {
            return custodyId != null || sentenceId != null;
        }

        boolean owns(Snapshot snapshot) {
            return snapshot != null && ((custodyId != null && custodyId.equals(snapshot.custodyId()))
                    || (sentenceId != null && sentenceId.equals(snapshot.sentenceId())));
        }
    }

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

    /** Captures the exact state that a refused or respawn-disabled sleep must put back. */
    public static RespawnState current(@Nullable ServerPlayer player) {
        if (player == null) {
            return new RespawnState(null, null, 0.0F, false);
        }
        ResourceKey<Level> dimension = player.getRespawnDimension();
        return new RespawnState(dimension == null ? null : dimension.location().toString(),
                player.getRespawnPosition(), player.getRespawnAngle(), player.isRespawnForced());
    }

    /** Restores an immediate pre-call state; this does not consume a persisted custody snapshot. */
    public static void restoreImmediate(@Nullable ServerPlayer player, @Nullable RespawnState state) {
        if (player == null || state == null) {
            return;
        }
        player.setRespawnPosition(dimension(state.dimension()), state.pos(), state.angle(),
                state.forced(), false);
    }

    /**
     * Saves the first displaced home for the current custody episode and updates its owned bunk.
     * Voluntary sleepers have no custody episode, so no temporary restoration state is created.
     */
    public static boolean take(@Nullable ServerPlayer player, @Nullable BlockPos bunkPos,
                               @Nullable RespawnState before) {
        if (player == null || bunkPos == null || before == null) {
            return false;
        }
        Episode episode = episode(player);
        if (!episode.present()) {
            return false;
        }
        Optional<PlayerCrimeData> data = CrimeCapabilities.get(player);
        if (data.isEmpty()) {
            return false;
        }
        String bunkDimension = player.level().dimension().location().toString();
        Snapshot existing = data.get().getBunkRespawnSnapshot();
        // Preserve the first home only while our previous bunk is still what this call displaced.
        // If the player chose a newer bed/anchor during custody, that deliberate choice becomes the
        // home restored after this later bunk sleep.
        Snapshot snapshot = existing != null && episode.owns(existing)
                && restores(before.pos(), before.dimension(), existing)
                ? existing.withBunk(bunkDimension, bunkPos, episode.custodyId(), episode.sentenceId())
                : new Snapshot(before.dimension(), before.pos(), before.angle(), before.forced(),
                        bunkDimension, bunkPos, episode.custodyId(), episode.sentenceId());
        data.get().setBunkRespawnSnapshot(snapshot);
        return true;
    }

    /** Compatibility overload; correct-order callers pass the pre-call state. */
    public static void take(@Nullable ServerPlayer player, @Nullable BlockPos bunkPos) {
        take(player, bunkPos, current(player));
    }

    /** True only while the live respawn equals the owned bunk in both world and block. */
    public static boolean restores(@Nullable BlockPos currentPos, @Nullable String currentDimension,
                                   @Nullable Snapshot snapshot) {
        return snapshot != null && currentPos != null && currentPos.equals(snapshot.bunkPos())
                && Objects.equals(currentDimension, snapshot.bunkDimension());
    }

    /** The persisted snapshot on a live player; this is the runtime-test and recovery seam. */
    public static Optional<Snapshot> snapshotFor(@Nullable ServerPlayer player) {
        return player == null ? Optional.empty()
                : CrimeCapabilities.get(player).map(PlayerCrimeData::getBunkRespawnSnapshot);
    }

    /** The old memory-only lookup cannot resolve persisted capability state by UUID alone. */
    @Deprecated
    public static Optional<Snapshot> snapshot(@Nullable UUID player) {
        return Optional.empty();
    }

    /**
     * Restores the first home once the snapshot's custody/sentence episode is no longer active.
     * The snapshot is consumed even when the player chose a newer spawn, because ownership has ended.
     */
    public static boolean release(@Nullable ServerPlayer player) {
        if (player == null) {
            return false;
        }
        PlayerCrimeData data = CrimeCapabilities.get(player).orElse(null);
        Snapshot snapshot = data == null ? null : data.getBunkRespawnSnapshot();
        if (snapshot == null || episode(player).owns(snapshot)) {
            return false;
        }
        data.setBunkRespawnSnapshot(null);
        ResourceKey<Level> currentDimension = player.getRespawnDimension();
        String current = currentDimension == null ? null : currentDimension.location().toString();
        if (!restores(player.getRespawnPosition(), current, snapshot)) {
            return false;
        }
        player.setRespawnPosition(dimension(snapshot.dimension()), snapshot.pos(), snapshot.angle(),
                snapshot.forced(), false);
        return true;
    }

    /** Offline releases leave the persisted snapshot for this login reconciliation. */
    public static void reconcileOnLogin(@Nullable ServerPlayer player) {
        release(player);
    }

    /** Jail-only release fallback; lawful custody release may run later in the same event. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJailReleased(PlayerReleasedFromJailEvent event) {
        release(event == null ? null : event.getPlayer());
    }

    /** Runs after the main jail/custody login reconciliation has removed any ended episode. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            reconcileOnLogin(player);
        }
    }

    /** Kept as a harmless compatibility hook; persisted snapshots are never deleted by logout. */
    @Deprecated
    public static void forget(@Nullable UUID player) {
    }

    /** Kept for old pure tests; there is no process-global snapshot table to clear. */
    @Deprecated
    public static void clearAll() {
    }

    private static Episode episode(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return new Episode(null, null);
        }
        CustodyRecord custody = CrimeWorldData.get(server).getCustody(player.getUUID());
        UUID custodyId = custody == null ? null : custody.getCustodyId();
        UUID sentenceId = custody == null ? null : custody.getSentenceId();
        UUID jailedSentence = CrimeCapabilities.get(player)
                .map(PlayerCrimeData::getJail)
                .map(jail -> jail.getSentenceId())
                .orElse(null);
        return new Episode(custodyId, jailedSentence == null ? sentenceId : jailedSentence);
    }

    private static ResourceKey<Level> dimension(@Nullable String name) {
        ResourceLocation id = name == null ? null : ResourceLocation.tryParse(name);
        return id == null ? Level.OVERWORLD : ResourceKey.create(Registries.DIMENSION, id);
    }
}
