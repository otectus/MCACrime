package dev.otectus.mcacrime.compat.mca.client;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.RequestActionMenuC2SPacket;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;

/**
 * Client-only, package-root-neutral bridge that contributes a real narrated widget to MCA's interaction
 * screen without replacing MCA JSON or sending MCA packets. Forge's screen-init hook keeps the shipped
 * jar independent of MCA's relocated client package names; the server still validates the exact UUID.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class McaInteractionScreenBridge {
    private static volatile boolean applied;

    private McaInteractionScreenBridge() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        String name = screen.getClass().getName();
        if (!name.endsWith(".client.gui.InteractScreen")) return;
        Entity villager = findVillager(screen);
        if (villager == null) return;
        Button crime = Button.builder(Component.translatable("gui.mcacrime.actions"), button ->
                        CrimeNetwork.CHANNEL.sendToServer(new RequestActionMenuC2SPacket(villager.getUUID())))
                .bounds(Math.max(4, screen.width - 106), 6, 100, 20)
                .build();
        event.addListener(crime);
        applied = true;
    }

    public static boolean wasApplied() { return applied; }

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
