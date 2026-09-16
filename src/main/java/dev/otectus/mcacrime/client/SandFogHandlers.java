package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.effect.SandBlindness;
import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * What sand in your own eyes looks like (0.7.2 §13.4).
 *
 * <p>A short fog and a dusty tint, nothing else. No shader requirement, no full-screen flashing, no
 * camera shake and no opaque overlay: the brief is "you cannot see far", not "you cannot look at the
 * screen", and the accessible version of temporary blindness is the one that stays readable.
 *
 * <p>The rule that matters is subtraction only. If the player already has Blindness or Darkness the
 * engine has already brought the fog in closer than sand would, and this handler leaves it alone —
 * sand may never <em>improve</em> anybody's vision, on application or on expiry (SAND-11).
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class SandFogHandlers {

    /** How far you can see with sand in your eyes. */
    private static final float FAR_PLANE = 6.0F;
    private static final float NEAR_PLANE = 0.5F;

    private SandFogHandlers() {
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!sanded()) {
            return;
        }
        // Only ever pull the fog in. A stronger source already in effect keeps its own numbers.
        if (event.getFarPlaneDistance() > FAR_PLANE) {
            event.setFarPlaneDistance(FAR_PLANE);
        }
        if (event.getNearPlaneDistance() > NEAR_PLANE) {
            event.setNearPlaneDistance(NEAR_PLANE);
        }
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (!sanded()) {
            return;
        }
        // A dust colour blended in, rather than replaced: underwater, lava and the nether keep reading
        // as themselves while the air in front of you goes gritty.
        event.setRed(blend(event.getRed(), 0.76F));
        event.setGreen(blend(event.getGreen(), 0.70F));
        event.setBlue(blend(event.getBlue(), 0.50F));
    }

    private static float blend(float current, float dust) {
        return current * 0.45F + dust * 0.55F;
    }

    /**
     * Whether the local player is sanded and nothing stronger is already deciding their view.
     *
     * <p>Blindness and Darkness are checked here rather than in each handler so that both the
     * distance and the colour agree about who owns the screen.
     */
    private static boolean sanded() {
        LivingEntity player = Minecraft.getInstance().player;
        if (player == null || !SandBlindness.isBlinded(player)) {
            return false;
        }
        return !player.hasEffect(MobEffects.BLINDNESS) && !player.hasEffect(MobEffects.DARKNESS);
    }

}
