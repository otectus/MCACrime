package dev.otectus.mcacrime.job;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiType;

import java.util.function.Predicate;

/**
 * The rules the two acquisition mixins apply, as plain functions (0.7.2 §10.1).
 *
 * <p>Separated from the mixins deliberately. A mixin cannot be unit-tested — it exists only once the
 * transformer has run — so a decision left inside one is a decision nothing checks. Everything here is
 * a pure function of values the mixin has already read, which is what {@code NativeOccupationPolicy}'s
 * tests exercise.
 */
public final class NativeOccupationPolicy {

    private NativeOccupationPolicy() {
    }

    /**
     * The acquirable-job-site predicate with Mask Station removed, and nothing else changed.
     *
     * <p>Applied to every profession except Thief. An unemployed villager searches with {@code NONE}'s
     * predicate, which is the whole {@code minecraft:acquirable_job_site} tag — and the station has to
     * be in that tag to be findable at all — so without this filter the first unemployed farmhand to
     * walk past a Mask Station would become a Thief without going through any of Crime's checks. It
     * also, and just as deliberately, filters MCA's own "set workplace" action, which reads the same
     * accessor.
     *
     * <p>The original predicate is composed rather than replaced, so a profession whose acquirable set
     * was already narrowed by another mod keeps that narrowing.
     */
    public static Predicate<Holder<PoiType>> excludingMaskStation(Predicate<Holder<PoiType>> original) {
        return excludingMaskStation(original, NativeOccupationPolicy::isMaskStation);
    }

    /**
     * The same composition with the station test supplied.
     *
     * <p>The overload exists so the composition itself can be tested without a live POI registry: what
     * matters here is that the original predicate is still consulted and that exactly one type is
     * removed, and neither of those needs a real {@link Holder}.
     */
    public static Predicate<Holder<PoiType>> excludingMaskStation(Predicate<Holder<PoiType>> original,
                                                                  Predicate<Holder<PoiType>> isStation) {
        Predicate<Holder<PoiType>> base = original == null ? holder -> false : original;
        Predicate<Holder<PoiType>> station = isStation == null ? holder -> false : isStation;
        return holder -> !station.test(holder) && base.test(holder);
    }

    public static boolean isMaskStation(Holder<PoiType> holder) {
        return holder != null && holder.is(CrimePoiTypes.MASK_STATION_KEY);
    }

    /** What the native job-assignment wrapper does with one potential job site. */
    public enum NativeDecision {
        /** Not our business: run vanilla's behaviour exactly as it was. */
        DELEGATE,
        /** A committed Thief arriving at its own reservation — commit through the transaction. */
        REBIND,
        /** Unapproved acquisition: clean up this reservation and let nothing else happen. */
        REJECT
    }

    /**
     * Decides what to do about a villager whose potential job site is a Mask Station.
     *
     * @param maskStation   the potential site is a Mask Station in this villager's own dimension
     * @param arrived       the villager is genuinely standing at it. The spawn-distance shortcut
     *                      vanilla also accepts ({@code assignProfessionWhenSpawned}) is deliberately
     *                      not honoured: it would let a freshly spawned villager claim a station from
     *                      across the village without ever walking there.
     * @param committedThief Crime already holds a Thief occupation for this villager
     * @param enabled       thieves are enabled in config
     */
    public static NativeDecision decide(boolean maskStation, boolean arrived, boolean committedThief,
                                        boolean enabled) {
        if (!maskStation) {
            return NativeDecision.DELEGATE;
        }
        if (!enabled || !committedThief) {
            return NativeDecision.REJECT;
        }
        return arrived ? NativeDecision.REBIND : NativeDecision.REJECT;
    }
}
