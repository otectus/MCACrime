package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.facility.CrimeFacilityService;
import dev.otectus.mcacrime.facility.FacilityAssignment;
import dev.otectus.mcacrime.facility.FacilityRole;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

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

    /**
     * The nearest assigned anchor in the player's current dimension, if any, at any distance.
     *
     * <p>Unlimited on purpose. An operator running {@code /crime jail} means the jail they assigned,
     * wherever it is; only the automatic arrest path applies a ceiling.
     */
    public static Optional<JailAnchor> nearestTo(ServerPlayer player) {
        return nearestTo(player, 0.0);
    }

    /**
     * The nearest assigned anchor within {@code maxDistance} blocks, or empty.
     *
     * <p>A ceiling of zero or less means unlimited, which is the historical behaviour: one
     * {@code /crime assignjail} anywhere in a dimension became the destination for every arrest in it,
     * teleporting prisoners across the map and permanently suppressing holding-cell construction,
     * because the assigned anchor always won the priority ladder.
     */
    public static Optional<JailAnchor> nearestTo(ServerPlayer player, double maxDistance) {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null) {
            return Optional.empty();
        }
        ResourceLocation dim = player.level().dimension().location();
        BlockPos pos = player.blockPosition();
        return all(server).stream()
                .filter(a -> a.dim().equals(dim))
                .filter(a -> withinCeiling(a.pos().distSqr(pos), maxDistance))
                .min(Comparator.comparingDouble(a -> a.pos().distSqr(pos)));
    }

    /** Pure: whether a squared distance is inside a ceiling. Zero or less means no ceiling at all. */
    public static boolean withinCeiling(double distSqr, double maxDistance) {
        return maxDistance <= 0.0 || distSqr <= maxDistance * maxDistance;
    }

    /**
     * Where an automatic arrest should go, and whether a facility slot has to be reserved for it.
     *
     * @param anchor   the region the prisoner is taken to
     * @param facility the assigned facility it came from, or null for a plain manual anchor
     */
    public record Destination(JailAnchor anchor, @Nullable FacilityAssignment facility) {

        /** Whether this destination needs a cell slot held before the escort starts. */
        public boolean reservable() {
            return facility != null && facility.holdsPrisoners();
        }
    }

    /**
     * The top of the <em>automatic</em> arrest ladder: an assigned civic facility, then the nearest
     * manual anchor.
     *
     * <p>Facilities come first because they carry more than a position — a role somebody chose, a
     * capacity arrests are reserved against, and a building reference that can be revalidated on
     * arrival. A manual anchor has none of that and is the right answer only when no facility applies.
     *
     * <p>Only the automatic path. {@link #nearestTo(ServerPlayer)} stays exactly as it was: an operator
     * running {@code /crime jail} means the jail they assigned, at any distance, and inserting a
     * facility above it would quietly redirect an explicit command.
     */
    public static Optional<Destination> automatic(@Nullable ServerPlayer player, double maxDistance) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        Optional<FacilityAssignment> facility =
                CrimeFacilityService.selectDestination(level, FacilityRole.JAIL_CELL, player.blockPosition());
        if (facility.isPresent()) {
            FacilityAssignment chosen = facility.get();
            return Optional.of(new Destination(new JailAnchor(chosen.anchor(),
                    chosen.ref().dimension(), McaCrimeConfig.COMMON.jailRadiusDefault.get()), chosen));
        }
        return nearestTo(player, maxDistance).map(anchor -> new Destination(anchor, null));
    }
}
