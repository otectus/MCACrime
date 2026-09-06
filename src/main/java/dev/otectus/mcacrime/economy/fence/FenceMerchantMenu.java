package dev.otectus.mcacrime.economy.fence;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantMenu;

/**
 * Vanilla's merchant screen with a fence's validity rules behind it (0.6.0, audit finding B07).
 *
 * <p>A subclass rather than a new menu type, and deliberately: {@code getType()} is still
 * {@code MenuType.MERCHANT}, so the client opens the ordinary trading screen and no client code,
 * packet or screen registration is involved. All this changes is the answer to
 * {@link #stillValid(Player)}, which vanilla asks every tick and which vanilla answers with "is this
 * the player it is trading with" — an answer a fence that has died, been arrested, stopped being a
 * fence or been left behind in another dimension still gives cheerfully.
 */
public final class FenceMerchantMenu extends MerchantMenu {

    private final FenceMerchant merchant;

    public FenceMerchantMenu(int containerId, Inventory inventory, FenceMerchant merchant) {
        super(containerId, inventory, merchant);
        this.merchant = merchant;
    }

    @Override
    public boolean stillValid(Player player) {
        return super.stillValid(player) && merchant.stillValid(player);
    }
}
