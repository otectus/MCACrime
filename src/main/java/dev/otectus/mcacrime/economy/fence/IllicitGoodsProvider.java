package dev.otectus.mcacrime.economy.fence;

/**
 * The extension point for stock a fence deals in that this mod does not know about (spec §"Locks
 * Reforged compatibility").
 *
 * <p>Tags already let a pack author add contraband with no Java at all. A provider is for the case
 * tags cannot serve: an optional mod whose items should be priced by tier rather than by one flat
 * default, and only when that mod is actually installed. Registering one is how a guns mod, a
 * contraband mod or a lock mod contributes stock without anybody editing {@code FenceTradeService}.
 *
 * <p>A provider is called on every rebuild — datapack reload and config reload alike — so it must be
 * cheap, must look its items up by registry id rather than caching them across a reload, and must
 * skip in silence whatever it cannot find.
 */
@FunctionalInterface
public interface IllicitGoodsProvider {

    void contribute(FenceGoodsRegistry registry);
}
