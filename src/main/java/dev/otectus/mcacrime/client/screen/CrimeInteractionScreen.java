package dev.otectus.mcacrime.client.screen;

import dev.otectus.mcacrime.network.ActionMenuS2CPacket;
import dev.otectus.mcacrime.network.ActionMenuEntry;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.StartActionC2SPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/** Mod-owned, server-populated action panel; it never computes availability or outcomes locally. */
public final class CrimeInteractionScreen extends Screen {
    private final ActionMenuS2CPacket menu;
    private final Screen parent;

    public CrimeInteractionScreen(ActionMenuS2CPacket menu, Screen parent) {
        super(Component.translatable("gui.mcacrime.actions"));
        this.menu = menu;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int y = height / 2 - 50;
        for (ActionMenuEntry action : menu.actions()) {
            Button row = Button.builder(Component.translatable(action.labelKey()), button -> {
                CrimeNetwork.CHANNEL.sendToServer(new StartActionC2SPacket(UUID.randomUUID(), menu.menuId(),
                        menu.revision(), action.actionId(), menu.targetId()));
                if (minecraft != null) minecraft.setScreen(null);
            }).bounds(width / 2 - 100, y, 200, 20).build();
            row.active = action.available();
            if (!action.available() && !action.reasonKey().isEmpty()) {
                row.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable(action.reasonKey())));
            }
            addRenderableWidget(row);
            y += 24;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(width / 2 - 100, Math.min(height - 28, y + 6), 200, 20).build());
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 55, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
