package dev.otectus.mcacrime.detect;

/** Pure perception policy: detecting an event and identifying its actor are separate answers. */
public final class PerceptionRules {
    private PerceptionRules() {}
    /**
     * @param sandBlinded       the observer has sand in their eyes (0.7.2 §13.5). Unlike {@code blind}
     *                          this one has a range exemption, because the point of sand is that it
     *                          stops you seeing across the square rather than stopping you noticing
     *                          somebody pressed against you.
     * @param closeContactRange how close something has to be to be noticed anyway
     */
    public record Input(double distance, double visualRadius, double soundRadius,
                        boolean seesAct, boolean seesActor, boolean blind, boolean sleeping,
                        boolean invisibleActor, boolean sneakingActor, boolean obstructedSound,
                        double facingDot, double light, boolean raining,
                        boolean sandBlinded, double closeContactRange) {

        /** The shape every caller written before sand existed uses: not sanded. */
        public Input(double distance, double visualRadius, double soundRadius,
                     boolean seesAct, boolean seesActor, boolean blind, boolean sleeping,
                     boolean invisibleActor, boolean sneakingActor, boolean obstructedSound,
                     double facingDot, double light, boolean raining) {
            this(distance, visualRadius, soundRadius, seesAct, seesActor, blind, sleeping, invisibleActor,
                    sneakingActor, obstructedSound, facingDot, light, raining, false, 0.0D);
        }
    }
    public record Result(boolean sawAct, boolean heardAct, float confidence) {
        public boolean aware() { return sawAct || heardAct; }
        public boolean identifiesActor() { return confidence >= 0.25F; }
    }
    public static Result evaluate(Input in) {
        if (in.sleeping) return new Result(false, false, 0);
        boolean heard = in.soundRadius > 0 && in.distance <= in.soundRadius * (in.obstructedSound ? 0.5 : 1);
        // Sand takes sight and leaves everything else. Hearing is computed above and untouched, so a
        // sanded villager still registers that something happened; what they lose is seeing it, and
        // with it any chance of naming who did it (invariant 12: sight, not history).
        boolean sanded = in.sandBlinded && in.distance > in.closeContactRange;
        boolean saw = in.distance <= in.visualRadius && in.seesAct && !in.blind && !in.sleeping && !sanded
                && (in.facingDot > -0.7 || heard || in.distance <= 3);
        if (!saw || !in.seesActor || in.invisibleActor) return new Result(saw, heard, 0);
        double distance = 1 - 0.35 * Math.max(0, in.distance - 4) / Math.max(1, in.visualRadius - 4);
        double light = in.distance <= 4 ? 1 : 0.55 + 0.45 * Math.max(0, Math.min(1, in.light));
        double confidence = distance * light * (in.facingDot < 0 ? 0.8 : 1)
                * (in.sneakingActor ? 0.85 : 1) * (in.raining ? 0.9 : 1);
        return new Result(saw, heard, (float) Math.max(0, Math.min(1, confidence)));
    }
}
