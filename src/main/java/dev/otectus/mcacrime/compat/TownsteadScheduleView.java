package dev.otectus.mcacrime.compat;

import java.util.List;
import java.util.Locale;

/**
 * One villager's Townstead schedule, flattened to strings and ints MCA: Crime owns.
 *
 * <p>{@code currentActivity} and {@code plannedActivity} are Townstead's own shift names —
 * {@code work}, {@code meet}, {@code rest}, {@code idle} — lowercased at the boundary so no Townstead
 * enum constant can reach this record. {@code known} distinguishes a real reading from the empty one
 * handed back when the capability did not bind, for the same reason
 * {@link TownsteadNeedsView#tracked()} exists: an empty activity must not read as "idle, go ahead".
 */
public record TownsteadScheduleView(
        boolean known,
        String mode,
        String templateId,
        boolean customShifts,
        boolean nonDefaultCustomShifts,
        int currentTickHour,
        int currentDisplayHour,
        int currentShiftOrdinal,
        String currentActivity,
        String plannedActivity,
        String currentTemplateId,
        List<Integer> shifts,
        List<String> weekDayTemplates) {

    public static final String ACTIVITY_WORK = "work";
    public static final String ACTIVITY_MEET = "meet";
    public static final String ACTIVITY_REST = "rest";
    public static final String ACTIVITY_IDLE = "idle";

    private static final TownsteadScheduleView UNKNOWN = new TownsteadScheduleView(
            false, "", "", false, false, 0, 0, 0, "", "", "", List.of(), List.of());

    public TownsteadScheduleView {
        mode = mode == null ? "" : mode;
        templateId = templateId == null ? "" : templateId;
        currentActivity = currentActivity == null ? "" : currentActivity.toLowerCase(Locale.ROOT);
        plannedActivity = plannedActivity == null ? "" : plannedActivity.toLowerCase(Locale.ROOT);
        currentTemplateId = currentTemplateId == null ? "" : currentTemplateId;
        shifts = shifts == null ? List.of() : List.copyOf(shifts);
        weekDayTemplates = weekDayTemplates == null ? List.of() : List.copyOf(weekDayTemplates);
    }

    /** The empty reading: no activity, and every judgement below false. */
    public static TownsteadScheduleView unknown() {
        return UNKNOWN;
    }

    /** True only when Townstead says this villager is on shift right now. */
    public boolean working() {
        return known && ACTIVITY_WORK.equals(currentActivity);
    }

    /** True only when Townstead says this villager is resting right now. */
    public boolean resting() {
        return known && ACTIVITY_REST.equals(currentActivity);
    }

    /** True only when the next planned shift is work, used to decide whether a hold will cost a shift. */
    public boolean nextIsWork() {
        return known && ACTIVITY_WORK.equals(plannedActivity);
    }

    public String describe() {
        if (!known) {
            return "schedule: unknown";
        }
        return "schedule: " + (currentActivity.isEmpty() ? "?" : currentActivity)
                + " (planned " + (plannedActivity.isEmpty() ? "?" : plannedActivity) + ")"
                + " hour " + currentDisplayHour
                + (mode.isEmpty() ? "" : ", mode " + mode)
                + (currentTemplateId.isEmpty() ? "" : ", template " + currentTemplateId);
    }
}
