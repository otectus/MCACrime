package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.activity.CrimeActivityRegistry;
import dev.otectus.mcacrime.activity.CrimeActivityView;
import dev.otectus.mcacrime.ai.NpcAwareness;
import dev.otectus.mcacrime.compat.TownsteadBridge;
import dev.otectus.mcacrime.compat.TownsteadNeedsView;
import dev.otectus.mcacrime.compat.TownsteadScheduleView;
import dev.otectus.mcacrime.compat.mca.McaHandles;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.facility.CrimeFacilityService;
import dev.otectus.mcacrime.facility.FacilityAssignment;
import dev.otectus.mcacrime.facility.FacilityRole;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Who is actually available to police a village right now — and, separately, what an operator could do
 * about it.
 *
 * <h2>Derived, never persisted</h2>
 *
 * <p>Every number here is recomputed from the live world on each call. That is not a performance
 * compromise, it is the correctness requirement: a roster written down at any point would start
 * disagreeing with the village the moment a guard slept, died, was drafted, or went on shift, and a
 * stale roster is worse than none because it reads as authoritative. Nothing in this class writes
 * anything.
 *
 * <h2>Why "4 guards" was not enough</h2>
 *
 * <p>The old report said how many guards a village had. With a settlement mod installed, the ordinary
 * reason a village looks unpoliced is that its guards are asleep, at work, or already escorting
 * somebody, and "4 guards" is indistinguishable from a broken feature to the operator staring at an
 * empty street. So the view separates them: on duty, engaged, resting, unfit, and how many the
 * recruitment pass would add if it could (§9.1).
 *
 * <h2>Suggestions are suggestions</h2>
 *
 * <p>{@link #suggest} never changes anything. Applying a shift plan must be explicit (§9.1), and the
 * patrol advice it produces is bounded and derived from assigned facilities rather than from a scan of
 * the whole map, so it cannot turn a village into permanent pursuit mode (§9.4).
 */
public final class GuardDutyService {

    /** How many suggestions are ever produced for one village. */
    public static final int MAX_SUGGESTIONS = 6;

    private GuardDutyService() {
    }

    /**
     * One village's coverage, as it stands this tick.
     *
     * @param village    the dimension-and-village key, as {@code RecruitmentReport} prints it
     * @param guards     law found among the loaded residents
     * @param onDuty     awake, capable, and not already committed to something
     * @param engaged    holding an MCA: Crime enforcement claim: escorting, pursuing, arresting
     * @param resting    asleep, collapsed or on a Townstead rest activity
     * @param unfit      alive but unable to respond at all
     * @param unloaded   residents MCA counts that are not loaded right now
     * @param shortfall  how many guards the recruitment pass would add, before its own rules
     * @param notes      one line per distinct cause, for the command output
     */
    public record DutyView(String village, int guards, int onDuty, int engaged, int resting, int unfit,
                           int unloaded, int shortfall, List<String> notes) {

        public DutyView {
            village = village == null ? "" : village;
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        /** Whether anybody at all could answer a report in this village right now. */
        public boolean covered() {
            return onDuty > 0;
        }

        /** The line §9.1 asks for: "4 guards, 1 on duty, 1 escorting, 2 resting". */
        public String describe() {
            StringBuilder out = new StringBuilder(village + ": " + guards + " guard(s), " + onDuty
                    + " on duty, " + engaged + " engaged, " + resting + " resting, " + unfit + " unfit");
            if (unloaded > 0) {
                out.append(", ").append(unloaded).append(" not loaded");
            }
            if (shortfall > 0) {
                out.append(", ").append(shortfall).append(" short of the configured target");
            }
            for (String note : notes) {
                out.append("\n    ").append(note);
            }
            return out.toString();
        }
    }

    /** One coverage view per village in this dimension. */
    public static List<DutyView> inspect(@Nullable ServerLevel level) {
        List<DutyView> views = new ArrayList<>();
        if (level == null || !McaHandles.populationAvailable()) {
            return views;
        }
        Map<String, RecruitmentReport> recruitment = new LinkedHashMap<>();
        for (RecruitmentReport report : GuardPopulationService.reports(level)) {
            recruitment.put(report.village(), report);
        }
        for (Object village : McaHandles.villagesIn(level)) {
            if (!McaHandles.isRealVillage(village)) {
                continue;
            }
            String key = level.dimension().location() + "/" + McaHandles.villageIdOf(village);
            if (recruitment.containsKey(key) || !hasView(views, key)) {
                views.add(assess(level, village, key, recruitment.get(key)));
            }
        }
        return views;
    }

    /** The view for one village, or empty when this dimension has no such village. */
    public static Optional<DutyView> inspect(@Nullable ServerLevel level, @Nullable String village) {
        for (DutyView view : inspect(level)) {
            if (village == null || village.isBlank() || view.village().endsWith("/" + village.trim())
                    || view.village().equalsIgnoreCase(village.trim())) {
                return Optional.of(view);
            }
        }
        return Optional.empty();
    }

    private static boolean hasView(List<DutyView> views, String key) {
        for (DutyView view : views) {
            if (view.village().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static DutyView assess(ServerLevel level, Object village, String key,
                                   @Nullable RecruitmentReport recruitment) {
        List<Object> residents = McaHandles.villageResidents(village, level);
        int guards = 0;
        int onDuty = 0;
        int engaged = 0;
        int resting = 0;
        int unfit = 0;
        List<String> notes = new ArrayList<>();
        for (Object resident : residents) {
            if (!(resident instanceof LivingEntity entity) || !entity.isAlive()) {
                continue;
            }
            if (!isLaw(entity)) {
                continue;
            }
            guards++;
            switch (statusOf(entity, notes)) {
                case ENGAGED -> engaged++;
                case RESTING -> resting++;
                case UNFIT -> unfit++;
                default -> onDuty++;
            }
        }
        int population = recruitment == null ? residents.size() : recruitment.population();
        int unloaded = Math.max(0, population - residents.size());
        if (recruitment != null && recruitment.halted()) {
            notes.add("recruitment is halted for this village: " + String.join("; ", recruitment.reasons()));
        }
        if (guards > 0 && onDuty == 0) {
            notes.add("every guard is engaged, resting or unfit; a report raised now waits for one of "
                    + "them rather than going unanswered.");
        }
        return new DutyView(key, guards, onDuty, engaged, resting, unfit, unloaded,
                recruitment == null ? 0 : recruitment.shortfall(), notes);
    }

    /** What one guard is doing. */
    private enum Status { ON_DUTY, ENGAGED, RESTING, UNFIT }

    /**
     * The union of both guard tests, exactly as the recruitment pass uses it: this mod's own notion of
     * law plus MCA's, so a village's archers are not quietly left out of its coverage.
     */
    private static boolean isLaw(Entity entity) {
        return (entity instanceof LivingEntity living && EntitySelectors.isResponder(living))
                || McaHandles.isMcaGuard(entity);
    }

    private static Status statusOf(LivingEntity guard, List<String> notes) {
        Optional<CrimeActivityView> claim = CrimeActivityRegistry.activeFor(guard.getUUID(),
                guard.level().getGameTime());
        if (claim.isPresent() && claim.get().authority().atLeast(CrimeActivityView.Authority.ENFORCEMENT)) {
            return Status.ENGAGED;
        }
        if (!NpcAwareness.canRespondAsGuard(guard)) {
            // Incapacity is the settlement's call when it has one, and MCA: Crime's own awake test
            // otherwise. Either way it is "cannot respond", which is not the same as "off shift".
            TownsteadNeedsView needs = TownsteadBridge.needs(guard).orElse(null);
            if (needs != null && needs.tracked() && (needs.collapsed() || needs.exhausted())) {
                return Status.RESTING;
            }
            return Status.UNFIT;
        }
        TownsteadScheduleView schedule = TownsteadBridge.schedule(guard).orElse(null);
        // The view's own judgement rather than a string test here: Townstead owns what "rest" means and
        // a second spelling of it in this file would drift the first time a shift name changed.
        if (schedule != null && schedule.resting()) {
            if (notes.isEmpty()) {
                notes.add("some guards are on a settlement rest shift; off-duty response is governed by "
                        + "the configured guard rules rather than by vanilla time of day.");
            }
            return Status.RESTING;
        }
        return Status.ON_DUTY;
    }

    /**
     * Bounded, advisory coverage suggestions for one village. Nothing is applied.
     *
     * <p>Every suggestion comes from something already recorded — an assigned facility, a live claim, a
     * recruitment report — rather than from a survey of the world. That is what keeps it bounded, and
     * it is also what keeps patrol advice away from evidence: a suggestion to walk past the jail is not
     * a statement about who committed anything (§9.4).
     */
    public static List<String> suggest(@Nullable ServerLevel level, @Nullable String village,
                                       @Nullable BlockPos from) {
        List<String> lines = new ArrayList<>();
        if (level == null) {
            return lines;
        }
        Optional<DutyView> view = inspect(level, village);
        if (view.isEmpty()) {
            lines.add("No MCA village found" + (village == null ? " in this dimension." : ": " + village));
            return lines;
        }
        DutyView duty = view.get();
        lines.add(duty.describe());

        BlockPos origin = from == null ? BlockPos.ZERO : from;
        List<FacilityAssignment> posts = CrimeFacilityService.list(level.getServer(), FacilityRole.GUARD_POST);
        if (posts.isEmpty()) {
            lines.add("suggestion: no guard post is assigned here. "
                    + "/crime facility assign guard_post <pos> gives reports a destination and patrols an "
                    + "anchor; without one, a witness has nowhere to take a report but the nearest guard.");
        } else {
            int shown = 0;
            for (FacilityAssignment post : posts) {
                if (shown >= MAX_SUGGESTIONS - 1) {
                    break;
                }
                if (!post.ref().dimension().equals(level.dimension().location())) {
                    continue;
                }
                lines.add("suggestion: patrol " + post.describe()
                        + " (" + (int) Math.sqrt(post.anchor().distSqr(origin)) + " blocks away)");
                shown++;
            }
        }
        if (CrimeFacilityService.list(level.getServer(), FacilityRole.JAIL_CELL).isEmpty()) {
            lines.add("suggestion: no jail cell is assigned. Arrests fall back to a manual anchor or a "
                    + "temporary cell, neither of which can be reserved against simultaneous arrests.");
        }
        if (duty.shortfall() > 0) {
            lines.add("suggestion: the village is " + duty.shortfall() + " guard(s) under its configured "
                    + "target. See /crime debug guards for whether the recruitment pass is allowed to "
                    + "close that gap.");
        }
        if (!duty.covered() && duty.guards() == 0) {
            lines.add("suggestion: this village has no law at all. Nothing MCA: Crime does will produce a "
                    + "response here until it has a guard.");
        }
        return lines.size() > MAX_SUGGESTIONS + 1 ? lines.subList(0, MAX_SUGGESTIONS + 1) : lines;
    }
}
