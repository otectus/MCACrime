package dev.otectus.mcacrime.report;

/** Player-facing state derived from the receipt plus canonical case/custody state. */
public enum PlayerReportStatus {
    ELIGIBLE,
    ACCEPTED,
    AWAITING_GUARD,
    SEARCHING,
    IN_CUSTODY,
    SENTENCE_COMPLETED,
    CASE_CLOSED,
    EXPIRED
}
