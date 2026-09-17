package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What {@code /crime debug townstead} prints, and the single table of which config switch needs which
 * capability.
 *
 * <h2>Why the four words matter</h2>
 *
 * <p>The failure this whole layer is built against is a server owner believing a feature is on when
 * nothing is doing it. So every switch is reported in one of four states, and they are not synonyms:
 *
 * <ul>
 *   <li><b>off</b> — the operator turned it off. Nothing to see.</li>
 *   <li><b>available</b> — the capability bound, but the switch is off, so nothing uses it.</li>
 *   <li><b>active</b> — switch on, capability bound, the feature is really running.</li>
 *   <li><b>degraded</b> — switch on, capability missing. The feature is not running, and this is the
 *       word that says so out loud instead of reading as "off".</li>
 * </ul>
 *
 * <p>The same table backs {@code ConfigValidator}, so {@code /crime validate} and this command can
 * never disagree about what a setting needs.
 */
public final class TownsteadDiagnostics {

    /** One config switch and the capabilities without which it cannot do anything. */
    public record SwitchRequirement(String setting, List<TownsteadCapability> capabilities, String summary) {
        public SwitchRequirement {
            capabilities = List.copyOf(capabilities);
        }
    }

    /**
     * Every {@code [townstead]} switch, with what it needs.
     *
     * <p>A switch whose requirement list names a cooperation capability can never be active against
     * Townstead as it ships today; that is the point of listing it rather than leaving the setting
     * undocumented and inert.
     */
    public static final List<SwitchRequirement> REQUIREMENTS = List.of(
            new SwitchRequirement("respectIncapacity",
                    List.of(TownsteadCapability.READ_NEEDS, TownsteadCapability.STAGE_CAPABILITIES),
                    "collapsed or immobile villagers stop being treated as witnesses and suspects"),
            new SwitchRequirement("protectWorkerAssignments",
                    List.of(TownsteadCapability.READ_SCHEDULE, TownsteadCapability.READ_PROFESSION),
                    "villagers on shift are passed over when drafting guards and responders"),
            new SwitchRequirement("excludeWorksitesFromTemporaryCells",
                    List.of(TownsteadCapability.READ_BUILDING),
                    "temporary cells are never built inside a registered building"),
            new SwitchRequirement("equipmentProvenance",
                    List.of(TownsteadCapability.EQUIPMENT_PROVENANCE),
                    "a Townstead display tool is told apart from a villager's own equipment"),
            new SwitchRequirement("publicReactions",
                    List.of(TownsteadCapability.DISPATCH_REACTION),
                    "Townstead plays its own reactions when a crime becomes public"),
            new SwitchRequirement("needResponseModifiers",
                    List.of(TownsteadCapability.READ_NEEDS),
                    "hunger, thirst and fatigue nudge how strongly a villager reacts"),
            new SwitchRequirement("propertyLaw",
                    List.of(TownsteadCapability.STORAGE_POLICY, TownsteadCapability.READ_BUILDING),
                    "settlement containers and buildings become property with an owner"),
            new SwitchRequirement("autoProtectGeneratedProperty",
                    List.of(TownsteadCapability.BUILDING_ENUMERATION),
                    "generated buildings are protected without an operator marking each one"),
            new SwitchRequirement("serviceRestrictions",
                    List.of(TownsteadCapability.READ_PROFESSION, TownsteadCapability.READ_SCHEDULE),
                    "a settlement may refuse services to an outlaw"),
            // What community service actually consumes, which is not what this row used to claim. It
            // asked for WORK_SUSPENSION, and TownsteadBridge.has never grants that -- the start-gate is
            // MCA: Crime's own vanilla brain hook and is permanently partial, so the switch could only
            // ever report DEGRADED however well the feature was working. What a contract really uses is
            // an activity claim on an NPC offender (the coordination mixins are what make a claim stop
            // anything) and that villager's schedule, so it does not hold somebody to civic work through
            // their night. The start-gate's limitation is stated in the config comment instead, which is
            // where a limitation belongs; a requirement list is for things that can be present.
            new SwitchRequirement("communityService",
                    List.of(TownsteadCapability.ACTIVITY_COORDINATION, TownsteadCapability.READ_SCHEDULE),
                    "civic work can settle a case instead of a fine"),
            new SwitchRequirement("economyProfiles",
                    List.of(TownsteadCapability.READ_SPIRIT),
                    "a village's character shapes fence prices, fine and bounty scales; without it "
                            + "every settlement is priced as an ordinary town"),
            new SwitchRequirement("automaticShiftAssignment",
                    List.of(TownsteadCapability.ACTIVITY_COORDINATION, TownsteadCapability.READ_SCHEDULE),
                    "guard shifts are assigned through Townstead's own scheduler"));

    /** What {@link #describe(TownsteadCapability)} prints when a read capability bound. */
    public static final String AVAILABLE_API = "available (api)";

    /** Both coordination mixins applied, and both of their hooks have been reached. */
    public static final String AVAILABLE_MIXIN = "available (mixin)";

    /**
     * Applied, but no handler has run yet.
     *
     * <p>Ambiguous by nature, and deliberately reported as such: it is what a freshly started world
     * looks like before any guard has rested, and it is also what a Townstead point release that moved
     * an injection point looks like forever. The word that would be wrong is "available".
     */
    public static final String DEGRADED_MIXIN_UNOBSERVED = "degraded (mixin applied, hook not yet observed)";

    /**
     * Work can be refused before it starts, and not interrupted once it has.
     *
     * <p>MCA: Crime start-gates every work behaviour on a villager it holds a claim on, by wrapping
     * that brain's {@code Activity.WORK} entries. What it cannot do is stop a producer task that is
     * already running: those stage inputs and hold a pending output, and there is no external entry
     * point that would reconcile them, so a committed recipe is allowed to finish. That is a real
     * capability and a real limit, and one word has to carry both.
     */
    public static final String DEGRADED_START_GATE = "degraded (start-gate only)";

    /** Nothing provides it. */
    public static final String UNAVAILABLE = "unavailable";

    /** How one switch stands right now. */
    public enum FeatureState {
        /** The operator turned it off. */
        OFF,
        /** On, and every capability it needs is bound. */
        ACTIVE,
        /** On, and at least one capability it needs is missing — the feature is not running. */
        DEGRADED
    }

    private TownsteadDiagnostics() {
    }

    /** The current value of every {@code [townstead]} switch, keyed by its config name. */
    public static Map<String, Boolean> currentSwitches() {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        Map<String, Boolean> values = new LinkedHashMap<>();
        values.put("respectIncapacity", c.townsteadRespectIncapacity.get());
        values.put("protectWorkerAssignments", c.townsteadProtectWorkerAssignments.get());
        values.put("excludeWorksitesFromTemporaryCells", c.townsteadExcludeWorksitesFromTemporaryCells.get());
        values.put("equipmentProvenance", c.townsteadEquipmentProvenance.get());
        values.put("publicReactions", c.townsteadPublicReactions.get());
        values.put("needResponseModifiers", c.townsteadNeedResponseModifiers.get());
        values.put("propertyLaw", c.townsteadPropertyLaw.get());
        values.put("autoProtectGeneratedProperty", c.townsteadAutoProtectGeneratedProperty.get());
        values.put("serviceRestrictions", c.townsteadServiceRestrictions.get());
        values.put("communityService", c.townsteadCommunityService.get());
        values.put("economyProfiles", c.townsteadEconomyProfiles.get());
        values.put("automaticShiftAssignment", c.townsteadAutomaticShiftAssignment.get());
        return values;
    }

    /**
     * The state of one switch, as a pure function of its value and what bound. Shared with the config
     * validator so the two can never disagree.
     */
    public static FeatureState stateOf(SwitchRequirement requirement, boolean switchedOn,
                                       java.util.function.Predicate<TownsteadCapability> available) {
        if (!switchedOn) {
            return FeatureState.OFF;
        }
        for (TownsteadCapability capability : requirement.capabilities()) {
            if (!available.test(capability)) {
                return FeatureState.DEGRADED;
            }
        }
        return FeatureState.ACTIVE;
    }

    /**
     * How one capability stands, in the vocabulary an operator can act on.
     *
     * <p>Four words, and the two in the middle are the reason this method exists. A read capability is
     * either bound or not, and {@code TownsteadBridge} knows which. The capabilities MCA: Crime
     * provides for itself are not like that:
     *
     * <ul>
     *   <li>{@link TownsteadCapability#ACTIVITY_COORDINATION} comes from two mixins merged into
     *       Townstead's own classes. They can be absent (no Townstead, or a class that moved), applied
     *       but never reached, or applied and demonstrably working — and only the third is
     *       "available".</li>
     *   <li>{@link TownsteadCapability#EQUIPMENT_PROVENANCE} is the same shape with one mixin: the
     *       work-tool hook has to have been reached before MCA: Crime can claim it knows where a held
     *       item came from.</li>
     *   <li>{@link TownsteadCapability#STORAGE_POLICY} is the same shape again, on the mixin that keeps
     *       settlement workers out of evidence storage and reserved containers.</li>
     *   <li>{@link TownsteadCapability#WORK_SUSPENSION} is provided by MCA: Crime's own vanilla brain
     *       hook, and is permanently partial: it refuses a start and never interrupts. Reporting that
     *       as "available" would promise something no Townstead build can currently deliver, and
     *       reporting it as "unavailable" would hide a gate that really does run.</li>
     * </ul>
     */
    public static String describe(TownsteadCapability capability) {
        if (capability == null) {
            return UNAVAILABLE;
        }
        return switch (capability) {
            case ACTIVITY_COORDINATION -> {
                if (!TownsteadBridge.activityCoordinationInstalled()) {
                    yield UNAVAILABLE;
                }
                boolean fired = TownsteadMixinStatus.isInjected(TownsteadMixinStatus.HOOK_REACTION_LOCK)
                        && TownsteadMixinStatus.isInjected(TownsteadMixinStatus.HOOK_GUARD_REST);
                yield fired ? AVAILABLE_MIXIN : DEGRADED_MIXIN_UNOBSERVED;
            }
            case EQUIPMENT_PROVENANCE -> {
                if (!TownsteadBridge.equipmentProvenanceInstalled()) {
                    yield UNAVAILABLE;
                }
                // The copy hook is the one that carries the capability: restore and forget only tidy
                // up after it. A world where no villager has started a work shift yet reads as
                // "applied, not observed", which is the honest answer and not "available".
                yield TownsteadMixinStatus.isInjected(TownsteadMixinStatus.HOOK_WORK_TOOL_COPY)
                        ? AVAILABLE_MIXIN : DEGRADED_MIXIN_UNOBSERVED;
            }
            case CLIENT_DIALOGUE_ENTRY -> {
                if (!TownsteadBridge.dialogueEntryInstalled()) {
                    // Also the answer on every dedicated server, where the client mixin is never
                    // applied. Nothing depends on it there.
                    yield UNAVAILABLE;
                }
                // The init hook is the criterion. The removal hook only fires when a conversation ends,
                // so a player with a dialogue open right now would otherwise read as degraded.
                yield TownsteadMixinStatus.isInjected(TownsteadMixinStatus.HOOK_DIALOGUE_INIT)
                        ? AVAILABLE_MIXIN : DEGRADED_MIXIN_UNOBSERVED;
            }
            case STORAGE_POLICY -> {
                if (!TownsteadBridge.storagePolicyInstalled()) {
                    yield UNAVAILABLE;
                }
                // Unlike the work-tool hook, this one is reached constantly on a village with workers,
                // so "applied but not observed" here is a much sharper signal that the method moved.
                yield TownsteadMixinStatus.isInjected(TownsteadMixinStatus.HOOK_STORAGE_POLICY)
                        ? AVAILABLE_MIXIN : DEGRADED_MIXIN_UNOBSERVED;
            }
            case WORK_SUSPENSION -> startGateLive() ? DEGRADED_START_GATE : UNAVAILABLE;
            default -> TownsteadBridge.has(capability) ? AVAILABLE_API : UNAVAILABLE;
        };
    }

    /**
     * Whether the claim-driven work start-gate can actually refuse anything.
     *
     * <p>Read off the policy table rather than assumed, because the table is where it would silently
     * stop being true: the wrapper {@code ThiefBrainMixin} installs asks
     * {@code CrimeActivityRegistry.permits(..., WORK_START)}, and if no activity kind yielded
     * {@code WORK_START} any more, the gate would still be installed and would still allow everything.
     * That is the one way this could become a report of a capability nobody has.
     */
    private static boolean startGateLive() {
        for (dev.otectus.mcacrime.activity.CrimeActivityView.Kind kind
                : dev.otectus.mcacrime.activity.CrimeActivityView.Kind.values()) {
            if (dev.otectus.mcacrime.activity.OperationPolicy.yields(kind,
                    dev.otectus.mcacrime.activity.CrimeActivityOperation.WORK_START)) {
                return true;
            }
        }
        return false;
    }

    /** The header an operator reads first: is it installed, did it bind, and how much of it. */
    public static List<String> summary() {
        List<String> lines = new ArrayList<>();
        boolean installed = TownsteadBridge.installed();
        lines.add("townstead: installed=" + installed
                + (installed && !TownsteadBridge.detectedVersion().isEmpty()
                        ? " version=" + TownsteadBridge.detectedVersion() : "")
                + " enabled=" + McaCrimeConfig.COMMON.townsteadEnabled.get()
                + " state=" + TownsteadBridge.state().name().toLowerCase(java.util.Locale.ROOT)
                + " (" + TownsteadBridge.status() + ")");
        if (!installed) {
            lines.add("  Townstead is not installed; every [townstead] setting is inert and crime behaves "
                    + "exactly as it does without it.");
            return lines;
        }
        TownsteadBridge.variant().ifPresent(variant ->
                lines.add("  Townstead was built against MCA package root '" + variant + "'."));
        lines.add("  capabilities bound: " + TownsteadBridge.capabilities().size() + "/"
                + TownsteadCapability.values().length);
        lines.add("  mixin layer: " + TownsteadMixinStatus.snapshot().describe());
        for (TownsteadCapability capability : TownsteadCapability.values()) {
            lines.add("  " + capability.id() + ": " + describe(capability)
                    + " — " + capability.description());
        }
        List<String> unresolved = TownsteadBridge.unresolvedMembers();
        if (!unresolved.isEmpty()) {
            lines.add("  unbound member(s): " + unresolved);
        }
        lines.add("  features:");
        Map<String, Boolean> switches = currentSwitches();
        for (SwitchRequirement requirement : REQUIREMENTS) {
            boolean on = Boolean.TRUE.equals(switches.get(requirement.setting()));
            FeatureState state = stateOf(requirement, on, TownsteadBridge::has);
            lines.add("    " + requirement.setting() + ": configured=" + (on ? "on" : "off")
                    + " -> " + state.name().toLowerCase(java.util.Locale.ROOT)
                    + (state == FeatureState.DEGRADED
                            ? " (needs " + ids(requirement) + ")"
                            : "")
                    + " — " + requirement.summary());
        }
        return lines;
    }

    /** What Townstead knows about one entity: the villager, its needs, its schedule and its stage. */
    public static List<String> entity(@Nullable Entity target) {
        List<String> lines = new ArrayList<>();
        if (target == null) {
            lines.add("townstead: no entity selected.");
            return lines;
        }
        lines.add("townstead entity: " + target.getName().getString());
        TownsteadQueryResult<TownsteadVillagerView> villager = TownsteadBridge.villager(target);
        TownsteadVillagerView view = villager.orElse(null);
        if (view == null) {
            lines.add("  " + villager.describe());
            return lines;
        }
        lines.add("  " + view.describe());
        lines.add("  " + view.needs().describe());
        lines.add("  " + view.schedule().describe());
        TownsteadQueryResult<TownsteadLifeStageView> stage = TownsteadBridge.lifeStage(target);
        lines.add("  " + stage.asOptional().map(TownsteadLifeStageView::describe).orElse(stage.describe()));
        return lines;
    }

    /** What Townstead knows about the place a command was run in: the building and the village spirit. */
    public static List<String> village(@Nullable ServerLevel level, @Nullable BlockPos pos) {
        List<String> lines = new ArrayList<>();
        if (level == null || pos == null) {
            lines.add("townstead: no position.");
            return lines;
        }
        lines.add("townstead village at " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ":");
        TownsteadQueryResult<TownsteadBuildingView> building = TownsteadBridge.buildingAt(level, pos);
        TownsteadBuildingView buildingView = building.orElse(null);
        if (buildingView == null) {
            lines.add("  " + building.describe());
        } else {
            lines.add("  " + buildingView.describe());
            TownsteadQueryResult<TownsteadSpiritView> spirit =
                    TownsteadBridge.spirit(level, buildingView.villageId());
            lines.add("  " + spirit.asOptional().map(TownsteadSpiritView::describe).orElse(spirit.describe()));
        }
        // The enumeration, separately from the facade lookup, because the two answer different
        // questions: the facade names one building in the nearest village, this names every recognised
        // building overlapping the block and the revision each was read at.
        TownsteadQueryResult<List<TownsteadBuildingView>> overlapping = TownsteadBridge.buildingsAt(level, pos);
        List<TownsteadBuildingView> all = overlapping.orElse(null);
        if (all == null) {
            lines.add("  overlapping buildings: " + overlapping.describe());
        } else if (all.isEmpty()) {
            lines.add("  overlapping buildings: none recognised here");
        } else {
            lines.add("  overlapping buildings: " + all.size());
            for (TownsteadBuildingView overlap : all) {
                lines.add("    " + overlap.describe());
            }
        }
        TownsteadQueryResult<TownsteadCalendarView> calendar = TownsteadBridge.calendar(level.getServer());
        lines.add("  " + calendar.asOptional().map(TownsteadCalendarView::describe).orElse(calendar.describe()));
        security(lines, level, buildingView);
        return lines;
    }

    /**
     * MCA: Crime's own security reading for this settlement, printed under Townstead's.
     *
     * <p>Side by side, and that is the whole of the relationship between the two numbers. Village spirit
     * above is Townstead's: slow, building-derived, and authored by whoever put the settlement up.
     * Security here is this mod's: derived from public incidents, guard coverage and assigned
     * facilities, capped and decaying. Neither is an input to the other (reference §11.4), and the one
     * failure mode worth printing a reminder about is a reader assuming that a low security score is
     * costing the village spirit points it earned.
     */
    private static void security(List<String> lines, ServerLevel level,
                                 @Nullable TownsteadBuildingView building) {
        if (building == null) {
            lines.add("  security: no recognised building here, so there is no settlement to score.");
            return;
        }
        dev.otectus.mcacrime.api.model.CrimeCommunityKey.of(level.dimension().location(),
                        building.villageId())
                .flatMap(community -> dev.otectus.mcacrime.civic.VillageSecurityService.of(level, community))
                .ifPresentOrElse(
                        view -> {
                            lines.add("  " + view.describe());
                            lines.add("    (MCA: Crime's own figure. Village spirit above is Townstead's "
                                    + "and is never touched by it.)");
                        },
                        () -> lines.add("  security: unavailable for this settlement."));
    }

    private static String ids(SwitchRequirement requirement) {
        StringBuilder out = new StringBuilder();
        for (TownsteadCapability capability : requirement.capabilities()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(capability.id());
        }
        return out.toString();
    }
}
