package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.DyedItemColor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * A wearable mask (0.7.0, sixteen styles in 0.7.2): one class, sixteen registrations, {@link
 * MaskVariant} for the constants and {@link MaskFamily} for everything that is not the picture.
 *
 * <p>Extending {@link ArmorItem} rather than inventing an equippable is what buys right-click equip,
 * dispenser equip and worn rendering with no client code at all. The worn texture comes with it: each
 * style's {@link MaskArmorMaterial} carries a layer naming this mod's own asset, so vanilla resolves
 * {@code textures/models/armor/<style>_layer_1.png} without an override.
 *
 * <p>Non-enchantable on purpose: Unbreaking on a disguise is a durability setting by another route,
 * and the durability setting is already {@code mask.maskDurabilityEnabled}.
 */
public class MaskItem extends ArmorItem {

    /**
     * Opaque white, the identity for both tints. Fully qualified with its alpha because that is the
     * shape {@code DyedItemColor#getOrDefault} answers for a mask that <em>is</em> dyed, and the two
     * have to be comparable for "this dye would change nothing" to mean anything.
     */
    public static final int OPAQUE_WHITE = 0xFFFFFFFF;

    private final MaskVariant variant;

    public MaskItem(MaskVariant variant) {
        super(MaskArmorMaterial.of(variant), ArmorItem.Type.HELMET,
                new Item.Properties().durability(variant.durability()));
        this.variant = variant;
    }

    public MaskVariant getVariant() {
        return variant;
    }

    /** The material family this style belongs to, and therefore its wear budget and recipe cost. */
    public MaskFamily getFamily() {
        return variant.family();
    }

    /**
     * An undyed mask is untinted, not leather-brown (0.7.2 §5.3).
     *
     * <p>1.21.1 has no {@code DyeableLeatherItem}; the colour is the {@code minecraft:dyed_color}
     * component, and a stack without one answers {@code DyedItemColor.LEATHER_COLOR} to every vanilla
     * caller. That brown is right for a leather cap and wrong for painted art — it would multiply
     * every shipped mask texture by a colour the artist never chose — so white, the identity for both
     * the item colour handler and vanilla's worn-armour tint, is the default here instead. An undyed
     * mask therefore looks exactly as it did before masks could be dyed at all.
     *
     * <p>Deliberately <em>not</em> a default component on {@code Item.Properties}: a default
     * {@code dyed_color} of white would be averaged in by {@code DyedItemColor.applyDyes}, and the
     * first dye a player applied would come out washed rather than the colour on the dye.
     */
    public static int colorOf(ItemStack stack) {
        return DyedItemColor.getOrDefault(stack, OPAQUE_WHITE);
    }

    /**
     * Names the family and says the thing a collection of sixteen faces otherwise invites players to
     * guess wrongly: the scary one is not the good one (0.7.2 section 4.2).
     */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable(variant.family().displayKey()).withStyle(ChatFormatting.GRAY));
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return false;
    }

    @Override
    public boolean isBookEnchantable(ItemStack stack, ItemStack book) {
        return false;
    }

    /**
     * Wear only costs the mask something when the operator says it does. The toggle is read here, at
     * damage time, rather than folded into the item's properties, because properties are built during
     * the registry event when no config file has been loaded yet.
     */
    @Override
    public <T extends LivingEntity> int damageItem(ItemStack stack, int amount, @Nullable T entity,
                                                   Consumer<Item> onBroken) {
        return McaCrimeConfig.COMMON.maskDurabilityEnabled.get() ? amount : 0;
    }
}
