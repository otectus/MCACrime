package dev.otectus.mcacrime.gametest;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.economy.fence.*;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import dev.otectus.mcacrime.loot.VillagerDeathLoot;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Real MCA die() calls must preserve gear before MCA clears it, without duplicating its inventory. */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeathLootGameTests {
    private DeathLootGameTests() {}

    @SuppressWarnings("unchecked")
    private static Villager villager(GameTestHelper helper) {
        var type = (EntityType<? extends Mob>) BuiltInRegistries.ENTITY_TYPE.getOptional(
                ResourceLocation.fromNamespaceAndPath("mca", "male_villager")).orElseThrow();
        var villager = (Villager) helper.spawnWithNoFreeWill(type, new BlockPos(6, 1, 6));
        villager.setAge(0);
        villager.getInventory().clearContent();
        for (var slot : EquipmentSlot.values()) villager.setItemSlot(slot, ItemStack.EMPTY);
        villager.setOffers(new MerchantOffers());
        helper.assertTrue(McaCrimeConfig.COMMON.dropVillagerEquipment.get()
                && McaCrimeConfig.COMMON.dropVillagerTradeStock.get(), "Default death loot must be enabled");
        return villager;
    }

    private static List<ItemEntity> loot(GameTestHelper helper, Villager villager) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, villager.getBoundingBox().inflate(3));
    }

    private static int count(List<ItemEntity> loot, Item item) {
        return loot.stream().map(ItemEntity::getItem).filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static void kill(GameTestHelper helper, Villager villager) {
        villager.invulnerableTime = 0;
        villager.hurt(helper.getLevel().damageSources().genericKill(), Float.MAX_VALUE);
        helper.assertTrue(!villager.isAlive(), "Fixture did not die");
    }

    private static void clean(GameTestHelper helper, Villager villager) {
        loot(helper, villager).forEach(ItemEntity::discard);
        villager.discard();
    }

    @GameTest(template = "cell_parity")
    public static void actualDeathDropsOnePurchasePerAvailableTradeOnce(GameTestHelper helper) {
        var villager = villager(helper);
        var bread = new MerchantOffer(new ItemCost(Items.EMERALD), new ItemStack(Items.BREAD, 3), 4, 1, 0);
        bread.increaseUses();
        var exhausted = new MerchantOffer(new ItemCost(Items.EMERALD), new ItemStack(Items.GOLD_INGOT, 2), 3, 1, 0);
        exhausted.setToOutOfStock();
        villager.getOffers().add(new MerchantOffer(new ItemCost(Items.EMERALD), new ItemStack(Items.IRON_AXE), 12, 1, 0));
        villager.getOffers().add(bread);
        villager.getOffers().add(exhausted);
        kill(helper, villager);
        var loot = loot(helper, villager);
        helper.assertTrue(count(loot, Items.IRON_AXE) == 1, "A stocked axe trade dropped more than one axe");
        helper.assertTrue(count(loot, Items.BREAD) == 3, "Trade output was multiplied by remaining uses");
        helper.assertTrue(count(loot, Items.GOLD_INGOT) == 0, "Sold-out stock dropped");
        helper.assertTrue(count(loot, Items.EMERALD) == 0, "Trade input cost was invented as loot");
        var repeated = new LivingDropsEvent(villager, helper.getLevel().damageSources().genericKill(), new ArrayList<>(), false);
        VillagerDeathLoot.onDrops(repeated);
        helper.assertTrue(repeated.getDrops().isEmpty() && bread.isOutOfStock(), "Repeated event duplicated stock");
        clean(helper, villager); helper.succeed();
    }

    @GameTest(template = "cell_parity")
    public static void actualDeathPreservesGearWithoutDuplicatingInventory(GameTestHelper helper) {
        var villager = villager(helper);
        var shared = new ItemStack(Items.IRON_SWORD);
        villager.getInventory().setItem(0, shared);
        villager.setItemSlot(EquipmentSlot.MAINHAND, shared);
        villager.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 3));
        var axe = new ItemStack(Items.DIAMOND_AXE);
        axe.set(DataComponents.CUSTOM_NAME, Component.literal("Watchman's Axe"));
        axe.setDamageValue(17);
        var enchantments = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        axe.enchant(enchantments.getOrThrow(Enchantments.SHARPNESS), 3);
        villager.setItemSlot(EquipmentSlot.OFFHAND, axe);
        var helmet = new ItemStack(Items.IRON_HELMET);
        helmet.enchant(enchantments.getOrThrow(Enchantments.VANISHING_CURSE), 1);
        villager.setItemSlot(EquipmentSlot.HEAD, helmet);
        kill(helper, villager);
        var loot = loot(helper, villager);
        helper.assertTrue(count(loot, Items.IRON_SWORD) == 1, "Inventory-backed sword duplicated or disappeared");
        helper.assertTrue(count(loot, Items.DIAMOND) == 3, "MCA carried inventory was changed");
        helper.assertTrue(count(loot, Items.DIAMOND_AXE) == 1, "Cleared equipment was not recovered exactly once");
        helper.assertTrue(loot.stream().anyMatch(drop -> ItemStack.isSameItemSameComponents(drop.getItem(), axe)),
                "Gear lost name, durability or enchantments");
        helper.assertTrue(count(loot, Items.IRON_HELMET) == 0, "Curse of Vanishing was ignored");
        clean(helper, villager); helper.succeed();
    }

    @GameTest(template = "cell_parity")
    public static void mobLootRuleSuppressesAddedGearAndTrades(GameTestHelper helper) {
        var villager = villager(helper);
        villager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_AXE));
        villager.getOffers().add(new MerchantOffer(new ItemCost(Items.EMERALD), new ItemStack(Items.BREAD), 4, 1, 0));
        var rule = helper.getLevel().getGameRules().getRule(GameRules.RULE_DOMOBLOOT);
        boolean before = rule.get();
        try {
            rule.set(false, helper.getLevel().getServer());
            kill(helper, villager);
            helper.assertTrue(loot(helper, villager).isEmpty(), "Crime added loot with doMobLoot disabled");
        } finally { rule.set(before, helper.getLevel().getServer()); clean(helper, villager); }
        helper.succeed();
    }

    @GameTest(template = "cell_parity")
    public static void cancelledDeathAndDropsDoNotSpawnCrimeLoot(GameTestHelper helper) {
        var villager = villager(helper);
        villager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_AXE));
        villager.getOffers().add(new MerchantOffer(new ItemCost(Items.EMERALD), new ItemStack(Items.BREAD), 4, 1, 0));
        Consumer<LivingDeathEvent> cancelDeath = event -> { if (event.getEntity() == villager) event.setCanceled(true); };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, LivingDeathEvent.class, cancelDeath);
        try {
            villager.hurt(helper.getLevel().damageSources().genericKill(), Float.MAX_VALUE);
            helper.assertTrue(loot(helper, villager).isEmpty(), "Canceled death still spawned Crime loot");
        } finally { NeoForge.EVENT_BUS.unregister(cancelDeath); }
        // Revive and allow one living tick to invalidate any gear captured for the canceled death.
        villager.setHealth(villager.getMaxHealth());
        helper.runAtTickTime(2, () -> {
            villager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            Consumer<LivingDropsEvent> cancelDrops = event -> {
                if (event.getEntity() == villager) {
                    helper.assertTrue(event.getDrops().stream().noneMatch(drop -> drop.getItem().is(Items.DIAMOND_AXE)),
                            "Canceled death leaked stale equipment into a later death");
                    event.setCanceled(true);
                }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingDropsEvent.class, cancelDrops);
            try {
                kill(helper, villager);
                helper.assertTrue(loot(helper, villager).isEmpty(), "Canceled drops escaped the event collection");
            } finally { NeoForge.EVENT_BUS.unregister(cancelDrops); clean(helper, villager); }
            helper.succeed();
        });
    }

    @GameTest(template = "cell_parity")
    public static void lootSettingsBoundStockAndAllowDisablingAdditions(GameTestHelper helper) {
        var villager = villager(helper);
        var goods = new ItemStack(Items.BREAD, 50);
        goods.set(DataComponents.CUSTOM_NAME, Component.literal("Bakery stock"));
        villager.getOffers().add(new MerchantOffer(new ItemCost(Items.EMERALD), goods, 4, 1, 0));
        villager.getOffers().add(new MerchantOffer(new ItemCost(Items.EMERALD, 2), goods.copy(), 4, 1, 0));
        villager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        var config = McaCrimeConfig.COMMON;
        int budget = config.maxTradeDeathDropStacks.get();
        try {
            config.maxTradeDeathDropStacks.set(1);
            kill(helper, villager);
            var loot = loot(helper, villager);
            helper.assertTrue(count(loot, Items.BREAD) == 50, "Trade-stack cap did not stop after the first output bundle");
            helper.assertTrue(count(loot, Items.IRON_AXE) == 1, "Trade cap suppressed equipment");
            helper.assertTrue(loot.stream().anyMatch(drop -> ItemStack.isSameItemSameComponents(goods, drop.getItem())),
                    "Trade output components were lost");
        } finally { config.maxTradeDeathDropStacks.set(budget); clean(helper, villager); }
        var disabled = villager(helper);
        disabled.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_AXE));
        disabled.getOffers().add(new MerchantOffer(new ItemCost(Items.EMERALD), new ItemStack(Items.BREAD), 4, 1, 0));
        boolean gear = config.dropVillagerEquipment.get();
        boolean stock = config.dropVillagerTradeStock.get();
        try {
            config.dropVillagerEquipment.set(false);
            config.dropVillagerTradeStock.set(false);
            kill(helper, disabled);
            helper.assertTrue(loot(helper, disabled).isEmpty(), "Disabled loot options still added drops");
        } finally {
            config.dropVillagerEquipment.set(gear); config.dropVillagerTradeStock.set(stock);
            clean(helper, disabled);
        }
        helper.succeed();
    }

    @GameTest(template = "cell_parity")
    public static void fenceDeathUsesPersistedStockWithoutRestocking(GameTestHelper helper) {
        var villager = villager(helper);
        var level = helper.getLevel();
        var policy = FencePolicy.fromConfig();
        // Find a stocking with physical goods in the actual loaded registry, including Locks goods.
        int epoch = 1;
        List<FenceOfferBuilder.FenceOffer> offers;
        do {
            offers = FenceOfferBuilder.build(villager.getUUID().hashCode() * 31L + epoch, policy.offerCount(),
                    FenceGoodsRegistry.active().tradeable(), new FencePricing.PricingInputs(0, 0, false, -1, 1), policy);
            if (offers.stream().anyMatch(offer -> !offer.playerSells())) break;
            epoch++;
        } while (epoch < 100);
        var selected = offers.stream().filter(offer -> !offer.playerSells()).findFirst().orElseThrow();
        var stock = new FenceStockRecord(villager.getUUID(), epoch, 0); // overdue, but death must not restock
        for (var offer : offers) {
            String id = FenceStockRecord.offerId(offer.item(), offer.playerSells());
            if (!offer.equals(selected)) stock.exhaust(id, policy.offerMaxUses());
        }
        String id = FenceStockRecord.offerId(selected.item(), false);
        stock.tryConsume(id, policy.offerMaxUses());
        CrimeWorldData.get(level.getServer()).putFenceStock(stock);
        WorldCriminalJobService.of(level.getServer()).set(villager.getUUID(), CriminalJob.FENCE);
        kill(helper, villager);
        var item = BuiltInRegistries.ITEM.get(selected.item());
        helper.assertTrue(count(loot(helper, villager), item) == 1,
                "Fence did not drop exactly one available trade output");
        var loaded = FenceStockRecord.load(CrimeWorldData.get(level.getServer()).getFenceStock(villager.getUUID()).save());
        helper.assertTrue(loaded.epoch() == epoch && loaded.remaining(id, policy.offerMaxUses()) == 0,
                "Fence death restocked or failed to exhaust saved goods");
        clean(helper, villager); helper.succeed();
    }
}
