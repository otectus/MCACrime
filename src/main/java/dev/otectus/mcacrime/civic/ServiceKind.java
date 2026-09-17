package dev.otectus.mcacrime.civic;

import org.jetbrains.annotations.Nullable;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What a settlement is being asked for, for the purpose of deciding whether it may say no
 * (reference §11.5).
 *
 * <h2>The split that matters</h2>
 *
 * <p>Reference §11.5 draws one hard line: "basic food, water, medical/care access, restitution,
 * surrender, and case-resolution actions remain reachable through a safe channel". Everything above
 * that line is {@link #essential()} and can never be refused, however far into outlaw territory the
 * subject has gone. That is not politeness — a mod that can lock a player out of food and shelter has
 * turned a reputation system into a soft ban, and there would be no route back.
 *
 * <p>Restitution, surrender and case resolution are deliberately <em>not</em> constants here. They are
 * not services a villager provides, they are MCA: Crime's own actions, and they stay reachable by not
 * being routed through this decision at all.
 */
public enum ServiceKind {

    /** Food and drink. Never refused. */
    ESSENTIAL_FOOD("essential_food", true),

    /** Shelter, a bed, care after harm. Never refused. */
    ESSENTIAL_SHELTER("essential_shelter", true),

    /** Ordinary buying and selling. */
    TRADE("trade", false),

    /** Anything a settlement provides for pleasure rather than need. */
    LUXURY("luxury", false),

    /**
     * The back room.
     *
     * <p>A separate kind because the standing rules invert: a fence is in business <em>because</em>
     * its customers are outlaws, so being wanted is not a reason for it to refuse. What still refuses
     * is the personal one — a fence this player mugged has the same memory anybody else does.
     */
    FENCE("fence", false);

    private final String id;
    private final boolean essential;

    ServiceKind(String id, boolean essential) {
        this.id = id;
        this.essential = essential;
    }

    public String id() {
        return id;
    }

    /** Whether reference §11.5 requires this to stay reachable no matter what. */
    public boolean essential() {
        return essential;
    }

    public static Optional<ServiceKind> parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(kind -> kind.id.equals(needle)
                        || kind.name().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst();
    }

    public static String names() {
        return Arrays.stream(values()).map(ServiceKind::id).collect(Collectors.joining(", "));
    }
}
