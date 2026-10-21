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
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A wearable mask (0.7.0, sixteen styles in 0.7.2): one class, sixteen registrations, {@link
 * MaskVariant} for the constants and {@link MaskFamily} for everything that is not the picture.
 *
 * <p>Extending {@link ArmorItem} rather than inventing an equippable is what buys right-click equip,
 * dispenser equip. The client's GeckoLib render provider supplies the shaped armour model; each style's
 * {@link MaskArmorMaterial} layer still names this mod's own asset, so a vanilla armour texture lookup
 * stays inside this mod's namespace.
 *
 * <p>Non-enchantable on purpose: Unbreaking on a disguise is a durability setting by another route,
 * and the durability setting is already {@code mask.maskDurabilityEnabled}.
 */
public class MaskItem extends ArmorItem implements GeoItem {

    /**
     * Opaque white, the identity for both tints. Fully qualified with its alpha because that is the
     * shape {@code DyedItemColor#getOrDefault} answers for a mask that <em>is</em> dyed, and the two
     * have to be comparable for "this dye would change nothing" to mean anything.
     */
    public static final int OPAQUE_WHITE = 0xFFFFFFFF;

    /**
     * Makes one worn-model provider per mask, installed by the client entrypoint. GeckoLib asks for it
     * only on the physical client, through {@link #createGeoRenderer}, the first time the mask is drawn;
     * a dedicated server keeps this empty default, and this class names no client type (the same seam as
     * {@code CrimeClientPayloadRouter}).
     */
    private static volatile Supplier<? extends GeoRenderProvider> renderProviders = () -> null;

    private final MaskVariant variant;
    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);

    /** Installed by the client entrypoint before any mask can be drawn. */
    public static void installRenderProviders(Supplier<? extends GeoRenderProvider> providers) {
        renderProviders = Objects.requireNonNull(providers, "providers");
    }

    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        GeoRenderProvider provider = renderProviders.get();
        if (provider != null) {
            consumer.accept(provider);
        }
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
     *
     * <p>Whether a mask is dyed at all is {@code MaskColors.hasColor}, never this value: a mask dyed white
     * answers the same white and still draws its dyed art (0.8.0).
     */
    public static int colorOf(ItemStack stack) {
        return DyedItemColor.getOrDefault(stack, OPAQUE_WHITE);
    }

    /**
     * A mask dyed one of the sixteen standard colours carries it in its name -- "Blue Plague Doctor Mask"
     * (0.8.0). Only the canonical colour counts: a blend or a custom colour keeps the plain style name
     * and is described in the tooltip instead, and a player's own name always wins, because the
     * {@code minecraft:custom_name} component is read before this.
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
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable(variant.family().displayKey()).withStyle(ChatFormatting.GRAY));
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
            tooltip.add(line.copy().withStyle(ChatFormatting.GRAY));
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
