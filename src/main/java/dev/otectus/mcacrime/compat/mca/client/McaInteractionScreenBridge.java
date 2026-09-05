package dev.otectus.mcacrime.compat.mca.client;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.client.ClientCriminalJobData;
import dev.otectus.mcacrime.client.ClientWeaponPolicy;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.RequestActionMenuC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Client-only, package-root-neutral bridge that contributes a real narrated widget to MCA's interaction
 * screen without replacing MCA JSON or sending MCA packets. The screen-init event keeps the shipped
 * jar independent of MCA's client package names; the server still validates the exact UUID.
 *
 * <p>This button stays a stock {@link Button}, drawn from vanilla's own widget texture, and is the one
 * place in the mod that deliberately does <em>not</em> use {@code CrimeSprites}. It is the only button
 * we draw inside somebody else's screen: matching this mod's chrome would make it the one foreign
 * element in MCA's panel, which is the opposite of what a bridge is for.
 *
 * <p>Since 0.5.1 the button sits at the bottom, under MCA's own widgets rather than at a hardcoded
 * offset: the panel is a different height on different MCA builds and at different GUI scales, so the
 * placement is measured from the widgets actually on the screen and only falls back to the screen edge
 * when there are none. It is also gated: without a drawn weapon there is no crime to commit, so the
 * button is visible, disabled, and carries a tooltip saying which hand the weapon has to be in. A fence
 * is the exception — trading contraband is not coercion, so an unarmed player may open that menu.
 *
 * <p>The gate here is convenience only. {@code CrimeActionService.openMenu} re-checks it against the
 * server's own config and the persisted job, so a client that skips the button gains nothing.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class McaInteractionScreenBridge {
    private static volatile boolean applied;
    private static volatile boolean warned;

    /** The screen the live button belongs to, so the per-frame refresh stops when it closes. */
    @Nullable
    private static Screen host;
    @Nullable
    private static Button button;
    @Nullable
    private static UUID target;

    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_HEIGHT = 20;
    /** Clear of MCA's own widgets, close enough to still read as part of the same panel. */
    private static final int GAP = 4;

    private McaInteractionScreenBridge() {}

    /**
     * Whether the button can be clicked, and what to explain when it cannot.
     *
     * <p>Pure, and separated from the widget precisely because it is the part with rules in it: the
     * matrix is unit tested and the screen code below is left with nothing but placement.
     *
     * @param tooltipKey the line to show, or {@code null} when the button needs no explanation
     */
    public record ButtonState(boolean active, @Nullable String tooltipKey) {

        /** The gate as it stood before 0.5.1's server-side switch: a weapon is always required. */
        public static ButtonState compute(boolean targetFound, boolean armed, boolean fence,
                                          boolean allowOffHand) {
            return compute(targetFound, armed, fence, allowOffHand, true);
        }

        /**
         * The full gate, including the server's {@code requireWeaponForCrimeMenu} switch.
         *
         * <p>A server with the gate off treats every player as armed for the purposes of this button,
         * because it is. Explicit rather than folded into {@code armed} by the caller so the disagreement
         * this exists to prevent -- a grey button over a menu the server would have opened -- cannot come
         * back through a caller that forgot.
         */
        public static ButtonState compute(boolean targetFound, boolean armed, boolean fence,
                                          boolean allowOffHand, boolean weaponRequired) {
            boolean effectivelyArmed = armed || !weaponRequired;
            boolean active = targetFound && (effectivelyArmed || fence);
            if (effectivelyArmed) {
                return new ButtonState(active, null);
            }
            if (fence) {
                // The one villager an unarmed player may open the menu on, so the tooltip invites the
                // trade rather than repeating a weapon requirement that does not apply here.
                return new ButtonState(active, "gui.mcacrime.crime.fence_trade");
            }
            return new ButtonState(active, allowOffHand
                    ? "gui.mcacrime.crime.requires_weapon"
                    : "gui.mcacrime.crime.requires_weapon_main_hand");
        }
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!McaCrimeConfig.CLIENT.showButtonOnMcaScreen.get()) return;
        Screen screen = event.getScreen();
        String name = screen.getClass().getName();
        if (!name.endsWith(".client.gui.InteractScreen")) return;
        Entity villager = findVillager(screen);
        if (villager == null) {
            warnOnce(name);
            return;
        }
        UUID villagerId = villager.getUUID();
        Button crime = Button.builder(Component.translatable("gui.mcacrime.crime.button"), b -> {
                    // A disabled button never fires, but the send is guarded anyway: this is the packet
                    // the server would have to reject, and not sending it is cheaper than refusing it.
                    if (b.active) {
                        CrimeNetwork.sendToServer(new RequestActionMenuC2SPacket(villagerId));
                    }
                })
                .bounds(anchorX(screen), anchorY(screen), BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        refresh(crime, villagerId);
        event.addListener(crime);
        host = screen;
        button = crime;
        target = villagerId;
        applied = true;
    }

    /**
     * Re-evaluates the gate every frame the screen is up.
     *
     * <p>Every frame rather than on an event, because "a weapon is drawn" is two hand reads and there
     * is no client-side event that covers swapping the off-hand, scrolling the hotbar, and a policy
     * packet arriving. A button that stays grey for a second after the sword is out reads as broken.
     */
    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Pre event) {
        Button crime = button;
        if (crime == null || host == null || event.getScreen() != host) {
            return;
        }
        refresh(crime, target);
    }

    private static void refresh(Button crime, @Nullable UUID villagerId) {
        Player player = Minecraft.getInstance().player;
        boolean armed = ClientWeaponPolicy.hasQualifyingDrawnWeapon(player);
        boolean fence = villagerId != null && ClientCriminalJobData.job(villagerId) == CriminalJob.FENCE;
        ButtonState state = ButtonState.compute(villagerId != null, armed, fence,
                ClientWeaponPolicy.allowOffHand(), ClientWeaponPolicy.crimeMenuRequiresWeapon());
        crime.active = state.active();
        crime.setTooltip(state.tooltipKey() == null
                ? null
                : Tooltip.create(Component.translatable(state.tooltipKey())));
    }

    private static int anchorX(Screen screen) {
        return McaCrimeConfig.CLIENT.crimeButtonAnchor.get() == McaCrimeConfig.CrimeButtonAnchor.TOP_RIGHT
                ? Math.max(4, screen.width - 106)
                : screen.width / 2 - BUTTON_WIDTH / 2;
    }

    /**
     * The bottom of MCA's own layout, plus a gap — measured, not assumed. A build whose panel already
     * reaches the bottom of the screen pins the button to the last row that still fits rather than
     * pushing it off the edge.
     */
    private static int anchorY(Screen screen) {
        if (McaCrimeConfig.CLIENT.crimeButtonAnchor.get() == McaCrimeConfig.CrimeButtonAnchor.TOP_RIGHT) {
            return 6;
        }
        int floor = screen.height - 24;
        int maxBottom = Integer.MIN_VALUE;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractWidget widget) {
                maxBottom = Math.max(maxBottom, widget.getY() + widget.getHeight());
            }
        }
        return maxBottom == Integer.MIN_VALUE ? floor : Math.min(maxBottom + GAP, floor);
    }

    public static boolean wasApplied() { return applied; }

    /**
     * One line, once, when MCA's screen no longer carries a villager we can find.
     *
     * <p>The bridge is optional and the screen is somebody else's, so the failure is soft: MCA's own
     * interaction screen must keep working. But silent softness is indistinguishable from the config
     * being off, which is the state this log exists to rule out.
     */
    private static void warnOnce(String screenClass) {
        if (warned) return;
        warned = true;
        McaCrime.LOGGER.warn("No villager field found on {}; the MCA: Crime button is not being added. "
                + "MCA's interaction screen has probably changed shape.", screenClass);
    }

    /**
     * The villager the screen is about, found by the type of a field's value rather than by its name.
     *
     * <p>Names are the one thing an update is free to change, and MCA's field is a {@code VillagerLike}
     * rather than an {@code Entity}, so the declared type is no safer. What does hold is that the value
     * is the villager entity itself, which {@code McaCompat} can recognise.
     */
    private static Entity findVillager(Screen screen) {
        Class<?> type = screen.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(screen);
                    if (value instanceof Entity entity && McaCompat.isMcaVillager(entity)) return entity;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Try the next field; an MCA layout change must not break the base interaction screen.
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }
}
