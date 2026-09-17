package dev.otectus.mcacrime.compat;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The public things that happen to a settlement, in MCA: Crime's vocabulary (reference §11.2).
 *
 * <h2>Why MCA: Crime owns this list</h2>
 *
 * <p>The obvious shape is to hand Townstead a reaction id straight from the code that arrests somebody.
 * That couples a legal transition to an animation: renaming a reaction in a resource pack would change
 * what MCA: Crime believes happened, and every new Townstead release would be a chance for the law to
 * stop working. So the law speaks in its own nouns, a datapack binds each of them to a Townstead
 * reaction, and the two can be versioned apart (see {@code TownsteadReactionBindings}).
 *
 * <h2>Why these are public-only</h2>
 *
 * <p>Every constant here names something a bystander could in principle know: a crime that became
 * common knowledge, a prisoner taken, a sentence finished, a cell found empty. There is deliberately no
 * constant for a crime nobody witnessed, and there never may be — a reaction is a thing villagers do
 * where other villagers can see them, and one triggered by private knowledge would publish it
 * (reference §11.1).
 *
 * <p>The ids are stable strings: they appear in {@code data/mcacrime/townstead/reaction_bindings/} and
 * in operator output, so renaming one breaks existing packs.
 */
public enum TownsteadReactionEvent {

    /** A crime became common knowledge here. */
    CRIME_WITNESSED("crime_witnessed",
            "a crime in this settlement became public knowledge"),

    /** Somebody is being threatened right now, in the open. */
    CRIME_THREATENED("crime_threatened",
            "a resident is being threatened where others can see it"),

    /** A noise nobody can attribute: the scene changes without anybody's name being published. */
    ALARM_HEARD("alarm_heard",
            "an alarm was raised without a named suspect"),

    /** A witness reached an authority and the account was accepted. */
    REPORT_DELIVERED("report_delivered",
            "a witness delivered a report an authority accepted"),

    /** Somebody was taken into lawful custody. */
    CUSTODY_STARTED("custody_started",
            "a suspect was taken into lawful custody"),

    /** A sentence finished and the prisoner walked out lawfully. */
    CUSTODY_ENDED("custody_ended",
            "a prisoner completed their sentence and was released"),

    /** A cell is empty that should not be. */
    JAILBREAK("jailbreak",
            "a prisoner escaped custody here"),

    /** Somebody held unlawfully was recovered. */
    RESCUED("rescued",
            "a resident held unlawfully was recovered"),

    /** Stolen goods came back to their owner. */
    PROPERTY_RETURNED("property_returned",
            "stolen property was returned to its owner"),

    /** A debt to this settlement was settled: a fine paid, restitution made. */
    RESTITUTION_COMPLETED("restitution_completed",
            "a fine or restitution settled a case here");

    private final String id;
    private final String description;

    TownsteadReactionEvent(String id, String description) {
        this.id = id;
        this.description = description;
    }

    /** The stable string form, as a datapack and an operator see it. */
    public String id() {
        return id;
    }

    /** One line for diagnostics and for a datapack error message. */
    public String description() {
        return description;
    }

    private static final Map<String, TownsteadReactionEvent> BY_ID = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(TownsteadReactionEvent::id, Function.identity()));

    /** Every id, sorted, for an error message that tells a pack author what was allowed. */
    public static String knownIds() {
        return BY_ID.keySet().stream().sorted().collect(Collectors.joining(", "));
    }

    /**
     * Resolves an id, or null.
     *
     * <p>Null rather than a default, because the caller is a datapack loader that has to report the bad
     * id by name. A default would bind somebody's typo to a real event and play the wrong reaction
     * forever.
     */
    public static TownsteadReactionEvent byId(String raw) {
        return raw == null || raw.isBlank() ? null : BY_ID.get(raw.trim().toLowerCase(Locale.ROOT));
    }
}
