package dev.otectus.mcacrime.client.render;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Registers the mod's render layers.
 *
 * <p>Players are covered by iterating {@code getSkins()} rather than naming {@code "default"} and
 * {@code "slim"}, so both arm models are handled without hardcoding either and a future skin type is
 * picked up for free.
 *
 * <p>MCA villagers are covered by walking {@link ForgeRegistries#ENTITY_TYPES} and taking every type
 * whose registry key is in the {@code mca} namespace. This is deliberate on two counts. It names no
 * MCA class, which is the rule the whole mod is built around — a registry key is a string. And it is
 * a namespace sweep rather than a list of ids, because MCA registers separate male and female types
 * for villagers and zombie villagers and has changed that set between releases; a hardcoded list
 * silently stops covering whatever was added. The {@code HumanoidModel} check is what keeps the sweep
 * honest: MCA's grim reaper is in the same namespace and has no arms to cuff, so it falls out here
 * rather than needing to be excluded by name.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CrimeRenderLayers {

    private static final String MCA_NAMESPACE = "mca";

    private CrimeRenderLayers() {
    }

    @SubscribeEvent
    public static void onRegisterLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(RestraintWristLayer.LAYER, RestraintWristLayer::createLayer);
    }

    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (String skin : event.getSkins()) {
            LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> renderer =
                    event.getSkin(skin);
            if (renderer instanceof PlayerRenderer player) {
                player.addLayer(new RestraintWristLayer<>(player,
                        event.getEntityModels().bakeLayer(RestraintWristLayer.LAYER)));
            }
        }
        for (EntityType<?> type : ForgeRegistries.ENTITY_TYPES) {
            ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(type);
            if (key != null && MCA_NAMESPACE.equals(key.getNamespace())) {
                attach(event, type);
            }
        }
    }

    /**
     * Attaches the wrist layer to one entity type's renderer, if that renderer can wear it.
     *
     * <p>The casts are unavoidable: the registry hands out {@code EntityType<?>} and the event's
     * lookup wants a bound on {@code LivingEntity}, so the generic identity is lost at the boundary
     * either way. Every one of them is guarded by a runtime check immediately before it, and a type
     * that is not a living humanoid returns without touching anything.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void attach(EntityRenderersEvent.AddLayers event, EntityType<?> type) {
        Object found = event.getRenderer((EntityType<? extends LivingEntity>) type);
        if (!(found instanceof LivingEntityRenderer<?, ?> renderer)
                || !(renderer.getModel() instanceof HumanoidModel<?>)) {
            return;
        }
        // A fresh bake per renderer: the layer copies the arm transforms into its own parts every
        // frame, so two renderers sharing one ModelPart would fight over it.
        ModelPart cuffs = event.getEntityModels().bakeLayer(RestraintWristLayer.LAYER);
        ((LivingEntityRenderer) renderer).addLayer(new RestraintWristLayer((RenderLayerParent) renderer, cuffs));
    }
}
