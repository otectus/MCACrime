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

/**
 * Suppress only the scoped victim's blame for an exempt act; nested bystanders, mourning and trade
 * remain intact (0.7.7 on this port, from the Forge tree).
 *
 * <p>Vanilla villager gossip is the third native consequence of a blow, beside MCA's hearts and its
 * guard alert: being hurt or killed tells every villager watching. Both reach here only inside a native
 * scope ({@code NativeCombatContext}), and only the actor that scope exempts is spared; a killing is
 * spared only where the policy also exempts lethal force.
 */
@Mixin(Villager.class)
public abstract class ThiefGossipMixin {
    @Redirect(method = "setLastHurtByMob", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;onReputationEvent(Lnet/minecraft/world/entity/ai/village/ReputationEventType;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/ReputationEventHandler;)V"))
    private void mcacrime$lawfulThiefHarm(ServerLevel level, ReputationEventType type, Entity actor,
                                        ReputationEventHandler observer) {
        if (!NativeCombatContext.suppressesActor(actor, (Entity)(Object)this)) level.onReputationEvent(type, actor, observer);
    }
    @Inject(method = "tellWitnessesThatIWasMurdered", at = @At("HEAD"), cancellable = true)
    private void mcacrime$lawfulThiefDeath(Entity actor, CallbackInfo ci) {
        if (NativeCombatContext.suppressesMurderWitnesses(actor, (Entity)(Object)this)) ci.cancel();
    }
}
