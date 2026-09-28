package dev.otectus.mcacrime.ledger;

import org.jetbrains.annotations.Nullable;
import java.util.Locale;
import java.util.Optional;

/**
 * What a sentence is, as opposed to how long it is (0.7.5 §3.19, M6.6).
 *
 * <p>Every sentence before this release was implicitly {@link #CUSTODIAL}, and a row written before
 * it has no kind at all — which is why {@link #parseOr} exists and why absent reads as custodial.
 * That is not a fallback, it is the truth about those worlds: nothing in them could ever have been
 * capital.
 *
 * <p>The kind is <b>never upgraded after binding</b>. A sentence is priced and classified once, when
 * custody commits, from the cases assessed at that moment; a later arrival, a replayed packet or a
 * second arrest cannot turn a custodial sentence into a capital one. The only movement the model
 * allows is downward, by clemency: {@link #CAPITAL} to {@link #CUSTODIAL} through a commutation,
 * which is an explicit privileged transaction.
 */
public enum SentenceKind {

    /** Time served. Every sentence this mod handed down before 0.7.5, and nearly all of them since. */
    CUSTODIAL,

    /**
     * A death sentence, for the one offence that may produce one: killing a guard.
     *
     * <p>Carrying this kind is not a countdown. Nothing in the mod kills a condemned subject on its
     * own — there is no timer, no scheduled task and no redstone path to it — and with no usable
     * device the sentence simply stands while the subject stays in custody.
     */
    CAPITAL;

    /** The stable lowercase id, used in saved data, in commands and on the dossier. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether this sentence may ever end in an execution. */
    public boolean capital() {
        return this == CAPITAL;
    }

    /** Resolves an id or a constant name, in any case. Empty for anything else. */
    public static Optional<SentenceKind> parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String needle = raw.trim().toUpperCase(Locale.ROOT);
        for (SentenceKind kind : values()) {
            if (kind.name().equals(needle)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /** The same, with a fallback. Saved data reads through this, and absent means custodial. */
    public static SentenceKind parseOr(@Nullable String raw, SentenceKind fallback) {
        return parse(raw).orElse(fallback == null ? CUSTODIAL : fallback);
    }
}
