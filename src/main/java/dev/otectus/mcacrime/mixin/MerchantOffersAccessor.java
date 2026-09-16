package dev.otectus.mcacrime.mixin;

import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import javax.annotation.Nullable;

/**
 * Reads {@code AbstractVillager.offers} without creating it.
 *
 * <p>{@code getOffers()} is not a getter: when the field is null it builds a fresh {@code
 * MerchantOffers} and calls {@code updateTrades}, so asking a villager whether it has trades gives it
 * some. The occupation transaction has to tell "never traded" from "has an empty trade list", because
 * a profession change nulls the field and restoring an empty list instead of null would leave a
 * rolled-back villager permanently unable to acquire trades.
 *
 * <p>An accessor, not an injection: no vanilla behaviour changes and nothing runs at runtime beyond
 * the field read.
 */
@Mixin(AbstractVillager.class)
public interface MerchantOffersAccessor {

    @Nullable
    @Accessor("offers")
    MerchantOffers mcacrime$rawOffers();

    @Accessor("offers")
    void mcacrime$setRawOffers(@Nullable MerchantOffers offers);
}
