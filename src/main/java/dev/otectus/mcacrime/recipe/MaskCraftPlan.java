package dev.otectus.mcacrime.recipe;

/**
 * What one extraction from the Mask Station would cost and produce, decided before anything is
 * consumed (0.7.2 §8.2).
 *
 * <p>This is the prepare half of prepare/commit, and it is pure arithmetic on counts so that the rule
 * that actually matters — <em>a craft is either fully paid and fully delivered, or it does not
 * happen</em> — is provable without a world. The menu supplies the counts it can see; this decides how
 * many whole crafts those counts support; the result slot commits exactly that many and no more.
 *
 * <p>Capacity is counted conservatively and as <em>one</em> pool. The caller passes whole free
 * destinations rather than a best-case stacking estimate, and a craft spends one of them on its mask
 * plus one per remainder — because in a player's inventory those are the same slots competing with
 * each other. The plan can therefore under-promise, but it can never authorise a craft whose output or
 * remainder would have nowhere to land (CRAFT-02).
 */
public record MaskCraftPlan(int crafts, int materialPerCraft, int bindingPerCraft, int dyePerCraft,
                            int remaindersPerCraft, MaskCraftRejection rejection) {

    /** The most crafts one shift-click may perform, however much the player is carrying. */
    public static final int MAX_BATCH = 64;

    /** A plan that authorises nothing, for the given reason. */
    public static MaskCraftPlan refused(MaskCraftRejection rejection) {
        return new MaskCraftPlan(0, 0, 0, 0, 0, rejection);
    }

    /** Whether a commit may proceed. */
    public boolean craftable() {
        return crafts > 0 && rejection == MaskCraftRejection.NONE;
    }

    public int materialCost() {
        return crafts * materialPerCraft;
    }

    public int bindingCost() {
        return crafts * bindingPerCraft;
    }

    public int dyeCost() {
        return crafts * dyePerCraft;
    }

    /**
     * How many whole crafts these inputs and destinations support.
     *
     * @param materialPerCraft   material items one craft consumes; must be positive
     * @param bindingPerCraft    binding items one craft consumes; must be positive
     * @param dyePerCraft        dye items one craft consumes; 0 when no dye is being used
     * @param remaindersPerCraft container items one craft hands back
     * @param materialAvailable  material items in the station's material slot
     * @param bindingAvailable   binding items in the binding slot
     * @param dyeAvailable       dye items in the dye slot
     * @param destinations       whole free destinations the output and its remainders share
     * @param requestedBatch     how many crafts this extraction route is asking for
     */
    public static MaskCraftPlan prepare(int materialPerCraft, int bindingPerCraft, int dyePerCraft,
                                        int remaindersPerCraft,
                                        int materialAvailable, int bindingAvailable, int dyeAvailable,
                                        int destinations, int requestedBatch) {
        if (materialPerCraft < 1 || bindingPerCraft < 1 || dyePerCraft < 0 || remaindersPerCraft < 0) {
            return refused(MaskCraftRejection.INVALID_RECIPE);
        }
        if (requestedBatch < 1) {
            return refused(MaskCraftRejection.NO_ROOM);
        }
        if (materialAvailable < materialPerCraft) {
            return refused(MaskCraftRejection.NOT_ENOUGH_MATERIAL);
        }
        if (bindingAvailable < bindingPerCraft) {
            return refused(MaskCraftRejection.NOT_ENOUGH_BINDING);
        }
        if (dyePerCraft > 0 && dyeAvailable < dyePerCraft) {
            return refused(MaskCraftRejection.NOT_ENOUGH_DYE);
        }
        int affordable = Math.min(materialAvailable / materialPerCraft, bindingAvailable / bindingPerCraft);
        if (dyePerCraft > 0) {
            affordable = Math.min(affordable, dyeAvailable / dyePerCraft);
        }
        int deliverable = Math.max(0, destinations) / (1 + remaindersPerCraft);
        int crafts = Math.min(Math.min(affordable, deliverable), Math.min(requestedBatch, MAX_BATCH));
        if (crafts < 1) {
            // Affordability was already proved above, so the only way to land here is destinations.
            return refused(MaskCraftRejection.NO_ROOM);
        }
        return new MaskCraftPlan(crafts, materialPerCraft, bindingPerCraft, dyePerCraft,
                remaindersPerCraft, MaskCraftRejection.NONE);
    }
}
