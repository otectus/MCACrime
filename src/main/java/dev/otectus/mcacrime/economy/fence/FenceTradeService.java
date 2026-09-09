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
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;

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
 * <p>Stock is stable between restocks: the offer seed is the fence's identity plus the stocking it is
 * on, so a player cannot reroll a fence's inventory by closing the screen. From 0.6.0 the uses spent
 * against that stock are persisted too (audit finding B07) — {@link FenceStockRecord} is read here and
 * the offers are built at the counts it holds, so reopening the screen shows what is left rather than
 * a fresh eight of everything.
 */
public final class FenceTradeService {

    private FenceTradeService() {
    }

    /**
     * @return false when the trade could not be opened, having already told the player why if there
     *         was anything useful to say
     */
    public static boolean open(ServerPlayer player, LivingEntity fence) {
        if (!dev.otectus.mcacrime.ai.NpcAwareness.isAwake(fence)) return false;
        if (player == null || fence == null || !(player.level() instanceof ServerLevel level)) {
            return false;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return false;
        }
        if (!ServerMutationGate.allows(server)) {
            // The stock counts cannot be written, so every trade would be untracked. A fence that
            // cannot remember what it sold is a fence with infinite goods.
            player.sendSystemMessage(Component.translatable("mcacrime.readonly"));
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
        CrimeWorldData data = CrimeWorldData.get(server);
        FenceStockRecord stock = stockFor(data, level, fence.getUUID(), policy);
        if (stock == null) {
            return false; // the stock table is full; opening would be a fence with no limit at all
        }
        long seed = fence.getUUID().hashCode() * 31L + stock.epoch();

        List<FenceOffer> planned = FenceOfferBuilder.build(seed, policy.offerCount(),
                FenceGoodsRegistry.active().tradeable(), inputs, policy);
        UUID fenceId = fence.getUUID();
        FenceMerchant merchant = new FenceMerchant(fence, planned, currency, stock,
                policy.offerMaxUses(), data::putFenceStock,
                viewer -> stillFencing(server, data, fenceId));
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
                (id, inventory, viewer) -> new FenceMerchantMenu(id, inventory, merchant), title));
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
     * This fence's stock, restocking it first when its interval has elapsed.
     *
     * <p>A fence that has never opened one gets a record here, and its epoch is seeded from the
     * 0.5.1 {@code fenceRestockDay} stamp so an existing world's fences keep the inventory they had.
     * That stamp is read this once and then ignored: it is an in-game day, and an in-game day moves
     * backwards whenever an operator sets the time, which is why the schedule below is a game time.
     *
     * <p>Zero interval means every visit re-rolls, which is what a pack asking for a restless black
     * market wants; anything else pins the stock until the next restock falls due.
     *
     * @return null when the stock table is full and this fence could not be added to it
     */
    private static FenceStockRecord stockFor(CrimeWorldData data, ServerLevel level, UUID fenceId,
                                             FencePolicy policy) {
        long now = level.getGameTime();
        FenceStockRecord record = data.getFenceStock(fenceId);
        if (record == null) {
            long legacyDay = data.fenceRestockDay(fenceId);
            int epoch = legacyDay > 0L && legacyDay < Integer.MAX_VALUE ? (int) legacyDay : 1;
            record = new FenceStockRecord(fenceId, epoch, nextRestockTick(now, policy));
            McaCrime.LOGGER.info("MCA: Crime is beginning to track stock for fence {}; whatever it sold "
                    + "before this version cannot be reconstructed, so it starts the stocking full.", fenceId);
            return data.putFenceStock(record).stored() ? record : null;
        }
        if (record.dueForRestock(now)) {
            record.restock(nextRestockTick(now, policy));
            data.putFenceStock(record);
        }
        return record;
    }

    /** When the stocking being opened now should be replaced. */
    private static long nextRestockTick(long now, FencePolicy policy) {
        return policy.restockIntervalDays() <= 0
                ? now // due again on the next opening: a market that re-rolls every visit
                : now + policy.restockIntervalDays() * 24000L;
    }

    /** Whether the villager behind the counter is still a fence who is free to trade. */
    private static boolean stillFencing(MinecraftServer server, CrimeWorldData data, UUID fenceId) {
        return !data.isCaptive(fenceId)
                && WorldCriminalJobService.of(server).get(fenceId) == CriminalJob.FENCE;
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
