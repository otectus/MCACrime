package dev.otectus.mcacrime.client.screen;

import dev.otectus.mcacrime.network.PlayerReportsS2CPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Read-only view of the player's own accepted reports and canonical outcomes. */
public final class PlayerReportsScreen extends Screen {
    private static final int PANEL_W = 300;
    private static final int PANEL_H = 190;
    private final PlayerReportsS2CPacket packet;
    private int page;

    public PlayerReportsScreen(PlayerReportsS2CPacket packet) {
        super(Component.translatable("gui.mcacrime.reports.title"));
        this.packet = packet;
    }

    @Override protected void init() {
        clearWidgets();
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(left + 110, top + PANEL_H - 27, 80, 20).build());
        if (page > 0) addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; init(); })
                .bounds(left + 10, top + PANEL_H - 27, 20, 20).build());
        if ((page + 1) * 8 < packet.reports().size())
            addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; init(); })
                    .bounds(left + PANEL_W - 30, top + PANEL_H - 27, 20, 20).build());
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        CrimeSprites.panel(graphics, left, top, PANEL_W, PANEL_H);
        graphics.drawCenteredString(font, title, left + PANEL_W / 2, top + 10, PanelColours.TEXT);
        if (packet.reports().isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("gui.mcacrime.reports.empty"),
                    left + PANEL_W / 2, top + 75, PanelColours.TEXT_MUTED);
        }
        int start = page * 8;
        for (int i = 0; i < 8 && start + i < packet.reports().size(); i++) {
            var row = packet.reports().get(start + i);
            int y = top + 30 + i * 16;
            Component status = Component.translatable("gui.mcacrime.report.status."
                    + row.status().name().toLowerCase(java.util.Locale.ROOT));
            graphics.drawString(font, font.plainSubstrByWidth(row.suspectName().getString(), 132), left + 12, y, PanelColours.TEXT, false);
            graphics.drawString(font, font.plainSubstrByWidth(status.getString(), 132), left + 155, y,
                    PanelColours.TEXT_MUTED, false);
            if (mouseX >= left + 12 && mouseX < left + PANEL_W - 12 && mouseY >= y && mouseY < y + 12)
                graphics.renderTooltip(font, Component.empty().append(row.suspectName()).append("\n").append(status), mouseX, mouseY);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
}
