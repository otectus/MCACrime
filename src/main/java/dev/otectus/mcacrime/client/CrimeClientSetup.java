package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.client.screen.CrimeConfigScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client-only setup: the settings screen registration, and cache hygiene across connections.
 *
 * <p>Registering the config screen is what puts a Config button on this mod's row in the Mods list.
 * Without it the client options are only reachable by editing a TOML file, which for options about
 * nameplates and HUD placement is the wrong ask — those are exactly the settings somebody wants to
 * change while looking at the thing they affect.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class CrimeClientSetup {

    private CrimeClientSetup() {
    }

    @Mod.EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        private ModBus() {
        }

        /**
         * The thrown Sand Bottle draws as its own item, like every vanilla thrown item (0.7.2 §13.2).
         */
        @SubscribeEvent
        public static void onRegisterRenderers(
                net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(dev.otectus.mcacrime.entity.CrimeEntities.SAND_BOTTLE.get(),
                    net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
            // A padlock draws as its own item, flat on the block it hangs on (0.7.5 M3.4). Not
            // optional: a client refuses to start with an entity type that has no renderer.
            event.registerEntityRenderer(dev.otectus.mcacrime.entity.CrimeEntities.PADLOCK.get(),
                    dev.otectus.mcacrime.client.render.PadlockRenderer::new);
            // The knot draws as a chain item at the fence post (0.7.5 M4.2). The same choice the
            // padlock made, for the same reason: the item art already exists, and a bespoke entity
            // model would need entity textures this release does not ship. Every entity type must
            // have a renderer or the client refuses to start.
            event.registerEntityRenderer(dev.otectus.mcacrime.entity.CrimeEntities.CHAIN_KNOT.get(),
                    dev.otectus.mcacrime.client.render.ChainKnotRenderer::new);
        }

        /**
         * A dyed mask shows its dye (0.7.2 §5.3, §7.2; split into base and detail in 0.8.0).
         *
         * <p>Layer 0 only: it is the undyed model's single layer, which {@code MaskItem.getColor}
         * leaves white so a mask nobody dyed looks exactly as it always did, and the dyed model's
         * neutral base, which takes the colour. A dyed model's layer 1 is its detail -- lenses, eyes,
         * straps, trim -- and stays untinted.
         *
         * <p>Registered from the catalogue rather than from a hand-written list of items: this is the
         * inventory half of the tint, the worn half is {@code MaskArmorRenderer} tinting the same
         * neutral base by the same {@code DyeableLeatherItem} colour, and a style that reached one and
         * not the other would be dyed in the bag and grey on the face.
         */
        @SubscribeEvent
        public static void onRegisterItemColors(
                net.minecraftforge.client.event.RegisterColorHandlersEvent.Item event) {
            event.register((stack, layer) -> layer > 0 ? 0xFFFFFF
                            : ((net.minecraft.world.item.DyeableLeatherItem) stack.getItem()).getColor(stack),
                    dev.otectus.mcacrime.item.CrimeItems.masks()
                            .toArray(new net.minecraft.world.item.Item[0]));
        }

        /**
         * Tells the client recipe book where Mask Station recipes go: nowhere.
         *
         * <p>They are made at the station's own screen, never through a recipe book. Without a finder
         * the client recipe book cannot place a custom recipe type and logs one "Unknown recipe
         * category" warning per mask recipe every time a player joins a world.
         */
        @SubscribeEvent
        public static void onRegisterRecipeBookCategories(
                net.minecraftforge.client.event.RegisterRecipeBookCategoriesEvent event) {
            event.registerRecipeCategoryFinder(dev.otectus.mcacrime.recipe.CrimeRecipes.MASK_MAKING.get(),
                    recipe -> net.minecraft.client.RecipeBookCategories.UNKNOWN);
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            // Item guidance expands while Shift is held (0.8.0). The check is client-only, so the common
            // tooltip helper is handed it here rather than naming Screen itself.
            dev.otectus.mcacrime.help.TooltipHelp.installExpandedCheck(
                    net.minecraft.client.gui.screens.Screen::hasShiftDown);
            // A key ring shows how many keys are on it (0.7.5 M3.2). The four count textures shipped
            // with the ring and the model that selects between them did not, so until now every ring
            // drew as the same two-key icon whatever it held. The predicate is client-only and reads
            // the same tag the server writes; a ring with more than four keys keeps the four-key art.
            event.enqueueWork(() -> net.minecraft.client.renderer.item.ItemProperties.register(
                    dev.otectus.mcacrime.item.CrimeItems.KEY_RING.get(),
                    dev.otectus.mcacrime.McaCrime.id("keys"),
                    (stack, level, holder, seed) -> Math.min(4,
                            dev.otectus.mcacrime.locks.KeyRingBindings.count(stack.getTag()))));
            // A dyed mask draws its dyed model (0.8.0): a neutral base the colour tints and its details
            // untinted over it, where an undyed one keeps its original art. Every style's model selects
            // the dyed one through this predicate, which reads presence, not value: a mask dyed white
            // is dyed. Registered for the whole catalogue, like the colour handler.
            event.enqueueWork(() -> {
                for (net.minecraft.world.item.Item mask : dev.otectus.mcacrime.item.CrimeItems.masks()) {
                    net.minecraft.client.renderer.item.ItemProperties.register(mask,
                            dev.otectus.mcacrime.McaCrime.id("dyed"),
                            (stack, level, holder, seed) ->
                                    dev.otectus.mcacrime.mask.MaskColors.hasColor(stack) ? 1.0F : 0.0F);
                }
            });
            // The station's screen, bound to the menu type the server opens (0.7.2 §8.1).
            event.enqueueWork(() -> net.minecraft.client.gui.screens.MenuScreens.register(
                    dev.otectus.mcacrime.menu.CrimeMenus.MASK_STATION.get(),
                    dev.otectus.mcacrime.client.screen.MaskStationScreen::new));
            // The frisking screen, bound to the menu the search opens (0.7.5 M5.2).
            event.enqueueWork(() -> net.minecraft.client.gui.screens.MenuScreens.register(
                    dev.otectus.mcacrime.menu.CrimeMenus.FRISKING.get(),
                    dev.otectus.mcacrime.client.screen.FriskingScreen::new));
            event.enqueueWork(() -> ModLoadingContext.get().registerExtensionPoint(
                    ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, parent) -> new CrimeConfigScreen(parent))));
        }
    }

    /**
     * Clears every client cache on disconnect.
     *
     * <p>Without this, joining a second world shows the first world's Heat, band colours, captivity
     * countdown and case file until the server happens to overwrite each one — and anything the new
     * server never sends (because the player is clean there) is never overwritten at all.
     */
    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSelfData.clear();
        ClientBandData.clear();
        ClientCaptiveData.clear();
        ClientActionData.clear();
        ClientChallengeData.clear();
        ClientFriskData.clear();
        ClientCaseData.clear();
        ClientPhysicalRestraintData.clear();
        ClientRestraintRig.clear();
        ClientVillageSecurityData.clear();
        ClientWeaponPolicy.clear();
        dev.otectus.mcacrime.network.ServerRulesView.clear();
        ClientCriminalJobData.clear();
        // The datapack layer belongs to the server that sent it (§3.13). Keeping it would predict the
        // next world's input against the last world's pack.
        dev.otectus.mcacrime.restraint.RestraintProfileOverrides.clear();
        // The close reason belongs to one screen on one server. Carrying it across a disconnect would
        // apply this session's interruption to the next session's first conversation.
        dev.otectus.mcacrime.compat.TownsteadDialogueState.clear();
    }
}
