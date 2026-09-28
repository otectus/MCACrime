package dev.otectus.mcacrime.enchantment;

import dev.otectus.mcacrime.restraint.AppliedRestraint;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

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
     * <p>{@code ItemStack.of}'s shape: the enchantment list lives under the stack's {@code tag}
     * compound, which is where an anvil, a command and a datapack all write it.
     */
    public static int rawLevel(@Nullable CompoundTag snapshot, @Nullable CrimeEnchantKind kind) {
        if (snapshot == null || kind == null) {
            return 0;
        }
        CompoundTag itemTag = snapshot.contains("tag", Tag.TAG_COMPOUND)
                ? snapshot.getCompound("tag") : snapshot;
        ListTag list = itemTag.contains(ENCHANTMENTS_TAG, Tag.TAG_LIST)
                ? itemTag.getList(ENCHANTMENTS_TAG, Tag.TAG_COMPOUND) : null;
        return levelIn(list, kind.id());
    }

    /** Vanilla's own key, so a restraint's enchantments are the stack's enchantments. */
    private static final String ENCHANTMENTS_TAG = "Enchantments";

    /** The level of one enchantment in a vanilla enchantment list, read by id. {@code 0} when absent. */
    private static int levelIn(@Nullable ListTag list, @Nullable ResourceLocation id) {
        if (list == null || id == null) {
            return 0;
        }
        String wanted = id.toString();
        for (Tag tag : list) {
            if (tag instanceof CompoundTag entry && wanted.equals(entry.getString("id"))) {
                return entry.getInt("lvl");
            }
        }
        return 0;
    }
}
