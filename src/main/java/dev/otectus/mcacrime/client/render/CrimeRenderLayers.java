package dev.otectus.mcacrime.client.render;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Registers the mod's render layers.
 *
 * <p>Players are covered by iterating {@code getSkins()} rather than naming {@code "default"} and
 * {@code "slim"}, so both arm models are covered without hardcoding either and a future skin type is
 * picked up for free.
 *
 * <p>MCA villagers are covered by walking the entity types that already have a renderer and taking
 * every one whose registry key is in the {@code mca} namespace. This is deliberate on two counts. It
 * names no MCA class, which is the rule the whole mod is built around — a registry key is a string.
 * And it is a namespace sweep rather than a list of ids, because MCA registers separate male and
 * female types for villagers and zombie villagers and has changed that set between releases; a
 * hardcoded list silently stops covering whatever was added. The {@code HumanoidModel} check is what
 * keeps the sweep honest: MCA's grim reaper is in the same namespace and has no arms to cuff, so it
 * falls out here rather than needing to be excluded by name.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
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
        for (PlayerSkin.Model skin : event.getSkins()) {
            EntityRenderer<? extends Player> renderer = event.getSkin(skin);
            if (renderer instanceof PlayerRenderer player) {
                player.addLayer(new RestraintWristLayer<>(player,
                        event.getEntityModels().bakeLayer(RestraintWristLayer.LAYER)));
            }
        }
        for (EntityType<?> type : event.getEntityTypes()) {
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (key != null && MCA_NAMESPACE.equals(key.getNamespace())) {
                attach(event, type);
            }
        }
    }

    /**
     * Attaches the wrist layer to one entity type's renderer, if that renderer can wear it.
     *
     * <p>The casts are unavoidable: the event hands out {@code EntityType<?>} and its lookup is
     * generic in the entity, so the generic identity is lost at the boundary either way. Every one of
     * them is guarded by a runtime check immediately before it, and a type that is not a living
     * humanoid returns without touching anything.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void attach(EntityRenderersEvent.AddLayers event, EntityType<?> type) {
        Object found = event.getRenderer(type);
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
