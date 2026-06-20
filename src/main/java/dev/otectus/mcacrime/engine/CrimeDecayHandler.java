package dev.otectus.mcacrime.engine;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyConfine;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.crime.CrimeMath;
import dev.otectus.mcacrime.crime.KarmaSource;
import dev.otectus.mcacrime.jail.JailConfine;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.state.CrimeCapabilities;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Optional;

/**
 * Online-tick Karma/Heat decay (spec §3.1, §7.1). The decay clock is a mod-owned monotonic counter
 * ({@link PlayerCrimeData#getOnlineTicksLived()}) advanced once per server player tick — never
 * {@code level.getGameTime()} or {@code player.tickCount}. Because it only advances while the player is
 * online and ticking, <b>logout pauses decay and restart resumes it</b> by construction: a player can
 * neither wait out Heat offline nor lose Karma while away.
 *
 * <p>Karma normalises gently toward 0 at {@code karmaDecayPerDay} per online MC day; Heat bleeds off at
 * {@code heatDecayPerMinute} per online minute. Both route through {@link CrimeState} so they clamp and
 * fire the wanted/band-change events. Only the counter increment runs every tick; the (rare) decay work
 * is throttled to ~1 Hz and does nothing while a player's values are already at rest.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeDecayHandler {

    /** One Minecraft day. */
    private static final long KARMA_PERIOD_TICKS = 24000L;
    /** One online minute (20 ticks/second × 60). */
    private static final long HEAT_PERIOD_TICKS = 1200L;

    private CrimeDecayHandler() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        Optional<PlayerCrimeData> opt = CrimeCapabilities.get(player);
        if (opt.isEmpty()) {
            return;
        }
        PlayerCrimeData data = opt.get();
        long online = data.incrementOnlineTicks(); // the only clock decay reads
        // Jail sentence + kidnapping cap decrement every online tick (matching the clock) BEFORE the throttle.
        if (data.isJailed()) {
            JailService.tick(player, data);
        }
        if (data.getHeldByRef() != null) {
            CustodyService.tick(player); // kidnapping captive: real-time captivity-cap accounting (§7.2)
        }
        if (player.tickCount % 20 != 0) {
            return; // throttle decay work to ~once per second
        }
        applyHeatDecay(player, data, online);
        applyKarmaDecay(player, data, online);
        // Throttled (~1/s) soft-confine / breakout / tether checks.
        if (data.isJailed()) {
            JailConfine.tick(player, data);
        }
        if (data.getHeldByRef() != null) {
            CustodyConfine.tick(player); // kidnapping captive: soft-tether (escape, never jailbreak)
        }
    }

    private static void applyHeatDecay(ServerPlayer player, PlayerCrimeData data, long online) {
        long steps = CrimeMath.stepsElapsed(online, data.getLastHeatDecayTick(), HEAT_PERIOD_TICKS);
        if (steps <= 0L) {
            return;
        }
        long perStep = McaCrimeConfig.COMMON.heatDecayPerMinute.get();
        if (perStep > 0L && data.getHeat() > 0L) {
            CrimeState.addHeat(player, -(perStep * steps)); // clamps at 0; fires wanted edge
        }
        data.setLastHeatDecayTick(data.getLastHeatDecayTick() + steps * HEAT_PERIOD_TICKS);
    }

    private static void applyKarmaDecay(ServerPlayer player, PlayerCrimeData data, long online) {
        long steps = CrimeMath.stepsElapsed(online, data.getLastKarmaDecayTick(), KARMA_PERIOD_TICKS);
        if (steps <= 0L) {
            return;
        }
        long perStep = McaCrimeConfig.COMMON.karmaDecayPerDay.get();
        long karma = data.getKarma();
        if (perStep > 0L && karma != 0L) {
            long target = CrimeMath.decayTowardZero(karma, perStep, steps);
            long delta = target - karma;
            if (delta != 0L) {
                CrimeState.addKarma(player, delta, KarmaSource.DECAY); // clamps; recomputes band; fires event
            }
        }
        data.setLastKarmaDecayTick(data.getLastKarmaDecayTick() + steps * KARMA_PERIOD_TICKS);
    }
}
