package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a villager's held item actually came from, so a borrowed prop is not treated as property.
 *
 * <h2>The bug this exists to close</h2>
 *
 * <p>Townstead dresses a villager for their shift: at the top of a work window it takes the matching
 * tool out of their inventory, puts a <em>copy</em> of it in their main hand, and stashes a copy of
 * whatever they were holding in a map of its own. When the shift ends it puts the stash back. Nothing
 * is created or destroyed as far as Townstead is concerned — the hand is a display, and the inventory
 * still owns the real tool.
 *
 * <p>MCA: Crime's death loot cannot see that. Its rule is reference identity
 * ({@code VillagerDeathLoot.inventoryOwns}), and it is deliberately reference identity: MCA equips
 * inventory items <em>by reference</em>, so a stack in the hand that is not the same object as any
 * inventory stack really is extra gear that would otherwise be lost. A Townstead display tool is
 * exactly such a stack, and it is not extra gear — so the villager died holding a hoe, MCA dropped the
 * real hoe out of the inventory, and MCA: Crime dropped a second one that never existed. The mirror
 * image is also true and quieter: the stashed original is in nobody's inventory and in nobody's hand,
 * so when a farmer died mid-shift the sword they had been carrying vanished.
 *
 * <p>Both are answered by knowing which of the two a stack is, which is what this class records.
 *
 * <h2>How it is populated</h2>
 *
 * <p>{@code mixin/townstead/WorkToolProvenanceMixin} redirects the two {@code ItemStack.copy()} calls
 * inside Townstead's work-tool ticker and hands the resulting stacks here, keyed by the villager that
 * the intercepted method's argument identifies. Townstead's own {@code restore} and {@code forget}
 * clear the record. With no Townstead installed nothing is ever recorded, every classification comes
 * back {@link Origin#UNKNOWN} or {@link Origin#INVENTORY_MIRROR}, and the death loot behaves exactly as
 * it did before this class existed.
 *
 * <h2>What is kept, and for how long</h2>
 *
 * <p>Memory only, keyed by UUID, never persisted — a display tool describes the current work shift and
 * a restart has no business inheriting one. The display stack is held through a {@link WeakReference}
 * because only its <em>identity</em> is ever needed: the entity holds the real reference while it is
 * equipped, and once it does not, a collected reference answering "not this one" is the correct
 * answer rather than a lost one. The stashed original is held strongly, because it is the item that
 * has to drop and there is no other copy of it anywhere in the world.
 *
 * <p>The map is bounded. Entries are removed by Townstead's own restore/forget, by
 * {@link #forget(UUID)} on death, and wholesale at server stop; the cap is the last line against a
 * pathological world, and dropping the oldest record only costs that villager the fallback behaviour.
 */
public final class TownsteadEquipmentProvenance {

    /** Where one equipped stack came from. */
    public enum Origin {

        /** A real item the villager owns that no inventory slot holds: their own gear. It drops. */
        PHYSICAL,

        /** The very same object as a stack in the villager's inventory. Whoever drops the inventory drops it. */
        INVENTORY_MIRROR,

        /** A copy Townstead put in the hand for the look of the thing. No physical item exists. */
        TEMPORARY_DISPLAY,

        /** Nothing is known. Falls back to MCA: Crime's own equipment rule, which never deletes anything. */
        UNKNOWN;

        /**
         * Whether MCA: Crime must make sure this stack drops.
         *
         * <p>{@link #UNKNOWN} counts, and that is the whole safety argument: not knowing falls back to
         * the behaviour this mod has always had, which only ever <em>adds</em> a drop and has no path
         * that removes an item from an inventory. The two negatives are the two cases where something
         * else is already responsible — the inventory, or nobody at all.
         */
        public boolean dropsAsEquipment() {
            return this == PHYSICAL || this == UNKNOWN;
        }
    }

    /**
     * The cap. Well past any plausible count of villagers on shift in loaded chunks at once; it exists
     * so a leak cannot grow without bound, not to be reached.
     */
    private static final int MAX_TRACKED = 4096;

    private static final Map<UUID, Entry> TRACKED = new ConcurrentHashMap<>();

    /** One villager's current work-tool swap. Either half may be absent. */
    private static final class Entry {
        private volatile WeakReference<ItemStack> display;
        private volatile ItemStack stashed;
    }

    private TownsteadEquipmentProvenance() {
    }

    // ---------------------------------------------------------------------------------------------
    // Recording — called from inside Townstead, through the mixin
    // ---------------------------------------------------------------------------------------------

    /** Records the copy Townstead is about to put in this villager's hand. */
    public static void displayTool(@Nullable UUID villager, @Nullable ItemStack copy) {
        if (villager == null || copy == null || copy.isEmpty()) {
            return;
        }
        entry(villager).display = new WeakReference<>(copy);
    }

    /** Records the copy Townstead took of whatever the villager was holding before the shift. */
    public static void stashedOriginal(@Nullable UUID villager, @Nullable ItemStack copy) {
        if (villager == null || copy == null || copy.isEmpty()) {
            return;
        }
        entry(villager).stashed = copy;
    }

    /** Drops everything known about one villager. Townstead's restore and forget both land here. */
    public static void forget(@Nullable UUID villager) {
        if (villager != null) {
            TRACKED.remove(villager);
        }
    }

    /** Drops every record. Server stop, and tests. */
    public static void clearAll() {
        TRACKED.clear();
    }

    /** Whether anything is recorded for this villager. Diagnostics and tests. */
    public static boolean tracked(@Nullable UUID villager) {
        return villager != null && TRACKED.containsKey(villager);
    }

    /** How many villagers are being tracked. Diagnostics and tests. */
    public static int size() {
        return TRACKED.size();
    }

    private static Entry entry(UUID villager) {
        Entry existing = TRACKED.get(villager);
        if (existing != null) {
            return existing;
        }
        if (TRACKED.size() >= MAX_TRACKED) {
            // Losing a record costs that villager the fallback behaviour and nothing else, which is a
            // far better outcome than an unbounded map in somebody else's tick.
            TRACKED.keySet().stream().findFirst().ifPresent(TRACKED::remove);
        }
        return TRACKED.computeIfAbsent(villager, ignored -> new Entry());
    }

    // ---------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------

    /**
     * Whether the operator has both the integration and this switch on.
     *
     * <p>Read at the point of use rather than cached, so a config reload takes effect without a
     * restart, and wrapped because a unit test and a dedicated CLI have no config at all. Off reads as
     * "do not consult the record", which leaves the death loot exactly as it behaves without Townstead.
     */
    public static boolean active() {
        try {
            return McaCrimeConfig.COMMON.townsteadEnabled.get()
                    && McaCrimeConfig.COMMON.townsteadEquipmentProvenance.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Where one equipped stack came from.
     *
     * <p>Order matters. Inventory ownership is checked first and without regard to Townstead, because
     * it is the strongest fact available and it is true whether or not a companion is installed. Only
     * then does the Townstead record get a say, and only for a villager it actually knows about.
     */
    public static Origin classify(@Nullable LivingEntity entity, @Nullable EquipmentSlot slot,
                                  @Nullable ItemStack stack) {
        if (entity == null || stack == null || stack.isEmpty()) {
            return Origin.UNKNOWN;
        }
        boolean inventoryOwned = inventoryOwns(entity, stack);
        if (!active()) {
            // Without the switch, the only thing this class is allowed to say is what the inventory
            // says -- which is precisely MCA: Crime's pre-Townstead rule.
            return classify(inventoryOwned, false, false);
        }
        Entry entry = TRACKED.get(entity.getUUID());
        if (entry == null) {
            return classify(inventoryOwned, false, false);
        }
        WeakReference<ItemStack> reference = entry.display;
        ItemStack display = reference == null ? null : reference.get();
        // The hand, and only the hand: Townstead swaps the main-hand item and nothing else, so a boot
        // that happened to be the same object as a remembered display copy is not a display copy.
        boolean isDisplay = display != null && display == stack
                && (slot == null || slot == EquipmentSlot.MAINHAND);
        return classify(inventoryOwned, true, isDisplay);
    }

    /**
     * The decision itself, with every lookup already done.
     *
     * <p>Separated so the rule can be stated and tested as a rule rather than inferred from a world
     * with a settlement mod in it.
     *
     * @param inventoryOwned whether some inventory slot holds this very object
     * @param tracked        whether Townstead's work-tool state for this villager is known
     * @param display        whether this very object is the copy Townstead put in the hand
     */
    public static Origin classify(boolean inventoryOwned, boolean tracked, boolean display) {
        if (inventoryOwned) {
            return Origin.INVENTORY_MIRROR;
        }
        if (!tracked) {
            return Origin.UNKNOWN;
        }
        return display ? Origin.TEMPORARY_DISPLAY : Origin.PHYSICAL;
    }

    /** The stash Townstead is holding for this villager, or an empty stack. */
    public static ItemStack stashedOriginal(@Nullable UUID villager) {
        if (villager == null) {
            return ItemStack.EMPTY;
        }
        Entry entry = TRACKED.get(villager);
        ItemStack stashed = entry == null ? null : entry.stashed;
        return stashed == null ? ItemStack.EMPTY : stashed;
    }

    /**
     * The stashed original, if it is a real item that nothing else is going to drop.
     *
     * <p>Three things have to be true, and each rules out a different way of dropping the same item
     * twice: the switch is on, nothing in the inventory is that object, and the villager is not still
     * holding it. Townstead only ever stashes a fresh copy, so the last two are belt and braces — but
     * the cost of being wrong here is a duplicated item, which is the bug this whole class is fixing,
     * so they are checked rather than reasoned about.
     *
     * <p>Deliberately identity-based and never equality-based. Two villagers, or one villager with two
     * identical iron hoes, must produce two drops: a stash that <em>looks</em> like an inventory item
     * is still a separate item, and collapsing them would delete one.
     */
    public static ItemStack unclaimedStash(@Nullable LivingEntity entity) {
        if (entity == null || !active()) {
            return ItemStack.EMPTY;
        }
        ItemStack stashed = stashedOriginal(entity.getUUID());
        if (stashed.isEmpty() || inventoryOwns(entity, stashed)) {
            return ItemStack.EMPTY;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (entity.getItemBySlot(slot) == stashed) {
                return ItemStack.EMPTY;
            }
        }
        return stashed;
    }

    /**
     * Whether a villager's own inventory holds this very object.
     *
     * <p>Reference identity on purpose, and the single implementation of that rule in the mod. MCA
     * equips inventory items by reference, so an <em>equal</em> stack in the hand is a second item and
     * an <em>identical</em> one is the same item seen twice. Comparing by equality here would delete
     * the second of two identical tools.
     */
    public static boolean inventoryOwns(@Nullable LivingEntity entity, @Nullable ItemStack stack) {
        if (!(entity instanceof AbstractVillager villager) || stack == null || stack.isEmpty()) {
            return false;
        }
        var inventory = villager.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i) == stack) {
                return true;
            }
        }
        return false;
    }
}
