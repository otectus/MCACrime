package dev.otectus.mcacrime.client.screen;

import dev.otectus.mcacrime.client.ClientPhysicalRestraintData;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.SelfRestraintC2SPacket;
import dev.otectus.mcacrime.restraint.ApplicationTransaction;
import dev.otectus.mcacrime.restraint.RestraintFamily;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * The self panel's restraint selector (0.7.5 M2.6, ledger row R10).
 *
 * <p>An explicit slot chosen by the player, which is the whole point. The source derives the body
 * region from the player's view pitch and compares a value in degrees against bounds in radians
 * ({@code mixin/PlayerMixin}), so two of its three regions are unreachable and the third catches
 * everything. A button per region has no such failure mode, and it is also the only shape that works
 * for somebody who cannot aim precisely.
 *
 * <p>Client-only and decides nothing. A row is drawn for every region the held item has a definition
 * for, greyed where the client already knows something is worn there; the server re-checks the slot,
 * the item, the gates and {@code allowSelfApplication} when the packet lands, so a modified client
 * that enables a greyed button gains a refusal.
 */
public final class SelfRestraintScreen extends Screen {

    private static final int PANEL_W = 176;
    private static final int PANEL_H = 118;
    private static final int ROW_H = 22;

    private final Screen parent;

    public SelfRestraintScreen(Screen parent) {
        super(Component.translatable("gui.mcacrime.self_restraint.title"));
        this.parent = parent;
    }

    /**
     * Whether there is anything to show: a restraint in either hand.
     *
     * <p>Asked before the button that opens this appears, so the self panel does not grow a control
     * that can only say "you are not holding anything".
     */
    public static boolean available(LocalPlayer player) {
        return player != null && heldFamily(player).isPresent();
    }

    private static Optional<RestraintFamily> heldFamily(LocalPlayer player) {
        Optional<RestraintFamily> main = CrimeItems.familyFor(player.getMainHandItem());
        return main.isPresent() ? main : CrimeItems.familyFor(player.getOffhandItem());
    }

    /** True when the off hand is the one holding it, which the packet has to say. */
    private static boolean usesOffHand(LocalPlayer player) {
        return CrimeItems.familyFor(player.getMainHandItem()).isEmpty()
                && CrimeItems.familyFor(player.getOffhandItem()).isPresent();
    }

    @Override
    protected void init() {
        LocalPlayer player = minecraft == null ? null : minecraft.player;
        if (player == null) {
            onClose();
            return;
        }
        RestraintFamily family = heldFamily(player).orElse(null);
        boolean offHand = usesOffHand(player);
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        int y = top + 28;

        for (RestraintSlot slot : RestraintSlot.values()) {
            boolean supported = family != null
                    && ApplicationTransaction.definitionFor(family, slot).isPresent();
            boolean worn = ClientPhysicalRestraintData.slot(player.getUUID(), slot).isPresent();
            Button button = Button.builder(
                            Component.translatable("gui.mcacrime.self_restraint.slot." + slot.id()),
                            b -> send(slot, offHand))
                    .bounds(left + 8, y, PANEL_W - 16, 20)
                    .build();
            button.active = supported && !worn;
            button.setTooltip(Tooltip.create(Component.translatable(
                    !supported ? "gui.mcacrime.self_restraint.unsupported"
                            : worn ? "gui.mcacrime.self_restraint.occupied"
                            : "gui.mcacrime.self_restraint.hint")));
            addRenderableWidget(button);
            y += ROW_H;
        }

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(left + 8, top + PANEL_H - 26, PANEL_W - 16, 20).build());
    }

    private void send(RestraintSlot slot, boolean offHand) {
        CrimeNetwork.sendSelfRestraint(new SelfRestraintC2SPacket(slot, offHand));
        onClose();
    }

    /** Never pauses the integrated server: a panel that stops the clock you are judged by is a bug. */
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
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        renderBackground(graphics);
        CrimeSprites.panel(graphics, left, top, PANEL_W, PANEL_H);
        graphics.drawString(font, title, left + 8, top + 8, PanelColours.TEXT, false);

        LocalPlayer player = minecraft == null ? null : minecraft.player;
        ItemStack held = player == null ? ItemStack.EMPTY
                : usesOffHand(player) ? player.getOffhandItem() : player.getMainHandItem();
        if (!held.isEmpty()) {
            graphics.drawString(font, held.getHoverName(), left + 8, top + 18,
                    PanelColours.TEXT_MUTED, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
