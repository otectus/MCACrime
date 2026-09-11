package dev.otectus.mcacrime;

import dev.otectus.mcacrime.dialogue.DialogueLineFormat;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The spoken-line template mirrors MCA Conversations' chat mode and never loses the name or the line. */
class DialogueLineFormatTest {

    private static final Component NAME = Component.literal("Idajete");
    private static final Component LINE = Component.literal("I would rather not speak to you.");

    @Test
    void defaultTemplateBracketsTheName() {
        assertEquals("<Idajete> I would rather not speak to you.",
                DialogueLineFormat.render("<%1$s> %2$s", NAME, LINE).getString());
    }

    @Test
    void placeholdersMayAppearInAnyOrderWithSurroundingText() {
        assertEquals("[I would rather not speak to you.] -- Idajete",
                DialogueLineFormat.render("[%2$s] -- %1$s", NAME, LINE).getString());
    }

    @Test
    void templateMissingAPlaceholderFallsBackToColonForm() {
        String expected = "Idajete: I would rather not speak to you.";
        assertEquals(expected, DialogueLineFormat.render("%1$s only", NAME, LINE).getString());
        assertEquals(expected, DialogueLineFormat.render("", NAME, LINE).getString());
        assertEquals(expected, DialogueLineFormat.render(null, NAME, LINE).getString());
    }

    @Test
    void nameKeepsItsOwnComponentRatherThanBeingFlattened() {
        MutableComponent out = DialogueLineFormat.render("<%1$s> %2$s", NAME, LINE);
        // literal "<", name, literal "> ", line
        assertEquals(4, out.getSiblings().size());
        assertSame(NAME, out.getSiblings().get(1));
        assertSame(LINE, out.getSiblings().get(3));
    }

    @Test
    void nameStyleFollowsConfig() {
        Component styled = DialogueLineFormat.styleName(NAME, "#FFC34D", true);
        assertTrue(styled.getStyle().isBold());
        assertNotNull(styled.getStyle().getColor());
        assertEquals(0xFFC34D, styled.getStyle().getColor().getValue());

        Component plain = DialogueLineFormat.styleName(NAME, "", false);
        assertFalse(plain.getStyle().isBold());
        assertNull(plain.getStyle().getColor());

        Component bad = DialogueLineFormat.styleName(NAME, "gold", true);
        assertNull(bad.getStyle().getColor(), "an unparseable colour leaves the name uncoloured");
        assertTrue(bad.getStyle().isBold());
    }

    @Test
    void colourParsingAcceptsWithAndWithoutHash() {
        assertEquals(0xFFC34D, DialogueLineFormat.parseRgb("#FFC34D"));
        assertEquals(0xFFC34D, DialogueLineFormat.parseRgb("ffc34d"));
        assertNull(DialogueLineFormat.parseRgb("#FFF"));
        assertNull(DialogueLineFormat.parseRgb("#GGGGGG"));
        assertNull(DialogueLineFormat.parseRgb(null));
    }
}
