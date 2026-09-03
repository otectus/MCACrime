package dev.otectus.mcacrime.ai;

import java.util.UUID;

/**
 * Generic personality and history inputs to a reaction decision (spec §11.5).
 *
 * <p>The point of this type is that no handler anywhere checks an MCA enum. MCA's personality and
 * trait APIs are not verified across the versions this mod supports, and scattering
 * {@code if (personality == BOLD)} through the reaction code would make every one of those sites a
 * separate compatibility break the day MCA renames a constant. Instead MCA data is normalised into
 * seven bounded factors here, once, and everything downstream reads a float.
 *
 * <p>Every factor is {@code 0..1}. When MCA data is unavailable the fallback is <b>deterministic per
 * villager</b> rather than a constant: derived from the villager's UUID, so the same villager is
 * always the same amount of brave, across restarts and across whether MCA happened to answer. A
 * constant would make every villager in the world react identically, which reads worse than any
 * wrong-but-varied guess.
 */
public record ReactionFactors(float bravery,
                              float sociability,
                              float lawfulness,
                              float greed,
                              float loyaltyToActor,
                              float loyaltyToVillage,
                              float combatConfidence) {

    public ReactionFactors {
        bravery = clamp(bravery);
        sociability = clamp(sociability);
        lawfulness = clamp(lawfulness);
        greed = clamp(greed);
        loyaltyToActor = clamp(loyaltyToActor);
        loyaltyToVillage = clamp(loyaltyToVillage);
        combatConfidence = clamp(combatConfidence);
    }

    private static float clamp(float value) {
        return Float.isNaN(value) ? 0.5F : Math.max(0.0F, Math.min(1.0F, value));
    }

    /**
     * Builds the factors from what can actually be read without naming an MCA type.
     *
     * @param villager        identity, used for the deterministic spread
     * @param hearts          MCA relationship hearts with the actor; negative means a soured history
     * @param isResponder     a guard or other law responder
     * @param isAdult         children are less brave and less combat-capable, never more
     * @param priorEncounters how many times this actor has already done something to them
     * @param priorResistance whether they have successfully resisted this actor before
     */
    public static ReactionFactors of(UUID villager, int hearts, boolean isResponder, boolean isAdult,
                                     int priorEncounters, boolean priorResistance) {
        float spreadA = spread(villager, 0x9E3779B9L);
        float spreadB = spread(villager, 0x7F4A7C15L);
        float spreadC = spread(villager, 0x2545F491L);

        // A guard is braver and more combat-confident by role, not by dice.
        float bravery = isResponder ? 0.75F + spreadA * 0.25F : 0.25F + spreadA * 0.5F;
        float combat = isResponder ? 0.7F + spreadB * 0.3F : 0.1F + spreadB * 0.35F;
        if (!isAdult) {
            bravery *= 0.4F;
            combat *= 0.2F;
        }
        // Having won before is the one history effect that raises confidence; everything else about a
        // repeat encounter makes a villager warier, which the state machine reads off memory directly.
        if (priorResistance) {
            bravery += 0.2F;
            combat += 0.15F;
        }

        // Hearts are MCA's relationship currency. Loyalty to the actor rises with them and is the
        // reason a family member hesitates where a stranger runs.
        float loyaltyToActor = clamp(0.5F + hearts / 200.0F);
        // A soured relationship does not make somebody disloyal to their village; it makes them wary
        // of one person. Village loyalty comes from role and disposition instead.
        float loyaltyToVillage = isResponder ? 0.9F : 0.4F + spreadC * 0.4F;
        float lawfulness = isResponder ? 0.9F : 0.35F + spreadA * 0.45F;
        float sociability = 0.3F + spreadB * 0.5F;
        float greed = 0.2F + spreadC * 0.5F;

        // Repeat victimisation wears down the willingness to stand there and talk about it.
        float wear = Math.min(0.3F, Math.max(0, priorEncounters) * 0.06F);
        return new ReactionFactors(bravery - wear, sociability, lawfulness, greed,
                loyaltyToActor, loyaltyToVillage, combat);
    }

    /** Safe defaults for an entity nothing can be read from. Neutral, and identical every time. */
    public static ReactionFactors unknown() {
        return new ReactionFactors(0.5F, 0.5F, 0.5F, 0.5F, 0.5F, 0.5F, 0.3F);
    }

    /**
     * How willing this villager is to fight rather than run, once a threat is real. Combat confidence
     * dominates, bravery modifies it, and loyalty to the actor pulls hard the other way — you do not
     * swing at family.
     */
    public float resistanceScore() {
        return clamp(combatConfidence * 0.6F + bravery * 0.3F - (loyaltyToActor - 0.5F) * 0.4F);
    }

    /** How willing they are to go and find a guard rather than hide. */
    public float helpSeekingScore() {
        return clamp(sociability * 0.4F + lawfulness * 0.4F + loyaltyToVillage * 0.2F);
    }

    /** How readily they hand over what is asked for instead of arguing about it. */
    public float complianceScore() {
        return clamp(1.0F - resistanceScore() * 0.7F - greed * 0.3F);
    }

    /**
     * A stable {@code 0..1} spread for one villager and one purpose. Mixing the UUID's two halves with
     * a per-purpose constant means bravery and greed are uncorrelated for the same villager, which a
     * plain {@code hashCode()} would not give.
     */
    private static float spread(UUID id, long salt) {
        if (id == null) {
            return 0.5F;
        }
        long mixed = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 17) ^ salt;
        mixed *= 0xFF51AFD7ED558CCDL;
        mixed ^= mixed >>> 33;
        return (float) ((mixed >>> 11) / (double) (1L << 53));
    }
}
