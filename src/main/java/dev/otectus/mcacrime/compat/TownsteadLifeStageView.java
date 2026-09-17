package dev.otectus.mcacrime.compat;

/**
 * The life stage a villager is currently in, plus the three behaviour flags MCA: Crime cares about.
 *
 * <p>{@code mobile}, {@code needs} and {@code talkable} are Townstead's server-side stage flags: a
 * stage that is not mobile has its AI frozen, one with no needs has hunger and thirst pinned, and one
 * that is not talkable blocks interaction entirely. All three decide whether a crime, an arrest or a
 * conversation is even coherent for this entity.
 *
 * <p>They live on Townstead's internal stage record rather than on its public snapshot, so they bind
 * under {@link TownsteadCapability#STAGE_CAPABILITIES} separately from the rest of this view.
 * {@code flagsKnown} says whether they were actually read. <b>When they were not, all three default
 * to true</b>, because "we could not ask" must fail towards treating the villager as an ordinary
 * participant — freezing a villager MCA: Crime cannot confirm is frozen would be the worse error.
 */
public record TownsteadLifeStageView(
        String id,
        String label,
        int days,
        float scale,
        String presentsAs,
        float narrativeStart,
        float narrativeEnd,
        boolean flagsKnown,
        boolean mobile,
        boolean needs,
        boolean talkable,
        String rig) {

    /**
     * The rig bases MCA: Crime's wrist cuffs are modelled for.
     *
     * <p>A Townstead stage names its rig the same way a species does — a model reference such as
     * {@code mca:villager} or {@code minecraft:spider}, or a custom geometry path. These are the ones
     * with a vanilla humanoid skeleton, which is what the cuff layer copies arm transforms from.
     */
    public static final java.util.Set<String> HUMANOID_RIGS = java.util.Set.of(
            "humanoid", "mca:villager", "minecraft:villager", "minecraft:player");

    public TownsteadLifeStageView {
        id = id == null ? "" : id;
        label = label == null ? "" : label;
        presentsAs = presentsAs == null ? "" : presentsAs;
        rig = rig == null ? "" : rig.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** The stage as read with no capability flags available: capable on every axis. */
    public static TownsteadLifeStageView withoutFlags(String id, String label, int days, float scale,
                                                      String presentsAs, float narrativeStart,
                                                      float narrativeEnd) {
        return new TownsteadLifeStageView(id, label, days, scale, presentsAs, narrativeStart, narrativeEnd,
                false, true, true, true, "");
    }

    /**
     * Whether this stage is rendered on a body MCA: Crime's restraint cuffs fit.
     *
     * <p>A Townstead stage may override the species rig — an egg stage renders as an egg, a larval
     * stage as a grub — and a rig that is not humanoid has no arms in the places the cuff layer copies
     * its transforms from. An empty rig means the stage overrode nothing, which is the ordinary case
     * and renders as the villager model; a rig in {@link #HUMANOID_RIGS} is humanoid; anything else is
     * not, and has to be presented some other way.
     *
     * <p>Only the stage's own override is read. A species-level rig — a root that is a spider at every
     * stage — is not on Townstead's public snapshot at all, so a non-humanoid species with no stage
     * override still reads as humanoid here. That is a known limit rather than an oversight: the
     * fallback exists to avoid cuffs floating where no arms are, and it covers the stage-shaped case
     * this mod can actually see.
     *
     * <p>Unknown reads as humanoid on purpose. The alternative is showing a tether on every ordinary
     * villager the moment the rig read stops binding, which is a visible regression for every player,
     * whereas cuffs on an unusual body are a cosmetic oddity for the few worlds that have one.
     */
    public boolean humanoidRig() {
        return rig.isEmpty() || HUMANOID_RIGS.contains(rig);
    }

    /** Townstead's canonical adult mapping, which MCA's own adult gates follow. */
    public boolean adult() {
        return "adult".equals(presentsAs);
    }

    public String describe() {
        return "life stage: " + (id.isEmpty() ? "?" : id)
                + (label.isEmpty() ? "" : " (" + label + ")")
                + ", presents as " + (presentsAs.isEmpty() ? "?" : presentsAs)
                + ", " + days + " days"
                + (flagsKnown
                        ? ", mobile=" + mobile + " needs=" + needs + " talkable=" + talkable
                        : ", stage flags unavailable (assumed capable)")
                + (rig.isEmpty() ? "" : ", rig " + rig);
    }
}
