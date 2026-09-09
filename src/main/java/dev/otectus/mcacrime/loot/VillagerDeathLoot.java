package dev.otectus.mcacrime.loot;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.economy.fence.FenceDeathStock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

/** Drops equipment and one output bundle per available trade; MCA continues to own its carried-inventory drop. */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class VillagerDeathLoot {
    private static final Map<LivingEntity, Gear> PENDING = new WeakHashMap<>();
    private static final class Gear {
        final Map<EquipmentSlot, ItemStack> removed = new EnumMap<>(EquipmentSlot.class);
        final Set<ItemStack> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean handled;
    }
    private VillagerDeathLoot() {}

    /** Called before MCA clears equipment in die(), which happens before LivingDeathEvent. */
    public static void beforeEquipmentChange(Mob mob, EquipmentSlot slot, ItemStack replacement) {
        if (mob.level().isClientSide || mob.isAlive() || !replacement.isEmpty()
                || !McaCompat.isMcaVillager(mob) || !McaCrimeConfig.COMMON.dropVillagerEquipment.get()) return;
        ItemStack equipped = mob.getItemBySlot(slot);
        if (equipped.isEmpty() || inventoryOwns(mob, equipped)) return;
        Gear gear = PENDING.computeIfAbsent(mob, ignored -> new Gear());
        if (!gear.handled && gear.identities.add(equipped)) gear.removed.putIfAbsent(slot, equipped.copy());
    }

    private static boolean inventoryOwns(LivingEntity entity, ItemStack stack) {
        if (!(entity instanceof AbstractVillager villager)) return false;
        var inventory = villager.getInventory();
        // MCA equips inventory items by reference. An equal but separate item really is extra loot.
        for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i) == stack) return true;
        return false;
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (!event.getEntity().level().isClientSide && event.getEntity().isAlive()) PENDING.remove(event.getEntity());
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDrops(LivingDropsEvent event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level) || !McaCompat.isMcaVillager(victim)
                || !(victim instanceof AbstractVillager villager)) return;
        Gear gear = PENDING.computeIfAbsent(victim, ignored -> new Gear());
        if (gear.handled) return;
        gear.handled = true;
        if (!level.getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT)) return;

        if (McaCrimeConfig.COMMON.dropVillagerEquipment.get()) {
            // Older/newer MCA builds may leave gear for vanilla. Cover both without duplicating it.
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = victim.getItemBySlot(slot);
                if (!stack.isEmpty() && !inventoryOwns(victim, stack)) gear.removed.putIfAbsent(slot, stack.copy());
            }
            List<ItemStack> existing = new ArrayList<>();
            event.getDrops().forEach(drop -> existing.add(drop.getItem().copy()));
            for (ItemStack stack : gear.removed.values()) {
                if (DeathLoot.vanishes(stack)) continue;
                ItemStack missing = DeathLoot.missingEquipment(stack, existing);
                if (!missing.isEmpty()) add(level, victim, event, List.of(missing));
            }
        }

        if (McaCrimeConfig.COMMON.dropVillagerTradeStock.get() && !villager.isBaby()) {
            int budget = McaCrimeConfig.COMMON.maxTradeDeathDropStacks.get();
            if (FenceDeathStock.isFence(level, villager)) {
                add(level, victim, event, FenceDeathStock.take(level, villager, budget));
            } else {
                // Only current, unlocked offers. Sold-out offers have nothing left to drop.
                for (var offer : villager.getOffers()) {
                    if (budget <= 0) break;
                    var stacks = DeathLoot.split(offer.getResult(), DeathLoot.tradeDropCount(offer), budget);
                    if (stacks.isEmpty()) continue;
                    add(level, victim, event, stacks);
                    budget -= stacks.size();
                    offer.setToOutOfStock();
                }
            }
        }
        gear.removed.clear();
        gear.identities.clear();
    }

    private static void add(ServerLevel level, LivingEntity victim, LivingDropsEvent event, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            ItemEntity drop = new ItemEntity(level, victim.getX(), victim.getY(), victim.getZ(), stack);
            drop.setDefaultPickUpDelay();
            event.getDrops().add(drop);
        }
    }
}
