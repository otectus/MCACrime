package dev.otectus.mcacrime.economy.fence;

import dev.otectus.mcacrime.item.CrimeItemTags;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Everything a fence deals in, assembled from three sources that never argue (spec §"Fence behavior
 * and inventory").
 *
 * <p>The tags are the public datapack API and decide <em>what</em> is contraband;
 * {@code data/&lt;ns&gt;/mcacrime/fence_prices/*.json} decides what it is worth; an
 * {@link IllicitGoodsProvider} contributes both for an optional mod this one cannot name. The
 * blacklist beats all three, because a server owner banning an item from the criminal economy must
 * not have to find which of the three put it there.
 *
 * <p>An instance is a plain mutable collection of ids with no Minecraft in it, which is what lets the
 * merge rules be tested; the static half owns the live one and is the only part that touches
 * registries.
 */
public final class FenceGoodsRegistry {

    /** The live goods list. Replaced wholesale on rebuild so no reader ever sees a half-built one. */
    private static volatile FenceGoodsRegistry active = new FenceGoodsRegistry();

    private static final List<IllicitGoodsProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private final Map<ResourceLocation, FenceGood> goods = new LinkedHashMap<>();
    private final Set<ResourceLocation> blacklist = new LinkedHashSet<>();

    public static FenceGoodsRegistry active() {
        return active;
    }

    /**
     * Adds a source of stock. Idempotent per provider class: common setup and a config reload both
     * reach the bridge that calls this, and a fence stocking two of everything would be the tell.
     */
    public static void registerProvider(IllicitGoodsProvider provider) {
        if (provider == null) {
            return;
        }
        for (IllicitGoodsProvider existing : PROVIDERS) {
            if (existing.getClass() == provider.getClass()) {
                return;
            }
        }
        PROVIDERS.add(provider);
        rebuild();
    }

    /**
     * Rebuilds the live list from tags, prices and providers. Called after every datapack reload; safe
     * to call before tags exist, in which case the price file and the providers still land.
     */
    public static void rebuild() {
        FenceGoodsRegistry built = new FenceGoodsRegistry();
        long defaultPrice = FencePolicy.fromConfig().defaultBasePrice();

        readTag(built, CrimeItemTags.ILLICIT_GOODS, defaultPrice, true, true);
        readTag(built, CrimeItemTags.FENCE_SELLS, defaultPrice, true, false);
        readTag(built, CrimeItemTags.FENCE_BUYS, defaultPrice, false, true);
        blacklistTag(built, CrimeItemTags.FENCE_BLACKLIST);

        for (FencePriceLoader.FencePrice price : FencePriceLoader.prices()) {
            built.contribute(price.item(), price.base(), price.sells(), price.buys(),
                    price.sellsStated(), price.buysStated());
        }
        for (IllicitGoodsProvider provider : PROVIDERS) {
            try {
                provider.contribute(built);
            } catch (Throwable t) {
                CrimeDebug.compat("illicit-goods provider {} threw and was skipped: {}",
                        provider.getClass().getName(), t.toString());
            }
        }
        active = built;
        CrimeDebug.crime("fence goods rebuilt: {} tradeable item(s), {} blacklisted",
                built.snapshot().size(), built.blacklist.size());
    }

    /**
     * Records one item. Called by the loaders above and by every provider; the last price stated wins
     * and the directions accumulate, so a price file may retune what a tag introduced.
     */
    public void contribute(ResourceLocation item, long basePrice, boolean sells, boolean buys) {
        contribute(item, basePrice, sells, buys, false, false);
    }

    /**
     * Records one item, saying which of the two directions the source stated outright.
     *
     * <p>A stated direction replaces what an earlier source contributed; an unstated one merges as
     * before. Only the price file states them, which is what gives a pack the last word over a tag
     * without having to remove the item from the tag as well.
     */
    public void contribute(ResourceLocation item, long basePrice, boolean sells, boolean buys,
                           boolean sellsStated, boolean buysStated) {
        if (item == null) {
            return;
        }
        FenceGood incoming = new FenceGood(item, basePrice, sells, buys);
        goods.merge(item, incoming, (existing, added) -> existing.mergedWith(added)
                .withDirections(sellsStated ? sells : null, buysStated ? buys : null));
    }

    /** Bans an item from the criminal economy however it was contributed. */
    public void blacklist(ResourceLocation item) {
        if (item != null) {
            blacklist.add(item);
        }
    }

    public boolean isBlacklisted(ResourceLocation item) {
        return item != null && blacklist.contains(item);
    }

    /** Everything tradeable, blacklist already applied. */
    public Map<ResourceLocation, FenceGood> snapshot() {
        Map<ResourceLocation, FenceGood> out = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, FenceGood> entry : goods.entrySet()) {
            if (!blacklist.contains(entry.getKey()) && entry.getValue().tradeable()) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    /** {@link #snapshot()} in a stable order, which is what the offer builder seeds its shuffle from. */
    public List<FenceGood> tradeable() {
        List<FenceGood> out = new ArrayList<>(snapshot().values());
        out.sort((a, b) -> a.item().toString().compareTo(b.item().toString()));
        return out;
    }

    public int size() {
        return snapshot().size();
    }

    private static void readTag(FenceGoodsRegistry into, TagKey<Item> tag, long defaultPrice,
                                boolean sells, boolean buys) {
        for (ResourceLocation id : idsIn(tag)) {
            into.contribute(id, defaultPrice, sells, buys);
        }
    }

    private static void blacklistTag(FenceGoodsRegistry into, TagKey<Item> tag) {
        for (ResourceLocation id : idsIn(tag)) {
            into.blacklist(id);
        }
    }

    /**
     * The item ids in one tag, or nothing at all when no datapack has been loaded yet. Tags do not
     * exist before the first reload, and a fence with no stock is a far better failure than a crash
     * during startup.
     */
    private static Collection<ResourceLocation> idsIn(TagKey<Item> tag) {
        List<ResourceLocation> ids = new ArrayList<>();
        try {
            for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
                holder.unwrapKey().map(ResourceKey::location).ifPresent(ids::add);
            }
        } catch (Throwable t) {
            CrimeDebug.crime("fence tag {} could not be read ({}); treating it as empty", tag.location(), t);
        }
        return ids;
    }
}
