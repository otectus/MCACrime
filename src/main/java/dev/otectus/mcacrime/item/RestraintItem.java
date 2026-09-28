package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.restraint.RestraintFamily;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * An item that applies a restraint, carrying only which {@link RestraintFamily} it belongs to.
 *
 * <p>No behaviour, deliberately. Which slot the restraint lands on, whether it may be applied at
 * all, what it costs and what it does to the subject are all decided by
 * {@code restraint/RestraintService} from the definitions table; an item that answered any of those
 * would be a second authority, and a client-side one at that. The right-click is routed centrally by
 * {@code restraint/RestraintInteractHandler} so a main-hand and off-hand delivery of the same
 * interaction cannot equip twice.
 *
 * <p>The family rather than a definition id, because one item is several definitions: the same pair
 * of handcuffs is {@code handcuffs_arms} or {@code handcuffs_legs} depending on where it is applied,
 * and the direction "a definition names its item" is what lets the two protected cuff icons keep
 * their registry ids (§3.1).
 */
public class RestraintItem extends Item {

    private final RestraintFamily family;

    public RestraintItem(RestraintFamily family, Properties properties) {
        super(properties);
        this.family = family == null ? RestraintFamily.LEGACY_ROPE : family;
    }

    /** The family this item applies. A key opens a family, never a single definition (§3.7). */
    public RestraintFamily family() {
        return family;
    }

    /**
     * What this restraint actually does, in one line (0.7.5 M6.3, dispositions D07 and D08).
     *
     * <p>One of the specification's dormant entries is wrong <em>text</em> rather than wrong code:
     * upstream's leg shackles are described as stopping movement when the code only stops sprinting
     * and jumping. That is corrected here, and shown — a description that is only in a language file
     * and never drawn is not a description anybody reads.
     */
    @Override
    public void appendHoverText(@Nonnull ItemStack stack, @Nullable Level level,
                                @Nonnull List<Component> tooltip, @Nonnull TooltipFlag flag) {
        tooltip.add(Component.translatable("mcacrime.restraint.family." + family.id() + ".scope")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    /** The family {@code item} applies, or null when it is not a restraint item. */
    @Nullable
    public static RestraintFamily familyOf(@Nullable Item item) {
        return item instanceof RestraintItem restraint ? restraint.family() : null;
    }
}
