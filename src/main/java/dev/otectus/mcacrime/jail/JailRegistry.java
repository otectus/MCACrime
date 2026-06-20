package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Accessor over the assigned jail anchors in {@link CrimeWorldData} (spec §7.4). */
public final class JailRegistry {

    private JailRegistry() {
    }

    public static void assign(MinecraftServer server, JailAnchor anchor) {
        CrimeWorldData.get(server).addJailAnchor(anchor);
    }

    public static List<JailAnchor> all(MinecraftServer server) {
        return CrimeWorldData.get(server).jailAnchors();
    }

    /** The nearest assigned anchor in the player's current dimension, if any. */
    public static Optional<JailAnchor> nearestTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return Optional.empty();
        }
        ResourceLocation dim = player.level().dimension().location();
        BlockPos pos = player.blockPosition();
        return all(server).stream()
                .filter(a -> a.dim().equals(dim))
                .min(Comparator.comparingDouble(a -> a.pos().distSqr(pos)));
    }
}
