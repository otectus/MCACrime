package dev.otectus.mcacrime.item.weapon;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.item.CrimeItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * The game-facing half of weapon classification (0.5.0): turns an {@link ItemStack} into a
 * {@link WeaponProbe} and asks {@link WeaponRules} what it is.
 *
 * <p>The split is deliberate. Everything with precedence lives in {@code WeaponRules} and is unit
 * tested; everything here is item introspection that cannot run outside a game, so it is kept as thin
 * as possible and holds no rules of its own.
 *
 * <p>Compiled rules are cached on the identity of the config lists, the same trick
 * {@code EntitySelectors} uses: this is consulted on every villager right-click and on every menu
 * build, and recompiling the lists that often would be waste. A reload that mutates a list in place
 * would not change its identity, so {@link #invalidate()} is called explicitly from the reload event.
 */
public final class WeaponDetector {

    private static volatile List<? extends String> whitelistSource = List.of();
    private static volatile List<? extends String> blacklistSource = List.of();
    private static volatile WeaponRules compiled;

    private WeaponDetector() {
    }

    /** What the stack is, and which rule layer decided it. Never null; an empty stack is NONE. */
    public static WeaponMatch classify(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return WeaponMatch.none("empty hand");
        }
        return rules().classify(probe(stack));
    }

    public static boolean isWeapon(ItemStack stack) {
        return classify(stack).isWeapon();
    }

    /** Whether either of the player's hands holds a weapon. */
    public static boolean isArmed(Player player) {
        return player != null && (isWeapon(player.getMainHandItem()) || isWeapon(player.getOffhandItem()));
    }

    /** The threshold currently in force, for {@code /crime debug weapon}. */
    public static double minAttackDamage() {
        return rules().minAttackDamage();
    }

    /** Drops the compiled rules so the next lookup rebuilds them. Called on config reload. */
    public static void invalidate() {
        whitelistSource = List.of();
        blacklistSource = List.of();
        compiled = null;
    }

    private static WeaponProbe probe(ItemStack stack) {
        Item item = stack.getItem();
        return new WeaponProbe(
                ForgeRegistries.ITEMS.getKey(item),
                tag -> stack.is(TagKey.create(Registries.ITEM, tag)),
                CrimeItems.restraintFor(stack) != RestraintType.NONE,
                item instanceof SwordItem,
                item instanceof AxeItem,
                item instanceof TridentItem,
                item instanceof ProjectileWeaponItem,
                item instanceof DiggerItem && !(item instanceof AxeItem),
                item instanceof BlockItem,
                stack.getMaxStackSize() > 1,
                attackDamage(stack),
                stack.getUseAnimation().name());
    }

    /**
     * Bonus main-hand attack damage. Only ADDITION modifiers are summed: a multiplier has no meaning
     * without the base value the player brings, and mixing the two would compare unlike numbers
     * against a single configured threshold.
     */
    private static double attackDamage(ItemStack stack) {
        double total = 0.0D;
        for (AttributeModifier modifier : stack.getAttributeModifiers(EquipmentSlot.MAINHAND)
                .get(Attributes.ATTACK_DAMAGE)) {
            if (modifier.getOperation() == AttributeModifier.Operation.ADDITION) {
                total += modifier.getAmount();
            }
        }
        return total;
    }

    private static WeaponRules rules() {
        List<? extends String> whitelist = safeList(McaCrimeConfig.COMMON.weaponWhitelist);
        List<? extends String> blacklist = safeList(McaCrimeConfig.COMMON.weaponBlacklist);
        WeaponRules current = compiled;
        if (current == null || whitelist != whitelistSource || blacklist != blacklistSource) {
            current = WeaponRules.compile(whitelist, blacklist,
                    safeBoolean(McaCrimeConfig.COMMON.weaponAutoDetect, true),
                    safeDouble(McaCrimeConfig.COMMON.weaponAutoDetectMinAttackDamage, 3.0D),
                    safeList(McaCrimeConfig.COMMON.weaponGunKeywords),
                    safeList(McaCrimeConfig.COMMON.weaponMods));
            whitelistSource = whitelist;
            blacklistSource = blacklist;
            compiled = current;
        }
        return current;
    }

    /**
     * Reads a config list without letting a not-yet-loaded spec throw — the same guard
     * {@code EntitySelectors} needs, and for the same reason: this runs from interaction handling,
     * which can fire before config load in a malformed setup.
     */
    private static List<? extends String> safeList(ForgeConfigSpec.ConfigValue<List<? extends String>> value) {
        try {
            List<? extends String> list = value.get();
            return list == null ? List.of() : list;
        } catch (IllegalStateException e) {
            return List.of();
        }
    }

    private static boolean safeBoolean(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    private static double safeDouble(ForgeConfigSpec.DoubleValue value, double fallback) {
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }
}
