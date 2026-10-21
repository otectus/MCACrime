package dev.otectus.mcacrime.client.render.mask;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;

/**
 * Lazily created on the client, after the resource manager is available.
 *
 * <p>The NeoForge counterpart of the Forge build's {@code MaskClientExtensions}: GeckoLib for 1.21.1
 * reads a worn model from the item's {@link GeoRenderProvider}, not from {@code IClientItemExtensions},
 * and prepares the renderer for each draw itself.
 */
public final class MaskRenderProvider implements GeoRenderProvider {
    private MaskArmorRenderer renderer;

    @Override
    public <T extends LivingEntity> HumanoidModel<?> getGeoArmorRenderer(@Nullable T wearer, ItemStack stack,
                                                                        @Nullable EquipmentSlot slot,
                                                                        @Nullable HumanoidModel<T> original) {
        if (renderer == null) renderer = new MaskArmorRenderer();
        return renderer;
    }
}
