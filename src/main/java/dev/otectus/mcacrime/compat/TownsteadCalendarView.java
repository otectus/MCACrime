package dev.otectus.mcacrime.compat;

/**
 * Townstead's calendar for the server: the day, the season and the profile behind them.
 *
 * <p>MCA: Crime keeps its own deadlines in ticks and days and always will — this is for presentation
 * and for anything that has to read the same date a villager would, not a replacement clock.
 */
public record TownsteadCalendarView(
        String profileId,
        long worldDay,
        int epochYearOffset,
        String timeMode,
        int year,
        int month,
        int day,
        int dayOfYear,
        int dayOfWeek,
        String season) {

    public TownsteadCalendarView {
        profileId = profileId == null ? "" : profileId;
        timeMode = timeMode == null ? "" : timeMode;
        season = season == null ? "" : season;
    }

    public String describe() {
        return "calendar: day " + worldDay + ", " + year + "-" + month + "-" + day
                + " (day " + dayOfYear + ", weekday " + dayOfWeek + ")"
                + (season.isEmpty() ? "" : ", " + season)
                + (profileId.isEmpty() ? "" : ", profile " + profileId);
    }
}
