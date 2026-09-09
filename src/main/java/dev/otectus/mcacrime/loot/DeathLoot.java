package dev.otectus.mcacrime.loot;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.trading.MerchantOffer;
import java.util.ArrayList;
import java.util.List;

/** Bounded stack arithmetic shared by ordinary merchant and fence death loot. */
public final class DeathLoot {
    private DeathLoot() {}

    public static int tradeDropCount(MerchantOffer offer) {
        return tradeDropCount(offer.getResult().getCount(), offer.getMaxUses(), offer.getUses());
    }

    public static int tradeDropCount(int resultCount, int maxUses, int uses) {
        // A stocked offer leaves one purchase worth of output, regardless of remaining uses.
        return Math.max(0, uses) < Math.max(0, maxUses) ? Math.max(0, resultCount) : 0;
    }

    /** Splits at the item's actual maximum, retaining enchantments, names, damage and custom data. */
    public static List<ItemStack> split(ItemStack prototype, long count, int stackBudget) {
        List<ItemStack> drops = new ArrayList<>();
        if (prototype.isEmpty()) return drops;
        int maximum = Math.max(1, prototype.getMaxStackSize());
        while (count > 0 && drops.size() < Math.max(0, stackBudget)) {
            int n = (int) Math.min(count, maximum);
            ItemStack stack = prototype.copy();
            stack.setCount(n);
            drops.add(stack);
            count -= n;
        }
        return drops;
    }

    public static boolean vanishes(ItemStack stack) {
        return EnchantmentHelper.hasVanishingCurse(stack);
    }

    /** Consume matching existing drops once, so identical items in separate slots remain separate. */
    public static ItemStack missingEquipment(ItemStack equipment, List<ItemStack> alreadyDropped) {
        ItemStack missing = equipment.copy();
        for (ItemStack existing : alreadyDropped) {
            if (!ItemStack.isSameItemSameTags(equipment, existing)) continue;
            int covered = Math.min(missing.getCount(), existing.getCount());
            missing.shrink(covered);
            existing.shrink(covered);
            if (missing.isEmpty()) break;
        }
        return missing;
    }
}
