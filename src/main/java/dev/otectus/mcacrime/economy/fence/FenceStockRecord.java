package dev.otectus.mcacrime.economy.fence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How much of its stock a fence has already sold, persisted (0.6.0, audit finding B07).
 *
 * <p>Before this, a trade's uses lived on the {@code MerchantOffer} objects built when the screen
 * opened, so closing the screen and opening it again handed the player a fresh set of eight uses. A
 * fence's day's stock was therefore unbounded, which is the whole "limited stock" mechanic gone. The
 * counts now live in world data, keyed by the fence's UUID, and the screen is built <em>from</em>
 * them.
 *
 * <p>Two fields beyond the counts earn their place:
 *
 * <ul>
 *   <li>{@code epoch} is the identity of one stocking. It goes up whenever the fence restocks, and an
 *       open menu carries the epoch it was built at — so a screen left open across a restock cannot
 *       spend the new stock at the old prices, and a stale packet cannot spend anything at all.</li>
 *   <li>{@code nextRestockTick} is a game time rather than an in-game day, because the day number a
 *       0.5.1 fence stored moved backwards whenever an operator set the time. {@link #DISABLED}
 *       ({@code -1}) means this fence never restocks on its own.</li>
 * </ul>
 *
 * <p>Mutable, unlike most of what world data holds, because the counts change one trade at a time
 * while the screen is open and the alternative is rebuilding and re-inserting the record on every
 * click. The caller re-puts it through {@code CrimeWorldData.putFenceStock} to mark the store dirty.
 */
public final class FenceStockRecord {

    /** A {@code nextRestockTick} that never arrives. */
    public static final long DISABLED = -1L;

    /** How many of one offer have been taken, and how many were on the counter. */
    public record OfferStock(int uses, int maxUses) {

        public OfferStock {
            uses = Math.max(0, uses);
            maxUses = Math.max(0, maxUses);
        }

        public boolean exhausted() {
            return uses >= maxUses;
        }
    }

    private final UUID fence;
    private int epoch;
    private long nextRestockTick;
    private final Map<String, OfferStock> stock = new LinkedHashMap<>();

    public FenceStockRecord(UUID fence, int epoch, long nextRestockTick) {
        this.fence = fence;
        this.epoch = Math.max(1, epoch);
        this.nextRestockTick = nextRestockTick;
    }

    /**
     * The key one trade is counted under: the item plus the direction it travels in.
     *
     * <p>Not the offer's index in the list, which changes whenever the shuffle does, and not the
     * price, which changes with the player standing there. The same lockpick bought from the fence and
     * sold back to it are two separate trades with separate stock, which is what the two directions
     * are for.
     */
    public static String offerId(ResourceLocation item, boolean playerSells) {
        return item + (playerSells ? "|to_fence" : "|from_fence");
    }

    public UUID fence() {
        return fence;
    }

    /** Which stocking this is. An open menu that disagrees is looking at goods that are gone. */
    public int epoch() {
        return epoch;
    }

    public long nextRestockTick() {
        return nextRestockTick;
    }

    /** Whether a menu built at {@code menuEpoch} is still looking at this stocking. */
    public boolean isCurrent(int menuEpoch) {
        return menuEpoch == epoch;
    }

    /** How many of this offer have already been taken. */
    public int uses(String offerId) {
        OfferStock entry = stock.get(offerId);
        return entry == null ? 0 : entry.uses();
    }

    /** How many are left, given the maximum this stocking was built with. */
    public int remaining(String offerId, int maxUses) {
        return Math.max(0, Math.max(0, maxUses) - uses(offerId));
    }

    /**
     * Takes one, if there is one to take.
     *
     * @return false when this offer is already exhausted, in which case nothing was changed and the
     *         trade must not complete
     */
    public boolean tryConsume(String offerId, int maxUses) {
        if (offerId == null || maxUses <= 0) {
            return false;
        }
        int used = uses(offerId);
        if (used >= maxUses) {
            return false;
        }
        stock.put(offerId, new OfferStock(used + 1, maxUses));
        return true;
    }

    /** Whether {@code gameTime} has reached the restock this fence is waiting for. */
    public boolean dueForRestock(long gameTime) {
        return nextRestockTick != DISABLED && gameTime >= nextRestockTick;
    }

    /** New goods on the counter: a new epoch, no uses spent, and the next restock scheduled. */
    public void restock(long nextRestockTick) {
        this.epoch = epoch == Integer.MAX_VALUE ? 1 : epoch + 1;
        this.nextRestockTick = nextRestockTick;
        stock.clear();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("fence", fence);
        tag.putInt("epoch", epoch);
        tag.putLong("nextRestockTick", nextRestockTick);
        ListTag entries = new ListTag();
        stock.forEach((offerId, entry) -> {
            CompoundTag row = new CompoundTag();
            row.putString("offer", offerId);
            row.putInt("uses", entry.uses());
            row.putInt("maxUses", entry.maxUses());
            entries.add(row);
        });
        tag.put("stock", entries);
        return tag;
    }

    /** Throws on a tag with no fence id; the caller skips that one entry. */
    public static FenceStockRecord load(CompoundTag tag) {
        FenceStockRecord record = new FenceStockRecord(tag.getUUID("fence"), tag.getInt("epoch"),
                tag.contains("nextRestockTick") ? tag.getLong("nextRestockTick") : DISABLED);
        ListTag entries = tag.getList("stock", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag row = entries.getCompound(i);
            String offerId = row.getString("offer");
            if (!offerId.isEmpty()) {
                record.stock.put(offerId, new OfferStock(row.getInt("uses"), row.getInt("maxUses")));
            }
        }
        return record;
    }
}
