package dev.otectus.mcacrime.engine;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.HeatChangedEvent;
import dev.otectus.mcacrime.api.event.KarmaChangedEvent;
import dev.otectus.mcacrime.api.event.WantedStatusChangedEvent;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.crime.CrimeMath;
import dev.otectus.mcacrime.crime.KarmaSource;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.CrimeAttachments;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;

import java.util.function.LongUnaryOperator;

/**
 * The single server-authoritative, idempotent chokepoint for every Karma/Heat change (spec §0 rules
 * 1–2, §20). Nothing else writes the two longs. Each mutator: clamps to config bounds, short-circuits
 * if the value is unchanged (replay-safe — packet spam or redundant calls fire nothing), recomputes the
 * band, fires the relevant NeoForge-bus event, and pushes a display-only sync to the owning client.
 *
 * <p>All methods take a {@link ServerPlayer} — there is no path to mutate state from a client value.
 */
public final class CrimeState {

    /** Attribution for a change with no more specific origin — decay, reconcile, an admin command. */
    public static final ResourceLocation INTERNAL = McaCrime.id("internal");

    private CrimeState() {
    }

    // ------------------------------------------------------------------ reads

    public static long getKarma(ServerPlayer player) {
        return CrimeAttachments.get(player).getKarma();
    }

    public static long getHeat(ServerPlayer player) {
        return CrimeAttachments.get(player).getHeat();
    }

    /** The band derived from current karma under current thresholds (never stale). */
    public static Band getBand(ServerPlayer player) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        return Band.fromKarma(getKarma(player), c.karmaBlueThreshold.get(), c.karmaRedThreshold.get());
    }

    public static boolean isWanted(ServerPlayer player) {
        return CrimeMath.isWanted(getHeat(player), McaCrimeConfig.COMMON.wantedHeatThreshold.get());
    }

    /** Whether the player is currently resisting arrest, having refused a guard's challenge (§13.2). */
    public static boolean isResistingArrest(ServerPlayer player) {
        return CrimeAttachments.get(player).isResistingArrest();
    }

    /**
     * Starts or ends resisting arrest, routing through the same chokepoint every other status change
     * uses so the client is told and nothing has to poll.
     *
     * <p>The clock is {@code onlineTicksLived}, not game time. Every consequence in this mod that
     * decays does so on the player's own online clock precisely so that logging out is not a way to
     * wait one out, and a refusal is the last thing that should be escapable by quitting for a minute.
     */
    public static void setResistingArrest(ServerPlayer player, boolean resisting) {
        PlayerCrimeData data = CrimeAttachments.get(player);
        boolean was = data.isResistingArrest();
        data.setResistingArrestUntilTick(resisting
                ? data.getOnlineTicksLived() + McaCrimeConfig.COMMON.resistingArrestTicks.get()
                : 0L);
        // Refusing ends the confrontation and hands the player to the resisting projection. The
        // phase and the flag must not both claim to describe the same moment.
        if (resisting && data.arrestPhase() == dev.otectus.mcacrime.enforcement.ArrestPhase.CONFRONTED) {
            data.setArrest(null);
        }
        if (was != data.isResistingArrest()) {
            CrimeNetwork.sendSelfStatus(player);
        }
    }

    /**
     * Expires a lapsed refusal, edge-triggered so the sync happens once rather than every tick.
     * Called from the existing decay handler; needs no ticker of its own.
     */
    public static void tickResistingArrest(ServerPlayer player, PlayerCrimeData data) {
        if (data.getResistingArrestUntilTick() > 0L && !data.isResistingArrest()) {
            data.setResistingArrestUntilTick(0L);
            CrimeNetwork.sendSelfStatus(player);
        }
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
        applyHeat(player, current -> current + delta, INTERNAL, "");
    }

    /**
     * Attributed Heat change. {@code source} names what caused it and {@code dedupeKey} identifies the
     * transaction, so a listener can tell a genuine second change from a redelivered first one.
     */
    public static void addHeat(ServerPlayer player, long delta, ResourceLocation source, String dedupeKey) {
        applyHeat(player, current -> current + delta, source, dedupeKey);
    }

    public static void setHeat(ServerPlayer player, long value) {
        applyHeat(player, current -> value, INTERNAL, "");
    }

    public static void setHeat(ServerPlayer player, long value, ResourceLocation source, String dedupeKey) {
        applyHeat(player, current -> value, source, dedupeKey);
    }

    public static void clearHeat(ServerPlayer player) {
        setHeat(player, 0L);
    }

    public static void clearHeat(ServerPlayer player, ResourceLocation source, String dedupeKey) {
        setHeat(player, 0L, source, dedupeKey);
    }

    /** Recomputes the cached band + wanted flag under current config (login reconcile, config change). */
    public static void recomputeDerived(ServerPlayer player) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        PlayerCrimeData data = CrimeAttachments.get(player);
        data.setCachedBand(Band.fromKarma(data.getKarma(), c.karmaBlueThreshold.get(), c.karmaRedThreshold.get()));
        data.setWantedCached(CrimeMath.isWanted(data.getHeat(), c.wantedHeatThreshold.get()));
    }

    // ------------------------------------------------------------------ internals

    /** Apply both incident values before any listener runs. The caller inserts the case first. */
    public static void applyIncident(ServerPlayer player, long karma, long heat,
                                     ResourceLocation source, String incidentId) {
        java.util.Optional.of(CrimeAttachments.get(player)).ifPresent(data -> {
            var c = McaCrimeConfig.COMMON;
            long oldKarma = data.getKarma(), oldHeat = data.getHeat();
            long newKarma = CrimeMath.clamp(dev.otectus.mcacrime.util.SafeMath.addSat(oldKarma, karma),
                    c.karmaMin.get(), c.karmaMax.get());
            long newHeat = CrimeMath.clamp(dev.otectus.mcacrime.util.SafeMath.addSat(oldHeat, heat), 0, c.heatMax.get());
            Band oldBand = Band.fromKarma(oldKarma, c.karmaBlueThreshold.get(), c.karmaRedThreshold.get());
            Band newBand = Band.fromKarma(newKarma, c.karmaBlueThreshold.get(), c.karmaRedThreshold.get());
            boolean wasWanted = CrimeMath.isWanted(oldHeat, c.wantedHeatThreshold.get());
            boolean nowWanted = CrimeMath.isWanted(newHeat, c.wantedHeatThreshold.get());
            data.setKarma(newKarma);
            data.setHeat(newHeat);
            data.setCachedBand(newBand);
            data.setWantedCached(nowWanted);
            if (oldKarma != newKarma) dev.otectus.mcacrime.incident.IncidentNotifications.post(
                    new KarmaChangedEvent(player, oldKarma, newKarma, oldBand, newBand, KarmaSource.CRIME));
            if (oldHeat != newHeat) dev.otectus.mcacrime.incident.IncidentNotifications.post(
                    new HeatChangedEvent(player, oldHeat, newHeat, source, incidentId));
            if (wasWanted != nowWanted) dev.otectus.mcacrime.incident.IncidentNotifications.post(
                    new WantedStatusChangedEvent(player, nowWanted, newHeat));
            dev.otectus.mcacrime.incident.IncidentNotifications.run(() -> CrimeNetwork.sendSelfStatus(player));
        });
    }

    private static void applyKarma(ServerPlayer player, LongUnaryOperator op, KarmaSource source) {
        PlayerCrimeData data = CrimeAttachments.get(player);
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
        NeoForge.EVENT_BUS.post(new KarmaChangedEvent(player, oldKarma, newKarma, oldBand, newBand, source));
        CrimeNetwork.sendSelfStatus(player);
    }

    private static void applyHeat(ServerPlayer player, LongUnaryOperator op,
                                  ResourceLocation source, String dedupeKey) {
        PlayerCrimeData data = CrimeAttachments.get(player);
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        long oldHeat = data.getHeat();
        long newHeat = CrimeMath.clamp(op.applyAsLong(oldHeat), 0L, c.heatMax.get());
        if (newHeat == oldHeat) {
            return; // idempotent no-op: no event, no sync
        }
        long threshold = c.wantedHeatThreshold.get();
        boolean wasWanted = CrimeMath.isWanted(oldHeat, threshold);
        boolean nowWanted = CrimeMath.isWanted(newHeat, threshold);
        data.setHeat(newHeat);
        data.setWantedCached(nowWanted);
        // Every real change is reported; the wanted event stays reserved for the boundary crossing,
        // so a listener that only cares about pursuit does not have to filter out ordinary decay.
        NeoForge.EVENT_BUS.post(new HeatChangedEvent(player, oldHeat, newHeat,
                source == null ? INTERNAL : source, dedupeKey));
        if (wasWanted != nowWanted) {
            NeoForge.EVENT_BUS.post(new WantedStatusChangedEvent(player, nowWanted, newHeat));
        }
        CrimeNetwork.sendSelfStatus(player);
    }
}
