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
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.List;

/**
 * What a killed villager leaves behind, by profession.
 *
 * <p>{@code enableProfessionDeathDrops} shipped in 0.1.0 and was read by nothing, so a server owner
 * could turn it on and see no difference. The plan (§22.3) says to keep it off until it is tested and
 * then implement it, which is what this is: off by default, and when on, a small profession-appropriate
 * drop.
 *
 * <p>Two deliberate limits. It only fires for a <b>player</b> kill, because the setting is about what
 * murdering a villager yields, not about zombies dropping bread. And the drops are the villager's
 * <em>trade goods</em> rather than anything valuable — a dead farmer leaves wheat, not emeralds. That
 * keeps this from becoming a reason to kill villagers, which is the exact incentive the rest of the mod
 * exists to remove: robbing someone already pays better than killing them, and this must not change
 * that.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class ProfessionDeathDrops {

    /** Ceiling on items from one death, before the profession table is consulted. */
    private static final int MAX_DROPS = 2;

    private ProfessionDeathDrops() {
    }

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (!McaCrimeConfig.COMMON.enableProfessionDeathDrops.get()) {
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
