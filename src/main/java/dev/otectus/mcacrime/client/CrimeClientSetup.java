package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.item.MaskItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Client-only setup: cache hygiene across connections, and the two halves of a dyed mask.
 *
 * <p>The config screen factory moved to {@link McaCrimeClient}, where the mod container is injected.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class CrimeClientSetup {

    private CrimeClientSetup() {
    }

    /** Everything that is registered rather than observed, which on NeoForge is the mod bus. */
    @EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.MOD)
    public static final class ModBus {

        private ModBus() {
        }

        /**
         * The thrown Sand Bottle draws as its own item, like every vanilla thrown item (0.7.2 §13.2).
         */
        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(dev.otectus.mcacrime.entity.CrimeEntities.SAND_BOTTLE.get(),
                    net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
        }

        /**
         * A dyed mask shows its dye in the inventory (0.7.2 §5.3, §7.2).
         *
         * <p>Layer 0 only: the item model's single layer carries the tint, and {@link
         * MaskItem#colorOf} answers opaque white for an undyed one, so a mask nobody dyed looks
         * exactly as it did before the station could dye anything.
         *
         * <p>Registered from the catalogue rather than from a hand-written list of items: this is the
         * inventory half of the tint, the worn half is vanilla's own {@code HumanoidArmorLayer}
         * multiplying the layer texture by the same colour, and a style that reached one and not the
         * other would be dyed in the bag and grey on the face.
         */
        /**
         * The Mask Station's screen (0.7.2 §6.3).
         *
         * <p>1.21.1 replaced the {@code MenuScreens.register} call that used to sit in client setup
         * with this mod-bus event, which is the only window in which the map may be written; doing it
         * from {@code FMLClientSetupEvent} now throws.
         */
        @SubscribeEvent
        public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
            event.register(dev.otectus.mcacrime.menu.CrimeMenus.MASK_STATION.get(),
                    dev.otectus.mcacrime.client.screen.MaskStationScreen::new);
        }

        @SubscribeEvent
        public static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
            event.register((stack, layer) -> layer > 0 ? MaskItem.OPAQUE_WHITE : MaskItem.colorOf(stack),
                    CrimeItems.masks().toArray(new Item[0]));
        }

        /**
         * The worn half of the same tint, and the reason an undyed mask is not leather-brown.
         *
         * <p>Each mask's {@link ArmorMaterial.Layer} is declared dyeable, which is what makes vanilla
         * multiply the worn texture by a colour at all. The colour it would otherwise use is
         * {@code DyedItemColor.LEATHER_COLOR} for any stack with no {@code minecraft:dyed_color}
         * component — right for a leather cap, wrong for sixteen painted faces. This extension is the
         * one place vanilla asks, so it is the one place to answer white.
         */
        @SubscribeEvent
        public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
            event.registerItem(new IClientItemExtensions() {
                @Override
                public int getDefaultDyeColor(ItemStack stack) {
                    return MaskItem.colorOf(stack);
                }

                @Override
                public int getArmorLayerTintColor(ItemStack stack, LivingEntity entity,
                                                  ArmorMaterial.Layer layer, int layerIdx,
                                                  int fallbackColor) {
                    return layer.dyeable() ? fallbackColor : MaskItem.OPAQUE_WHITE;
                }
            }, CrimeItems.masks().toArray(new Item[0]));
        }
    }

    /**
     * Clears every client cache on disconnect.
     *
     * <p>Without this, joining a second world shows the first world's Heat, band colours, captivity
     * countdown and case file until the server happens to overwrite each one — and anything the new
     * server never sends (because the player is clean there) is never overwritten at all.
     *
     * <p>Which caches those are lives in {@link ClientCaches#ALL}, not here, so a cache added later
     * cannot be left out of the sweep.
     */
    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientCaches.clearAll();
    }
}
