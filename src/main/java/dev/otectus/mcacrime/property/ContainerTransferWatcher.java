package dev.otectus.mcacrime.property;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Watches one open container for transfers MCA: Crime is prepared to attribute, and refuses to guess
 * about anything else.
 *
 * <h2>Why not open-and-close diffing</h2>
 *
 * <p>§10.2 rules it out in its first sentence, and it is right to: a cook, a hopper, a producer or a
 * second player can change a chest during the same interval, so a net loss between open and close is
 * evidence that something happened and no evidence at all about who. The rule here is a <b>matched
 * pair</b> instead. Once per server tick the container side and the actor side are both re-counted,
 * and a quantity is attributed only when the container lost exactly what this actor gained, in the
 * same tick, of the same item. A hopper emptying the chest produces a loss with no matching gain and
 * charges nobody; two players each match their own gain against the shared loss, which is why F02
 * comes out right.
 *
 * <h2>What it deliberately cannot see</h2>
 *
 * <ul>
 *   <li><b>Throwing from a container slot.</b> The container loses and nothing of the actor's gains —
 *       the item becomes an entity. That is a loss with no matched pair, so it is reported as
 *       unattributed rather than charged. The alternative is a rule that charges a player for a loss
 *       it cannot prove they caused, which is the exact failure §10.2 forbids.</li>
 *   <li><b>Anything but a plain vanilla storage menu.</b> A custom menu, a storage network, a sided
 *       item handler: {@link #supported()} is false, nothing is attributed, and the gap is reported.</li>
 *   <li><b>An identical item leaving the actor in the same tick it arrived.</b> The net counts would
 *       cancel. Bounded by the tick, and the failure is in the safe direction.</li>
 * </ul>
 */
final class ContainerTransferWatcher {

    /**
     * The vanilla storage menus a transfer can be proved through.
     *
     * <p>An explicit list rather than "anything in the inventory package", because that package also
     * holds crafting, anvils, enchanting and brewing, where items are consumed and transformed rather
     * than moved and a matched pair means nothing. §10.2 asks for a narrow, tested set; this is it.
     */
    private static final Set<Class<?>> SUPPORTED_MENUS =
            Set.of(ChestMenu.class, ShulkerBoxMenu.class, HopperMenu.class, DispenserMenu.class);

    /** How many distinct item kinds one tick's diff may produce before the rest are ignored. */
    private static final int MAX_TRANSFERS_PER_TICK = 16;

    private final UUID playerId;
    private final int containerId;
    private final ResourceLocation dimension;
    private final BlockPos container;
    private final boolean supported;
    private final String unsupportedReason;

    private Map<String, Integer> containerCounts;
    private Map<String, Integer> actorCounts;
    private final Map<String, String> labels = new HashMap<>();
    private long lastPolledTick = Long.MIN_VALUE;
    private int sequence;

    private ContainerTransferWatcher(ServerPlayer player, AbstractContainerMenu menu, BlockPos container,
                                     boolean supported, String unsupportedReason) {
        this.playerId = player.getUUID();
        this.containerId = menu.containerId;
        this.dimension = player.serverLevel().dimension().location();
        this.container = container.immutable();
        this.supported = supported;
        this.unsupportedReason = unsupportedReason;
        this.containerCounts = countContainer(player, menu);
        this.actorCounts = countActor(player, menu);
    }

    /** Starts watching, whether or not the menu turns out to be one we can attribute through. */
    static ContainerTransferWatcher open(ServerPlayer player, AbstractContainerMenu menu, BlockPos container) {
        boolean supported = SUPPORTED_MENUS.contains(menu.getClass());
        String reason = supported ? ""
                : menu.getClass().getName().startsWith("net.minecraft.")
                        ? "this vanilla menu is not one MCA: Crime can prove a transfer through"
                        : "a custom container menu (" + menu.getClass().getName() + ") needs its own adapter";
        return new ContainerTransferWatcher(player, menu, container, supported, reason);
    }

    UUID playerId() {
        return playerId;
    }

    BlockPos container() {
        return container;
    }

    ResourceLocation dimension() {
        return dimension;
    }

    boolean supported() {
        return supported;
    }

    String unsupportedReason() {
        return unsupportedReason;
    }

    /** Whether this watcher still describes the menu the player has open. */
    boolean watching(@Nullable ServerPlayer player) {
        return player != null && player.containerMenu != null
                && player.containerMenu.containerId == containerId;
    }

    /**
     * The transfers committed since the last poll.
     *
     * <p>At most one poll per tick: two polls in one tick would split one transfer across two
     * observations and mint two transfer ids for it. The sequence counter restarts each tick, which is
     * what keeps two stacks of the same item moved in the same tick from collapsing into one id.
     */
    List<TransferAttribution.CommittedTransfer> poll(ServerPlayer player, long now) {
        if (player == null || player.containerMenu == null || now == lastPolledTick) {
            return List.of();
        }
        lastPolledTick = now;
        sequence = 0;
        AbstractContainerMenu menu = player.containerMenu;
        Map<String, Integer> nowContainer = countContainer(player, menu);
        Map<String, Integer> nowActor = countActor(player, menu);
        List<TransferAttribution.CommittedTransfer> transfers = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : delta(containerCounts, nowContainer).entrySet()) {
            if (transfers.size() >= MAX_TRANSFERS_PER_TICK) {
                break;
            }
            String fingerprint = entry.getKey();
            int lost = -entry.getValue();
            int gained = valueOf(nowActor, fingerprint) - valueOf(actorCounts, fingerprint);
            if (lost > 0 && gained > 0) {
                transfers.add(transfer(fingerprint, TransferAttribution.Direction.OUT,
                        Math.min(lost, gained), now));
            } else if (lost < 0 && gained < 0) {
                // The container gained what the actor lost: a deposit, a delivery, a return.
                transfers.add(transfer(fingerprint, TransferAttribution.Direction.IN,
                        Math.min(-lost, -gained), now));
            }
        }

        containerCounts = nowContainer;
        actorCounts = nowActor;
        return transfers;
    }

    private TransferAttribution.CommittedTransfer transfer(String fingerprint,
                                                           TransferAttribution.Direction direction,
                                                           int count, long now) {
        return new TransferAttribution.CommittedTransfer(playerId, PropertyActor.Kind.PLAYER, dimension,
                container, direction, labels.getOrDefault(fingerprint, fingerprint), fingerprint, count,
                now, sequence++);
    }

    /** Every fingerprint whose count changed, as {@code now - before}. */
    private static Map<String, Integer> delta(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : before.entrySet()) {
            int change = valueOf(after, entry.getKey()) - entry.getValue();
            if (change != 0) {
                out.put(entry.getKey(), change);
            }
        }
        for (Map.Entry<String, Integer> entry : after.entrySet()) {
            if (!before.containsKey(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private static int valueOf(Map<String, Integer> counts, String key) {
        Integer value = counts.get(key);
        return value == null ? 0 : value;
    }

    /** Everything on the container side of the menu: every slot not backed by the player's inventory. */
    private Map<String, Integer> countContainer(ServerPlayer player, AbstractContainerMenu menu) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Container inventory = player.getInventory();
        for (Slot slot : menu.slots) {
            if (slot == null || slot.container == inventory) {
                continue;
            }
            add(counts, slot.getItem());
        }
        return counts;
    }

    /**
     * Everything the actor could have gained: their whole inventory plus the stack on the cursor.
     *
     * <p>The cursor matters more than it looks. A plain left-click takes a stack out of the chest and
     * puts it nowhere except the cursor, and a rule that only counted the inventory would see a loss
     * with no matching gain and charge nobody for the most ordinary theft there is.
     */
    private Map<String, Integer> countActor(ServerPlayer player, AbstractContainerMenu menu) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Container inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            add(counts, inventory.getItem(i));
        }
        add(counts, menu.getCarried());
        return counts;
    }

    private void add(Map<String, Integer> counts, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        String fingerprint = fingerprint(stack);
        counts.merge(fingerprint, stack.getCount(), Integer::sum);
        labels.putIfAbsent(fingerprint, stack.getHoverName().getString());
    }

    /**
     * The identity two stacks have to share to be the same goods.
     *
     * <p>Registry id plus a hash of the stack's data components. Not reference identity, which cannot
     * survive a stack being split and merged on the way out of a chest, and not full component equality
     * written into the key, which would make the map key unbounded. A hash collision would merge two
     * item kinds within one tick's diff, which at worst attributes the right count to the wrong label.
     *
     * <p><b>Platform divergence.</b> The Forge 1.20.1 baseline hashes {@code ItemStack.getTag()}, the
     * item's NBT. 1.21.1 has no such tag: item data is a {@link DataComponentPatch} instead, so the
     * patch is hashed here. The semantics are the ones the baseline states and the
     * receipt depends on — two stacks of the same item carrying the same data share a fingerprint, and
     * a named or enchanted stack never matches a plain one — so a return still has to match the lot
     * that was taken. The strings themselves are not comparable between the two platforms, which costs
     * nothing: a fingerprint is only ever compared against another one from the same world.
     */
    static String fingerprint(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        DataComponentPatch components = stack.getComponentsPatch();
        String data = components == null || components.isEmpty()
                ? "" : Integer.toHexString(components.hashCode());
        return (id == null ? "unregistered" : id.toString()) + "#" + data;
    }
}
