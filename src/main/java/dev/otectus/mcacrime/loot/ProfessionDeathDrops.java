package dev.otectus.mcacrime.loot;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.compat.ProfessionMatcher;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Legacy small profession drops for player kills. Used only when explicitly enabled and actual
 * trade-stock drops are disabled; {@link VillagerDeathLoot} owns the default equipment/stock policy.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class ProfessionDeathDrops {

    /** Ceiling on items from one death, before the profession table is consulted. */
    private static final int MAX_DROPS = 2;

    private ProfessionDeathDrops() {
    }

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (!McaCrimeConfig.COMMON.enableProfessionDeathDrops.get() || McaCrimeConfig.COMMON.dropVillagerTradeStock.get()
                || !event.getEntity().level().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOMOBLOOT)) {
            return;
        }
        LivingEntity victim = event.getEntity();
        if (!McaCompat.isMcaVillager(victim) || !(victim.level() instanceof ServerLevel level)) {
            return;
        }
        // A player kill only. Automation is excluded for the same reason it is excluded from crime
        // detection: a machine that farms villagers must not be the thing this rewards.
        var killer = event.getSource().getEntity();
        if (!(killer instanceof net.minecraft.server.level.ServerPlayer) || killer instanceof FakePlayer) {
            return;
        }

        Item drop = dropFor(McaCompat.getProfessionId(victim).orElse(null));
        if (drop == null) {
            return;
        }
        // Seeded from the victim's identity so the same villager always leaves the same thing. A
        // re-rollable drop would be a reason to reload a save over a murder.
        RandomSource random = RandomSource.create(victim.getUUID().hashCode());
        int count = 1 + random.nextInt(MAX_DROPS);
        event.getDrops().add(new ItemEntity(level, victim.getX(), victim.getY(), victim.getZ(),
                new ItemStack(drop, count)));
    }

    /**
     * The trade good for a profession, or null for one with nothing sensible to leave.
     *
     * <p>Matched through {@link ProfessionMatcher}, so a server running the {@code LOOSE} mode still
     * gets sensible drops from a modded {@code village_farmer}.
     */
    private static Item dropFor(ResourceLocation profession) {
        if (profession == null) {
            return null;
        }
        for (Entry entry : TABLE) {
            if (ProfessionMatcher.matches(profession, entry.profession())) {
                return entry.item();
            }
        }
        return null;
    }

    private record Entry(String profession, Item item) {
    }

    /**
     * Profession to trade good. Ordered so a more specific name is tested before a broader one, which
     * matters under {@code LOOSE} matching where {@code weaponsmith} also contains {@code smith}.
     */
    private static final List<Entry> TABLE = List.of(
            new Entry("farmer", Items.WHEAT),
            new Entry("baker", Items.BREAD),
            new Entry("fisherman", Items.COD),
            new Entry("shepherd", Items.WHITE_WOOL),
            new Entry("butcher", Items.PORKCHOP),
            new Entry("librarian", Items.BOOK),
            new Entry("cleric", Items.GLASS_BOTTLE),
            new Entry("cartographer", Items.PAPER),
            new Entry("fletcher", Items.ARROW),
            new Entry("leatherworker", Items.LEATHER),
            new Entry("mason", Items.STONE),
            new Entry("weaponsmith", Items.IRON_NUGGET),
            new Entry("armorer", Items.IRON_NUGGET),
            new Entry("toolsmith", Items.IRON_NUGGET),
            new Entry("guard", Items.IRON_NUGGET),
            new Entry("miner", Items.COAL),
            new Entry("smith", Items.IRON_NUGGET));
}
