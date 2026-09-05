package dev.otectus.mcacrime.item.weapon;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.item.CrimeItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.List;
import java.util.Optional;

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

    /** What the stack is under an explicitly supplied rule set — the client's path, off a synced snapshot. */
    public static WeaponMatch classify(ItemStack stack, WeaponRules rules) {
        if (stack == null || stack.isEmpty()) {
            return WeaponMatch.none("empty hand");
        }
        return rules.classify(probe(stack));
    }

    /**
     * The weapon this entity is holding, if any — the single canonical definition of "drawn" (0.5.1).
     *
     * <p>Main hand first and always, because that is the hand the threat is being made with; the
     * off-hand is only consulted when {@code allowOffHand} says so, and then it loses ties. Every
     * gate in the mod — the crime menu, the mug tick, villager resistance, the client button — asks
     * this one method, so there is no way for two of them to disagree about what counts.
     */
    public static Optional<DrawnWeapon> drawnWeapon(LivingEntity entity, boolean allowOffHand) {
        if (entity == null) {
            return Optional.empty();
        }
        ItemStack main = entity.getItemInHand(InteractionHand.MAIN_HAND);
        WeaponMatch mainMatch = classify(main);
        if (mainMatch.isWeapon()) {
            return Optional.of(new DrawnWeapon(InteractionHand.MAIN_HAND, main, mainMatch));
        }
        if (!allowOffHand) {
            return Optional.empty();
        }
        ItemStack off = entity.getItemInHand(InteractionHand.OFF_HAND);
        WeaponMatch offMatch = classify(off);
        return offMatch.isWeapon()
                ? Optional.of(new DrawnWeapon(InteractionHand.OFF_HAND, off, offMatch))
                : Optional.empty();
    }

    /** {@link #drawnWeapon(LivingEntity, boolean)} honouring {@code weaponTrigger.allowOffHand}. */
    public static Optional<DrawnWeapon> drawnWeapon(LivingEntity entity) {
        return drawnWeapon(entity, safeBoolean(McaCrimeConfig.COMMON.weaponTriggerAllowOffHand, true));
    }

    /** Whether this entity has a weapon drawn, per the configured off-hand policy. */
    public static boolean isArmed(LivingEntity entity) {
        return drawnWeapon(entity).isPresent();
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
                BuiltInRegistries.ITEM.getKey(item),
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
        double[] total = {0.0D};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.is(Attributes.ATTACK_DAMAGE)
                    && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                total[0] += modifier.amount();
            }
        });
        return total[0];
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
    private static List<? extends String> safeList(ModConfigSpec.ConfigValue<List<? extends String>> value) {
        try {
            List<? extends String> list = value.get();
            return list == null ? List.of() : list;
        } catch (IllegalStateException e) {
            return List.of();
        }
    }

    private static boolean safeBoolean(ModConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    private static double safeDouble(ModConfigSpec.DoubleValue value, double fallback) {
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }
}
