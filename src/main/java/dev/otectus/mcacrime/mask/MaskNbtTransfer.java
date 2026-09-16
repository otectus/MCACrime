package dev.otectus.mcacrime.mask;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * Moving one mask's item history onto another mask's tag, as pure {@link CompoundTag} arithmetic
 * (0.7.2 §5.4, invariant 8).
 *
 * <p>Split out of {@link MaskCustomization} for one reason: this is the part that has to be provable.
 * "Does a restyle keep a curse, keep provenance, keep a foreign mod's soulbound flag, and refuse a
 * capability it cannot honestly carry" is a matrix, and a matrix that needs a bootstrapped game to run
 * is a matrix nobody runs. Nothing here touches a registry, an {@code ItemStack} or an {@code Item}.
 *
 * <p>The policy is three-way rather than an allow-list, which is what changed in 0.7.2:
 *
 * <ul>
 *   <li><strong>Known</strong> keys are carried with understood semantics — the name, the wear, the
 *       enchantments and curses, the anvil cost, this mod's own {@code mcacrime} block.</li>
 *   <li><strong>Unknown but recoverable</strong> keys are copied verbatim. A foreign mod that stamped
 *       {@code somemod:owner} on a mask meant it to follow the item, and dropping it would make this
 *       station a laundering service for exactly the flags §14.4 says must survive customization.</li>
 *   <li><strong>Unsupported</strong> keys refuse the conversion outright. These are not data: {@code
 *       ForgeCaps} is the serialized state of capability providers that the <em>old</em> item attached
 *       and the new one never will, and {@code BlockEntityTag}/{@code EntityTag} are payloads for a
 *       placement this item cannot perform. Copying any of them would produce a stack whose contents
 *       nothing will ever read again, which is silent loss wearing a copy's clothes.</li>
 * </ul>
 */
public final class MaskNbtTransfer {

    /** Root keys carried with understood semantics. */
    public static final List<String> KNOWN_KEYS =
            List.of("Damage", "display", "Enchantments", "RepairCost", "Unbreakable", "HideFlags", "mcacrime");

    /**
     * Root keys that refuse the conversion.
     *
     * <p>Deliberately short and deliberately not "everything unfamiliar": rejecting every unknown key
     * would mean any mod that ever writes to a mask permanently disables restyling for its users, and
     * the spec asks for preserved unknown data with rejection reserved for what genuinely cannot move.
     */
    public static final List<String> UNSUPPORTED_KEYS = List.of("ForgeCaps", "BlockEntityTag", "EntityTag");

    private MaskNbtTransfer() {
    }

    /** Whether this root key stops a conversion. */
    public static boolean unsupported(String key) {
        return UNSUPPORTED_KEYS.contains(key);
    }

    /** Every unsupported key on this tag, in reading order. Empty means the transfer may proceed. */
    public static List<String> unsupportedKeys(@Nullable CompoundTag source) {
        if (source == null) {
            return List.of();
        }
        return source.getAllKeys().stream().filter(MaskNbtTransfer::unsupported).sorted().toList();
    }

    /**
     * The target's tag after the source's history has been moved onto it.
     *
     * <p>{@code Damage} is never carried here — the caller sets it through the stack so that the "same
     * wear budget or no conversion" rule is enforced against real maximum durabilities rather than
     * against two integers that happen to be present. Everything else the source has wins over
     * whatever the fresh target stack was born with, because the fresh stack has no history to lose.
     *
     * @param source the source stack's root tag, or {@code null} for a stack with no tag at all
     * @param target the fresh target stack's root tag, or {@code null}
     * @return the merged tag, or empty when the source carries an unsupported key
     */
    public static Optional<CompoundTag> merge(@Nullable CompoundTag source, @Nullable CompoundTag target) {
        if (!unsupportedKeys(source).isEmpty()) {
            return Optional.empty();
        }
        CompoundTag merged = target == null ? new CompoundTag() : target.copy();
        if (source == null) {
            return Optional.of(merged);
        }
        for (String key : source.getAllKeys()) {
            if ("Damage".equals(key)) {
                continue;
            }
            if ("display".equals(key)) {
                merged.put("display", mergeDisplay(source.getCompound("display"), merged));
                continue;
            }
            merged.put(key, source.get(key).copy());
        }
        return Optional.of(merged);
    }

    /**
     * The display block, which is the one place the two stacks genuinely both have an opinion.
     *
     * <p>The source's custom name and dye colour are its own and follow it. {@code Lore} likewise. Any
     * other display field the fresh target carries — a resource pack's {@code CustomModelData}, say —
     * is a property of the new style and stays.
     */
    private static CompoundTag mergeDisplay(CompoundTag sourceDisplay, CompoundTag mergedRoot) {
        CompoundTag display = mergedRoot.contains("display", CompoundTag.TAG_COMPOUND)
                ? mergedRoot.getCompound("display").copy() : new CompoundTag();
        for (String key : sourceDisplay.getAllKeys()) {
            display.put(key, sourceDisplay.get(key).copy());
        }
        return display;
    }
}
