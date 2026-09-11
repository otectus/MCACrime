package dev.otectus.mcacrime.dialogue;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Lays a spoken line out the way the {@code [dialogue]} config asks for.
 *
 * <p>Mirrors the template handling MCA Conversations uses for its chat mode ({@code <%1$s> %2$s},
 * gold bold name) so a villager sounds the same whichever mod is speaking. The name and the line
 * are appended as components rather than flattened to text, so the name keeps its own style and
 * the line stays translatable on the client.
 */
public final class DialogueLineFormat {

    /** Used when the configured template does not name both placeholders. */
    public static final String FALLBACK_TEMPLATE = "%1$s: %2$s";
    private static final String NAME = "%1$s";
    private static final String LINE = "%2$s";

    private DialogueLineFormat() {}

    /** {@code name} restyled with the configured colour ({@code #RRGGBB}, or empty/invalid for none) and weight. */
    public static Component styleName(Component name, String hexColor, boolean bold) {
        Integer rgb = parseRgb(hexColor);
        return name.copy().withStyle(style -> {
            var out = style.withBold(bold);
            return rgb == null ? out : out.withColor(rgb);
        });
    }

    /** Renders {@code template} with {@code %1$s} = name and {@code %2$s} = line, in template order. */
    public static MutableComponent render(String template, Component name, Component line) {
        String t = template == null || !template.contains(NAME) || !template.contains(LINE)
                ? FALLBACK_TEMPLATE : template;
        MutableComponent out = Component.empty();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < t.length()) {
            if (t.startsWith(NAME, i)) {
                flush(out, literal);
                out.append(name);
                i += NAME.length();
            } else if (t.startsWith(LINE, i)) {
                flush(out, literal);
                out.append(line);
                i += LINE.length();
            } else {
                literal.append(t.charAt(i));
                i++;
            }
        }
        flush(out, literal);
        return out;
    }

    /** {@code #RRGGBB} or {@code RRGGBB} to a packed RGB int; {@code null} when it is not one. */
    public static Integer parseRgb(String hex) {
        if (hex == null) {
            return null;
        }
        String digits = hex.trim();
        if (digits.startsWith("#")) {
            digits = digits.substring(1);
        }
        if (digits.length() != 6) {
            return null;
        }
        try {
            return Integer.parseInt(digits, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void flush(MutableComponent out, StringBuilder literal) {
        if (literal.length() > 0) {
            out.append(Component.literal(literal.toString()));
            literal.setLength(0);
        }
    }
}
