package dev.otectus.mcacrime.item.lock;

import dev.otectus.mcacrime.locks.LockInteractions;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;

/**
 * A lockpick: the item that opens a native lockpicking session (§3.5).
 *
 * <p>Its type matters to the M2 interaction router, which has to tell "a pick, aimed at a restraint"
 * apart from "a key, aimed at a restraint". {@link #useOn} covers the block half for the crouching
 * click that never reaches the block's own interaction.
 *
 * <p>The session is server-created and pinned to this exact stack, so swapping the pick mid-pick
 * cancels rather than continuing with a different tool, and the durability it costs is spent on the
 * server — never accepted from a packet, which is upstream's arrangement.
 */
public class LockpickItem extends Item {

    public LockpickItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        InteractionResult result = LockInteractions.useOnBlock(context);
        return result == InteractionResult.PASS ? super.useOn(context) : result;
    }

    /** A pick is a tool, not a weapon: it takes damage from picking and from nothing else. */
    @Override
    public boolean isValidRepairItem(ItemStack stack, ItemStack repair) {
        return repair.is(Items.IRON_NUGGET);
    }
}
