package dev.otectus.mcacrime.civic;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The work a settlement will accept instead of a fine (reference §12.1).
 *
 * <h2>Why the list is this short</h2>
 *
 * <p>Reference §12.1 point 4 is the whole constraint: <em>"work is measured by actual accepted outputs
 * or verified repairs"</em>. A task kind may therefore only exist if MCA: Crime can already tell,
 * from something it observes, that a unit of it was done — and it must be able to tell without
 * watching the world, because a contract that polled for progress would be a scan per contract per
 * tick and would credit work nobody can point at afterwards.
 *
 * <p>So every constant below names the event that credits it, and there are three of them rather than
 * the longer list the design sketch imagined. Two plausible-sounding kinds were left out on purpose
 * and are worth naming so nobody re-invents them without the missing half:
 *
 * <ul>
 *   <li><b>Facility upkeep.</b> Restocking a jail's care container or repairing a workstation is a
 *       block change or a container deposit into a place MCA: Crime does not watch. The container
 *       half only becomes observable with {@code townstead.propertyLaw} on, and the block half is not
 *       observed at all — there is no block-place handler in this mod.</li>
 *   <li><b>Public notice posting.</b> Reports are filed by villager witnesses
 *       ({@code memory/ReportService}); an offender has no way to file one, so nothing would ever
 *       credit it.</li>
 * </ul>
 *
 * <p>Both are additive later, behind a real signal. Shipping them inert would be a task board of
 * contracts that can only ever fail.
 */
public enum CivicTask {

    /**
     * Bring in an outlaw the law is already after.
     *
     * <p>Credited by {@code BountyResolvedEvent} when this offender is the claimant and the target was
     * taken alive or arrested. A kill does not count: §12.1 asks for a <em>useful</em> task, and
     * crediting a death would turn a minor fine into a licence to hunt.
     */
    GUARD_ASSIST_PATROL("guard_assist_patrol", 1,
            "help the guard bring in a wanted outlaw alive"),

    /**
     * Put back what was taken.
     *
     * <p>Credited when a property loss receipt in this offender's name is marked restored — the same
     * §10.6 route a voluntary return already takes. Needs {@code townstead.propertyLaw} on, because
     * without it no receipt is ever written and nothing could credit the work.
     */
    RESTITUTION_DELIVERY("restitution_delivery", 1,
            "return goods you took, to the container you took them from"),

    /**
     * Face the people you wronged.
     *
     * <p>Credited when an apology this offender made is accepted by a villager who remembers the
     * crime. It is the one task that touches the victim directly, which is why §12.1 point 6 matters
     * here more than anywhere: the incident and the victim's memory both remain, and the apology only
     * reconciles what the existing apology mechanic already reconciles.
     */
    VICTIM_AMENDS("victim_amends", 2,
            "apologise in person to the residents who remember what you did");

    /** How many units a contract asks for when nobody names a number. */
    public static final int DEFAULT_MAX_UNITS = 64;

    private final String id;
    private final int defaultUnits;
    private final String description;

    CivicTask(String id, int defaultUnits, String description) {
        this.id = id;
        this.defaultUnits = defaultUnits;
        this.description = description;
    }

    /** The stable string form, used in commands, NBT and operator output. */
    public String id() {
        return id;
    }

    /** How many accepted outputs one contract of this kind asks for by default. */
    public int defaultUnits() {
        return defaultUnits;
    }

    /** One line a player can act on, in English, for logs and operator output. */
    public String description() {
        return description;
    }

    /**
     * The translation key for the task's name.
     *
     * <p>Built by concatenation, so {@code LangCoverageTest} enumerates this family from the enum
     * rather than scanning for it: a fourth task added without a key would otherwise reach a player as
     * the raw string {@code mcacrime.civic.task.whatever}.
     */
    public String labelKey() {
        return "mcacrime.civic.task." + id;
    }

    /**
     * Whether crediting this task depends on explicit property law being switched on.
     *
     * <p>Asked before a contract is offered rather than after it is accepted: a contract whose only
     * progress signal is switched off can never be completed, and offering one would be a trap.
     */
    public boolean requiresPropertyLaw() {
        return this == RESTITUTION_DELIVERY;
    }

    /** Parses a persisted or typed id. Empty for anything unrecognised — never a silent default. */
    public static Optional<CivicTask> parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(task -> task.id.equals(needle)
                        || task.name().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst();
    }

    /** Every id, for a command suggestion list and for an error message. */
    public static String names() {
        return Arrays.stream(values()).map(CivicTask::id).collect(Collectors.joining(", "));
    }
}
