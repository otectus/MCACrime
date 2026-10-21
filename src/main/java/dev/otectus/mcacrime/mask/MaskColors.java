package dev.otectus.mcacrime.mask;

import dev.otectus.mcacrime.item.MaskVariant;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.FastColor;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * The one definition of a mask's standard colours (0.8.0): which sixteen there are, what each looks like
 * on a mask, and whether a stack wears one of them.
 *
 * <p>A standard colour is <em>defined</em> as what one dye does to an undyed mask at the Mask Station --
 * vanilla's {@link DyedItemColor#applyDyes} blend, through {@link MaskCustomization#tint} -- not as the
 * dye's own diffuse colour. {@link #standardColor} computes that blend arithmetically so the table is a unit
 * test, and {@link #sample} produces it through the station's own tint path so a creative sample and a
 * crafted mask can never disagree.
 *
 * <p>"No colour" and "a stored colour" are different states, even where the numbers match: an undyed
 * mask has no {@code minecraft:dyed_color} component and draws its original art, a dyed one has the
 * component and draws the dyeable art tinted. Nothing here compares an undyed mask's implied white
 * against a stored white.
 *
 * <p>The catalogue -- every style followed by its sixteen colours -- is generated from {@link MaskVariant}
 * and {@link DyeColor}, never written out: 16 styles x (1 + 16) = 272 stacks.
 */
public final class MaskColors {

    /** The sixteen dyes in Minecraft's own order, which is also every list's order here. */
    public static final List<DyeColor> STANDARD = List.of(DyeColor.values());

    private MaskColors() {
    }

    /**
     * The colour one dye gives an undyed mask: {@code DyedItemColor.applyDyes} with a single dye and no
     * existing colour, step for step (including its float arithmetic).
     */
    public static int standardColor(DyeColor dye) {
        int diffuse = dye.getTextureDiffuseColor();
        int r = FastColor.ARGB32.red(diffuse);
        int g = FastColor.ARGB32.green(diffuse);
        int b = FastColor.ARGB32.blue(diffuse);
        float average = (float) Math.max(r, Math.max(g, b));   // the summed maxima over one input
        float peak = (float) Math.max(r, Math.max(g, b));
        r = (int) ((float) r * average / peak);
        g = (int) ((float) g * average / peak);
        b = (int) ((float) b * average / peak);
        return (r << 16) | (g << 8) | b;
    }

    /** Whether the stack stores a colour of its own (dyed), as opposed to carrying none (undyed). */
    public static boolean hasColor(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.has(DataComponents.DYED_COLOR);
    }

    /** The stored colour (RGB, no alpha), or empty for an undyed stack. Never white-by-default. */
    public static Optional<Integer> storedColor(@Nullable ItemStack stack) {
        if (!hasColor(stack)) {
            return Optional.empty();
        }
        return Optional.of(stack.get(DataComponents.DYED_COLOR).rgb() & 0xFFFFFF);
    }

    /**
     * The standard dye whose canonical colour the stack wears exactly, or empty -- for an undyed stack,
     * and for a blend or custom colour that only resembles one. A blend is never named as a standard
     * colour unless its stored value really is that colour's sample.
     */
    public static Optional<DyeColor> standardOf(@Nullable ItemStack stack) {
        return storedColor(stack).flatMap(MaskColors::standardOf);
    }

    /** The standard dye with exactly this canonical colour, or empty. */
    public static Optional<DyeColor> standardOf(int rgb) {
        for (DyeColor dye : STANDARD) {
            if (standardColor(dye) == (rgb & 0xFFFFFF)) {
                return Optional.of(dye);
            }
        }
        return Optional.empty();
    }

    /** {@code color.minecraft.light_blue}: vanilla's translation key for a dye's name. */
    public static String colorNameKey(DyeColor dye) {
        return "color.minecraft." + dye.getName();
    }

    /** "#3C44AA", for advanced tooltips only. */
    public static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /**
     * A mask of {@code item} dyed {@code dye} exactly as the Mask Station dyes an undyed one.
     *
     * @param item a registered mask item
     */
    public static ItemStack sample(Item item, DyeColor dye) {
        return MaskCustomization.tint(new ItemStack(item), DyeItem.byColor(dye));
    }

    /**
     * The creative catalogue: each style's original, then its sixteen standard colours, in catalogue
     * order (family, then position in the family).
     *
     * @param itemOf the registered item for a style
     */
    public static List<ItemStack> catalogue(Function<MaskVariant, Item> itemOf) {
        List<MaskVariant> styles = new ArrayList<>(List.of(MaskVariant.values()));
        styles.sort(Comparator.comparing((MaskVariant variant) -> variant.family().ordinal())
                .thenComparingInt(MaskVariant::sortOrder));
        List<ItemStack> stacks = new ArrayList<>();
        for (MaskVariant variant : styles) {
            Item item = itemOf.apply(variant);
            stacks.add(new ItemStack(item));
            for (DyeColor dye : STANDARD) {
                stacks.add(sample(item, dye));
            }
        }
        return stacks;
    }
}
