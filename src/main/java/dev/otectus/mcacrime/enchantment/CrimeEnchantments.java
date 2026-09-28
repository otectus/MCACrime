package dev.otectus.mcacrime.enchantment;

import dev.otectus.mcacrime.enchantment.CrimeEnchantKind.Carrier;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.item.RestraintItem;
import dev.otectus.mcacrime.item.RestraintTags;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * The five restraint enchantments (0.7.5 §3.10, M6.1, port step P6.1).
 *
 * <p><b>1.21.1 difference from the Forge 1.20.1 baseline.</b> An {@code Enchantment} is a datapack
 * registry entry here, not a Java object a {@code DeferredRegister} can create: the baseline's
 * {@code CrimeEnchantments.CrimeEnchantment} subclass and its two {@code EnchantmentCategory}
 * instances have no counterpart on this line. The five definitions are
 * {@code src/main/resources/data/mcacrime/enchantment/*.json}, and what survives in Java is this
 * class: five {@link ResourceKey} constants, a {@link Holder} lookup for the call sites that need one,
 * and the carrier test the applicability table is written against.
 *
 * <p>Consequently there is no registration call from the {@code McaCrime} constructor. The
 * <em>applicability</em> rules still live in {@link EnchantmentApplicability} and are still pure and
 * still asserted without a booted registry - on the baseline that class backs the enchantment
 * object's overrides, and here it backs the services directly, which is the same table read at the
 * same points.
 *
 * <p>One rule the JSON carries that the baseline carried in code: {@code supported_items} is the item
 * tag {@code #mcacrime:enchantable/restraint}, so a pack that adds its own restraint gets the
 * enchantments with it.
 *
 * <p>Vanilla enchantments are supported on the same items rather than replaced: Unbreaking scales the
 * struggle roll in {@code restraint/EscapeService}, and Curse of Binding is a vanilla curse on a worn
 * item, which the physical state honours because a death does not remove what is worn.
 */
public final class CrimeEnchantments {

    private static final Map<CrimeEnchantKind, ResourceKey<Enchantment>> BY_KIND =
            new EnumMap<>(CrimeEnchantKind.class);

    public static final ResourceKey<Enchantment> IMBUE = key(CrimeEnchantKind.IMBUE);
    public static final ResourceKey<Enchantment> FAMINE = key(CrimeEnchantKind.FAMINE);
    public static final ResourceKey<Enchantment> SHROUD = key(CrimeEnchantKind.SHROUD);
    public static final ResourceKey<Enchantment> EXHAUST = key(CrimeEnchantKind.EXHAUST);
    public static final ResourceKey<Enchantment> SILENCE = key(CrimeEnchantKind.SILENCE);

    private CrimeEnchantments() {
    }

    private static ResourceKey<Enchantment> key(CrimeEnchantKind kind) {
        ResourceKey<Enchantment> created = ResourceKey.create(Registries.ENCHANTMENT, kind.id());
        BY_KIND.put(kind, created);
        return created;
    }

    /** The registry key for one kind. Never null: a key exists whether or not the datapack does. */
    public static ResourceKey<Enchantment> of(CrimeEnchantKind kind) {
        ResourceKey<Enchantment> found = BY_KIND.get(kind);
        return found == null ? key(kind) : found;
    }

    /**
     * The holder for one kind out of a registry lookup.
     *
     * <p>Empty rather than throwing when the datapack entry is absent - an operator who removed the
     * file gets an enchantment that is never found, not a crash inside a tick.
     */
    public static Optional<Holder.Reference<Enchantment>> holder(@Nullable HolderLookup.Provider lookup,
                                                                @Nullable CrimeEnchantKind kind) {
        if (lookup == null || kind == null) {
            return Optional.empty();
        }
        try {
            return lookup.lookupOrThrow(Registries.ENCHANTMENT).get(of(kind));
        } catch (RuntimeException missing) {
            return Optional.empty();
        }
    }

    /** The holder for one kind in one world. */
    public static Optional<Holder.Reference<Enchantment>> holder(@Nullable Level level,
                                                                @Nullable CrimeEnchantKind kind) {
        return level == null ? Optional.empty() : holder(level.registryAccess(), kind);
    }

    /**
     * The level of one of ours on a live stack, read through the registry.
     *
     * <p>The counterpart of {@link RestraintEnchantments#rawLevel}, which reads the same number off a
     * persisted snapshot with no registry. Both are clamped by
     * {@link EnchantmentApplicability#effectiveLevel}, so the two never disagree about what an
     * operator has allowed.
     */
    public static int levelOn(@Nullable Level level, @Nullable ItemStack stack,
                              @Nullable CrimeEnchantKind kind) {
        if (level == null || stack == null || stack.isEmpty() || kind == null) {
            return 0;
        }
        int raw = holder(level, kind)
                .map(found -> EnchantmentHelper.getItemEnchantmentLevel(found, stack))
                .orElse(0);
        return EnchantmentApplicability.effectiveLevel(kind, raw);
    }

    /** Which of ours this stack is, if it is one of ours at all. */
    public static Carrier carrierOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Carrier.OTHER;
        }
        Item item = stack.getItem();
        if (item == Items.BOOK || item == Items.ENCHANTED_BOOK) {
            return Carrier.BOOK;
        }
        return stack.is(RestraintTags.RESTRAINTS) || isRestraintItem(item)
                ? Carrier.RESTRAINT : Carrier.OTHER;
    }

    /**
     * Whether an item is one MCA: Crime treats as a worn restraint.
     *
     * <p>The tag first, the class second: a pack that adds its own restraint to
     * {@code mcacrime:restraints} gets the enchantments with it, and the class check keeps the five
     * shipped items enchantable in a world whose tags have not been sent yet.
     */
    public static boolean isRestraintItem(@Nullable Item item) {
        return item instanceof RestraintItem
                || item == CrimeItems.DUCK_TAPE.get();
    }
}
