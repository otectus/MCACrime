package dev.otectus.mcacrime.client.screen;

import dev.otectus.mcacrime.lockpick.LockpickOutcome;
import dev.otectus.mcacrime.lockpick.LockpickProfile;
import dev.otectus.mcacrime.lockpick.LockpickSession;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.LockpickAttemptC2SPacket;
import dev.otectus.mcacrime.network.LockpickBeginS2CPacket;
import dev.otectus.mcacrime.network.LockpickCancelC2SPacket;
import dev.otectus.mcacrime.network.LockpickPhaseS2CPacket;
import dev.otectus.mcacrime.network.LockpickResultS2CPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The lockpicking dial: aim the pick at the ghost target and confirm (M3.3, spec §8).
 *
 * <p>Client-only, and it decides nothing. It draws where the server said the target is, reads the
 * player's aim from the mouse, and sends an angle. Whether that angle was close enough, what the
 * meter is worth, whether the lock opened and what the pick cost are all answered on the server,
 * which is the single difference between this screen and the one it is adapted from — that one
 * computes the outcome locally and tells the server what happened.
 *
 * <p>The drawn meter interpolates the server's drain so the bar moves smoothly between packets, and
 * is corrected to the server's value on every phase message. The interpolation is decoration: a client
 * that draws a full bar still loses when the server's meter hits zero.
 */
public final class LockpickScreen extends Screen {

    private static final int DIAL_RADIUS = 52;
    private static final int BAR_WIDTH = 120;
    private static final int BAR_HEIGHT = 6;
    /** How wide the drawn target wedge is, in degrees, as a hint rather than the true window. */
    private static final float TARGET_HINT_DEGREES = 7.5F;

    private final long sessionId;
    private final int progressIncrease;
    private final int speedIncrease;
    private final int drainDivisor;
    private int phase;
    private int targetMilliDegrees;
    private float meter;
    private float aimDegrees;
    private long lastDrainNanos = System.nanoTime();
    private Component message = Component.translatable("mcacrime.lockpick.hint");

    public LockpickScreen(LockpickBeginS2CPacket begin) {
        super(Component.translatable("mcacrime.lockpick.title"));
        this.sessionId = begin.sessionId();
        this.progressIncrease = begin.progressIncrease();
        this.speedIncrease = begin.speedIncrease();
        // From the packet, never from this client's own config file: COMMON is server-authoritative
        // and a client reading it would be reading the wrong server's answer.
        this.drainDivisor = Math.max(1, begin.drainDivisor());
        this.phase = begin.phase();
        this.targetMilliDegrees = begin.targetMilliDegrees();
        this.meter = begin.meter();
    }

    /** The server moved the phase on, or corrected the meter. Both are simply adopted. */
    public void acceptPhase(LockpickPhaseS2CPacket msg) {
        if (msg.sessionId() != sessionId) {
            return;
        }
        this.phase = msg.phase();
        this.targetMilliDegrees = msg.targetMilliDegrees();
        this.meter = msg.meter();
    }

    /** The session ended. */
    public void acceptResult(LockpickResultS2CPacket msg) {
        if (msg.sessionId() != sessionId) {
            return;
        }
        if (msg.outcome() == LockpickOutcome.SUCCESS && minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable("mcacrime.lockpick.opened"), true);
        }
        onCloseWithoutCancel();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        long now = System.nanoTime();
        float seconds = Math.min(0.25F, (now - lastDrainNanos) / 1_000_000_000F);
        lastDrainNanos = now;
        // The same expression the server runs, at twenty ticks a second, purely so the bar is not a
        // staircase between packets. The server's number wins on every phase message.
        float perTick = (phase + 1) * (float) speedIncrease / drainDivisor;
        meter = Math.max(0F, meter - perTick * seconds * 20F);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        aimDegrees = angleTo(mouseX, mouseY);
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        aimDegrees = angleTo(mouseX, mouseY);
        sendAttempt();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 32 || key == 257) { // space, enter
            sendAttempt();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    private void sendAttempt() {
        CrimeNetwork.sendLockpickAttempt(new LockpickAttemptC2SPacket(sessionId, phase,
                Math.round(aimDegrees * 1000F)));
    }

    private float angleTo(double mouseX, double mouseY) {
        double dx = mouseX - width / 2.0D;
        double dy = mouseY - height / 2.0D;
        double degrees = Math.toDegrees(Math.atan2(-dx, -dy));
        return (float) ((degrees % 360.0D + 360.0D) % 360.0D);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics);
        aimDegrees = angleTo(mouseX, mouseY);
        int cx = width / 2;
        int cy = height / 2;

        drawSpoke(graphics, cx, cy, targetMilliDegrees / 1000F, 0x80FFD24A, DIAL_RADIUS);
        drawSpoke(graphics, cx, cy, targetMilliDegrees / 1000F - TARGET_HINT_DEGREES, 0x40FFD24A,
                DIAL_RADIUS - 8);
        drawSpoke(graphics, cx, cy, targetMilliDegrees / 1000F + TARGET_HINT_DEGREES, 0x40FFD24A,
                DIAL_RADIUS - 8);
        drawSpoke(graphics, cx, cy, aimDegrees, 0xFFE8E8E8, DIAL_RADIUS);

        int barX = cx - BAR_WIDTH / 2;
        int barY = cy + DIAL_RADIUS + 16;
        graphics.fill(barX - 1, barY - 1, barX + BAR_WIDTH + 1, barY + BAR_HEIGHT + 1, 0xFF202020);
        int filled = Math.round(BAR_WIDTH * Math.min(1F, meter / LockpickProfile.WIN_METER));
        graphics.fill(barX, barY, barX + filled, barY + BAR_HEIGHT, 0xFF4FA64F);

        graphics.drawCenteredString(font, title, cx, cy - DIAL_RADIUS - 26, 0xFFFFFF);
        graphics.drawCenteredString(font, message, cx, barY + BAR_HEIGHT + 6, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partial);
    }

    /** One radial line, drawn as short filled segments: no texture, no model, no resource to ship. */
    private void drawSpoke(GuiGraphics graphics, int cx, int cy, float degrees, int colour, int radius) {
        double radians = Math.toRadians(degrees);
        for (int r = 10; r < radius; r += 2) {
            int x = cx - (int) Math.round(Math.sin(radians) * r);
            int y = cy - (int) Math.round(Math.cos(radians) * r);
            graphics.fill(x, y, x + 2, y + 2, colour);
        }
    }

    @Override
    public void onClose() {
        CrimeNetwork.sendLockpickCancel(new LockpickCancelC2SPacket(sessionId));
        onCloseWithoutCancel();
    }

    /** Closes without telling the server, for the case where the server is what closed it. */
    private void onCloseWithoutCancel() {
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    /** The progress one alignment is worth, for the tooltip a later HUD pass may want. */
    public int progressIncrease() {
        return progressIncrease;
    }

    /** The angle the server is asking for, in milli-degrees. */
    public int targetMilliDegrees() {
        return LockpickSession.wrap(targetMilliDegrees);
    }
}
