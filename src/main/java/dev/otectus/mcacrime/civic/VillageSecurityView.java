package dev.otectus.mcacrime.civic;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimePublicView;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * How safe a settlement currently is, in MCA: Crime's own terms and nobody else's.
 *
 * <h2>What this is for</h2>
 *
 * <p>Three questions keep coming up and none of them has an answer today: should a guard suggestion be
 * offered here, does this village have anywhere to put a prisoner, and is it reasonable for a resident
 * to be nervous. Each of them has been answered ad hoc from a different place — a guard count here, a
 * facility list there — and the answers disagree. This is the one derived view they can all read
 * (reference §11.4).
 *
 * <h2>Why it is strictly separate from village spirit</h2>
 *
 * <p>Townstead's spirit totals describe what a settlement <em>is</em>: martial, commercial, pastoral,
 * derived from the buildings its players chose to put up. It is a slow, earned, player-authored number
 * and MCA: Crime has no business writing to it or folding its own opinions into it. The reference plan
 * is explicit (§11.4): keep the security view separate, and never let it become a hidden collective
 * reputation penalty that quietly drains something a player spent a week building.
 *
 * <p>So spirit is not an input here. Not weighted lightly, not read and discarded — not an input. A
 * diagnostic may print both side by side, and that is the whole of the relationship.
 *
 * <h2>Why it decays, and why it is capped</h2>
 *
 * <p>An uncapped, undecayed crime counter has one behaviour: it goes down forever. A village where
 * something happened a month ago would be permanently marked, every petty theft would be as
 * consequential as the one before it, and the number would stop tracking anything a player could
 * change. So each known incident contributes less as it ages and stops contributing entirely outside
 * the window, the total pressure is capped, and the score is bounded at both ends. A village that gets
 * its guards back and its cases settled recovers, which is the only version of this that is worth
 * showing anybody.
 *
 * <h2>What it is built from</h2>
 *
 * <p>Only public knowledge and things an operator assigned: incidents from {@link CrimePublicView},
 * which has already applied the knowledge rule, plus guard coverage and validated facilities. Never a
 * raw ledger walk — a security score that moved on a crime nobody witnessed would be the same privacy
 * leak as a village reacting to one, with a number in front of it.
 */
public record VillageSecurityView(String village, int score, Rating rating, int knownIncidents,
                                  int openIncidents, int guards, int guardsOnDuty, int shortfall,
                                  int cells, int careRooms, int guardPosts, List<String> notes) {

    /** The score a village with nothing known against it and adequate cover sits at. */
    public static final int BASELINE = 70;

    /** Bounds. A score outside these says nothing extra and invites false precision. */
    public static final int MIN_SCORE = 0;
    public static final int MAX_SCORE = 100;

    /**
     * How long an incident keeps counting. Three in-game days.
     *
     * <p>Long enough that a bad night is still visible the next morning, short enough that a village
     * cannot be haunted by a season-old mugging. The linear falloff inside the window is deliberate
     * over an exponential one: it is explainable to a player in one sentence.
     */
    public static final long DEFAULT_WINDOW_TICKS = 72_000L;

    /** The most a single incident may cost, before decay. */
    private static final double INCIDENT_WEIGHT = 6.0D;

    /** An unsettled case keeps a village on edge beyond the deed itself. */
    private static final double OPEN_CASE_WEIGHT = 4.0D;

    /** The ceiling on everything incidents can do, so no amount of crime drives the score to nothing. */
    private static final double MAX_PRESSURE = 55.0D;

    /** What each guard actually standing a watch is worth. */
    private static final int PER_ON_DUTY_GUARD = 6;

    /** The ceiling on guard credit: a garrison of twenty is not twice as safe as a garrison of ten. */
    private static final int MAX_GUARD_CREDIT = 24;

    /** What a village is short, per guard the recruitment pass wanted and could not add. */
    private static final int PER_SHORTFALL = 4;

    /** Credit for somewhere lawful to put a prisoner, and somewhere lawful for them to recover. */
    private static final int PER_CELL = 4;
    private static final int MAX_CELL_CREDIT = 12;
    private static final int PER_CARE_ROOM = 3;
    private static final int MAX_CARE_CREDIT = 6;
    private static final int PER_GUARD_POST = 2;
    private static final int MAX_POST_CREDIT = 6;

    /** How many notes one view carries, so an operator reads a report rather than a log. */
    public static final int MAX_NOTES = 6;

    /** The word an operator or a UI uses. Four, because five would be a number with extra steps. */
    public enum Rating {

        /** Covered, quiet, and with somewhere to put anybody who is not. */
        SECURE,

        /** Ordinary. Something has happened, or cover is thin, but not both. */
        STEADY,

        /** Visibly struggling: open cases, missing guards, or nowhere to hold anybody. */
        STRAINED,

        /** No effective law here right now. */
        LAWLESS;

        /** The rating for a score. Thresholds are the enum's, so nothing else invents its own. */
        public static Rating of(int score) {
            if (score >= 75) {
                return SECURE;
            }
            if (score >= 50) {
                return STEADY;
            }
            return score >= 25 ? STRAINED : LAWLESS;
        }
    }

    public VillageSecurityView {
        village = village == null ? "" : village;
        score = Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
        rating = rating == null ? Rating.of(score) : rating;
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** A view for a village nothing is known about and nothing has been assigned in. */
    public static VillageSecurityView unknown(String village) {
        return new VillageSecurityView(village, BASELINE, Rating.of(BASELINE), 0, 0, 0, 0, 0, 0, 0, 0,
                List.of("no coverage information for this settlement"));
    }

    /** Whether an arrest made here has anywhere lawful to end. */
    public boolean canHold() {
        return cells > 0;
    }

    /** Whether a prisoner who becomes unfit has somewhere better than their cell to recover. */
    public boolean canCare() {
        return careRooms > 0;
    }

    /** One line for {@code /crime debug townstead village}. */
    public String describe() {
        StringBuilder out = new StringBuilder("security: " + score + "/" + MAX_SCORE + " ("
                + rating.name().toLowerCase(java.util.Locale.ROOT) + "), " + knownIncidents
                + " known incident(s), " + openIncidents + " unsettled, " + guardsOnDuty + "/" + guards
                + " guard(s) on duty");
        if (shortfall > 0) {
            out.append(", ").append(shortfall).append(" short of the configured target");
        }
        out.append(", ").append(cells).append(" cell(s), ").append(careRooms).append(" care room(s), ")
                .append(guardPosts).append(" guard post(s)");
        for (String note : notes) {
            out.append("\n    ").append(note);
        }
        return out.toString();
    }

    /**
     * The score, as a pure function of public knowledge, coverage and assignments.
     *
     * <p>Order of operations is the model: start from the baseline, subtract decayed incident pressure
     * (capped), add coverage credit (capped), add facility credit (capped), then bound. Every cap is
     * named above rather than inlined, because each one is a judgement about how much a single fact is
     * allowed to matter, and those are the numbers somebody will want to argue with.
     *
     * @param community  the village this is about
     * @param now        the current game time
     * @param windowTicks how long an incident keeps counting; non-positive uses the default
     * @param incidents  what this community publicly knows, already filtered by {@link CrimePublicView}
     * @param guards     law found among the loaded residents
     * @param guardsOnDuty how many of them could answer a report right now
     * @param shortfall  how many guards the recruitment pass wanted and did not add
     * @param cells      validated jail-cell assignments here
     * @param careRooms  validated care-room assignments here
     * @param guardPosts validated guard-post assignments here
     */
    public static VillageSecurityView compute(@Nullable CrimeCommunityKey community, long now,
                                              long windowTicks,
                                              @Nullable List<CrimePublicView.PublicIncident> incidents,
                                              int guards, int guardsOnDuty, int shortfall,
                                              int cells, int careRooms, int guardPosts) {
        String village = community == null ? "" : community.asString();
        long window = windowTicks > 0L ? windowTicks : DEFAULT_WINDOW_TICKS;

        double pressure = 0.0D;
        int known = 0;
        int open = 0;
        for (CrimePublicView.PublicIncident incident : incidents == null ? List.<CrimePublicView.PublicIncident>of() : incidents) {
            double freshness = decay(incident.gameTime(), now, window);
            if (freshness <= 0.0D) {
                continue; // outside the window: the village has moved on, and so does the number
            }
            known++;
            pressure += INCIDENT_WEIGHT * freshness;
            if (incident.open()) {
                open++;
                pressure += OPEN_CASE_WEIGHT * freshness;
            }
        }
        pressure = Math.min(MAX_PRESSURE, pressure);

        int coverage = Math.min(MAX_GUARD_CREDIT, Math.max(0, guardsOnDuty) * PER_ON_DUTY_GUARD)
                - Math.max(0, shortfall) * PER_SHORTFALL;
        int facilities = Math.min(MAX_CELL_CREDIT, Math.max(0, cells) * PER_CELL)
                + Math.min(MAX_CARE_CREDIT, Math.max(0, careRooms) * PER_CARE_ROOM)
                + Math.min(MAX_POST_CREDIT, Math.max(0, guardPosts) * PER_GUARD_POST);

        int score = (int) Math.round(BASELINE - pressure + coverage + facilities);
        score = Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));

        return new VillageSecurityView(village, score, Rating.of(score), known, open,
                Math.max(0, guards), Math.max(0, guardsOnDuty), Math.max(0, shortfall),
                Math.max(0, cells), Math.max(0, careRooms), Math.max(0, guardPosts),
                notesFor(open, guards, guardsOnDuty, shortfall, cells, careRooms));
    }

    /**
     * How much an incident still counts, from 1 at the moment it happened to 0 at the window's edge.
     *
     * <p>A future timestamp reads as fresh rather than as an error: a replayed or clock-skewed record
     * is not evidence that the village is safe.
     */
    public static double decay(long gameTime, long now, long windowTicks) {
        long window = windowTicks > 0L ? windowTicks : DEFAULT_WINDOW_TICKS;
        long age = now - gameTime;
        if (age <= 0L) {
            return 1.0D;
        }
        if (age >= window) {
            return 0.0D;
        }
        return 1.0D - ((double) age / (double) window);
    }

    private static List<String> notesFor(int open, int guards, int onDuty, int shortfall, int cells,
                                         int careRooms) {
        List<String> notes = new ArrayList<>();
        if (guards == 0) {
            notes.add("no law is present in this settlement at all.");
        } else if (onDuty == 0) {
            notes.add("every guard here is engaged, resting or unfit; a report raised now waits.");
        }
        if (shortfall > 0) {
            notes.add(shortfall + " guard(s) short of the configured target for this settlement.");
        }
        if (cells == 0) {
            notes.add("no jail cell is assigned here; an arrest falls back to a temporary cell.");
        }
        if (careRooms == 0 && open > 0) {
            notes.add("no care room is assigned here; a prisoner who becomes unfit recovers in place.");
        }
        if (open > 0) {
            notes.add(open + " publicly known case(s) here are still unsettled.");
        }
        return notes.size() <= MAX_NOTES ? notes : new ArrayList<>(notes.subList(0, MAX_NOTES));
    }
}
