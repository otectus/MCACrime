package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * A wearable mask (0.7.0, sixteen styles in 0.7.2): one class, sixteen registrations, {@link
 * MaskVariant} for the constants and {@link MaskFamily} for everything that is not the picture.
 *
 * <p>Extending {@link ArmorItem} rather than inventing an equippable is what buys right-click equip,
 * dispenser equip. The client extension supplies the shaped GeckoLib armor model; the texture
 * override also keeps vanilla armor texture lookups inside this mod's namespace.
 *
 * <p>Non-enchantable on purpose: Unbreaking on a disguise is a durability setting by another route,
 * and the durability setting is already {@code mask.maskDurabilityEnabled}.
 */
public class MaskItem extends ArmorItem implements DyeableLeatherItem, GeoItem {

    private final MaskVariant variant;
    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);

    /** Forge invokes this extension hook only on the physical client. */
    @Override
    public void initializeClient(Consumer<net.minecraftforge.client.extensions.common.IClientItemExtensions> consumer) {
        consumer.accept(new dev.otectus.mcacrime.client.render.mask.MaskClientExtensions());
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // The supplied models are static; head movement comes from the wearer's armor pose.
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return animationCache;
    }

    public MaskItem(MaskVariant variant) {
        super(MaskArmorMaterial.of(variant.family()), ArmorItem.Type.HELMET,
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
     * <p>The interface's own default answers {@code DEFAULT_LEATHER_COLOR} for a stack with no colour
     * tag, which is right for a leather cap and wrong for painted art: it would multiply every shipped
     * mask texture by a brown the artist never chose. White is the identity for both the item colour
     * handler and the worn renderer's tint, so an undyed mask looks exactly as it did before masks
     * could be dyed at all. Whether a mask is dyed at all is {@code MaskColors.hasColor}, never this
     * value: a mask dyed white answers the same white and still draws its dyed art (0.8.0).
     */
    @Override
    public int getColor(ItemStack stack) {
        return dev.otectus.mcacrime.mask.MaskColors.storedColor(stack).orElse(0xFFFFFF);
    }

    /**
     * A mask dyed one of the sixteen standard colours carries it in its name -- "Blue Plague Doctor Mask"
     * (0.8.0). Only the canonical colour counts: a blend or a custom colour keeps the plain style name
     * and is described in the tooltip instead, and a player's own name always wins, because
     * {@code getHoverName} reads it before this.
     */
    @Override
    public Component getName(ItemStack stack) {
        return dev.otectus.mcacrime.mask.MaskColors.standardOf(stack)
                .<Component>map(dye -> Component.translatable("item.mcacrime.mask.colored",
                        Component.translatable(dev.otectus.mcacrime.mask.MaskColors.colorNameKey(dye)),
                        Component.translatable(variant.displayKey())))
                .orElseGet(() -> super.getName(stack));
    }

    /**
     * Names the family and says the thing a collection of sixteen faces otherwise invites players to
     * guess wrongly: the scary one is not the good one (0.7.2 section 4.2). Then the colour, in words
     * (0.8.0): a standard colour by name, anything else as a custom colour with its hex value in advanced
     * tooltips only, and where to change or wash it.
     */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable(variant.family().displayKey())
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        java.util.Optional<Integer> color = dev.otectus.mcacrime.mask.MaskColors.storedColor(stack);
        if (color.isPresent()) {
            java.util.Optional<net.minecraft.world.item.DyeColor> standard =
                    dev.otectus.mcacrime.mask.MaskColors.standardOf(color.get());
            Component line = standard.<Component>map(dye -> Component.translatable("item.mcacrime.mask.color",
                            Component.translatable(dev.otectus.mcacrime.mask.MaskColors.colorNameKey(dye))))
                    .orElseGet(() -> flag.isAdvanced()
                            ? Component.translatable("item.mcacrime.mask.color.custom_hex",
                                    dev.otectus.mcacrime.mask.MaskColors.hex(color.get()))
                            : Component.translatable("item.mcacrime.mask.color.custom"));
            tooltip.add(line.copy().withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        dev.otectus.mcacrime.help.TooltipHelp.append(tooltip,
                Component.translatable("item.mcacrime.mask.tooltip"),
                List.of(Component.translatable("item.mcacrime.mask.tooltip.1"),
                        Component.translatable("item.mcacrime.mask.tooltip.2")),
                dev.otectus.mcacrime.help.CrimeHelpTopic.MASKS);
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return false;
    }

    @Override
    public boolean isBookEnchantable(ItemStack stack, ItemStack book) {
        return false;
    }

    @Override
    public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        return McaCrime.MOD_ID + ":textures/models/armor/" + variant.textureName() + "_layer_1.png";
    }

    /**
     * Wear only costs the mask something when the operator says it does. The toggle is read here, at
     * damage time, rather than folded into the item's properties, because properties are built during
     * the registry event when no config file has been loaded yet.
     */
    @Override
    public <T extends LivingEntity> int damageItem(ItemStack stack, int amount, T entity, Consumer<T> onBroken) {
        return McaCrimeConfig.COMMON.maskDurabilityEnabled.get() ? amount : 0;
    }
}
