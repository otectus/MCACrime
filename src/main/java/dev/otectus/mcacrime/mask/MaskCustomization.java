package dev.otectus.mcacrime.mask;

import dev.otectus.mcacrime.item.MaskFamily;
import dev.otectus.mcacrime.item.MaskItem;
import dev.otectus.mcacrime.item.MaskVariant;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * The one path by which an existing mask becomes a different mask, and the only place allowed to move
 * a player's item history from one stack to another (0.7.2 sections 5.4 and 7.4, invariant 8).
 *
 * <p>"Appearance changes preserve identity and item history" is the invariant this class exists to
 * keep. It keeps it by carrying everything it understands, copying everything it merely recognises as
 * somebody else's data, and refusing outright the moment it meets something it cannot move honestly --
 * see {@link MaskNbtTransfer} for that three-way policy and the tests that hold it.
 *
 * <p>Three refusals are structural rather than data-driven:
 *
 * <ul>
 *   <li><strong>Same family only.</strong> Damage is transferred exactly, and exact transfer is only
 *       meaningful between masks with the same wear budget. Moving 40 damage from a 64-use clay mask
 *       onto a 256-use iron one is a quiet 192-use gift; rescaling it is a quiet loss. Section 5.4
 *       prefers disallowing the pairing, so a family is the conversion boundary and a restyle is never
 *       a repair.</li>
 *   <li><strong>Registered styles only.</strong> The {@code mcacrime:masks/<family>} tag makes an item
 *       conceal a face. It does not make it convertible: only an item this mod registered as a style
 *       may go down the protected transfer path, so a pack that tags a third-party helmet has not
 *       thereby volunteered that helmet's data for rewriting.</li>
 *   <li><strong>No no-ops.</strong> Selecting the style the mask already is, with no colour change, is
 *       a disabled operation rather than a way to spend a binding on nothing (section 5.4).</li>
 * </ul>
 *
 * <p>Nothing here touches the deferred-Heat ledger, a witness memory or a pursuit clock, and that is
 * not an omission. Those live on the player and on the witness, never on the item, so a restyle
 * cannot reach them even by accident -- which is what makes MASK-12 hold structurally rather than by
 * remembering to be careful.
 */
public final class MaskCustomization {

    private MaskCustomization() {
    }

    /** A finished conversion, or the reason there is not one. */
    public record Result(ItemStack stack, MaskRestyleRejection rejection) {

        public static Result refused(MaskRestyleRejection rejection) {
            return new Result(ItemStack.EMPTY, rejection);
        }

        public boolean allowed() {
            return rejection.allowed() && !stack.isEmpty();
        }
    }

    /**
     * Moves {@code source}'s identity onto a fresh {@code target} stack.
     *
     * @return the finished stack, or empty when the pair is not a supported conversion
     */
    public static Optional<ItemStack> restyle(ItemStack source, ItemStack target) {
        Result result = restyleResult(source, target);
        return result.allowed() ? Optional.of(result.stack()) : Optional.empty();
    }

    /** The same conversion, with the reason attached when it is refused. */
    public static Result restyleResult(@Nullable ItemStack source, @Nullable ItemStack target) {
        if (source == null || target == null || source.isEmpty() || target.isEmpty()) {
            return Result.refused(MaskRestyleRejection.NOT_A_MASK);
        }
        MaskRestyleRejection pairing = conversion(source, target);
        if (!pairing.allowed()) {
            return Result.refused(pairing);
        }
        Optional<DataComponentPatch> merged =
                MaskNbtTransfer.merge(source.getComponentsPatch(), target.getComponentsPatch());
        if (merged.isEmpty()) {
            return Result.refused(MaskRestyleRejection.UNSUPPORTED_DATA);
        }
        // Built from the item rather than copied from the target stack, so the merged patch is the
        // whole of the result's state and no stray component rides along unexamined.
        ItemStack result = new ItemStack(target.getItemHolder(), 1);
        result.applyComponents(merged.get());
        // Set through the stack, not through the copied patch: this is the assertion that the two
        // masks really do share a maximum durability, and it is what keeps a restyle from being a
        // repair.
        if (source.isDamageableItem() != result.isDamageableItem()
                || (source.isDamageableItem() && source.getMaxDamage() != result.getMaxDamage())) {
            return Result.refused(MaskRestyleRejection.WRONG_FAMILY);
        }
        if (source.isDamageableItem()) {
            result.setDamageValue(source.getDamageValue());
        }
        return new Result(result, MaskRestyleRejection.NONE);
    }

    /**
     * The whole rule, as a function of values rather than of a running game.
     *
     * <p>Everything the restyle decision actually depends on is here: two registered styles, the
     * source's component patch, and whether the operation would change the colour. No registry, no
     * {@code ItemStack}, no {@code Level} -- so "cross-family is refused, a no-op is refused, an
     * unmovable payload is refused, a foreign flag is carried" is a plain unit test rather than an
     * in-world experiment.
     *
     * @param from         the source's registered style, or {@code null} when it has none
     * @param to           the target's registered style, or {@code null} when it has none
     * @param sourceData   the source stack's component patch, or {@code null}
     * @param colorChanges whether the operation would land on a different colour than the source wears
     */
    public static MaskRestyleRejection decide(@Nullable MaskVariant from, @Nullable MaskVariant to,
                                              @Nullable DataComponentPatch sourceData,
                                              boolean colorChanges) {
        if (from == null || to == null) {
            return MaskRestyleRejection.NOT_A_REGISTERED_STYLE;
        }
        if (from.family() != to.family()) {
            return MaskRestyleRejection.WRONG_FAMILY;
        }
        if (!MaskNbtTransfer.unsupportedKeys(sourceData).isEmpty()) {
            return MaskRestyleRejection.UNSUPPORTED_DATA;
        }
        if (from == to && !colorChanges) {
            return MaskRestyleRejection.NO_CHANGE;
        }
        return MaskRestyleRejection.NONE;
    }

    /**
     * Whether these two masks are a supported pair at all, before any dye is considered.
     *
     * <p>The colour half of "same style and no colour change" belongs to {@link #wouldChange}, because
     * only the caller knows whether a dye is in the slot; this asks the rest of the question.
     */
    public static MaskRestyleRejection conversion(@Nullable ItemStack source, @Nullable ItemStack target) {
        if (source == null || target == null || source.isEmpty() || target.isEmpty()) {
            return MaskRestyleRejection.NOT_A_MASK;
        }
        if (!Masks.isMask(source)) {
            return MaskRestyleRejection.NOT_A_MASK;
        }
        return decide(MaskVariant.byStack(source).orElse(null), MaskVariant.byStack(target).orElse(null),
                source.getComponentsPatch(), true);
    }

    /**
     * Whether this conversion actually changes anything the player can see.
     *
     * <p>Same style plus a dye that lands on the colour the mask already wears is still a no-op --
     * dyeing a scarlet mask scarlet blends to scarlet -- so the dye is applied to a probe rather than
     * assumed to be a change.
     *
     * @param dye the dye in the optional slot, or {@code null} when the slot is empty or disallowed
     */
    public static MaskRestyleRejection wouldChange(ItemStack source, ItemStack target, @Nullable DyeItem dye) {
        MaskVariant from = MaskVariant.byStack(source).orElse(null);
        MaskVariant to = MaskVariant.byStack(target).orElse(null);
        if (from == null || to == null || from != to) {
            return MaskRestyleRejection.NONE;
        }
        boolean colorChanges = dye != null && colorOf(tint(source.copy(), dye)) != colorOf(source);
        return decide(from, to, source.getComponentsPatch(), colorChanges);
    }

    /**
     * Applies one dye's colour to a mask being made (section 7.2's optional dye slot).
     *
     * <p>Vanilla's own algorithm, not a private one: {@link DyedItemColor#applyDyes} is what a leather
     * cap, a shulker-adjacent dyeable and the crafting-table dye recipe all use, so one dye produces
     * exactly the colour that dye produces everywhere else and a dyed mask reads as dyed to every
     * piece of machinery that already understands {@code minecraft:dyed_color}. Appearance only -- no
     * other component is touched.
     *
     * <p>Vanilla gates the call on the {@code minecraft:dyeable} item tag, which every shipped mask is
     * in; an item outside it is returned untouched rather than silently half-dyed.
     *
     * @return the tinted stack, or {@code stack} unchanged when this mask does not take dye
     */
    public static ItemStack tint(ItemStack stack, DyeItem dye) {
        if (stack == null || stack.isEmpty() || dye == null) {
            return stack;
        }
        ItemStack dyed = DyedItemColor.applyDyes(stack, List.of(dye));
        return dyed == null || dyed.isEmpty() ? stack : dyed;
    }

    /** The dye colour on a stack, or white when it carries none (matching {@code MaskItem#colorOf}). */
    public static int colorOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return MaskItem.OPAQUE_WHITE;
        }
        return DyedItemColor.getOrDefault(stack, MaskItem.OPAQUE_WHITE);
    }

    /** The family a stack's registered style belongs to, or empty when it is not a registered style. */
    public static Optional<MaskFamily> familyOf(@Nullable ItemStack stack) {
        return MaskVariant.byStack(stack).map(MaskVariant::family);
    }
}
