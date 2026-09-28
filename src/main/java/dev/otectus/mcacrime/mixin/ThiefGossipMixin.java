package dev.otectus.mcacrime.mixin;

import dev.otectus.mcacrime.compat.mca.NativeCombatContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ReputationEventHandler;
import net.minecraft.world.entity.ai.village.ReputationEventType;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppress only the scoped victim's blame; nested bystanders, mourning and trade remain intact. */
@Mixin(Villager.class)
public abstract class ThiefGossipMixin {
    @Redirect(method = "setLastHurtByMob", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;onReputationEvent(Lnet/minecraft/world/entity/ai/village/ReputationEventType;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/ReputationEventHandler;)V"))
    private void mcacrime$lawfulThiefHarm(ServerLevel level, ReputationEventType type, Entity actor,
                                        ReputationEventHandler observer) {
        if (!NativeCombatContext.suppressesActor(actor, (Entity)(Object)this)) level.onReputationEvent(type, actor, observer);
    }
    @Inject(method = "tellWitnessesThatIWasMurdered", at = @At("HEAD"), cancellable = true)
    private void mcacrime$lawfulThiefDeath(Entity actor, CallbackInfo ci) {
        if (NativeCombatContext.suppressesActor(actor, (Entity)(Object)this)) ci.cancel();
    }
}
