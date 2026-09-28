package dev.otectus.mcacrime.compat.locksreforged;

import dev.otectus.mcacrime.economy.fence.FenceGoodsRegistry;
import dev.otectus.mcacrime.economy.fence.IllicitGoodsProvider;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Locks Reforged as a source of fence stock (spec section "Locks Reforged compatibility").
 *
 * <p>Locks and lockpicks are the one contraband category this mod does not have to invent: that mod
 * already tiers them by security, and its own documentation treats picks as something the economy
 * hands out. What a fence adds is the other half of that economy -- somewhere to buy a pick without
 * asking a blacksmith, and somewhere to sell a lock nobody should ask where you got.
 *
 * <p><b>No Locks type is named anywhere in this file, and none may be.</b> The items are looked up by
 * registry id and an id nothing registers is skipped in silence, which is what makes the class safe
 * to load whether or not the mod is installed, and makes removing the mod later cost a fence a row
 * rather than corrupting anything. The tier table is therefore plain strings and numbers, and is a
 * pure static so it can be asserted without a registry.
 */
public final class LocksReforgedCompat implements IllicitGoodsProvider {

    /** The Locks Reforged mod id, and the namespace every lookup below uses. */
    public static final String MOD_ID = "locks";

    /** What a fence charges for one Locks Reforged good before any Karma/Heat modifier. */
    public record LocksGood(String id, long basePrice) {
    }

    /** Security tier -> what a lock of that tier is worth. Picks are priced off the same table. */
    private static final Map<String, Long> LOCK_TIERS = new LinkedHashMap<>();
    /** A key is worth what it opens, which is nothing until somebody cuts it. */
    private static final long KEY_PRICE = 3L;
    /** A key that opens everything is the most valuable thing on this counter. */
    private static final long MASTER_KEY_PRICE = 48L;
    /** A pick is worth three quarters of the lock it defeats: always cheaper than what it beats. */
    private static final double PICK_RATIO = 0.75D;

    static {
        LOCK_TIERS.put("wood", 4L);
        LOCK_TIERS.put("copper", 5L);
        LOCK_TIERS.put("iron", 8L);
        LOCK_TIERS.put("steel", 10L);
        LOCK_TIERS.put("gold", 12L);
        LOCK_TIERS.put("diamond", 20L);
        LOCK_TIERS.put("netherite", 32L);
    }

    /** Resolved once: Locks Reforged's own "is anything lockable here" lookup, or null. */
    private static volatile java.lang.reflect.Method intersecting;
    private static volatile boolean intersectingResolved;
    private static volatile boolean ownershipProbeReported;

    /**
     * Whether Locks Reforged already owns a lock covering {@code pos} (§3.16, spec §10.4).
     *
     * <p>Reflective, like everything else about this mod: the method looked up is
     * {@code LocksUtil.intersecting(Level, BlockPos)}, which is that mod's own answer to "which of my
     * lockables cover this block". Asking it is what lets {@code locks.foreignLockPolicy = REFUSE}
     * decline to be a second, contradictory access check on one chest.
     *
     * <p>A probe that cannot resolve reports <b>once</b> and then answers false. False is the honest
     * answer to "I could not determine this": refusing every padlock in the world because a lookup
     * changed shape would be a worse failure than allowing one, and the report is what keeps the
     * degradation visible instead of silent.
     *
     * @param level a {@code Level}; anything else answers false
     * @param pos   a {@code BlockPos}
     */
    public static boolean ownsLock(Object level, Object pos) {
        if (!(level instanceof net.minecraft.world.level.Level world)
                || !(pos instanceof net.minecraft.core.BlockPos blockPos)) {
            return false;
        }
        java.lang.reflect.Method method = resolveIntersecting();
        if (method == null) {
            return false;
        }
        try {
            Object result = method.invoke(null, world, blockPos);
            return result instanceof java.util.stream.Stream<?> stream && stream.findAny().isPresent();
        } catch (Throwable t) {
            reportProbeFailure(t.toString());
            return false;
        }
    }

    private static java.lang.reflect.Method resolveIntersecting() {
        if (intersectingResolved) {
            return intersecting;
        }
        synchronized (LocksReforgedCompat.class) {
            if (intersectingResolved) {
                return intersecting;
            }
            intersectingResolved = true;
            try {
                intersecting = Class.forName("melonslise.locks.common.util.LocksUtil")
                        .getMethod("intersecting", net.minecraft.world.level.Level.class,
                                net.minecraft.core.BlockPos.class);
            } catch (Throwable t) {
                intersecting = null;
                reportProbeFailure(t.toString());
            }
            return intersecting;
        }
    }

    private static void reportProbeFailure(String detail) {
        if (ownershipProbeReported) {
            return;
        }
        ownershipProbeReported = true;
        dev.otectus.mcacrime.McaCrime.LOGGER.warn("MCA: Crime - Locks Reforged is installed but its lock "
                + "lookup could not be reached, so locks.foreignLockPolicy cannot tell which blocks it "
                + "already owns. MCA: Crime locks will be allowed on every supported block. ({})", detail);
    }

    /** Test hook: forgets the resolved probe. */
    public static synchronized void resetOwnershipProbe() {
        intersecting = null;
        intersectingResolved = false;
        ownershipProbeReported = false;
    }

    /**
     * Installs the provider. Called only by {@code LocksReforgedBridge}, and only once the mod is
     * confirmed present and the integration is switched on.
     */
    public static void register() {
        FenceGoodsRegistry.registerProvider(new LocksReforgedCompat());
        CrimeDebug.compat("Locks Reforged illicit-goods provider enabled");
    }

    /**
     * Every Locks Reforged good a fence deals in, priced. Pure: no registry, no mod, no game.
     *
     * <p>Locks by ascending tier, then picks by ascending tier, then keys -- the order a fence's book
     * reads in, and the order the test asserts against.
     */
    public static List<LocksGood> tierTable() {
        List<LocksGood> table = new ArrayList<>();
        LOCK_TIERS.forEach((tier, price) -> table.add(new LocksGood(tier + "_lock", price)));
        LOCK_TIERS.forEach((tier, price) ->
                table.add(new LocksGood(tier + "_lock_pick", Math.max(1L, Math.round(price * PICK_RATIO)))));
        table.add(new LocksGood("key", KEY_PRICE));
        table.add(new LocksGood("key_blank", KEY_PRICE));
        table.add(new LocksGood("key_ring", KEY_PRICE));
        table.add(new LocksGood("master_key", MASTER_KEY_PRICE));
        return table;
    }

    /**
     * Adds whatever of the table this installation actually has.
     *
     * <p>Both directions for everything: the spec asks for picks and locks to be sold and, optionally,
     * bought, and a fence that would sell you a lock but not take one off your hands is a shop rather
     * than a fence. The tags remain the finer control, and the blacklist still beats this.
     */
    @Override
    public void contribute(FenceGoodsRegistry registry) {
        int added = 0;
        for (LocksGood good : tierTable()) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MOD_ID, good.id());
            if (BuiltInRegistries.ITEM.getOptional(id).isEmpty()) {
                continue; // this installation does not have that one; say nothing about it
            }
            registry.contribute(id, good.basePrice(), true, true);
            added++;
        }
        CrimeDebug.compat("Locks Reforged contributed {} good(s) to the fence pool", added);
    }
}
