package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.block.GuillotineBlock;
import dev.otectus.mcacrime.facility.CrimeFacilityService;
import dev.otectus.mcacrime.facility.FacilityAssignment;
import dev.otectus.mcacrime.facility.FacilityRole;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a condemned prisoner can actually be taken (0.7.5 §3.19, M6.7).
 *
 * <p>Two questions, and they are deliberately separate. <b>Is there an assigned execution site?</b> is
 * answered by the facility table — an explicit operator assignment, exactly like a jail cell, so no
 * village acquires one by somebody placing a block. <b>Is there a device at it?</b> is answered by
 * this index, which remembers guillotines as they are placed and falls back to one bounded look
 * around the site's own anchor when it has nothing for a site yet (a server restart, an existing
 * world).
 *
 * <p>The index is the reason this is not a world scan. Upstream asks the same kind of question by
 * walking every entity in the world on each block right-click ({@code ModServerEvents.java:165-171});
 * here a placed device writes one map entry and a lookup reads one.
 *
 * <p>Memory-only. A forgotten device is found again by the bounded look; a remembered device that has
 * since been broken is verified against the block before it is handed out, so the index can be stale
 * without ever being wrong.
 */
public final class ExecutionSiteRegistry {

    /**
     * How far from a site's anchor the fallback look reaches, in blocks.
     *
     * <p>Small on purpose: this looks inside the assigned building, not around the village. Anything
     * further away is a different place, and walking a prisoner to it is not what the operator
     * assigned.
     */
    public static final int SITE_DEVICE_RADIUS = 12;

    /** One remembered device: dimension and canonical position. */
    private record Device(ResourceLocation dimension, BlockPos pos) {
    }

    /** Known devices, keyed by dimension and position so two in one village are two entries. */
    private static final Map<String, Device> DEVICES = new ConcurrentHashMap<>();

    private ExecutionSiteRegistry() {
    }

    private static String key(ResourceLocation dimension, BlockPos pos) {
        return dimension + "@" + pos.asLong();
    }

    /** Remembers a placed guillotine. Called from the block when one is placed or first ticked. */
    public static void remember(@Nullable ServerLevel level, @Nullable BlockPos devicePos) {
        if (level == null || devicePos == null) {
            return;
        }
        ResourceLocation dimension = level.dimension().location();
        BlockPos canonical = devicePos.immutable();
        DEVICES.put(key(dimension, canonical), new Device(dimension, canonical));
    }

    /** Forgets one. Called when the device is broken; a stale entry is harmless but pointless. */
    public static void forget(@Nullable ServerLevel level, @Nullable BlockPos devicePos) {
        if (level != null && devicePos != null) {
            DEVICES.remove(key(level.dimension().location(), devicePos.immutable()));
        }
    }

    /** Forgets everything. Server stop, and every test's setup. */
    public static void clear() {
        DEVICES.clear();
    }

    /** How many devices are currently remembered. Diagnostics. */
    public static int size() {
        return DEVICES.size();
    }

    /** {@code sentencing.capitalPunishment.executionSiteSearchRadius}. */
    public static int searchRadius() {
        try {
            return McaCrimeConfig.COMMON.executionSiteSearchRadius.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return 48;
        }
    }

    /** The nearest assigned execution site to {@code from}, within the configured radius. */
    public static Optional<FacilityAssignment> nearestSite(@Nullable ServerLevel level,
                                                           @Nullable BlockPos from) {
        return CrimeFacilityService.selectDestination(level, FacilityRole.EXECUTION_SITE, from,
                searchRadius());
    }

    /**
     * The device belonging to one assigned site, if there is one standing.
     *
     * <p>Index first, one bounded look second, and the answer verified against the block either way:
     * a remembered position whose guillotine has been broken is dropped rather than returned, which
     * is what keeps "the device was destroyed" a clearing rule rather than a crash.
     */
    public static Optional<BlockPos> deviceAt(@Nullable ServerLevel level,
                                              @Nullable FacilityAssignment site) {
        if (level == null || site == null) {
            return Optional.empty();
        }
        ResourceLocation dimension = level.dimension().location();
        if (!dimension.equals(site.ref().dimension())) {
            return Optional.empty();
        }
        BlockPos anchor = site.anchor();
        Optional<BlockPos> remembered = nearestRemembered(level, anchor, SITE_DEVICE_RADIUS);
        if (remembered.isPresent()) {
            return remembered;
        }
        return look(level, anchor, SITE_DEVICE_RADIUS);
    }

    /** The nearest remembered device to {@code from}, verified against the world. */
    public static Optional<BlockPos> nearestRemembered(@Nullable ServerLevel level,
                                                       @Nullable BlockPos from, int radius) {
        if (level == null || from == null) {
            return Optional.empty();
        }
        ResourceLocation dimension = level.dimension().location();
        long ceiling = (long) radius * radius;
        Map<Double, BlockPos> byDistance = new LinkedHashMap<>();
        for (Device device : List.copyOf(DEVICES.values())) {
            if (!dimension.equals(device.dimension())) {
                continue;
            }
            double distance = device.pos().distSqr(from);
            if (distance > ceiling) {
                continue;
            }
            byDistance.put(distance, device.pos());
        }
        return byDistance.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .filter(pos -> standing(level, pos))
                .findFirst();
    }

    /**
     * One bounded look for a guillotine around a point.
     *
     * <p>Bounded by construction: a cube of {@code radius} blocks around an <em>assigned</em> site,
     * run once when the index has nothing, and every device it finds is remembered so it is not run
     * again. It is not a world scan and cannot become one.
     */
    private static Optional<BlockPos> look(ServerLevel level, BlockPos anchor, int radius) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(anchor.offset(-radius, -radius / 2, -radius),
                anchor.offset(radius, radius / 2, radius))) {
            if (!level.isLoaded(pos) || !standing(level, pos)) {
                continue;
            }
            BlockPos canonical = GuillotineBlock.devicePos(pos);
            remember(level, canonical);
            double distance = canonical.distSqr(anchor);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = canonical;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Whether a guillotine is actually standing at this position right now. */
    public static boolean standing(@Nullable ServerLevel level, @Nullable BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return false;
        }
        return level.getBlockState(pos).getBlock() instanceof GuillotineBlock
                || level.getBlockState(pos.above(2)).getBlock() instanceof GuillotineBlock;
    }
}
