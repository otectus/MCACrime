package dev.otectus.mcacrime.enchantment;

import dev.otectus.mcacrime.restraint.AppliedRestraint;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import org.jetbrains.annotations.Nullable;

/**
 * Reading one of our enchantments off a worn restraint (0.7.5 M6.1).
 *
 * <p>Off the persisted item snapshot rather than through {@code EnchantmentHelper}, and that is
 * deliberate: the snapshot is plain NBT, so the level a prisoner is actually carrying can be
 * asserted in a unit test with no registry booted, and the same code path answers for a villager
 * whose chunk is not loaded. {@code restraint/EscapeService} reads vanilla Unbreaking through
 * {@code EnchantmentHelper} because vanilla ids are registry-resolved there; ours are our own.
 *
 * <p>Every level returned here has already been through
 * {@link EnchantmentApplicability#effectiveLevel}, so a datapack- or command-set level above the
 * configured maximum is read as the maximum and a disabled enchantment reads as zero.
 */
public final class RestraintEnchantments {

    /** The key a saved {@code ItemStack} puts its component map under on 1.21.1. */
    private static final String COMPONENTS = "components";

    /** The component id the enchantment levels live under. */
    private static final String ENCHANTMENTS_COMPONENT = "minecraft:enchantments";

    private RestraintEnchantments() {
    }

    /** The effective level of {@code kind} on one worn instance. Zero when it is not enchanted. */
    public static int levelOn(@Nullable AppliedRestraint worn, @Nullable CrimeEnchantKind kind) {
        if (worn == null || kind == null) {
            return 0;
        }
        return EnchantmentApplicability.effectiveLevel(kind, rawLevel(worn.itemSnapshot(), kind));
    }

    /** The highest level of {@code kind} across everything a subject is wearing. */
    public static int levelOn(@Nullable PhysicalRestraintState state, @Nullable CrimeEnchantKind kind) {
        if (state == null || kind == null) {
            return 0;
        }
        int best = 0;
        for (RestraintSlot slot : RestraintSlot.values()) {
            AppliedRestraint worn = state.slot(slot).orElse(null);
            best = Math.max(best, levelOn(worn, kind));
        }
        return best;
    }

    /** Whether anything a subject wears carries {@code kind} at all. */
    public static boolean present(@Nullable PhysicalRestraintState state, @Nullable CrimeEnchantKind kind) {
        return levelOn(state, kind) > 0;
    }

    /**
     * The unclamped level stored in an item snapshot, for the tests that assert clamping.
     *
     * <p><b>1.21.1 difference from the baseline.</b> There is no item NBT and no {@code "tag"}
     * compound: a saved stack is {@code {id, count, components}}, and the enchantments are the
     * {@code minecraft:enchantments} component. That component's codec writes
     * {@code {levels: {"mcacrime:imbue": 1}}} and accepts the bare level map as an alternative, so
     * both shapes are read here. Still registry-free, which is the point of reading the snapshot
     * rather than a live stack: the level a prisoner is carrying can be asserted with nothing booted,
     * and the same path answers for a villager whose chunk is not loaded.
     */
    public static int rawLevel(@Nullable CompoundTag snapshot, @Nullable CrimeEnchantKind kind) {
        if (snapshot == null || kind == null) {
            return 0;
        }
        CompoundTag levels = levels(snapshot);
        return levels == null ? 0 : levels.getInt(kind.id().toString());
    }

    /** The {@code levels} map out of a saved stack, in either of the component codec's two shapes. */
    @Nullable
    private static CompoundTag levels(CompoundTag snapshot) {
        CompoundTag components = snapshot.contains(COMPONENTS, Tag.TAG_COMPOUND)
                ? snapshot.getCompound(COMPONENTS) : snapshot;
        if (!components.contains(ENCHANTMENTS_COMPONENT, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag enchantments = components.getCompound(ENCHANTMENTS_COMPONENT);
        return enchantments.contains("levels", Tag.TAG_COMPOUND)
                ? enchantments.getCompound("levels") : enchantments;
    }
}
