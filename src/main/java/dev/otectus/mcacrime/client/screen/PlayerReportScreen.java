package dev.otectus.mcacrime.client.screen;

import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.ReportMenuS2CPacket;
import dev.otectus.mcacrime.network.SubmitPlayerReportC2SPacket;
import dev.otectus.mcacrime.report.PlayerCrimeReportService;
import dev.otectus.mcacrime.util.TickFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/** Compact two-step picker: choose one server-offered account, then press Report. */
public final class PlayerReportScreen extends Screen {
    private static final int PANEL_W = 300;
    private static final int PANEL_H = 190;
    private static final int PAGE_SIZE = 5;
    private final ReportMenuS2CPacket packet;
    private final Screen parent;
    private int page;
    private int selected = -1;

    public PlayerReportScreen(ReportMenuS2CPacket packet, Screen parent) {
        super(Component.translatable("gui.mcacrime.report.title"));
        this.packet = packet;
        this.parent = parent;
    }

    @Override protected void init() { rebuild(); }

    private void rebuild() {
        clearWidgets();
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        List<PlayerCrimeReportService.Choice> rows = packet.menu().choices();
        int start = page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < rows.size(); slot++) {
            int index = start + slot;
            var row = rows.get(index);
            Component label = Component.empty().append(row.suspectName()).append(" — ")
                    .append(Component.translatable(crimeKey(row)));
            addRenderableWidget(Button.builder(label, button -> { selected = index; rebuild(); })
                    .bounds(left + 10, top + 30 + slot * 23, PANEL_W - 20, 20).build());
        }
        Button report = Button.builder(Component.translatable("gui.mcacrime.report.submit"), b -> submit())
                .bounds(left + 45, top + PANEL_H - 27, 90, 20).build();
        report.active = selected >= 0 && selected < rows.size();
        addRenderableWidget(report);
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(left + PANEL_W - 135, top + PANEL_H - 27, 90, 20).build());
        if (page > 0) addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; selected = -1; rebuild(); })
                .bounds(left + 10, top + PANEL_H - 27, 20, 20).build());
        if ((page + 1) * PAGE_SIZE < rows.size()) addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; selected = -1; rebuild(); })
                .bounds(left + PANEL_W - 30, top + PANEL_H - 27, 20, 20).build());
    }

    private void submit() {
        if (selected < 0 || selected >= packet.menu().choices().size()) return;
        CrimeNetwork.CHANNEL.sendToServer(new SubmitPlayerReportC2SPacket(UUID.randomUUID(),
                packet.menu().id(), packet.menu().revision(), packet.menu().responderId(),
                packet.menu().choices().get(selected).evidenceId()));
        if (minecraft != null) minecraft.setScreen(null);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        CrimeSprites.panel(graphics, left, top, PANEL_W, PANEL_H);
        graphics.drawCenteredString(font, title, left + PANEL_W / 2, top + 10, PanelColours.TEXT);
        if (packet.menu().choices().isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable(packet.menu().emptyReasonKey()),
                    left + PANEL_W / 2, top + 70, PanelColours.TEXT_MUTED);
        } else if (selected >= 0) {
            var row = packet.menu().choices().get(selected);
            graphics.drawString(font, font.plainSubstrByWidth(row.area().getString(), 130),
                    left + 12, top + 151, PanelColours.TEXT_MUTED, false);
            graphics.drawString(font, Component.translatable("gui.mcacrime.report.age",
                    TickFormat.compact(Math.max(0L, packet.menu().issuedAt() - row.occurredAt()))),
                    left + 150, top + 151, PanelColours.TEXT_MUTED, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private static String crimeKey(PlayerCrimeReportService.Choice row) {
        return "crime." + row.incidentType().getNamespace() + "." + row.incidentType().getPath();
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
