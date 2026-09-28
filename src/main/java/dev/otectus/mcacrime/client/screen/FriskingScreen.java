package dev.otectus.mcacrime.client.screen;

import dev.otectus.mcacrime.client.ClientFriskData;
import dev.otectus.mcacrime.menu.FriskingLayout;
import dev.otectus.mcacrime.menu.FriskingMenu;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.FriskTransferC2SPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Looking through somebody's pockets (M5.2).
 *
 * <p>Drawn with this mod's own sprite sheet rather than a container background, and written without
 * any of the upstream screen's library helpers — the whole of it is vanilla
 * {@link AbstractContainerScreen} plus {@link CrimeSprites}.
 *
 * <p>It is a viewer with one verb. Clicking a slot sends a transfer request and nothing else: the
 * menu itself rejects every vanilla click type server-side, so there is no drag, no shift-move, no
 * double-click collect and no cursor stack to lose. Left-click asks for the whole stack, right-click
 * for half, and both are only ever requests — what actually moves is decided on the server.
 */
public class FriskingScreen extends AbstractContainerScreen<FriskingMenu> {

    public FriskingScreen(FriskingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = FriskingLayout.WIDTH;
        this.imageHeight = FriskingLayout.height(menu.slotCount());
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        // There is no player inventory on this screen, so vanilla's inventory label would name a
        // section that is not drawn.
        this.inventoryLabelY = Integer.MIN_VALUE;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        CrimeSprites.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        for (Slot slot : this.menu.slots) {
            CrimeSprites.well(graphics, leftPos + slot.x - 1, topPos + slot.y - 1, 18, 18);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
        Component legend = Component.translatable(ClientFriskData.kind().messageKey() + ".legend");
        graphics.drawString(this.font, legend, 8, imageHeight - 14,
                ChatFormatting.DARK_GRAY.getColor() == null ? 0x404040
                        : ChatFormatting.DARK_GRAY.getColor(), false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    /**
     * One click, one request.
     *
     * <p>Deliberately not routed through {@code super.mouseClicked}: that would feed the click into
     * the vanilla menu click pipeline, which this menu rejects wholesale. Sending the intent directly
     * is both the only thing that works and the only thing that should.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Slot slot = findSlot(mouseX, mouseY);
        if (slot != null) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                int count = button == 1 ? Math.max(1, stack.getCount() / 2) : stack.getCount();
                CrimeNetwork.CHANNEL.sendToServer(new FriskTransferC2SPacket(
                        ClientFriskData.sessionId(), slot.index, ClientFriskData.revision(slot.index),
                        count));
                return true;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** The slot under the cursor, or null. */
    private Slot findSlot(double mouseX, double mouseY) {
        for (Slot slot : this.menu.slots) {
            if (isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY) && slot.isActive()) {
                return slot;
            }
        }
        return null;
    }

    @Override
    public void onClose() {
        ClientFriskData.clear();
        super.onClose();
    }
}
