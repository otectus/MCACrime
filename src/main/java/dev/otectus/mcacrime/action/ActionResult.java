package dev.otectus.mcacrime.action;

/** Non-sensitive result returned to every action entry point. */
public record ActionResult(boolean accepted, String code) {
    public static ActionResult accepted(String code) { return new ActionResult(true, code); }
    public static ActionResult rejected(String code) { return new ActionResult(false, code); }
}
