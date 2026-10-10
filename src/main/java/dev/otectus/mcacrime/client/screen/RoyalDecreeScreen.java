package dev.otectus.mcacrime.client.screen;

import dev.otectus.mcacrime.action.ActionLegality;
import dev.otectus.mcacrime.decree.DecreeMenuService;
import dev.otectus.mcacrime.decree.DecreeReason;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.DecreeActionC2SPacket;
import dev.otectus.mcacrime.network.DecreeMenuS2CPacket;
import dev.otectus.mcacrime.util.TickFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * The royal decree screen (0.7.8): name a player present in the village and a reason, or withdraw one
 * of the capital's open decrees here.
 *
 * <p>Presentation only. Every row was chosen by the server, every answer names the server's menu by id
 * and revision with a fresh nonce, and the server re-checks the throne, the target's standing and the
 * store before anything is written. A player who could not be named is shown greyed with the reason,
 * so a ruler is never left guessing why somebody is missing.
 */
public final class RoyalDecreeScreen extends Screen {

    private static final int PANEL_W = 320;
    private static final int PANEL_H = 214;
    private static final int PAGE_SIZE = 4;

    private final DecreeMenuS2CPacket packet;
    private final Screen parent;
    private int candidatePage;
    private int openPage;
    private int selected = -1;
    private DecreeReason reason = DecreeReason.TRESPASS;

    public RoyalDecreeScreen(DecreeMenuS2CPacket packet, Screen parent) {
        super(Component.translatable("gui.mcacrime.decree.title"));
        this.packet = packet;
        this.parent = parent;
    }

    private DecreeMenuService.Menu menu() {
        return packet.menu();
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;

        List<DecreeMenuService.Candidate> rows = menu().candidates();
        int start = candidatePage * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < rows.size(); slot++) {
            int index = start + slot;
            DecreeMenuService.Candidate row = rows.get(index);
            Component label = index == selected
                    ? Component.literal("> ").append(row.name()).append(" <") : row.name();
            Button button = Button.builder(label, b -> {
                selected = index;
                rebuild();
            }).bounds(left + 10, top + 38 + slot * 22, 150, 20).build();
            button.active = row.eligible();
            if (!row.eligible() && !row.reasonKey().isEmpty()) {
                button.setTooltip(Tooltip.create(Component.translatable(row.reasonKey())));
            }
            addRenderableWidget(button);
        }
        if (candidatePage > 0) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                candidatePage--;
                selected = -1;
                rebuild();
            }).bounds(left + 10, top + 128, 20, 20).build());
        }
        if ((candidatePage + 1) * PAGE_SIZE < rows.size()) {
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                candidatePage++;
                selected = -1;
                rebuild();
            }).bounds(left + 140, top + 128, 20, 20).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.mcacrime.decree.reason",
                Component.translatable(reason.labelKey())), b -> {
            DecreeReason[] values = DecreeReason.values();
            reason = values[(reason.ordinal() + 1) % values.length];
            rebuild();
        }).bounds(left + 10, top + 152, 150, 20).build());

        Button issue = Button.builder(Component.translatable("gui.mcacrime.decree.issue"), b -> confirmIssue())
                .bounds(left + 10, top + PANEL_H - 28, 150, 20).build();
        issue.active = selected >= 0 && selected < rows.size() && rows.get(selected).eligible();
        addRenderableWidget(issue);

        List<DecreeMenuService.OpenRow> open = menu().open();
        int openStart = openPage * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && openStart + slot < open.size(); slot++) {
            DecreeMenuService.OpenRow row = open.get(openStart + slot);
            addRenderableWidget(Button.builder(Component.translatable("gui.mcacrime.decree.withdraw_row", row.name()),
                    b -> confirmWithdraw(row)).bounds(left + 170, top + 38 + slot * 22, 140, 20)
                    .tooltip(Tooltip.create(Component.translatable("gui.mcacrime.decree.open_row",
                            Component.translatable("mcacrime.decree.reason." + row.reasonId()),
                            TickFormat.compact(row.remainingTicks())))).build());
        }
        if (openPage > 0) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                openPage--;
                rebuild();
            }).bounds(left + 170, top + 128, 20, 20).build());
        }
        if ((openPage + 1) * PAGE_SIZE < open.size()) {
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                openPage++;
                rebuild();
            }).bounds(left + 290, top + 128, 20, 20).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(left + 170, top + PANEL_H - 28, 140, 20).build());
    }

    private void confirmIssue() {
        List<DecreeMenuService.Candidate> rows = menu().candidates();
        if (selected < 0 || selected >= rows.size() || minecraft == null) {
            return;
        }
        DecreeMenuService.Candidate row = rows.get(selected);
        DecreeReason chosen = reason;
        minecraft.setScreen(new CrimeConfirmationScreen(this, Component.translatable("gui.mcacrime.decree.issue"),
                row.name(), ActionLegality.LAWFUL, () -> send(DecreeMenuService.Op.ISSUE, row.player(), chosen.id())));
    }

    private void confirmWithdraw(DecreeMenuService.OpenRow row) {
        if (minecraft == null) {
            return;
        }
        minecraft.setScreen(new CrimeConfirmationScreen(this, Component.translatable("gui.mcacrime.decree.withdraw"),
                row.name(), ActionLegality.LAWFUL, () -> send(DecreeMenuService.Op.WITHDRAW, row.decree(), "")));
    }

    private void send(DecreeMenuService.Op op, UUID subject, String reasonId) {
        CrimeNetwork.CHANNEL.sendToServer(new DecreeActionC2SPacket(UUID.randomUUID(), menu().id(), menu().revision(),
                op, subject, reasonId));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        CrimeSprites.panel(graphics, left, top, PANEL_W, PANEL_H);
        graphics.drawString(font, title, left + (PANEL_W - font.width(title)) / 2, top + 8, PanelColours.TEXT, false);
        String village = font.plainSubstrByWidth(menu().village().getString(), PANEL_W - 20);
        graphics.drawString(font, village, left + (PANEL_W - font.width(village)) / 2, top + 20,
                PanelColours.TEXT_MUTED, false);
        graphics.drawString(font, Component.translatable("gui.mcacrime.decree.present"), left + 10, top + 28,
                PanelColours.TEXT, false);
        graphics.drawString(font, Component.translatable("gui.mcacrime.decree.open"), left + 170, top + 28,
                PanelColours.TEXT, false);
        if (menu().candidates().isEmpty()) {
            graphics.drawString(font, Component.translatable("gui.mcacrime.decree.nobody"), left + 12, top + 44,
                    PanelColours.TEXT_MUTED, false);
        }
        if (menu().open().isEmpty()) {
            graphics.drawString(font, Component.translatable("gui.mcacrime.decree.none_open"), left + 172, top + 44,
                    PanelColours.TEXT_MUTED, false);
        }
        Component terms = menu().payable()
                ? Component.translatable("gui.mcacrime.decree.terms", TickFormat.compact(menu().durationTicks()),
                        menu().fine())
                : Component.translatable("gui.mcacrime.decree.terms_unpayable", TickFormat.compact(menu().durationTicks()));
        graphics.drawString(font, font.plainSubstrByWidth(terms.getString(), PANEL_W - 20), left + 10, top + 176,
                PanelColours.TEXT_MUTED, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
