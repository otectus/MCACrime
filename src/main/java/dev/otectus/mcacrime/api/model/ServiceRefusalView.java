package dev.otectus.mcacrime.api.model;

/**
 * Whether one villager will serve one person, and what they say if not (reference §11.5).
 *
 * <p>Published so a companion can ask the same question MCA: Crime asks itself rather than
 * re-implementing the rule from a band and a wanted flag. That matters more here than for most
 * projections: the rule has one exception that must never be got wrong — an essential service is
 * never refused — and a second implementation of it is a second chance to lock a player out of food.
 *
 * <p>Both keys are translation keys, never sentences: MCA: Crime never sends text over the wire, and a
 * consumer is expected to render them the same way it renders any other line.
 *
 * @param refused   whether the villager will refuse
 * @param kind      the service that was asked about, as its stable id
 * @param reasonKey the key for what the villager says, empty when nothing was refused
 * @param repairKey the key for the route back, empty when nothing was refused
 */
public record ServiceRefusalView(boolean refused, String kind, String reasonKey, String repairKey) {

    public ServiceRefusalView {
        kind = kind == null ? "" : kind;
        reasonKey = reasonKey == null ? "" : reasonKey;
        repairKey = repairKey == null ? "" : repairKey;
    }

    /** The answer for a service nobody may refuse, and for every server with the feature off. */
    public static ServiceRefusalView allowed(String kind) {
        return new ServiceRefusalView(false, kind, "", "");
    }
}
