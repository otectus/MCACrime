package dev.otectus.mcacrime.client.hud;

import dev.otectus.mcacrime.client.ClientPhysicalRestraintData;
import dev.otectus.mcacrime.client.screen.CrimeSprites;
import dev.otectus.mcacrime.restraint.PhysicalRestraintView;
import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The restraint panel: what is on you, how worn it is, and what you can do about it (0.7.5 M2.10).
 *
 * <p>Laid out through {@link CrimeHudLayout} like every other panel in this mod, and drawn from the
 * existing {@link CrimeSprites} sheet, so it sits with the status indicator and the jail clock rather
 * than beside them in a different visual language.
 *
 * <p>The escape hint is the reason this exists at all. A restrained player is the one player who
 * cannot experiment their way to an answer — their hands are the thing that has been taken away — so
 * the panel says, in words, which input frees them and roughly how far along they are. The source
 * puts the same information in a screen behind a keybind, which a player who has never opened it does
 * not know exists.
 *
 * <p>Presentation only. The durability shown is the bounded fraction the server chose to publish, not
 * a number this client can act on: the server owns every point of it, and a client that lied about
 * this fraction would change what a bar looks like and nothing else.
 */
public final class RestraintHudSection {

    /** The panel's inner padding, matching {@code CrimeHudOverlays}. */
    private static final int PAD = 3;

    /** How tall one slot row is: a text line plus its bar. */
    private static final int ROW_HEIGHT = 16;

    private static final int BAR_WIDTH = 60;

    private RestraintHudSection() {
    }

    /**
     * One line of the panel.
     *
     * @param label    the restraint's own name
     * @param fraction how much of it is left, 0..1
     */
    public record Row(Component label, float fraction) {
    }

    /**
     * The rows to draw for {@code subject}, in slot order, empty when nothing is worn.
     *
     * <p>Pure, and separate from the drawing for the reason every layout in this mod is: the
     * interesting assertions — that the panel is empty when nothing is worn, that it grows by exactly
     * one row per occupied slot, that a slot the client has no definition for still gets a line rather
     * than disappearing — are about the list, not about pixels.
     */
    public static List<Row> rows(UUID subject) {
        PhysicalRestraintView view = ClientPhysicalRestraintData.get(subject).orElse(null);
        List<Row> rows = new ArrayList<>(3);
        if (view == null) {
            return rows;
        }
        for (RestraintSlot slot : RestraintSlot.values()) {
            view.slot(slot).ifPresent(slotView -> rows.add(new Row(
                    name(slotView.definitionId()), slotView.durabilityFraction())));
        }
        return rows;
    }

    /**
     * The name to show for a definition id.
     *
     * <p>A definition this build does not have still gets a line, labelled by its slot, rather than
     * being skipped: "something is on your arms and this client does not know what" is a far better
     * answer for a player than an arm restraint that is invisible in the panel and very much present
     * in the game.
     */
    private static Component name(net.minecraft.resources.ResourceLocation definitionId) {
        if (RestraintDefinitions.exists(definitionId)) {
            return Component.translatable("mcacrime.restraint." + definitionId.getPath() + ".name");
        }
        return Component.translatable("mcacrime.hud.restraint.title");
    }

    /** The panel's height for {@code rowCount} rows, or 0 when there is nothing to draw. */
    public static int height(int rowCount) {
        return rowCount <= 0 ? 0 : PAD * 2 + 10 + rowCount * ROW_HEIGHT;
    }

    /** The panel's width, which is fixed: a ragged panel that resizes per restraint reads as a glitch. */
    public static int width(Minecraft mc) {
        return PAD * 2 + Math.max(BAR_WIDTH, mc.font.width(
                Component.translatable("mcacrime.hud.restraint.struggle")));
    }

    /** Draws the panel at the origin of the current pose. */
    public static void render(GuiGraphics graphics, Minecraft mc, List<Row> rows, boolean held,
                              boolean detained) {
        if (rows.isEmpty()) {
            return;
        }
        int boxW = width(mc);
        int boxH = height(rows.size());
        CrimeSprites.hudPlate(graphics, 0, 0, boxW, boxH);
        Component title = detained
                ? Component.translatable("mcacrime.hud.restraint.detained")
                : held ? Component.translatable("mcacrime.hud.restraint.held")
                : Component.translatable("mcacrime.hud.restraint.title");
        graphics.drawString(mc.font, title, PAD, PAD, 0xFFFFB060, false);

        int y = PAD + 10;
        for (Row row : rows) {
            graphics.drawString(mc.font, row.label(), PAD, y, 0xFFCCCCCC, false);
            CrimeSprites.bar(graphics, PAD, y + 9, BAR_WIDTH, row.fraction());
            y += ROW_HEIGHT;
        }
    }
}
