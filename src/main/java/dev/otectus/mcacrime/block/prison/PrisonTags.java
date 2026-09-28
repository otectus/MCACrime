package dev.otectus.mcacrime.block.prison;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * The prison construction tags (M5.4, M5.12).
 *
 * <p>{@code reinforced_blocks} is the set the breaking policy reads.
 * A tag rather than an {@code instanceof} check so a pack can add its own prison masonry to the same
 * rules, and so the two consumers cannot drift apart.
 */
public final class PrisonTags {

    public static final TagKey<Block> REINFORCED_BLOCKS =
            TagKey.create(Registries.BLOCK, McaCrime.id("reinforced_blocks"));

    private PrisonTags() {
    }
}
