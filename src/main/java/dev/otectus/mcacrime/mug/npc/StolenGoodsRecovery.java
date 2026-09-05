package dev.otectus.mcacrime.mug.npc;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.economy.Currency;
import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * A dead thief gives back what they took (spec §"Stolen-goods recovery").
 *
 * <p>This is the recovery path the whole ledger exists for: kill the villager who robbed you and your
 * property is on the ground, as the thing that was taken rather than a plausible copy of it. The
 * claim happens first and exactly once — {@code claimAll} removes the entries before returning them —
 * so a second death event, a mod that re-fires drops, or a reload between the drop and the save
 * cannot mint a second sword.
 *
 * <p>Highest priority so the entries are claimed before any other listener can see, cancel or
 * duplicate the drop list, and gated on the ledger first so an ordinary villager death costs one map
 * lookup.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class StolenGoodsRecovery {

    private StolenGoodsRecovery() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDrops(LivingDropsEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead == null || !(dead.level() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        UUID thief = dead.getUUID();
        CrimeWorldData data = CrimeWorldData.get(server);
        if (data.stolenGoodsByThief(thief).isEmpty()
                && !WorldCriminalJobService.of(server).isCriminal(thief)) {
            return;
        }
        List<StolenGoodsRecord> claimed = StolenGoodsLedger.claimAll(server, thief);
        if (claimed.isEmpty()) {
            return;
        }

        long currency = 0L;
        int items = 0;
        for (StolenGoodsRecord record : claimed) {
            if (record.hasStack()) {
                ItemStack stack = record.stack();
                if (!stack.isEmpty()) {
                    event.getDrops().add(drop(level, dead, stack));
                    items++;
                }
            }
            currency += Math.max(0L, record.currency());
        }
        if (currency > 0L) {
            payCurrency(level, dead, event, currency, killerOf(event));
        }
        CrimeDebug.crime("stolen goods recovered from dead thief {}: {} item(s), {} currency",
                thief, items, currency);
    }

    /**
     * Currency comes back as coin when the active currency has one, and as a direct credit to whoever
     * did the killing when it does not.
     *
     * <p>An abstract balance cannot fall on the ground, and quietly deleting it would make an economy
     * mod's currency the one thing a thief can steal irrecoverably. Crediting the killer is the
     * spec's "direct reward mechanism"; with no player killer there is nobody to credit, which is
     * worth a line in the log rather than a silent loss.
     */
    private static void payCurrency(ServerLevel level, LivingEntity dead, LivingDropsEvent event,
                                    long amount, @Nullable ServerPlayer killer) {
        Currency currency = Currencies.active();
        if (currency.hasItemForm()) {
            for (ItemStack stack : currency.toStacks(amount)) {
                if (stack != null && !stack.isEmpty()) {
                    event.getDrops().add(drop(level, dead, stack));
                }
            }
            return;
        }
        if (killer != null) {
            currency.credit(killer, amount, TransactionReason.RECOVERY);
            return;
        }
        CrimeDebug.crime("stolen currency ({}) had no item form and no player killer to credit", amount);
    }

    private static ItemEntity drop(ServerLevel level, LivingEntity dead, ItemStack stack) {
        ItemEntity entity = new ItemEntity(level, dead.getX(), dead.getY(0.5D), dead.getZ(), stack);
        entity.setDefaultPickUpDelay();
        return entity;
    }

    @Nullable
    private static ServerPlayer killerOf(LivingDropsEvent event) {
        Entity attacker = event.getSource() == null ? null : event.getSource().getEntity();
        return attacker instanceof ServerPlayer player ? player : null;
    }
}
