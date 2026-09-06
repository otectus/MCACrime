package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Optional;

/**
 * Where it is safe to put somebody the game is moving against their will (0.6.0).
 *
 * <p>Custody teleports had two destination rules and both were guesses. "One block above the anchor"
 * was used whenever the anchor's chunk was not loaded, which is to say whenever the server knew least
 * about what was there — inside a wall, in lava, or a hundred blocks up. The loaded path was better but
 * only asked whether three blocks were air, so a prisoner could be dropped into a campfire, a cactus,
 * powder snow, or standing water, none of which is air and all of which are survivable-looking right up
 * until the sentence starts.
 *
 * <p>The rules are stated over a {@link BlockProbe} rather than a level, which is what makes them
 * assertable. Every one of them is a reason somebody has actually been hurt by a teleport, and a test
 * that can fail exactly one probe at a time is the only way to know each is still being asked.
 */
public final class SafeCustodyDestination {

    private SafeCustodyDestination() {
    }

    /**
     * One yes/no question about one position.
     *
     * <p>A single method with a {@link Check} rather than six methods, so the whole probe is a lambda
     * and a test can refuse exactly one check while passing the rest.
     */
    @FunctionalInterface
    public interface BlockProbe {

        /** The questions asked of a candidate. Every one must answer true. */
        enum Check {
            /** The chunk is loaded, so what is here is actually known rather than assumed. */
            LOADED,
            /** The block here has an empty collision shape: a body fits without being crushed. */
            PASSABLE,
            /** The block below has a collision shape to stand on. */
            SUPPORTED,
            /** Nothing here burns, stings, freezes or drowns. */
            SAFE,
            /** Inside the world border, so the destination is somewhere the player may legally be. */
            IN_BORDER,
            /** No other player is already standing here. */
            UNOCCUPIED
        }

        boolean test(Check check, BlockPos pos);
    }

    /**
     * Whether a body can stand at {@code feet} without being hurt by the arrival.
     *
     * <p>The head position is checked as thoroughly as the feet, because a two-block gap whose upper
     * block is lava is not a gap.
     */
    public static boolean isSafeStand(BlockProbe probe, BlockPos feet) {
        if (probe == null || feet == null) {
            return false;
        }
        BlockPos head = feet.above();
        return probe.test(BlockProbe.Check.LOADED, feet)
                && probe.test(BlockProbe.Check.LOADED, head)
                && probe.test(BlockProbe.Check.IN_BORDER, feet)
                && probe.test(BlockProbe.Check.PASSABLE, feet)
                && probe.test(BlockProbe.Check.PASSABLE, head)
                && probe.test(BlockProbe.Check.SUPPORTED, feet)
                && probe.test(BlockProbe.Check.SAFE, feet)
                && probe.test(BlockProbe.Check.SAFE, head)
                && probe.test(BlockProbe.Check.UNOCCUPIED, feet);
    }

    /**
     * The nearest safe stand within {@code searchRadius} blocks vertically of {@code anchor}.
     *
     * <p>Searched outwards from the anchor, alternating up and down, so the first answer is the closest
     * one: a prisoner moved as little as possible is a prisoner who ends up where the sentence meant
     * them to be. Empty means there is nowhere safe, and every caller treats that as a refusal rather
     * than as permission to fall back on a guess.
     */
    public static Optional<BlockPos> validate(BlockProbe probe, BlockPos anchor, int searchRadius) {
        if (probe == null || anchor == null) {
            return Optional.empty();
        }
        if (isSafeStand(probe, anchor)) {
            return Optional.of(anchor);
        }
        for (int dy = 1; dy <= Math.max(0, searchRadius); dy++) {
            BlockPos up = anchor.above(dy);
            if (isSafeStand(probe, up)) {
                return Optional.of(up);
            }
            BlockPos down = anchor.below(dy);
            if (isSafeStand(probe, down)) {
                return Optional.of(down);
            }
        }
        return Optional.empty();
    }

    /** The same search against a live level. */
    public static Optional<BlockPos> validate(ServerLevel level, BlockPos anchor, int searchRadius) {
        return level == null ? Optional.empty() : validate(probe(level), anchor, searchRadius);
    }

    /** Whether one position in a live level is safe to stand in. */
    public static boolean isSafeStand(ServerLevel level, BlockPos feet) {
        return level != null && isSafeStand(probe(level), feet);
    }

    /**
     * The level binding.
     *
     * <p>Collision shape rather than {@code isAir}: a torch, a sign and a patch of grass are all not
     * air and all perfectly fine to stand in, and refusing them would send prisoners hunting for a spot
     * in the middle of any decorated village. A throwing probe answers false — a destination this code
     * cannot inspect is not a destination it may use.
     */
    private static BlockProbe probe(ServerLevel level) {
        return (check, pos) -> {
            try {
                if (level.isOutsideBuildHeight(pos)) {
                    return false;
                }
                return switch (check) {
                    case LOADED -> level.isLoaded(pos);
                    case IN_BORDER -> level.getWorldBorder().isWithinBounds(pos);
                    case PASSABLE -> level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
                    case SUPPORTED -> !level.getBlockState(pos.below())
                            .getCollisionShape(level, pos.below()).isEmpty()
                            && !hazardous(level.getBlockState(pos.below()));
                    case SAFE -> !hazardous(level.getBlockState(pos)) && level.getFluidState(pos).isEmpty();
                    case UNOCCUPIED -> level.getEntitiesOfClass(Player.class, new AABB(pos)).isEmpty();
                };
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA: Crime could not probe {} for {}; treating it as unsafe", pos, check, t);
                return false;
            }
        };
    }

    /** Blocks that hurt whatever is standing in or on them. */
    private static boolean hazardous(BlockState state) {
        return state.is(BlockTags.FIRE)
                || state.is(BlockTags.CAMPFIRES)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH);
    }
}
