package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.block.GuillotineBlock;
import dev.otectus.mcacrime.detention.DeviceFeedback;
import dev.otectus.mcacrime.help.CrimeHelpTopic;
import dev.otectus.mcacrime.help.TooltipHelp;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The pillory's and the guillotine's block items (0.8.0): ordinary block items that say how the device
 * is assembled, and why a placement was refused when it was.
 *
 * <p>The block decides whether it can stand somewhere ({@code getStateForPlacement},
 * {@code canSurvive}); this only explains the refusal afterwards, on the action bar, so the rule stays
 * in one place and a refused frame no longer looks like a broken item.
 */
public class DetentionDeviceItem extends BlockItem {

    /** Which device this item places. */
    public enum Device {
        PILLORY,
        GUILLOTINE
    }

    private final Device device;

    public DetentionDeviceItem(Block block, Properties properties, Device device) {
        super(block, properties);
        this.device = device;
    }

    public Device device() {
        return device;
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        InteractionResult result = super.place(context);
        if (result == InteractionResult.FAIL && !context.getLevel().isClientSide()
                && context.getPlayer() instanceof ServerPlayer player) {
            Component why = refusal(context.getLevel(), context.getClickedPos(), context);
            if (why != null) {
                DeviceFeedback.say(player, context.getClickedPos(), why);
            }
        }
        return result;
    }

    /** Why this device cannot go where the player tried to put it, or null when the reason is vanilla's. */
    @Nullable
    private Component refusal(Level level, BlockPos pos, BlockPlaceContext context) {
        return switch (device) {
            case GUILLOTINE -> GuillotineBlock.supported(level, pos)
                    ? Component.translatable("mcacrime.device.blocked")
                    : Component.translatable("mcacrime.device.guillotine.place_on_pillory");
            case PILLORY -> {
                if (pos.getY() >= level.getMaxBuildHeight() - 1) {
                    yield Component.translatable("mcacrime.device.pillory.too_high");
                }
                if (!level.getBlockState(pos.above()).canBeReplaced(context)) {
                    yield Component.translatable("mcacrime.device.pillory.no_room");
                }
                BlockPos below = pos.below();
                if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                    yield Component.translatable("mcacrime.device.pillory.needs_ground");
                }
                yield Component.translatable("mcacrime.device.blocked");
            }
        };
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        String base = "item.mcacrime." + (device == Device.PILLORY ? "pillory" : "guillotine") + ".tooltip";
        TooltipHelp.append(tooltip, Component.translatable(base),
                List.of(Component.translatable(base + ".1"), Component.translatable(base + ".2"),
                        Component.translatable(base + ".3")),
                CrimeHelpTopic.GUILLOTINE);
    }
}
