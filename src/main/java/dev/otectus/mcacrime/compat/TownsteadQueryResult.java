package dev.otectus.mcacrime.compat;

import javax.annotation.Nullable;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;

/**
 * The answer to one Townstead question: a value, "nothing to say", or "that went wrong".
 *
 * <p>Three outcomes rather than an {@link Optional} because the two empty cases are not the same
 * fact and must not read the same way to a caller or to an operator. {@link Unavailable} is the
 * ordinary, silent state — Townstead is absent, the capability did not bind, or this entity simply
 * has no Townstead state. {@link Failed} means the call was expected to work and did not, which is
 * the only one of the three that is worth a diagnostic line.
 *
 * <p><b>Neither empty case ever yields a value.</b> That is the rule this type exists to enforce:
 * with a plain record carrying defaults, an absent Townstead would hand every caller a villager whose
 * hunger is 0, and the guard code reading it would conclude the village is starving. Callers must
 * choose their own fallback through {@link #orElse} — and for needs specifically, the "is this zero
 * real?" question is answered inside {@link TownsteadNeedsView} rather than at each call site.
 *
 * @param <T> the view type this query produces
 */
public sealed interface TownsteadQueryResult<T> {

    /** Townstead answered. The value is never null. */
    record Available<T>(T value) implements TownsteadQueryResult<T> {
        public Available {
            if (value == null) {
                throw new IllegalArgumentException("an AVAILABLE result must carry a value");
            }
        }
    }

    /**
     * Nothing to report, and nothing is wrong: no Townstead, no capability, or no state for this
     * target. The normal path on the great majority of installs, so it is never logged.
     */
    record Unavailable<T>(String reason) implements TownsteadQueryResult<T> {
        public Unavailable {
            reason = reason == null ? "" : reason;
        }
    }

    /** Townstead was there and the read did not work. Worth showing an operator. */
    record Failed<T>(String reason) implements TownsteadQueryResult<T> {
        public Failed {
            reason = reason == null || reason.isBlank() ? "unknown error" : reason;
        }
    }

    static <T> TownsteadQueryResult<T> available(T value) {
        return new Available<>(value);
    }

    static <T> TownsteadQueryResult<T> unavailable() {
        return new Unavailable<>("");
    }

    static <T> TownsteadQueryResult<T> unavailable(String reason) {
        return new Unavailable<>(reason);
    }

    /** Unavailable because the capability behind this query did not bind. */
    static <T> TownsteadQueryResult<T> missing(TownsteadCapability capability) {
        return new Unavailable<>("capability " + capability.id() + " is not available");
    }

    static <T> TownsteadQueryResult<T> failed(String reason) {
        return new Failed<>(reason);
    }

    /** True only for {@link Available}. */
    default boolean isAvailable() {
        return this instanceof Available<T>;
    }

    default boolean isFailed() {
        return this instanceof Failed<T>;
    }

    /**
     * The value as an {@link Optional}, present only for {@link Available}.
     *
     * <p>Not called {@code value()}: that name belongs to {@link Available}'s own record component,
     * which returns the value itself rather than an optional, and one type cannot carry both.
     */
    default Optional<T> asOptional() {
        return this instanceof Available<T> available ? Optional.of(available.value()) : Optional.empty();
    }

    /** The value, or {@code fallback} for either empty case. The only way to get a default. */
    default T orElse(@Nullable T fallback) {
        return this instanceof Available<T> available ? available.value() : fallback;
    }

    /** The value, or a hard failure. For tests and for code that has already checked. */
    default T orThrow() {
        if (this instanceof Available<T> available) {
            return available.value();
        }
        throw new NoSuchElementException("Townstead query has no value: " + describe());
    }

    /** Maps the value, keeping the empty case and its reason intact. */
    default <R> TownsteadQueryResult<R> map(Function<T, R> mapper) {
        if (this instanceof Available<T> available) {
            R mapped = mapper.apply(available.value());
            return mapped == null ? TownsteadQueryResult.unavailable("mapped to nothing")
                    : TownsteadQueryResult.available(mapped);
        }
        if (this instanceof Failed<T> failed) {
            return TownsteadQueryResult.failed(failed.reason());
        }
        return TownsteadQueryResult.unavailable(((Unavailable<T>) this).reason());
    }

    /** A short form for diagnostics; never includes the value itself. */
    default String describe() {
        if (this instanceof Available<T>) {
            return "available";
        }
        if (this instanceof Failed<T> failed) {
            return "failed: " + failed.reason();
        }
        String reason = ((Unavailable<T>) this).reason();
        return reason.isEmpty() ? "unavailable" : "unavailable (" + reason + ")";
    }
}
