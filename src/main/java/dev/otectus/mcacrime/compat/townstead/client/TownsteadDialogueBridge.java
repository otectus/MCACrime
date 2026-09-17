package dev.otectus.mcacrime.compat.townstead.client;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.client.ClientCriminalJobData;
import dev.otectus.mcacrime.client.ClientWeaponPolicy;
import dev.otectus.mcacrime.compat.TownsteadDialogueState;
import dev.otectus.mcacrime.compat.mca.client.McaInteractionScreenBridge;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.RequestActionMenuC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * The law action inside Townstead's dialogue screen, and the clean way out of it.
 *
 * <h2>What this is and is not</h2>
 *
 * <p>It is the Townstead counterpart of {@link McaInteractionScreenBridge}: one button, using the same
 * server-issued Crime menu, gated by the same pure {@code ButtonState} matrix so the two entry points
 * cannot come to different conclusions about whether a player may open it. It is deliberately
 * <em>not</em> an extension of that class's screen-name heuristic (reference §13.1, [C14]): recognising
 * a screen by the suffix of its class name and then hunting its fields for an entity is a guess that is
 * acceptable exactly once, for the one screen it was written against. Townstead has a dozen screens and
 * this one hands us the villager's UUID directly.
 *
 * <h2>Why reflection, not a type</h2>
 *
 * <p>Townstead is compiled against MCA, so its dialogue screen's own fields carry relocated MCA types.
 * Naming the screen here would put one of those in MCA: Crime's constant pool and re-link the whole mod
 * to a single MCA package layout — the failure {@code NoMcaStaticLinkTest} exists to prevent. So the
 * screen arrives as a {@link Screen} (vanilla, its real supertype) and everything Townstead-specific
 * about it is reached by field and method <em>name</em>. That is also why this class can be compiled and
 * shipped unconditionally while naming no Townstead type at all.
 *
 * <h2>Why it is here rather than in the mixin</h2>
 *
 * <p>The mixin is merged into somebody else's class and is the one place that must stay as close to
 * empty as possible — a line of logic in there is a line executing inside Townstead's own stack frame,
 * attributed to Townstead in any crash report. All of it lives here instead, behind a
 * {@code try/catch} the mixin repeats, so the worst a mistake in this file can do is a dialogue screen
 * with no Crime button on it.
 *
 * <p>Client-only. Nothing outside {@code mixin/townstead/client/} may name this class, and nothing on a
 * dedicated server ever loads it.
 */
public final class TownsteadDialogueBridge {

    /** Matches the MCA bridge's button, because it is the same button in a different panel. */
    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_HEIGHT = 20;

    /** Clear of the screen edge, and of Townstead's own top-row indicators. */
    private static final int MARGIN = 6;

    /** The field Townstead's dialogue screen keeps the conversation partner's id in. */
    private static final String VILLAGER_UUID_FIELD = "villagerUUID";

    /** The field holding the controller that owns the dialogue camera. */
    private static final String CAMERA_FIELD = "cameraController";

    /** The camera controller's own "put it back where you found it" method. */
    private static final String CAMERA_RESTORE = "snapToOriginal";

    /** The screen's private notification to Townstead's server side that the dialogue ended. */
    private static final String SEND_STATE = "sendDialogueState";

    /** The screen's own "the player closed this" flag, which suppresses its queued reopen. */
    private static final String USER_CLOSE_FIELD = "userInitiatedClose";

    private TownsteadDialogueBridge() {
    }

    /**
     * The law button for this dialogue screen, or null when there is nothing to open it against.
     *
     * <p>Null rather than a disabled button when the villager cannot be identified: a dead control in
     * somebody else's panel is worse than no control, because it invites a player to conclude the
     * integration is broken when what actually happened is that Townstead changed a field name.
     *
     * <p>The active/tooltip matrix is {@link McaInteractionScreenBridge.ButtonState}'s, unchanged and
     * unduplicated. A peaceful option — an apology, a restitution offer, a surrender — stays reachable
     * unarmed; the coercive rows are filtered by the server, which re-checks all of this anyway.
     */
    @Nullable
    public static Button buildLawButton(Screen screen) {
        UUID villager = villagerId(screen);
        if (villager == null) {
            return null;
        }
        Button button = Button.builder(Component.translatable("gui.mcacrime.crime.button"), widget -> {
                    if (widget.active) {
                        // Mark first, send second. The server's answer arrives on a later tick and
                        // replaces the screen, and the mark has to already be on this screen by then or
                        // Townstead queues the conversation back on top of the Crime menu.
                        TownsteadDialogueState.markExternalClose(screen);
                        CrimeNetwork.sendToServer(new RequestActionMenuC2SPacket(villager));
                    }
                })
                .bounds(anchorX(screen), MARGIN, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();

        boolean fence = ClientCriminalJobData.job(villager) == CriminalJob.FENCE;
        McaInteractionScreenBridge.ButtonState state = McaInteractionScreenBridge.ButtonState.compute(
                true, ClientWeaponPolicy.hasQualifyingDrawnWeapon(Minecraft.getInstance().player), fence,
                ClientWeaponPolicy.allowOffHand(), ClientWeaponPolicy.crimeMenuRequiresWeapon());
        button.active = state.active();
        button.setTooltip(state.tooltipKey() == null ? null
                : Tooltip.create(Component.translatable(state.tooltipKey())));
        return button;
    }

    /**
     * The explicit external-interruption close, run once, from the head of Townstead's {@code removed()}.
     *
     * <p>Three things have to happen and they have to happen in this order, because the third is what
     * stops the first two from happening again:
     *
     * <ol>
     *   <li><b>The HUD comes back.</b> The dialogue hides it; a player left in a Crime menu with no
     *       hotbar, no health and no hearts has no way to get them back short of reopening a
     *       conversation they did not want.</li>
     *   <li><b>The camera goes home, and the server is told.</b> Townstead's own close branch does both;
     *       skipping them leaves the view framed on a villager who is no longer being spoken to and a
     *       dialogue token open on its server side.</li>
     *   <li><b>The screen is marked as user-closed.</b> Not a lie about who closed it — it is the flag
     *       Townstead's {@code removed()} tests two instructions later to decide whether to queue the
     *       screen back. Setting it is how the reopen is cancelled without cancelling the callback, so
     *       the vanilla {@code super.removed()} still runs exactly as it would have.</li>
     * </ol>
     *
     * <p>Every step is independently guarded. A Townstead point release that renamed the camera field
     * must still leave the HUD restored and the reopen cancelled.
     */
    public static void onScreenRemoved(Screen screen) {
        if (!TownsteadDialogueState.consumeExternalClose(screen)) {
            return; // an ordinary close: Townstead's own logic is correct and is left alone
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options != null) {
            minecraft.options.hideGui = false;
        }
        Object camera = readField(screen, CAMERA_FIELD);
        if (camera != null) {
            invoke(camera, CAMERA_RESTORE);
        }
        invokeBoolean(screen, SEND_STATE, false);
        writeBoolean(screen, USER_CLOSE_FIELD, true);
    }

    /**
     * The conversation partner's id, read from the screen.
     *
     * <p>Asked for by name first, because Townstead stores it as a plain {@link UUID} field beside the
     * MCA-typed villager reference — which is the whole reason this integration can be done without
     * touching an MCA type. The fallback scan is for a rename, and stops at the first {@code UUID}: a
     * dialogue screen has exactly one.
     */
    @Nullable
    public static UUID villagerId(Screen screen) {
        if (screen == null) {
            return null;
        }
        Object direct = readField(screen, VILLAGER_UUID_FIELD);
        if (direct instanceof UUID id) {
            return id;
        }
        for (Class<?> type = screen.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != UUID.class) {
                    // Only UUID-typed fields are touched. Reading an arbitrary field would resolve its
                    // declared type, and on this screen one of those is a relocated MCA class.
                    continue;
                }
                try {
                    field.setAccessible(true);
                    if (field.get(screen) instanceof UUID id) {
                        return id;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Try the next field; a Townstead layout change must not break its own dialogue.
                }
            }
        }
        return null;
    }

    /** Top-right or top-left, following the same preference as the MCA button, never the bottom. */
    private static int anchorX(Screen screen) {
        boolean right;
        try {
            right = McaCrimeConfig.CLIENT.crimeButtonAnchor.get()
                    == McaCrimeConfig.CrimeButtonAnchor.TOP_RIGHT;
        } catch (Throwable t) {
            right = true;
        }
        // Always the top edge, whichever side. The dialogue box, the choice panel and the needs icons
        // all live along the bottom of this screen (reference §13.1: custody information must not
        // obscure needs icons), so the one place a foreign widget is certain not to cover anything is
        // above them.
        return right ? Math.max(MARGIN, screen.width - BUTTON_WIDTH - MARGIN) : MARGIN;
    }

    @Nullable
    private static Object readField(Object owner, String name) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Not on this class; keep walking up.
            }
        }
        return null;
    }

    private static void writeBoolean(Object owner, String name, boolean value) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.setBoolean(owner, value);
                return;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Keep walking up; a missing flag costs a queued reopen, not a crash.
            }
        }
    }

    private static void invoke(Object owner, String name) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                method.invoke(owner);
                return;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Keep walking up.
            }
        }
    }

    private static void invokeBoolean(Object owner, String name, boolean argument) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, boolean.class);
                method.setAccessible(true);
                method.invoke(owner, argument);
                return;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Keep walking up.
            }
        }
    }
}
