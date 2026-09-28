package dev.otectus.mcacrime.locks;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * The three tags the lock system reads (M3.8).
 *
 * <p>{@code lockable_blocks} is the answer to "attach only to supported blocks, even if the source
 * description says any block" (spec §10.2): a padlock goes on what the tag names and on nothing else,
 * and a pack extends the set rather than the code doing so. {@code can_reinforce_padlock} is the
 * reinforcement material. {@code lockpicks} is what counts as a pick, so another mod's pick can work
 * without naming its class here.
 *
 * <p>Deliberately not here: {@code mcacrime:keys}. That is the cuff-family tag and it is explicitly
 * <b>not</b> a master-key tag for block locks (§3.7) — a lock is opened by a binding, never by a tag.
 */
public final class LockTags {

    public static final TagKey<Block> LOCKABLE_BLOCKS =
            TagKey.create(Registries.BLOCK, McaCrime.id("lockable_blocks"));

    public static final TagKey<Item> CAN_REINFORCE_PADLOCK =
            TagKey.create(Registries.ITEM, McaCrime.id("can_reinforce_padlock"));

    public static final TagKey<Item> LOCKPICKS =
            TagKey.create(Registries.ITEM, McaCrime.id("lockpicks"));

    private LockTags() {
    }
}
