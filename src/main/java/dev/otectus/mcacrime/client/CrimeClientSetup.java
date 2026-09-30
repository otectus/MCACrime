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
            // A padlock draws as its own item, flat on the face it hangs on (0.7.5 M3.4). Every entity
            // type needs a renderer or the client refuses to start, so this is not decoration.
            event.registerEntityRenderer(dev.otectus.mcacrime.entity.CrimeEntities.PADLOCK.get(),
                    dev.otectus.mcacrime.client.render.PadlockRenderer::new);
            // The chain knot draws as its own item (0.7.5 M4.2), for the same reason the padlock
            // does: the art exists and a bespoke rig would need a texture this release does not ship.
            event.registerEntityRenderer(dev.otectus.mcacrime.entity.CrimeEntities.CHAIN_KNOT.get(),
                    dev.otectus.mcacrime.client.render.ChainKnotRenderer::new);
        }

        /**
         * A key ring shows how many keys are on it (0.7.5 M3.2).
         *
         * <p>{@code ItemProperties.register} still exists in 1.21.1 and is still client-only, so this
         * is where the ring's four count textures are selected: the predicate reads the same data
         * component the server writes, and a ring with more than four keys keeps the four-key art.
         */
        /**
         * Tells the client recipe book where Mask Station recipes go: nowhere.
         *
         * <p>They are made at the station's own screen, never through a recipe book. Without a finder
         * the client recipe book cannot place a custom recipe type and logs one "Unknown recipe
         * category" warning per mask recipe every time a player joins a world.
         */
        @SubscribeEvent
        public static void onRegisterRecipeBookCategories(
                net.neoforged.neoforge.client.event.RegisterRecipeBookCategoriesEvent event) {
            event.registerRecipeCategoryFinder(dev.otectus.mcacrime.recipe.CrimeRecipes.MASK_MAKING.get(),
                    recipe -> net.minecraft.client.RecipeBookCategories.UNKNOWN);
        }

        @SubscribeEvent
        public static void onClientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
            event.enqueueWork(() -> net.minecraft.client.renderer.item.ItemProperties.register(
                    CrimeItems.KEY_RING.get(), McaCrime.id("keys"),
                    (stack, level, entity, seed) ->
                            dev.otectus.mcacrime.locks.KeyRingBindings.count(stack)));
        }

        /** The station's and the frisking screen's menus, bound to the menu types the server opens. */
        @SubscribeEvent
        public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
            event.register(dev.otectus.mcacrime.menu.CrimeMenus.MASK_STATION.get(),
                    dev.otectus.mcacrime.client.screen.MaskStationScreen::new);
            event.register(dev.otectus.mcacrime.menu.CrimeMenus.FRISKING.get(),
                    dev.otectus.mcacrime.client.screen.FriskingScreen::new);
        }

        /**
         * A dyed mask shows its dye in the inventory (0.7.2 §5.3, §7.2). Layer 0 only: the item
         * model's single layer carries the tint, and an undyed mask reads as opaque white.
         */
        @SubscribeEvent
        public static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
            event.register((stack, layer) -> layer > 0 ? MaskItem.OPAQUE_WHITE : MaskItem.colorOf(stack),
                    CrimeItems.masks().toArray(new Item[0]));
        }

        /**
         * The worn half of the same tint. 1.21.1 colours an armour layer through the client item
         * extension rather than through {@code DyeableLeatherItem}, so a mask dyed in the bag is
         * dyed on the face as well.
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
                                                  ArmorMaterial.Layer layer, int layerIndex, int fallback) {
                    return layer.dyeable() ? fallback : MaskItem.OPAQUE_WHITE;
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
