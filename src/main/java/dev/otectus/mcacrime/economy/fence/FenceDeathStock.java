package dev.otectus.mcacrime.economy.fence;

import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import dev.otectus.mcacrime.loot.DeathLoot;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/** Uses the same deterministic stocking as the fence menu; buying orders are not goods on hand. */
public final class FenceDeathStock {
    private FenceDeathStock() {}

    public static boolean isFence(ServerLevel level, LivingEntity villager) {
        return WorldCriminalJobService.of(level.getServer()).get(villager.getUUID()) == CriminalJob.FENCE;
    }

    public static List<ItemStack> take(ServerLevel level, LivingEntity fence, int budget) {
        if (!ServerMutationGate.allows(level.getServer())) return List.of();
        var data = CrimeWorldData.get(level.getServer());
        var policy = FencePolicy.fromConfig();
        FenceStockRecord stock = data.getFenceStock(fence.getUUID());
        if (stock == null) {
            long oldDay = data.fenceRestockDay(fence.getUUID());
            int epoch = oldDay > 0 && oldDay < Integer.MAX_VALUE ? (int) oldDay : 1;
            stock = new FenceStockRecord(fence.getUUID(), epoch, FenceStockRecord.DISABLED);
            if (!data.putFenceStock(stock).stored()) return List.of();
        }
        // A death does not restock or reroll yesterday's unsold goods.
        long seed = fence.getUUID().hashCode() * 31L + stock.epoch();
        var offers = FenceOfferBuilder.build(seed, policy.offerCount(), FenceGoodsRegistry.active().tradeable(),
                new FencePricing.PricingInputs(0, 0, false, -1, 1), policy);
        List<ItemStack> drops = new ArrayList<>();
        for (var offer : offers) {
            if (budget <= 0) break;
            if (offer.playerSells()) continue;
            var item = BuiltInRegistries.ITEM.getOptional(offer.item()).orElse(null);
            if (item == null) continue;
            String id = FenceStockRecord.offerId(offer.item(), false);
            var stacks = DeathLoot.split(new ItemStack(item), DeathLoot.tradeDropCount(1, policy.offerMaxUses(), stock.uses(id)), budget);
            if (stacks.isEmpty()) continue;
            drops.addAll(stacks);
            budget -= stacks.size();
            stock.exhaust(id, policy.offerMaxUses());
        }
        data.putFenceStock(stock);
        return drops;
    }
}
