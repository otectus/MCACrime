package dev.otectus.mcacrime.compat;

import org.jetbrains.annotations.Nullable;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;

/**
 * The answer to one MCA Capitals question (0.7.8).
 *
 * <p>Four outcomes, because a royal decree has to tell three empty cases apart and must never treat
 * one as another:
 *
 * <ul>
 *   <li>{@link Available}: Capitals answered with a value.</li>
 *   <li>{@link Absent}: Capitals answered, and the answer is "nothing" -- this village is not a capital,
 *       this player holds no title. A confirmed fact, and the only empty case a decision may act on.</li>
 *   <li>{@link Unavailable}: nothing could be asked -- Capitals is absent, switched off, or the capability
 *       behind the question did not bind. Never logged; the ordinary state of most servers.</li>
 *   <li>{@link Failed}: the question was expected to work and threw. Worth an operator's attention.</li>
 * </ul>
 *
 * <p>The rule this type exists to enforce: <b>an unavailable eligibility check denies.</b> A decree
 * against somebody whose title could not be read is refused, never issued on the assumption that they
 * hold none, because "the read failed" and "they are a commoner" must not look the same.
 *
 * @param <T> the view type this query produces
 */
public sealed interface CapitalsQueryResult<T> {

    /** Capitals answered. The value is never null. */
    record Available<T>(T value) implements CapitalsQueryResult<T> {
        public Available {
            if (value == null) {
                throw new IllegalArgumentException("an AVAILABLE result must carry a value");
            }
        }
    }

    /** Capitals answered, and there is nothing: confirmed absence, not missing data. */
    record Absent<T>() implements CapitalsQueryResult<T> {
    }

    /** Nothing could be asked. Never a confirmed fact about the world. */
    record Unavailable<T>(String reason) implements CapitalsQueryResult<T> {
        public Unavailable {
            reason = reason == null ? "" : reason;
        }
    }

    /** Capitals was there and the read threw. */
    record Failed<T>(String reason) implements CapitalsQueryResult<T> {
        public Failed {
            reason = reason == null || reason.isBlank() ? "unknown error" : reason;
        }
    }

    static <T> CapitalsQueryResult<T> available(T value) {
        return new Available<>(value);
    }

    static <T> CapitalsQueryResult<T> absent() {
        return new Absent<>();
    }

    static <T> CapitalsQueryResult<T> unavailable(String reason) {
        return new Unavailable<>(reason);
    }

    /** Unavailable because the capability behind this query did not bind. */
    static <T> CapitalsQueryResult<T> missing(CapitalsCapability capability) {
        return new Unavailable<>("capability " + capability.id() + " is not available");
    }

    static <T> CapitalsQueryResult<T> failed(String reason) {
        return new Failed<>(reason);
    }

    default boolean isAvailable() {
        return this instanceof Available<T>;
    }

    /** True for a confirmed "nothing". */
    default boolean isAbsent() {
        return this instanceof Absent<T>;
    }

    /** True when Capitals gave an answer at all, a value or a confirmed "nothing". */
    default boolean answered() {
        return this instanceof Available<T> || this instanceof Absent<T>;
    }

    default Optional<T> asOptional() {
        return this instanceof Available<T> available ? Optional.of(available.value()) : Optional.empty();
    }

    default T orElse(@Nullable T fallback) {
        return this instanceof Available<T> available ? available.value() : fallback;
    }

    default T orThrow() {
        if (this instanceof Available<T> available) {
            return available.value();
        }
        throw new NoSuchElementException("Capitals query has no value: " + describe());
    }

    /** Maps the value, keeping every empty case and its reason intact. */
    default <R> CapitalsQueryResult<R> map(Function<T, R> mapper) {
        if (this instanceof Available<T> available) {
            R mapped = mapper.apply(available.value());
            return mapped == null ? new Absent<>() : new Available<>(mapped);
        }
        if (this instanceof Failed<T> failed) {
            return new Failed<>(failed.reason());
        }
        if (this instanceof Absent<T>) {
            return new Absent<>();
        }
        return new Unavailable<>(((Unavailable<T>) this).reason());
    }

    /** A short form for diagnostics; never includes the value itself. */
    default String describe() {
        if (this instanceof Available<T>) {
            return "available";
        }
        if (this instanceof Absent<T>) {
            return "none";
        }
        if (this instanceof Failed<T> failed) {
            return "failed: " + failed.reason();
        }
        String reason = ((Unavailable<T>) this).reason();
        return reason.isEmpty() ? "unavailable" : "unavailable (" + reason + ")";
    }
}
