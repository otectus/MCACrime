package dev.otectus.mcacrime.compat;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One independently-bindable piece of the Townstead integration.
 *
 * <p>The unit of failure for the whole seam. A binding miss disables exactly the capability that
 * needed it, makes the Crime feature resting on it report <em>degraded</em>, and produces one line in
 * {@code /crime debug townstead} — it never disables the bridge and never throws into gameplay.
 *
 * <h2>Two kinds of constant</h2>
 *
 * <p>The first group is read-only state MCA: Crime can take from a Townstead that is installed today;
 * each has members declared in {@code TownsteadBinding}'s manifest and can therefore actually bind.
 *
 * <p>The second group is <em>cooperation</em> Townstead does not expose yet — an external-activity
 * check, a safe work interruption, equipment provenance. They are declared here on purpose rather
 * than left out: a switch that needs one has to be able to say "configured on, capability
 * unavailable, feature degraded" instead of quietly doing nothing, which is the failure mode this
 * whole diagnostic layer exists to prevent. They never bind until the upstream surface exists.
 *
 * <p>The ids are stable strings: they appear in operator output and in config validation messages, so
 * renaming one is a user-visible change.
 */
public enum TownsteadCapability {

    // --- read-only, bindable against Townstead as it ships today ---------------------------------

    READ_VILLAGER("read_villager",
            "Villager identity, life stage, age, personality and fertility."),
    READ_NEEDS("read_needs",
            "Hunger, thirst, fatigue, collapse and the fatigue recovery gate."),
    READ_SCHEDULE("read_schedule",
            "Shift mode, template, and the current and planned activity."),
    READ_PROFESSION("read_profession",
            "Townstead's own profession id, tier and XP for a villager."),
    READ_BUILDING("read_building",
            "The registered building at a position: type, size, bounds and owning village."),
    READ_ROOT("read_root",
            "A root (species/ancestry/lineage) definition and its life cycle."),
    READ_CALENDAR("read_calendar",
            "World day, season, weekday and the active calendar profile."),
    READ_SPIRIT("read_spirit",
            "Village spirit totals, tier and classification."),
    STAGE_CAPABILITIES("stage_capabilities",
            "Whether the current life stage can move, has needs, and can be talked to."),
    DISPATCH_REACTION("dispatch_reaction",
            "Playing a Townstead reaction on a public consequence transition."),

    // --- cooperation that needs an upstream Townstead surface ------------------------------------

    ACTIVITY_COORDINATION("activity_coordination",
            "Declaring an external activity so Townstead yields control of a villager."),
    WORK_SUSPENSION("work_suspension",
            "Suspending an in-progress work task without corrupting its state."),
    EQUIPMENT_PROVENANCE("equipment_provenance",
            "Telling a Townstead display tool apart from a villager's own equipment."),
    STORAGE_POLICY("storage_policy",
            "Asking whether a container access is permitted by the owning settlement."),
    CONSUMPTION_IN_CUSTODY("consumption_in_custody",
            "Letting a villager eat and drink while MCA: Crime holds them."),
    BUILDING_ENUMERATION("building_enumeration",
            "Listing a village's buildings with a revision, rather than one lookup per position."),
    CLIENT_DIALOGUE_ENTRY("client_dialogue_entry",
            "Registering a dialogue entry point with an explicit external-interruption reason."),
    RIG_ATTACHMENTS("rig_attachments",
            "Querying which humanoid attachment points a root's rig actually supports.");

    private final String id;
    private final String description;

    TownsteadCapability(String id, String description) {
        this.id = id;
        this.description = description;
    }

    /** The stable string form, as it appears in operator output. */
    public String id() {
        return id;
    }

    /** One line for {@code /crime debug townstead}. */
    public String description() {
        return description;
    }

    private static final Map<String, TownsteadCapability> BY_ID = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(TownsteadCapability::id, Function.identity()));

    /**
     * Resolves an id, accepting either the stable id or the constant name in any case.
     *
     * <p>Fails hard on an unknown id rather than returning null or a default. A capability gate that
     * silently matched nothing would be worse than no gate at all: the feature would report as
     * configured while nothing checked anything.
     */
    public static TownsteadCapability fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Townstead capability id is required; expected one of " + BY_ID.keySet());
        }
        TownsteadCapability capability = BY_ID.get(raw.trim().toLowerCase(Locale.ROOT));
        if (capability == null) {
            throw new IllegalArgumentException("Unknown Townstead capability '" + raw + "'; expected one of "
                    + BY_ID.keySet());
        }
        return capability;
    }
}
