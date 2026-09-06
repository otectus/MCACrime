package dev.otectus.mcacrime.economy.fence;

import dev.otectus.mcacrime.api.event.FenceTradeEvent;
import dev.otectus.mcacrime.economy.Currency;
import dev.otectus.mcacrime.economy.fence.FenceOfferBuilder.FenceOffer;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The trading side of a fence: a vanilla {@link Merchant} that is not an entity.
 *
 * <p>Implementing the interface rather than making the villager one is what keeps MCA out of this
 * entirely. The screen, the slots, the click validation and the network protocol are vanilla's; all
 * this supplies is a list of offers and somewhere for the result of a completed trade to go. The
 * villager is held only for identity and for the trade sound.
 *
 * <p>Every offer is priced in the active {@code Currency}'s item form, which is why
 * {@code FenceTradeService} refuses to open at all for a currency that has none: the merchant screen
 * moves item stacks, and an abstract balance has nothing to put in the slot.
 *
 * <p>0.6.0 moves the uses off these objects and into {@link FenceStockRecord} (audit finding B07).
 * The offers are still built per opening, but they are built <em>at</em> the persisted count, and a
 * completed trade only completes once the record has been decremented — so closing the screen and
 * opening it again is no longer how a player gets eight more of everything.
 */
public final class FenceMerchant implements Merchant {

    /** How far a player may wander from the counter before the screen closes itself. */
    private static final double TRADE_RANGE = 8.0D;

    private final LivingEntity fence;
    /** The persisted counts these offers were built from, and the one place a use is spent. */
    private final FenceStockRecord stock;
    /** Which stocking the open screen belongs to; a trade against any other epoch is stale. */
    private final int epoch;
    private final int maxUses;
    /** Told whenever a use is spent, so the store is marked dirty by whoever owns it. */
    private final Consumer<FenceStockRecord> onSpend;
    private final MerchantOffers offers = new MerchantOffers();
    /** Which of our offers a vanilla offer is, so a completed trade can be described without guessing. */
    private final Map<MerchantOffer, FenceOffer> origins = new IdentityHashMap<>();
    /** Whether the fence is still somebody a player may trade with. Re-asked on every menu tick. */
    private final Predicate<Player> counterOpen;

    @Nullable
    private Player tradingPlayer;

    public FenceMerchant(LivingEntity fence, List<FenceOffer> planned, Currency currency,
                         FenceStockRecord stock, int maxUses, Consumer<FenceStockRecord> onSpend,
                         Predicate<Player> counterOpen) {
        this.fence = fence;
        this.stock = stock;
        this.epoch = stock.epoch();
        this.maxUses = Math.max(1, maxUses);
        this.onSpend = onSpend;
        this.counterOpen = counterOpen;
        for (FenceOffer offer : planned) {
            int used = stock.uses(FenceStockRecord.offerId(offer.item(), offer.playerSells()));
            if (used >= this.maxUses) {
                continue; // sold out this stocking; the row is simply not on the counter
            }
            MerchantOffer built = toVanilla(offer, currency, used, this.maxUses);
            if (built != null) {
                offers.add(built);
                origins.put(built, offer);
            }
        }
    }

    /** The villager behind the counter. */
    public UUID fenceId() {
        return fence.getUUID();
    }

    public boolean hasOffers() {
        return !offers.isEmpty();
    }

    /**
     * Turns one planned trade into a vanilla offer, or {@code null} when it cannot be one.
     *
     * <p>Two things make an offer impossible, and both are silent on purpose. The item may belong to a
     * mod that is no longer installed — the spec's "removing Locks later does not corrupt world data"
     * case, which costs the fence a row and nothing else. Or the price may not fit the slots: a trade
     * takes at most two cost stacks and pays out exactly one, so a price larger than that is refused
     * rather than quietly rounded down to something the player would notice was wrong.
     */
    @Nullable
    private static MerchantOffer toVanilla(FenceOffer offer, Currency currency, int uses, int maxUses) {
        Item item = ForgeRegistries.ITEMS.getValue(offer.item());
        if (item == null) {
            return null;
        }
        // Asked before the stacks are built, never after. A price of a hundred million emeralds is a
        // list of a million stacks, and the offer is refused either way; counting first is what keeps
        // an absurd base price in a datapack from being an allocation instead of a rejection.
        long needed = currency.stacksNeeded(offer.price());
        if (needed <= 0L || needed > (offer.playerSells() ? 1L : 2L)) {
            return null;
        }
        List<ItemStack> money = currency.toStacks(offer.price());
        if (money.isEmpty() || money.size() > 2) {
            return null;
        }
        ItemStack goods = new ItemStack(item, 1);
        if (offer.playerSells()) {
            if (money.size() != 1) {
                return null; // the payout is one slot; a price too large for it is not offered
            }
            return new MerchantOffer(goods, ItemStack.EMPTY, money.get(0), uses, maxUses, 0, 0.0F);
        }
        ItemStack costB = money.size() > 1 ? money.get(1) : ItemStack.EMPTY;
        return new MerchantOffer(money.get(0), costB, goods, uses, maxUses, 0, 0.0F);
    }

    /**
     * Whether the screen this merchant is behind may stay open (0.6.0, audit finding B07).
     *
     * <p>{@code MerchantMenu.stillValid} asks only whether the merchant is trading with this player,
     * which a fence that has died, been arrested, stopped being a fence or been left half a village
     * behind still answers yes to. Everything that can change underneath an open screen is re-asked
     * here, the caller supplying the parts that need a server. The stocking is checked too: a screen
     * built before a restock is looking at goods that no longer exist, and closes rather than
     * spending the new ones.
     */
    public boolean stillValid(@Nullable Player player) {
        if (!(player instanceof ServerPlayer) || player != tradingPlayer) {
            return false;
        }
        if (!fence.isAlive() || fence.level() != player.level()) {
            return false;
        }
        if (fence.distanceToSqr(player) > TRADE_RANGE * TRADE_RANGE) {
            return false;
        }
        if (!stock.isCurrent(epoch)) {
            return false;
        }
        return counterOpen.test(player);
    }

    // --- Merchant ---------------------------------------------------------------------------------

    @Override
    public void setTradingPlayer(@Nullable Player player) {
        this.tradingPlayer = player;
    }

    @Nullable
    @Override
    public Player getTradingPlayer() {
        return tradingPlayer;
    }

    @Override
    public MerchantOffers getOffers() {
        return offers;
    }

    @Override
    public void overrideOffers(MerchantOffers replacement) {
        // Only the client-side merchant is ever told its offers; the server built these and keeps them.
    }

    @Override
    public void notifyTrade(MerchantOffer offer) {
        FenceOffer origin = origins.get(offer);
        if (!(tradingPlayer instanceof ServerPlayer player) || origin == null) {
            return;
        }
        // The persisted count decides, and it decides before anything else happens. Vanilla has
        // already refused an out-of-stock offer by the time it calls this, so a refusal here means the
        // record and the screen disagree — a stale menu, or a restock mid-click — and the safe answer
        // is to spend nothing and say nothing.
        String offerId = FenceStockRecord.offerId(origin.item(), origin.playerSells());
        if (!stock.isCurrent(epoch) || !stock.tryConsume(offerId, maxUses)) {
            CrimeDebug.crime("fence {} refused a trade of {}: stock exhausted or the stocking changed",
                    fenceId(), origin.item());
            return;
        }
        offer.increaseUses();
        onSpend.accept(stock);
        ItemStack goods = origin.playerSells() ? offer.getCostA() : offer.getResult();
        MinecraftForge.EVENT_BUS.post(new FenceTradeEvent(player, fenceId(), goods, origin.price(),
                origin.playerSells()));
        CrimeDebug.crime("fence {} traded {} with {} for {} ({})", fenceId(), origin.item(),
                player.getGameProfile().getName(), origin.price(),
                origin.playerSells() ? "player sold" : "player bought");
    }

    @Override
    public void notifyTradeUpdated(ItemStack stack) {
    }

    @Override
    public int getVillagerXp() {
        return 0;
    }

    @Override
    public void overrideXp(int xp) {
    }

    @Override
    public boolean showProgressBar() {
        return false; // a fence has no levels to show off
    }

    @Override
    public SoundEvent getNotifyTradeSound() {
        return SoundEvents.VILLAGER_YES;
    }

    @Override
    public boolean isClientSide() {
        return fence.level().isClientSide();
    }
}
