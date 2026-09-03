package dev.otectus.mcacrime.compat.mca.client;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.RequestActionMenuC2SPacket;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.lang.reflect.Field;

/**
 * Client-only, package-root-neutral bridge that contributes a real narrated widget to MCA's interaction
 * screen without replacing MCA JSON or sending MCA packets. The screen-init event keeps the shipped
 * jar independent of MCA's client package names; the server still validates the exact UUID.
 *
 * <p>This button stays a stock {@link Button}, drawn from vanilla's own widget texture, and is the one
 * place in the mod that deliberately does <em>not</em> use {@code CrimeSprites}. It is the only button
 * we draw inside somebody else's screen: matching this mod's chrome would make it the one foreign
 * element in MCA's panel, which is the opposite of what a bridge is for.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class McaInteractionScreenBridge {
    private static volatile boolean applied;
    private static volatile boolean warned;

    private McaInteractionScreenBridge() {}

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
        Button crime = Button.builder(Component.translatable("gui.mcacrime.actions"), button ->
                        CrimeNetwork.sendToServer(new RequestActionMenuC2SPacket(villager.getUUID())))
                .bounds(Math.max(4, screen.width - 106), 6, 100, 20)
                .build();
        event.addListener(crime);
        applied = true;
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
