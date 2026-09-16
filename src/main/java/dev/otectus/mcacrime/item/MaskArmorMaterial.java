package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The zero-stat armour material each mask style carries (0.7.0, sixteen styles in 0.7.2 section 4.2).
 *
 * <p>A mask is a legal-layer item, not a combat item: it grants no defense, no toughness and no
 * knockback resistance, and it takes no enchantments. Durability is deliberately absent here and
 * supplied per style through {@code Item.Properties#durability}, which {@code ArmorItem} leaves alone
 * once it is set. The only thing the {@link MaskFamily} changes here is the sound the mask makes going
 * on — cloth and leather rustle, clay clicks, iron clanks — which is flavour, not a stat.
 *
 * <p>One registered material per <em>style</em>, rather than one per family, because 1.21.1 resolves
 * the worn-armour texture from {@link ArmorMaterial.Layer} rather than from a {@code getArmorTexture}
 * override — the layer's asset name is what names {@code textures/models/armor/<style>_layer_1.png},
 * so two styles cannot share one material even when everything else about them is identical.
 *
 * <p>Every layer is declared dyeable ({@code new ArmorMaterial.Layer(id, "", true)}, the shape vanilla
 * leather uses), which is what makes the worn half of a dyed mask follow the inventory half. The
 * undyed default is white rather than vanilla's leather brown; that correction lives in the client
 * item extension, because {@code IClientItemExtensions#getDefaultDyeColor} is the only place vanilla
 * asks the question.
 */
public final class MaskArmorMaterial {

    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, McaCrime.MOD_ID);

    /** One material per style, keyed by the style itself so a seventeenth cannot be forgotten. */
    public static final Map<MaskVariant, DeferredHolder<ArmorMaterial, ArmorMaterial>> MATERIALS =
            registerAll();

    private static Map<MaskVariant, DeferredHolder<ArmorMaterial, ArmorMaterial>> registerAll() {
        Map<MaskVariant, DeferredHolder<ArmorMaterial, ArmorMaterial>> materials =
                new EnumMap<>(MaskVariant.class);
        for (MaskVariant variant : MaskVariant.values()) {
            materials.put(variant, register(variant));
        }
        return Collections.unmodifiableMap(materials);
    }

    private MaskArmorMaterial() {
    }

    private static DeferredHolder<ArmorMaterial, ArmorMaterial> register(MaskVariant variant) {
        return ARMOR_MATERIALS.register(variant.textureName(), () -> new ArmorMaterial(
                Map.of(), // no defense on any slot
                0,        // not enchantable
                equipSound(variant.family()),
                () -> Ingredient.EMPTY,
                List.of(new ArmorMaterial.Layer(McaCrime.id(variant.textureName()), "", true)),
                0.0F,     // no toughness
                0.0F));   // no knockback resistance
    }

    /** Flavour, not a stat: what a family sounds like going on. */
    private static Holder<SoundEvent> equipSound(MaskFamily family) {
        return switch (family) {
            case CLOTH, LEATHER -> SoundEvents.ARMOR_EQUIP_LEATHER;
            case CLAY -> SoundEvents.ARMOR_EQUIP_GENERIC;
            case METAL -> SoundEvents.ARMOR_EQUIP_IRON;
        };
    }

    /** The material a style wears, as the holder {@code ArmorItem} wants. */
    public static Holder<ArmorMaterial> of(MaskVariant variant) {
        return MATERIALS.get(variant);
    }

    public static void register(IEventBus modBus) {
        ARMOR_MATERIALS.register(modBus);
    }
}
