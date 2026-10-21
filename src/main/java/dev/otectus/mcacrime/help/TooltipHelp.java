package dev.otectus.mcacrime.help;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The tooltip convention for items that need instructions (0.8.0): one short line that is always
 * visible, a compact set of detail lines while Shift is held, and a pointer to the matching
 * {@code /crime help} topic. Longer sequences belong on the help page, not in a tooltip.
 *
 * <p>Common code. Whether Shift is held is a client question, so the client installs the check at
 * setup and a server that ever asks for tooltip lines simply gets the short form.
 */
public final class TooltipHelp {

    private static volatile BooleanSupplier expanded = () -> false;

    private TooltipHelp() {
    }

    /** Installed once by the client: {@code Screen::hasShiftDown}. */
    public static void installExpandedCheck(@Nullable BooleanSupplier check) {
        expanded = check == null ? () -> false : check;
    }

    /** Whether the player is asking for the detailed tooltip right now. */
    public static boolean expanded() {
        try {
            return expanded.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Appends one item's guidance.
     *
     * @param shortLine the always-visible instruction
     * @param details   the lines shown while Shift is held
     * @param topic     the help topic the item points to, or null
     */
    public static void append(List<Component> tooltip, Component shortLine, List<Component> details,
                              @Nullable CrimeHelpTopic topic) {
        tooltip.add(shortLine.copy().withStyle(ChatFormatting.GRAY));
        if (details.isEmpty() && topic == null) {
            return;
        }
        if (!expanded()) {
            tooltip.add(Component.translatable("mcacrime.tooltip.hold_shift",
                    Component.translatable("mcacrime.tooltip.shift_key")).withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        for (Component line : details) {
            tooltip.add(line.copy().withStyle(ChatFormatting.GRAY));
        }
        if (topic != null) {
            tooltip.add(topic.tooltipHint());
        }
    }
}
