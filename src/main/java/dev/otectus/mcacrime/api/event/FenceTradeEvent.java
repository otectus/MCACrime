package dev.otectus.mcacrime.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Fired once per completed trade at a fence (0.5.1 §"Fence behavior and inventory"), after the goods
 * and the money have already changed hands.
 *
 * <p>A notification rather than a veto: the vanilla merchant screen has taken the trade by the time
 * this can fire, so a listener that refused it would be refusing something that has happened. What a
 * listener can do is react — a quest that wanted a lockpick bought, an economy mod tracking the
 * criminal money supply, a faction mod noticing who deals with whom.
 */
public final class FenceTradeEvent extends CrimeEvent {

    private final UUID fence;
    private final ItemStack goods;
    private final long price;
    private final boolean playerSold;

    public FenceTradeEvent(ServerPlayer player, UUID fence, ItemStack goods, long price, boolean playerSold) {
        super(player);
        this.fence = fence;
        this.goods = goods == null ? ItemStack.EMPTY : goods.copy();
        this.price = price;
        this.playerSold = playerSold;
    }

    /** The villager that traded. Its criminal job is {@code FENCE}. */
    public UUID getFence() {
        return fence;
    }

    /** A copy of what moved, so a listener cannot edit the traded stack. */
    public ItemStack getGoods() {
        return goods.copy();
    }

    /** What it went for, in the active currency, after every Karma/Heat modifier. */
    public long getPrice() {
        return price;
    }

    /** True when the player handed the goods over, false when the fence was the one selling. */
    public boolean isPlayerSold() {
        return playerSold;
    }
}
