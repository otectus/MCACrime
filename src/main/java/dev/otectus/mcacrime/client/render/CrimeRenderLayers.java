package dev.otectus.mcacrime.client.render;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Registers the mod's player render layers.
 *
 * <p>Iterates {@code getSkins()} rather than naming {@code "default"} and {@code "slim"}, so both arm
 * models are covered without hardcoding either and a future skin type is picked up for free.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class CrimeRenderLayers {

    private CrimeRenderLayers() {
    }

    @SubscribeEvent
    public static void onRegisterLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(CuffsLayer.LAYER, CuffsLayer::createLayer);
    }

    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (String skin : event.getSkins()) {
            LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> renderer =
                    event.getSkin(skin);
            if (renderer instanceof PlayerRenderer player) {
                player.addLayer(new CuffsLayer(player,
                        event.getEntityModels().bakeLayer(CuffsLayer.LAYER)));
            }
        }
    }
}
