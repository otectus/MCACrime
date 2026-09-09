package dev.otectus.mcacrime.mixin;

import dev.otectus.mcacrime.loot.VillagerDeathLoot;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe equipment removal, without changing vanilla or MCA's inventory ownership. */
@Mixin(Mob.class)
public abstract class MobDeathEquipmentMixin {
    @Inject(method = "setItemSlot", at = @At("HEAD"))
    private void mcacrime$rememberDeathEquipment(EquipmentSlot slot, ItemStack replacement, CallbackInfo ci) {
        VillagerDeathLoot.beforeEquipmentChange((Mob) (Object) this, slot, replacement);
    }
}
