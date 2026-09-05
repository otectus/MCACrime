package dev.otectus.mcacrime.economy.fence;

import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.dialogue.CrimeDialogueService;
import dev.otectus.mcacrime.dialogue.DialogueEvents;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.economy.Currency;
import dev.otectus.mcacrime.economy.fence.FenceOfferBuilder.FenceOffer;
import dev.otectus.mcacrime.economy.fence.FencePricing.PricingInputs;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.enforcement.OutlawResolver;
import dev.otectus.mcacrime.enforcement.OutlawStatus;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.MerchantMenu;

import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Opening the back room (spec §"Fence behavior and inventory").
 *
 * <p>The screen is vanilla's merchant screen and the offers are built here, per player, at the moment
 * it opens — because the price depends on who is standing there. Karma buys a discount, Heat charges a
 * surcharge, being Wanted charges again, and the fence says so out loud on the way in.
 *
 * <p>Stock is stable between restocks: the offer seed is the fence's identity plus the day it last
 * restocked, so a player cannot reroll a fence's inventory by closing the screen.
 */
public final class FenceTradeService {

    private FenceTradeService() {
    }

    /**
     * @return false when the trade could not be opened, having already told the player why if there
     *         was anything useful to say
     */
    public static boolean open(ServerPlayer player, LivingEntity fence) {
        if (player == null || fence == null || !(player.level() instanceof ServerLevel level)) {
            return false;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return false;
        }
        Currency currency = Currencies.active();
        if (!currency.hasItemForm()) {
            // The merchant screen moves item stacks between slots. A balance that is only a number has
            // nothing to put in one, so the fence closes rather than opening a screen that cannot pay.
            player.sendSystemMessage(Component.translatable("mcacrime.fence.no_item_currency"));
            return false;
        }

        FencePolicy policy = FencePolicy.fromConfig();
        PricingInputs inputs = pricingFor(player);
        long seed = restockSeed(server, level, fence.getUUID(), policy);

        List<FenceOffer> planned = FenceOfferBuilder.build(seed, policy.offerCount(),
                FenceGoodsRegistry.active().tradeable(), inputs, policy);
        FenceMerchant merchant = new FenceMerchant(fence, planned, currency);
        if (!merchant.hasOffers()) {
            player.sendSystemMessage(Component.translatable("mcacrime.fence.no_stock"));
            CrimeDebug.crime("fence {} had no offers to make ({} planned)", fence.getUUID(), planned.size());
            return false;
        }

        Component title = Component.translatable("gui.mcacrime.fence.title",
                McaCompat.getVillagerDisplayName(fence));
        // Set before the menu opens: MerchantMenu.stillValid asks the merchant who it is trading with,
        // and a menu that opens before the answer exists closes itself on the same tick.
        merchant.setTradingPlayer(player);
        OptionalInt containerId = player.openMenu(new SimpleMenuProvider(
                (id, inventory, viewer) -> new MerchantMenu(id, inventory, merchant), title));
        if (containerId.isEmpty()) {
            merchant.setTradingPlayer(null);
            return false;
        }
        player.sendMerchantOffers(containerId.getAsInt(), merchant.getOffers(), 0, 0, false, false);
        greet(level, fence, player, inputs);
        CrimeDebug.crime("fence {} opened for {} with {} offer(s)", fence.getUUID(),
                player.getGameProfile().getName(), merchant.getOffers().size());
        return true;
    }

    /** What this player's standing does to a price. */
    public static PricingInputs pricingFor(ServerPlayer player) {
        OutlawStatus status = OutlawResolver.resolve(player);
        return new PricingInputs(status.karma(), status.heat(), CrimeState.isWanted(player),
                McaCrimeConfig.COMMON.karmaRedThreshold.get(),
                McaCrimeConfig.COMMON.wantedHeatThreshold.get());
    }

    /**
     * The seed the day's stock is drawn from, restocking the fence when its interval has elapsed.
     *
     * <p>Zero interval means every visit re-rolls, which is what a pack asking for a restless black
     * market wants; anything else pins the stock to the day it was drawn.
     */
    private static long restockSeed(MinecraftServer server, ServerLevel level, UUID fenceId, FencePolicy policy) {
        long today = level.getDayTime() / 24000L;
        if (policy.restockIntervalDays() <= 0) {
            return fenceId.hashCode() * 31L + today;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        long lastRestock = data.fenceRestockDay(fenceId);
        if (lastRestock == 0L || today - lastRestock >= policy.restockIntervalDays()) {
            data.setFenceRestockDay(fenceId, today);
            lastRestock = today;
        }
        return fenceId.hashCode() * 31L + lastRestock;
    }

    /**
     * One line on the way in, naming the reason the prices are what they are. Wanted first, then Heat,
     * then criminal standing — the same precedence the multiplier applies, so what the fence says and
     * what it charges can never disagree.
     */
    private static void greet(ServerLevel level, LivingEntity fence, ServerPlayer player,
                              PricingInputs inputs) {
        ResourceLocation event;
        if (inputs.wanted()) {
            event = DialogueEvents.FENCE_GREET_WANTED;
        } else if (FencePricing.heatRisk(inputs) >= 0.5D) {
            event = DialogueEvents.FENCE_GREET_HOT;
        } else if (FencePricing.criminalAffinity(inputs) >= 0.5D) {
            event = DialogueEvents.FENCE_GREET_AFFINITY;
        } else {
            event = DialogueEvents.FENCE_GREET_OUTSIDER;
        }
        CrimeDialogueService.speak(fence, player, event,
                CrimeDialogueService.context(level, fence, player, fence.getUUID(), event));
    }
}
