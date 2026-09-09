package dev.otectus.mcacrime.detect;

/** Pure perception policy: detecting an event and identifying its actor are separate answers. */
public final class PerceptionRules {
    private PerceptionRules() {}
    public record Input(double distance, double visualRadius, double soundRadius,
                        boolean seesAct, boolean seesActor, boolean blind, boolean sleeping,
                        boolean invisibleActor, boolean sneakingActor, boolean obstructedSound,
                        double facingDot, double light, boolean raining) {}
    public record Result(boolean sawAct, boolean heardAct, float confidence) {
        public boolean aware() { return sawAct || heardAct; }
        public boolean identifiesActor() { return confidence >= 0.25F; }
    }
    public static Result evaluate(Input in) {
        if (in.sleeping) return new Result(false, false, 0);
        boolean heard = in.soundRadius > 0 && in.distance <= in.soundRadius * (in.obstructedSound ? 0.5 : 1);
        boolean saw = in.distance <= in.visualRadius && in.seesAct && !in.blind && !in.sleeping
                && (in.facingDot > -0.7 || heard || in.distance <= 3);
        if (!saw || !in.seesActor || in.invisibleActor) return new Result(saw, heard, 0);
        double distance = 1 - 0.35 * Math.max(0, in.distance - 4) / Math.max(1, in.visualRadius - 4);
        double light = in.distance <= 4 ? 1 : 0.55 + 0.45 * Math.max(0, Math.min(1, in.light));
        double confidence = distance * light * (in.facingDot < 0 ? 0.8 : 1)
                * (in.sneakingActor ? 0.85 : 1) * (in.raining ? 0.9 : 1);
        return new Result(saw, heard, (float) Math.max(0, Math.min(1, confidence)));
    }
}
