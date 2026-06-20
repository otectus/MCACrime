package dev.otectus.mcacrime.event;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.CrimeWitnessedEvent;
import dev.otectus.mcacrime.crime.Band;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The three §10.3 ambient player messages missing from earlier phases: <b>band change</b>, <b>"a guard saw
 * your crime"</b>, and <b>why a guard is pursuing you</b>. Sent server-side via {@code sendSystemMessage}
 * (as the jail/fine/surrender feedback already is). A transient per-player throttle keyed on
 * {@code "uuid|kind"} keeps repeated events from spamming chat; it is cleared on logout and never persisted
 * (same rationale as the harm cooldown). Band transitions are inherently rare and are not throttled.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class AmbientMessages {

    private static final ConcurrentHashMap<String, Integer> LAST_SENT = new ConcurrentHashMap<>();

    private AmbientMessages() {
    }

    private static boolean enabled() {
        return McaCrimeConfig.COMMON.ambientMessagesEnabled.get();
    }

    /** True if a message of this kind was sent to this player within the throttle window (and records this send otherwise). */
    private static boolean throttled(ServerPlayer player, String kind) {
        int window = McaCrimeConfig.COMMON.ambientMessageThrottleTicks.get();
        int now = player.tickCount;
        String key = player.getUUID() + "|" + kind;
        Integer last = LAST_SENT.get(key);
        if (last != null && window > 0 && now - last < window) {
            return true;
        }
        LAST_SENT.put(key, now);
        return false;
    }

    /** Band transition message (rare; not throttled). Called from {@link CrimeBandSync}. */
    public static void bandChanged(ServerPlayer player, Band band) {
        if (!enabled()) {
            return;
        }
        player.sendSystemMessage(Component.translatable("mcacrime.msg.band." + band.name().toLowerCase(Locale.ROOT)));
    }

    @SubscribeEvent
    public static void onWitnessed(CrimeWitnessedEvent event) {
        if (!enabled()) {
            return;
        }
        ServerPlayer player = event.getPlayer();
        if (player == null || throttled(player, "witnessed")) {
            return;
        }
        player.sendSystemMessage(Component.translatable("mcacrime.msg.witnessed"));
    }

    /** Guard-pursuit reason message (throttled). Called from {@link dev.otectus.mcacrime.enforcement.GuardEnforcement}. */
    public static void noteGuardAggro(ServerPlayer player, String reasonKey) {
        if (!enabled() || throttled(player, "guardaggro")) {
            return;
        }
        player.sendSystemMessage(Component.translatable(reasonKey));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            String prefix = player.getUUID() + "|";
            LAST_SENT.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }
}
