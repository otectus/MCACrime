package dev.otectus.mcacrime.enchantment;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.enchantment.CrimeEnchantKind.Carrier;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.item.RestraintItem;
import dev.otectus.mcacrime.item.RestraintTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * The five restraint enchantments (0.7.5 §3.10, M6.1).
 *
 * <p>Registered through a {@code DeferredRegister} on the MOD bus from the {@code McaCrime}
 * constructor like every other registry in this mod. The classes themselves are deliberately inert:
 * an {@code Enchantment} in this package decides <em>applicability</em> and nothing else, and every
 * consequence is applied by a service — {@link ImbueHandler} for Imbue and {@link RestraintEffects} for
 * Famine, Shroud, Exhaust and Silence. That is
 * upstream's own arrangement, kept because it is the right one: an enchantment object has no tick,
 * no server and no way to be unit-tested.
 *
 * <p>Vanilla enchantments are supported on the same items rather than replaced: Unbreaking scales
 * the struggle roll in {@code restraint/EscapeService} ({@code :251-265}), and Curse of Binding is a
 * vanilla curse on a worn item, which the physical state honours because a death does not remove
 * what is worn.
 */
public final class CrimeEnchantments {

    public static final DeferredRegister<Enchantment> ENCHANTMENTS =
            DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, McaCrime.MOD_ID);

    /**
     * Anything MCA: Crime treats as a worn restraint.
     *
     * <p>The tag first, the class second: a pack that adds its own restraint to
     * {@code mcacrime:restraints} gets the enchantments with it, and the class check keeps the five
     * shipped items enchantable in a world whose tags have not been sent yet.
     */
    public static final EnchantmentCategory RESTRAINT_CATEGORY =
            EnchantmentCategory.create("MCACRIME_RESTRAINT", CrimeEnchantments::isRestraintItem);

    private static final Map<CrimeEnchantKind, RegistryObject<Enchantment>> BY_KIND =
            new EnumMap<>(CrimeEnchantKind.class);

    public static final RegistryObject<Enchantment> IMBUE = register(CrimeEnchantKind.IMBUE);
    public static final RegistryObject<Enchantment> FAMINE = register(CrimeEnchantKind.FAMINE);
    public static final RegistryObject<Enchantment> SHROUD = register(CrimeEnchantKind.SHROUD);
    public static final RegistryObject<Enchantment> EXHAUST = register(CrimeEnchantKind.EXHAUST);
    public static final RegistryObject<Enchantment> SILENCE = register(CrimeEnchantKind.SILENCE);

    private CrimeEnchantments() {
    }

    private static RegistryObject<Enchantment> register(CrimeEnchantKind kind) {
        RegistryObject<Enchantment> object =
                ENCHANTMENTS.register(kind.path(), () -> new CrimeEnchantment(kind));
        BY_KIND.put(kind, object);
        return object;
    }

    public static void register(IEventBus modBus) {
        ENCHANTMENTS.register(modBus);
    }

    /** The registered object for one kind. Present only after registration has run. */
    public static RegistryObject<Enchantment> of(CrimeEnchantKind kind) {
        return BY_KIND.get(kind);
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

    private static boolean isRestraintItem(Item item) {
        return item instanceof RestraintItem
                || item == CrimeItems.DUCK_TAPE.get();
    }

    /**
     * One of the five.
     *
     * <p>Every override here is an applicability rule, and each delegates to
     * {@link EnchantmentApplicability} so that the same answer can be asserted without a registry.
     */
    public static final class CrimeEnchantment extends Enchantment {

        private final CrimeEnchantKind kind;

        CrimeEnchantment(CrimeEnchantKind kind) {
            super(Rarity.RARE, RESTRAINT_CATEGORY, EquipmentSlot.values());
            this.kind = kind;
        }

        public CrimeEnchantKind kind() {
            return kind;
        }

        @Override
        public int getMinLevel() {
            return 1;
        }

        @Override
        public int getMaxLevel() {
            return Math.max(1, EnchantmentApplicability.maxLevel(kind));
        }

        @Override
        public int getMinCost(int level) {
            return 10 + (level - 1) * 10;
        }

        @Override
        public int getMaxCost(int level) {
            return getMinCost(level) + 30;
        }

        @Override
        public boolean canEnchant(ItemStack stack) {
            return EnchantmentApplicability.appliesTo(kind, carrierOf(stack));
        }

        @Override
        public boolean canApplyAtEnchantingTable(ItemStack stack) {
            return canEnchant(stack);
        }

        @Override
        public boolean isAllowedOnBooks() {
            return EnchantmentApplicability.allowedBy(kind,
                    EnchantmentApplicability.allowedFromConfig());
        }

        @Override
        protected boolean checkCompatibility(Enchantment other) {
            if (!super.checkCompatibility(other)) {
                return false;
            }
            return !(other instanceof CrimeEnchantment mine)
                    || EnchantmentApplicability.compatible(kind, mine.kind());
        }

        @Override
        public String getDescriptionId() {
            return kind.descriptionId();
        }

        @Override
        public boolean isTradeable() {
            return false; // not on a villager's book table: these are prison equipment, not commerce
        }

        @Override
        public boolean isDiscoverable() {
            return EnchantmentApplicability.allowedBy(kind,
                    EnchantmentApplicability.allowedFromConfig());
        }
    }
}
