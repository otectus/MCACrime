package dev.otectus.mcacrime.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A bottle of sand you throw at somebody's face (0.7.2 §13.1).
 *
 * <p>Both ways of using it — at the air, and at an entity — reach the same server-side throw, so the
 * cooldown, the consumption and the projectile are decided in exactly one place (invariant 11). The
 * client half of each path only predicts the swing; it never spawns anything and never takes an item.
 *
 * <p>The entity path returns a consuming result deliberately. Right-clicking a villager while holding
 * this is a throw, not a conversation, and a result that merely passed would open MCA's screen with a
 * bottle in hand.
 */
public class SandBottleItem extends Item {

    public SandBottleItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer server) {
            return new InteractionResultHolder<>(SandThrowService.throwBottle(server, hand), stack);
        }
        // The client swings now and finds out in a tick whether the server agreed. It deliberately
        // does not re-check the common config, which is not synced.
        return InteractionResultHolder.success(stack);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target,
                                                  InteractionHand hand) {
        if (player instanceof ServerPlayer server) {
            return SandThrowService.throwBottle(server, hand);
        }
        return InteractionResult.CONSUME;
    }

    /** Says the one thing players will otherwise assume wrongly: sand does not unsee a crime. */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("item.mcacrime.sand_bottle.tooltip")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
