package dev.otectus.mcacrime.client.screen;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.ActionCategory;
import dev.otectus.mcacrime.client.screen.widget.CrimeHeaderWidget;
import dev.otectus.mcacrime.client.screen.widget.CrimeRowList;
import dev.otectus.mcacrime.client.screen.widget.CrimeRowWidget;
import dev.otectus.mcacrime.network.ActionMenuS2CPacket;
import dev.otectus.mcacrime.network.ActionMenuEntry;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.StartActionC2SPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The mod-owned action panel: server-populated, grouped by category, and incapable of deciding
 * anything for itself.
 *
 * <p>Every row here was authored by the server — its label, its legality marker, its requirements,
 * whether it is usable and why not. Clicking one sends the action id back and the server re-evaluates
 * it from scratch, so a modified client that draws a row as available gains nothing by it.
 *
 * <p>The rows are real widgets in a real list. They were rectangles hit-tested by dividing the mouse
 * position by a row height, which meant the panel could only ever be used with a mouse: no keyboard
 * focus, no narration, nothing for a controller mod to find. A panel that tells the player which
 * actions are crimes is exactly the wrong one to make unreachable.
 */
public class CrimeInteractionScreen extends Screen {

    private static final int PANEL_W = 232;
    private static final int ROW_H = 22;
    private static final int HEADER_H = 34;
    private static final int FOOTER_H = 28;
    /** Margin between the panel's edge and the list well, matching the footer button's. */
    private static final int LIST_INSET = 6;

    private final ActionMenuS2CPacket menu;
    private final Screen parent;
    private final List<Object> rows = new ArrayList<>();
    /** Height of the subclass header block, measured once per layout. */
    private int extraHeader;

    private int panelLeft;
    private int panelTop;
    private int panelHeight;
    private CrimeRowList list;
    /** Carried across a resize, so rebuilding the layout does not throw the player back to the top. */
    private double scrollAmount;

    public CrimeInteractionScreen(ActionMenuS2CPacket menu, Screen parent) {
        this(menu, parent, Component.translatable("gui.mcacrime.actions"));
    }

    /** Subclass entry point, for a panel that is the same list under a different name and header. */
    protected CrimeInteractionScreen(ActionMenuS2CPacket menu, Screen parent, Component title) {
        super(title);
        this.menu = menu;
        this.parent = parent;
    }

    /**
     * Extra pixels a subclass needs between the title and the first row. Zero by default, so the
     * ordinary villager menu is laid out exactly as before.
     */
    protected int extraHeaderHeight() {
        return 0;
    }

    /** Draws the subclass header into the space {@link #extraHeaderHeight()} reserved. */
    protected void renderExtraHeader(GuiGraphics graphics, int left, int top, int width) {
    }

    /** The menu this screen is drawing, for a subclass that needs the target or the row set. */
    protected final ActionMenuS2CPacket menu() {
        return menu;
    }

    @Override
    protected void init() {
        buildRows();
        extraHeader = Math.max(0, extraHeaderHeight());

        int header = HEADER_H + extraHeader;
        int desired = header + rows.size() * ROW_H + FOOTER_H;
        panelHeight = Math.min(desired, Math.max(header + ROW_H + FOOTER_H, height - 40));
        panelLeft = (width - PANEL_W) / 2;
        panelTop = (height - panelHeight) / 2;

        int listTop = panelTop + header;
        int listHeight = panelHeight - header - FOOTER_H;

        // Inset by the same margin as the footer button. The list draws its own sunken well, and a
        // well flush with the panel's edges would paint over the bevel that makes the panel a panel.
        list = new CrimeRowList(minecraft, panelLeft + LIST_INSET, listTop, listTop + listHeight,
                PANEL_W - 2 * LIST_INSET, ROW_H);
        for (Object row : rows) {
            if (row instanceof ActionCategory category) {
                list.addRow(new CrimeHeaderWidget(Component.translatable(category.labelKey())));
            } else if (row instanceof ActionMenuEntry entry) {
                list.addRow(actionRow(entry));
            }
        }
        list.setScrollAmount(scrollAmount);
        addRenderableWidget(list);

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(panelLeft + 6, panelTop + panelHeight - 24, PANEL_W - 12, 20).build());
    }

    /** Builds one action row, with the server's own reason for refusing it attached as a tooltip. */
    private CrimeRowWidget actionRow(ActionMenuEntry entry) {
        // The duration sits hard right so the eye can scan a column of them without reading names.
        CrimeRowWidget widget = new CrimeRowWidget(
                Component.translatable(entry.labelKey()),
                entry.legality().rgb(),
                Component.translatable(entry.duration().labelKey()),
                PanelColours.TEXT_MUTED,
                entry.available(),
                () -> activate(entry));
        widget.setTooltip(Tooltip.create(describe(entry), Component.translatable(entry.descriptionKey())));
        return widget;
    }

    /**
     * Describes a row: what it does, what it needs, and — when it is unusable — the reason the server
     * gave. Showing the reason rather than hiding the row is what teaches the rules.
     *
     * <p>The colours here stay bright rather than going through {@link PanelColours#onPanel}: a
     * tooltip is drawn on its own dark background, so darkening these would make them unreadable
     * for the sake of a panel they are never drawn on.
     *
     * <p>The second, shorter component handed to {@code Tooltip.create} is the narration. Without it
     * a screen reader recites the entire requirement list every time focus lands on a row.
     */
    private static Component describe(ActionMenuEntry entry) {
        MutableComponent body = Component.translatable(entry.labelKey());
        body.append("\n").append(Component.translatable(entry.descriptionKey())
                .withStyle(s -> s.withColor(0xAAAAAA)));
        body.append("\n").append(Component.translatable(entry.legality().labelKey())
                .withStyle(s -> s.withColor(entry.legality().rgb())));
        for (String requirement : entry.requirementKeys()) {
            body.append("\n").append(Component.literal("- ")
                    .append(Component.translatable(requirement)).withStyle(s -> s.withColor(0x8899AA)));
        }
        if (!entry.available() && !entry.reasonKey().isEmpty()) {
            body.append("\n").append(Component.translatable(entry.reasonKey())
                    .withStyle(s -> s.withColor(0xFF5555)));
        }
        return body;
    }

    /**
     * Flattens the server's rows into a draw list of category headers and action rows.
     *
     * <p>Categories appear in enum order and empty ones are omitted, so a target who offers only
     * restorative actions shows one "Resolve" heading rather than four headings and three gaps.
     */
    private void buildRows() {
        rows.clear();
        Map<ActionCategory, List<ActionMenuEntry>> grouped = new EnumMap<>(ActionCategory.class);
        for (ActionMenuEntry entry : menu.actions()) {
            grouped.computeIfAbsent(entry.category(), ignored -> new ArrayList<>()).add(entry);
        }
        for (ActionCategory category : ActionCategory.values()) {
            List<ActionMenuEntry> entries = grouped.get(category);
            if (entries == null || entries.isEmpty()) continue;
            rows.add(category);
            rows.addAll(entries);
        }
    }

    /**
     * Never pauses the integrated server. Any menu that froze singleplayer would also freeze the
     * captivity cap, the guard challenge window and every action channel — which turns opening a
     * panel into a way of stopping the clock you are being judged by.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    /** Sends the row, behind a confirmation when the action is hostile and confirmations are on. */
    private void activate(ActionMenuEntry entry) {
        if (entry.hostile() && McaCrimeConfig.CLIENT.confirmHostileActions.get()) {
            if (minecraft != null) {
                minecraft.setScreen(new CrimeConfirmationScreen(this,
                        Component.translatable(entry.labelKey()), menu.targetName(), entry.legality(),
                        () -> send(entry)));
            }
            return;
        }
        send(entry);
        if (minecraft != null) minecraft.setScreen(null);
    }

    private void send(ActionMenuEntry entry) {
        CrimeNetwork.sendToServer(new StartActionC2SPacket(UUID.randomUUID(), menu.menuId(),
                menu.revision(), entry.actionId(), menu.targetId()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (list != null) {
            scrollAmount = list.getScrollAmount();
        }

        renderBackground(graphics);
        CrimeSprites.panel(graphics, panelLeft, panelTop, PANEL_W, panelHeight);

        graphics.drawString(font, title, panelLeft + 8, panelTop + 8, PanelColours.TEXT, false);
        if (menu.targetName() != null) {
            graphics.drawString(font, menu.targetName(), panelLeft + 8, panelTop + 20,
                    PanelColours.TEXT_MUTED, false);
        }
        if (extraHeader > 0) {
            renderExtraHeader(graphics, panelLeft + 8, panelTop + HEADER_H - 2, PANEL_W - 16);
        }

        // The list draws its own well, rows, scissor and scrollbar, and the rows raise their own
        // tooltips, so there is nothing left for the screen to do by hand.
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
