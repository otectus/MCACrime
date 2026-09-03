package dev.otectus.mcacrime.action;

/** Server-authoritative action availability with a stable localization reason. */
public record ActionAvailability(Status status, String reason) {
    public enum Status { AVAILABLE, HIDDEN, BLOCKED }

    public static ActionAvailability available() { return new ActionAvailability(Status.AVAILABLE, ""); }
    public static ActionAvailability blocked(String reason) { return new ActionAvailability(Status.BLOCKED, reason); }
    public static ActionAvailability hidden(String reason) { return new ActionAvailability(Status.HIDDEN, reason); }
    public boolean isAvailable() { return status == Status.AVAILABLE; }
}
