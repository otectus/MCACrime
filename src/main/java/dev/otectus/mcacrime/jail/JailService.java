package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.PlayerJailedEvent;
import dev.otectus.mcacrime.api.event.PlayerReleasedFromJailEvent;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.CrimeCapabilities;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * The server-authoritative, idempotent jail state machine (spec §7). The only writer of {@code JailState}.
 * Every release routes through {@link #release}, which is guarded so a double-release is a harmless no-op
 * (no double event, no double teleport). The real-time captivity cap in {@link #advanceTick} is the
 * universal backstop that makes a softlock unreachable: it needs no anchor, dimension, or chunk, so an
 * online player is always freed within {@code maxCaptivityRealMinutes}, and admin {@link #release} always works.
 */
public final class JailService {

    private JailService() {
    }

    // ------------------------------------------------------------------ queries

    public static boolean isJailed(ServerPlayer player) {
        return CrimeCapabilities.get(player).map(PlayerCrimeData::isJailed).orElse(false);
    }

    public static long remainingTicks(ServerPlayer player) {
        return CrimeCapabilities.get(player)
                .map(d -> d.getJail() == null ? 0L : d.getJail().getRemainingOnlineTicks())
                .orElse(0L);
    }

    // ------------------------------------------------------------------ jail

    /**
     * Jails the player for {@code ticks} online ticks. Returns false (refused, never softlock) when no jail
     * anchor can be resolved (none assigned + no fallback) — the caller reports it. Re-jailing an
     * already-jailed player only extends the sentence (max) and fires no duplicate event.
     */
    public static boolean jail(ServerPlayer player, long ticks, @Nullable JailAnchor explicit) {
        Optional<PlayerCrimeData> opt = CrimeCapabilities.get(player);
        if (opt.isEmpty()) {
            return false;
        }
        PlayerCrimeData data = opt.get();
        long clamped = Math.max(1L, Math.min(ticks, McaCrimeConfig.COMMON.maxJailCommandTicks.get()));

        if (data.isJailed()) {
            JailState existing = data.getJail();
            existing.setRemainingOnlineTicks(Math.max(existing.getRemainingOnlineTicks(), clamped));
            CrimeNetwork.sendSelfStatus(player);
            return true; // sentence update; no duplicate PlayerJailedEvent
        }

        JailAnchor anchor = resolveAnchor(player, explicit).orElse(null);
        if (anchor == null) {
            return false; // no jail assigned and no fallback -> refuse, don't create a stuck state (§7.4)
        }
        JailContainmentMode mode = McaCrimeConfig.COMMON.jailContainmentMode.get();
        JailState jail = new JailState(clamped, anchor.pos(), anchor.dim(), anchor.radius(), mode);
        data.setJail(jail);
        teleportToAnchor(player, jail); // best-effort; soft-confine fixes an unsafe/unloaded landing later
        MinecraftForge.EVENT_BUS.post(new PlayerJailedEvent(player, clamped, anchor.pos()));
        CrimeNetwork.sendSelfStatus(player);
        player.sendSystemMessage(Component.translatable("mcacrime.jail.jailed", clamped / 20L));
        return true;
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

    private static Optional<JailAnchor> configFallbackAnchor() {
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
        Optional<PlayerCrimeData> opt = CrimeCapabilities.get(player);
        if (opt.isEmpty()) {
            return;
        }
        PlayerCrimeData data = opt.get();
        if (!data.isJailed()) {
            return; // the single guard that makes double-release a no-op
        }
        data.setJail(null);
        MinecraftForge.EVENT_BUS.post(new PlayerReleasedFromJailEvent(player, reason));
        CrimeNetwork.sendSelfStatus(player);
        player.sendSystemMessage(Component.translatable(releaseKey(reason)));
    }

    private static String releaseKey(ReleaseReason reason) {
        return switch (reason) {
            case SENTENCE_SERVED -> "mcacrime.jail.released.served";
            case CAPTIVITY_CAP -> "mcacrime.jail.released.cap";
            case ADMIN -> "mcacrime.jail.released.admin";
            case PARDON -> "mcacrime.jail.released.pardon";
            case INVALID_JAIL -> "mcacrime.jail.released.invalid";
        };
    }

    /** On login, free a player whose jail dimension vanished and has no fallback (avoids a softlock, §7.4). */
    public static void reconcileOnLogin(ServerPlayer player) {
        CrimeCapabilities.get(player).ifPresent(data -> {
            JailState jail = data.getJail();
            if (jail == null) {
                return;
            }
            if (jail.hasValidAnchor()
                    && resolveLevel(player.getServer(), jail.getJailDim()) == null
                    && configFallbackAnchor().isEmpty()) {
                release(player, ReleaseReason.INVALID_JAIL);
            }
        });
    }

    // ------------------------------------------------------------------ per-tick (from CrimeDecayHandler)

    /** Per-tick countdown + cap; releases when due. Called every tick while jailed. */
    public static void tick(ServerPlayer player, PlayerCrimeData data) {
        JailState jail = data.getJail();
        if (jail == null) {
            return;
        }
        long capTicks = (long) McaCrimeConfig.COMMON.maxCaptivityRealMinutes.get() * 1200L;
        ReleaseReason due = advanceTick(jail, capTicks);
        if (due != null) {
            release(player, due);
        }
    }

    /**
     * Pure one-tick advance: decrements the sentence, accumulates served time, and returns the release
     * reason if the sentence is finished or the real-time cap is hit — else null. No game/config deps, so
     * the online-tick accounting (incl. the cap backstop) is unit-testable.
     */
    public static ReleaseReason advanceTick(JailState jail, long capTicks) {
        jail.setRemainingOnlineTicks(jail.getRemainingOnlineTicks() - 1L);
        jail.setRealOnlineTicksServed(jail.getRealOnlineTicksServed() + 1L);
        if (jail.getRemainingOnlineTicks() <= 0L) {
            return ReleaseReason.SENTENCE_SERVED;
        }
        if (capTicks > 0L && jail.getRealOnlineTicksServed() >= capTicks) {
            return ReleaseReason.CAPTIVITY_CAP;
        }
        return null;
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
        BlockPos anchor = jail.getJailAnchor();
        BlockPos target = anchor.above(); // default: one above the anchor block (likely floor)
        if (level.isLoaded(anchor)) {
            BlockPos safe = findSafeStand(level, anchor, Math.max(2, jail.getJailRadius()));
            if (safe == null) {
                return false; // no safe spot in a loaded region -> caller retries / falls back
            }
            target = safe;
        }
        player.teleportTo(level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                player.getYRot(), player.getXRot());
        return true;
    }

    /** Finds a 2-high air gap with footing within {@code range} vertical blocks of the anchor; null if none. */
    @Nullable
    private static BlockPos findSafeStand(ServerLevel level, BlockPos anchor, int range) {
        if (isSafeStand(level, anchor)) {
            return anchor;
        }
        for (int dy = 1; dy <= range; dy++) {
            BlockPos up = anchor.above(dy);
            if (isSafeStand(level, up)) {
                return up;
            }
            BlockPos down = anchor.below(dy);
            if (isSafeStand(level, down)) {
                return down;
            }
        }
        return null;
    }

    private static boolean isSafeStand(ServerLevel level, BlockPos feet) {
        if (level.isOutsideBuildHeight(feet) || level.isOutsideBuildHeight(feet.above()) || level.isOutsideBuildHeight(feet.below())) {
            return false;
        }
        try {
            return level.getBlockState(feet).isAir()
                    && level.getBlockState(feet.above()).isAir()
                    && !level.getBlockState(feet.below()).isAir(); // some footing below
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("jail safe-stand probe failed; treating as unsafe", t);
            return false;
        }
    }
}
