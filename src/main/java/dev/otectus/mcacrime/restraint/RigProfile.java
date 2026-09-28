package dev.otectus.mcacrime.restraint;

/**
 * What body a subject is being restrained on, as the definitions' rig predicate sees it.
 *
 * <p>A described rig rather than a hard-coded world height, because §7.2 requires the head/arms/legs
 * regions to work for a crouching player, a short or tall MCA body and a Townstead rig. A predicate
 * over this record can answer "these cuffs need two arms" without naming any entity class, which is
 * also what keeps the definitions registry free of MCA and Townstead types.
 *
 * @param rigId    the rig's identifier, lower case; {@code "humanoid"} for a vanilla biped
 * @param humanoid whether the rig is a biped at all
 * @param head     whether the rig has a head region that can carry gear
 * @param arms     whether the rig has arms
 * @param legs     whether the rig has legs
 * @param scale    the rig's height scale relative to a vanilla player, for region placement
 */
public record RigProfile(String rigId, boolean humanoid, boolean head, boolean arms, boolean legs,
                         float scale) {

    public RigProfile {
        rigId = rigId == null || rigId.isBlank() ? "unknown" : rigId;
        scale = Float.isFinite(scale) && scale > 0.0F ? scale : 1.0F;
    }

    /** The vanilla biped every player and villager is drawn on unless something says otherwise. */
    public static RigProfile vanillaHumanoid() {
        return new RigProfile("humanoid", true, true, true, true, 1.0F);
    }

    /** A biped at another scale: a child body, or a tall Townstead rig. */
    public static RigProfile scaledHumanoid(String rigId, float scale) {
        return new RigProfile(rigId, true, true, true, true, scale);
    }

    /** True when this rig has the region {@code slot} needs. */
    public boolean supports(RestraintSlot slot) {
        if (slot == null) {
            return false;
        }
        return switch (slot) {
            case HEAD -> head;
            case ARMS -> arms;
            case LEGS -> legs;
        };
    }
}
