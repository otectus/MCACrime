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
 */
public final class FenceMerchant implements Merchant {

    /** How many times one trade may be repeated before the fence is out of that stock for the day. */
    private static final int MAX_USES = 8;

    private final LivingEntity fence;
    private final MerchantOffers offers = new MerchantOffers();
    /** Which of our offers a vanilla offer is, so a completed trade can be described without guessing. */
    private final Map<MerchantOffer, FenceOffer> origins = new IdentityHashMap<>();

    @Nullable
    private Player tradingPlayer;

    public FenceMerchant(LivingEntity fence, List<FenceOffer> planned, Currency currency) {
        this.fence = fence;
        for (FenceOffer offer : planned) {
            MerchantOffer built = toVanilla(offer, currency);
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
    private static MerchantOffer toVanilla(FenceOffer offer, Currency currency) {
        Item item = ForgeRegistries.ITEMS.getValue(offer.item());
        if (item == null) {
            return null;
        }
        List<ItemStack> money = currency.toStacks(offer.price());
        if (money.isEmpty()) {
            return null;
        }
        ItemStack goods = new ItemStack(item, 1);
        if (offer.playerSells()) {
            if (money.size() != 1) {
                return null; // the payout is one slot; a price too large for it is not offered
            }
            return new MerchantOffer(goods, ItemStack.EMPTY, money.get(0), 0, MAX_USES, 0, 0.0F);
        }
        if (money.size() > 2) {
            return null; // two cost slots, no more
        }
        ItemStack costB = money.size() > 1 ? money.get(1) : ItemStack.EMPTY;
        return new MerchantOffer(money.get(0), costB, goods, 0, MAX_USES, 0, 0.0F);
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
        offer.increaseUses();
        FenceOffer origin = origins.get(offer);
        if (!(tradingPlayer instanceof ServerPlayer player) || origin == null) {
            return;
        }
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
