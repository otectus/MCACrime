package dev.otectus.mcacrime.client.screen;

import dev.otectus.mcacrime.client.ClientCaseData;
import dev.otectus.mcacrime.client.screen.widget.CrimeRowList;
import dev.otectus.mcacrime.client.screen.widget.CrimeRowWidget;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.network.CaseLedgerS2CPacket;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.RequestCaseLedgerC2SPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * The player's own case file.
 *
 * <p>Before this, a player's criminal record existed entirely in the save file and in operator
 * commands. They could see a Heat number and a band colour — both of which decay — but never the
 * thing those numbers were derived from, which is a list of specific unresolved cases that do not
 * decay and that guards act on. Paying a fine settled cases the player had never been shown.
 *
 * <p>Everything here comes from one server-authored answer to one argument-free request, so the
 * screen cannot be used to look at anybody else's record. It is a read-only view; settling a case is
 * still an action, and still goes through the action engine.
 *
 * <p>The answer arrives a tick or two after the screen opens, which this screen used to handle by
 * resizing its panel from inside {@code render()}. That is no longer merely untidy: the rows are
 * widgets now, and rebuilding them mid-render would mutate the list the screen is iterating. So the
 * panel is sized once, the list scrolls, and the rebuild happens in {@link #tick()}.
 */
public final class CaseDossierScreen extends Screen {

    private static final int PANEL_W = 260;
    private static final int ROW_H = 20;
    private static final int HEADER_H = 46;
    private static final int FOOTER_H = 28;
    /** Margin between the panel's edge and the list well, matching the footer button's. */
    private static final int LIST_INSET = 6;
    /** Rows the panel is sized to show at once. Beyond this the list scrolls, as it should. */
    private static final int VISIBLE_ROWS = 10;

    private final Screen parent;

    private int panelLeft;
    private int panelTop;
    private int panelHeight;
    private CrimeRowList list;
    private int lastRevision = -1;

    public CaseDossierScreen(Screen parent) {
        super(Component.translatable("gui.mcacrime.dossier.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Ask on open rather than on a timer. A record is a record: it does not need to be live, and
        // polling it would turn an occasional screen into steady traffic on a busy server.
        CrimeNetwork.sendToServer(new RequestCaseLedgerC2SPacket());

        panelHeight = Math.min(HEADER_H + VISIBLE_ROWS * ROW_H + FOOTER_H, Math.max(140, height - 40));
        panelLeft = (width - PANEL_W) / 2;
        panelTop = (height - panelHeight) / 2;

        int listTop = panelTop + HEADER_H;
        int listHeight = panelHeight - HEADER_H - FOOTER_H;

        // Inset by the same margin as the footer button, so the well sits inside the panel's bevel
        // rather than over it.
        list = new CrimeRowList(minecraft, panelLeft + LIST_INSET, listTop, listTop + listHeight,
                PANEL_W - 2 * LIST_INSET, ROW_H);
        lastRevision = -1;
        rebuildRows();

        addRenderableWidget(list);
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(panelLeft + 6, panelTop + panelHeight - 24, PANEL_W - 12, 20).build());
    }

    /**
     * Rebuilds the rows when the server's answer lands.
     *
     * <p>In {@code tick()} rather than {@code render()} on purpose: this replaces the screen's
     * widgets, and doing that during a render pass would throw a {@code ConcurrentModificationException}
     * out of vanilla's own draw loop.
     */
    @Override
    public void tick() {
        if (ClientCaseData.revision() != lastRevision) {
            rebuildRows();
        }
    }

    private void rebuildRows() {
        lastRevision = ClientCaseData.revision();
        list.clearRows();

        List<CaseLedgerS2CPacket.Row> rows = ClientCaseData.rows();
        if (rows.isEmpty()) {
            // A row rather than a bare line of text, so the empty state fills the well instead of
            // leaving it looking like something failed to load.
            list.addRow(new CrimeRowWidget(
                    Component.translatable(ClientCaseData.received()
                            ? "gui.mcacrime.dossier.clean" : "gui.mcacrime.dossier.loading"),
                    0xAAAAAA, Component.empty(), PanelColours.TEXT_MUTED, false, null));
            return;
        }

        for (CaseLedgerS2CPacket.Row row : rows) {
            list.addRow(caseRow(row));
        }
    }

    private CrimeRowWidget caseRow(CaseLedgerS2CPacket.Row row) {
        boolean open = row.resolution() == Resolution.UNRESOLVED || row.resolution() == Resolution.ESCAPED;
        int resolutionRgb = PanelColours.resolution(row.resolution());

        CrimeRowWidget widget = new CrimeRowWidget(
                Component.translatable(crimeKey(row)),
                resolutionRgb,
                Component.translatable(resolutionKey(row.resolution())),
                open ? PanelColours.onPanel(resolutionRgb) : PanelColours.TEXT_DISABLED,
                // A dossier row is a record, not a control: enabled here means "still open", which is
                // what decides whether it reads as live text or as settled history.
                open,
                null);
        widget.setTooltip(Tooltip.create(describe(row), Component.translatable(crimeKey(row))));
        return widget;
    }

    /** The case in full: where, whether anybody saw it, and what it was assessed at. */
    private static Component describe(CaseLedgerS2CPacket.Row row) {
        // Bright colours, not the on-panel ones: a tooltip is drawn on its own dark background.
        MutableComponent body = Component.translatable(crimeKey(row));
        body.append("\n").append(Component.translatable(resolutionKey(row.resolution()))
                .withStyle(s -> s.withColor(PanelColours.resolution(row.resolution()))));
        body.append("\n").append(Component.translatable(row.witnessed()
                        ? "gui.mcacrime.dossier.witnessed" : "gui.mcacrime.dossier.unwitnessed")
                .withStyle(s -> s.withColor(0xAAAAAA)));
        if (!row.community().isEmpty()) {
            body.append("\n").append(Component.translatable("gui.mcacrime.dossier.community", row.community())
                    .withStyle(s -> s.withColor(0x8899AA)));
        }
        if (row.fine() > 0L) {
            body.append("\n").append(Component.translatable("gui.mcacrime.dossier.fine", row.fine())
                    .withStyle(s -> s.withColor(0x8899AA)));
        }
        return body;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        CrimeSprites.panel(graphics, panelLeft, panelTop, PANEL_W, panelHeight);
        graphics.drawString(font, title, panelLeft + 8, panelTop + 8, PanelColours.TEXT, false);

        int openCount = ClientCaseData.totalOpen();
        graphics.drawString(font, Component.translatable("gui.mcacrime.dossier.open", openCount),
                panelLeft + 8, panelTop + 22,
                openCount > 0 ? PanelColours.onPanel(0xFF5555) : PanelColours.TEXT_MUTED, false);
        if (ClientCaseData.totalDue() > 0L) {
            Component due = Component.translatable("gui.mcacrime.dossier.due", ClientCaseData.totalDue());
            graphics.drawString(font, due, panelLeft + PANEL_W - 8 - font.width(due), panelTop + 22,
                    PanelColours.TEXT_MUTED, false);
        }
        graphics.drawString(font, Component.translatable("gui.mcacrime.dossier.columns"),
                panelLeft + 8, panelTop + 34, PanelColours.TEXT_DISABLED, false);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * The crime's own translation key. Datapack crimes get one for free under the same convention, so
     * a pack that adds a crime type gets a readable dossier row without touching this screen.
     */
    private static String crimeKey(CaseLedgerS2CPacket.Row row) {
        return "crime." + row.crimeType().getNamespace() + "." + row.crimeType().getPath();
    }

    private static String resolutionKey(Resolution resolution) {
        return "gui.mcacrime.resolution." + resolution.name().toLowerCase(java.util.Locale.ROOT);
    }
}
