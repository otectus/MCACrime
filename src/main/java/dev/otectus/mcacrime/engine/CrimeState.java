package dev.otectus.mcacrime.engine;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.KarmaChangedEvent;
import dev.otectus.mcacrime.api.event.WantedStatusChangedEvent;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.crime.CrimeMath;
import dev.otectus.mcacrime.crime.KarmaSource;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.CrimeCapabilities;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;

import java.util.function.LongUnaryOperator;

/**
 * The single server-authoritative, idempotent chokepoint for every Karma/Heat change (spec §0 rules
 * 1–2, §20). Nothing else writes the two longs. Each mutator: clamps to config bounds, short-circuits
 * if the value is unchanged (replay-safe — packet spam or redundant calls fire nothing), recomputes the
 * band, fires the relevant Forge-bus event, and pushes a display-only sync to the owning client.
 *
 * <p>All methods take a {@link ServerPlayer} — there is no path to mutate state from a client value.
 */
public final class CrimeState {

    private CrimeState() {
    }

    // ------------------------------------------------------------------ reads

    public static long getKarma(ServerPlayer player) {
        return CrimeCapabilities.get(player).map(PlayerCrimeData::getKarma).orElse(0L);
    }

    public static long getHeat(ServerPlayer player) {
        return CrimeCapabilities.get(player).map(PlayerCrimeData::getHeat).orElse(0L);
    }

    /** The band derived from current karma under current thresholds (never stale). */
    public static Band getBand(ServerPlayer player) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        return Band.fromKarma(getKarma(player), c.karmaBlueThreshold.get(), c.karmaRedThreshold.get());
    }

    public static boolean isWanted(ServerPlayer player) {
        return CrimeMath.isWanted(getHeat(player), McaCrimeConfig.COMMON.wantedHeatThreshold.get());
    }

    // ------------------------------------------------------------------ karma mutators

    public static void addKarma(ServerPlayer player, long delta, KarmaSource source) {
        applyKarma(player, current -> current + delta, source);
    }

    public static void setKarma(ServerPlayer player, long value, KarmaSource source) {
        applyKarma(player, current -> value, source);
    }

    // ------------------------------------------------------------------ heat mutators

    public static void addHeat(ServerPlayer player, long delta) {
        applyHeat(player, current -> current + delta);
    }

    public static void setHeat(ServerPlayer player, long value) {
        applyHeat(player, current -> value);
    }

    public static void clearHeat(ServerPlayer player) {
        setHeat(player, 0L);
    }

    /** Recomputes the cached band + wanted flag under current config (login reconcile, config change). */
    public static void recomputeDerived(ServerPlayer player) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        CrimeCapabilities.get(player).ifPresent(data -> {
            data.setCachedBand(Band.fromKarma(data.getKarma(), c.karmaBlueThreshold.get(), c.karmaRedThreshold.get()));
            data.setWantedCached(CrimeMath.isWanted(data.getHeat(), c.wantedHeatThreshold.get()));
        });
    }

    // ------------------------------------------------------------------ internals

    private static void applyKarma(ServerPlayer player, LongUnaryOperator op, KarmaSource source) {
        CrimeCapabilities.get(player).ifPresentOrElse(data -> {
            McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
            long oldKarma = data.getKarma();
            long newKarma = CrimeMath.clamp(op.applyAsLong(oldKarma), c.karmaMin.get(), c.karmaMax.get());
            if (newKarma == oldKarma) {
                return; // idempotent no-op: no event, no sync
            }
            Band oldBand = Band.fromKarma(oldKarma, c.karmaBlueThreshold.get(), c.karmaRedThreshold.get());
            Band newBand = Band.fromKarma(newKarma, c.karmaBlueThreshold.get(), c.karmaRedThreshold.get());
            data.setKarma(newKarma);
            data.setCachedBand(newBand);
            MinecraftForge.EVENT_BUS.post(new KarmaChangedEvent(player, oldKarma, newKarma, oldBand, newBand, source));
            CrimeNetwork.sendSelfStatus(player);
        }, () -> McaCrime.LOGGER.debug("Karma mutation on a player without the crime capability; ignoring"));
    }

    private static void applyHeat(ServerPlayer player, LongUnaryOperator op) {
        CrimeCapabilities.get(player).ifPresentOrElse(data -> {
            McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
            long oldHeat = data.getHeat();
            long newHeat = CrimeMath.clamp(op.applyAsLong(oldHeat), 0L, c.heatMax.get());
            if (newHeat == oldHeat) {
                return; // idempotent no-op
            }
            long threshold = c.wantedHeatThreshold.get();
            boolean wasWanted = CrimeMath.isWanted(oldHeat, threshold);
            boolean nowWanted = CrimeMath.isWanted(newHeat, threshold);
            data.setHeat(newHeat);
            data.setWantedCached(nowWanted);
            if (wasWanted != nowWanted) {
                MinecraftForge.EVENT_BUS.post(new WantedStatusChangedEvent(player, nowWanted, newHeat));
            }
            CrimeNetwork.sendSelfStatus(player);
        }, () -> McaCrime.LOGGER.debug("Heat mutation on a player without the crime capability; ignoring"));
    }
}
