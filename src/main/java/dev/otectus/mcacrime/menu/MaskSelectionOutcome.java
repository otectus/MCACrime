package dev.otectus.mcacrime.menu;

/** What the server decided about one {@code SelectMaskRecipeC2SPacket} (0.7.2 §8.1). */
public enum MaskSelectionOutcome {

    /** The selection is honoured. */
    ACCEPTED,
    /** The sender has no Mask Station open, or a different one. */
    WRONG_CONTAINER,
    /** Recipes were reloaded since the client built its grid. */
    STALE_GENERATION,
    /** The id is not in the catalogue these inputs produce. */
    UNKNOWN_RECIPE;

    public boolean accepted() {
        return this == ACCEPTED;
    }
}
