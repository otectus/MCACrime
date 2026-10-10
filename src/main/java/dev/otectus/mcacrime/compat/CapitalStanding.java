package dev.otectus.mcacrime.compat;

import org.jetbrains.annotations.Nullable;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * What MCA Capitals says one player holds in one capital (0.7.8).
 *
 * <p>Raw facts, by Capitals' own names, read from that capital's record and nothing else: a duke of
 * another capital is a stranger here, which is the whole point of reading per capital.
 *
 * @param rankName      the {@code PlayerRank} Capitals' authority resolver gives this player here
 *                      ({@code STRANGER}, {@code COMMONER}, {@code LORD}, ...)
 * @param grantedTitle  the {@code NobleTitle} granted to the player in this capital, or null
 * @param marriageTitle the title held through a living spouse, or null
 * @param dowagerTitle  the title kept from a dead spouse, or null
 */
public record CapitalStanding(String rankName, @Nullable String grantedTitle, @Nullable String marriageTitle,
                              @Nullable String dowagerTitle) {

    /**
     * The only ranks that are below Noble. An allow-list of names, never an ordinal comparison: a rank
     * Capitals adds in a later release is treated as protected until someone decides otherwise, which
     * errs on the side of not decreeing against a person the Crown has raised.
     */
    public static final Set<String> COMMON_RANKS = Set.of("STRANGER", "COMMONER");

    /** Capitals' "no title" constant. */
    public static final String NO_TITLE = "COMMONER";

    public CapitalStanding {
        Objects.requireNonNull(rankName, "rankName");
        rankName = rankName.toUpperCase(Locale.ROOT);
        grantedTitle = normalise(grantedTitle);
        marriageTitle = normalise(marriageTitle);
        dowagerTitle = normalise(dowagerTitle);
    }

    @Nullable
    private static String normalise(@Nullable String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String upper = title.toUpperCase(Locale.ROOT);
        return NO_TITLE.equals(upper) ? null : upper;
    }

    /**
     * Noble or above in this capital: a rank above commoner, or any held noble title. The title half is
     * what covers knights and dames, whom Capitals' rank enum folds into {@code COMMONER}.
     */
    public boolean noble() {
        return !COMMON_RANKS.contains(rankName) || grantedTitle != null || marriageTitle != null
                || dowagerTitle != null;
    }

    /** The most specific name to show: a held title if there is one, otherwise the rank. */
    public String displayName() {
        if (grantedTitle != null) {
            return grantedTitle;
        }
        if (marriageTitle != null) {
            return marriageTitle;
        }
        if (dowagerTitle != null) {
            return dowagerTitle;
        }
        return rankName;
    }
}
