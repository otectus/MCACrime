package dev.otectus.mcacrime.dialogue;

/**
 * Whether a villager's line can reach a listener (0.7.7).
 *
 * <p>A spoken line used to be delivered to its listener wherever they were. That was invisible in
 * single player, where a villager's chunk unloads when its only player walks off, and loud in
 * multiplayer: a second player standing in the village keeps the chunk loaded, so a reaction that ended
 * there told the first player "I would rather not speak to you" from the other side of the map, or from
 * another dimension.
 *
 * <p>Pure, so the boundary is pinned by a test: same level, and no farther than the radius. Exactly at
 * the radius still hears.
 */
public final class SpeechLocality {

    private SpeechLocality() {
    }

    /**
     * @param sameLevel   speaker and listener are in the same level instance
     * @param distanceSqr squared distance between them
     * @param radius      {@code dialogue.speechRadius}
     */
    public static boolean reaches(boolean sameLevel, double distanceSqr, double radius) {
        if (!sameLevel || !Double.isFinite(distanceSqr) || distanceSqr < 0.0D || !(radius > 0.0D)) {
            return false;
        }
        return distanceSqr <= radius * radius;
    }
}
