package dev.otectus.mcacrime.client.render.mask;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

/** Lazily created on the client, after the resource manager is available. */
public final class MaskClientExtensions implements IClientItemExtensions {
    private MaskArmorRenderer renderer;

    @Override
    public HumanoidModel<?> getHumanoidArmorModel(LivingEntity wearer, ItemStack stack,
                                                 EquipmentSlot slot, HumanoidModel<?> original) {
        if (renderer == null) renderer = new MaskArmorRenderer();
        renderer.prepForRender(wearer, stack, slot, original);
        return renderer;
    }
}
