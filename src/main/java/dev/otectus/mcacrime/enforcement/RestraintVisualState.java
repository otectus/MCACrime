package dev.otectus.mcacrime.enforcement;

/**
 * Everything a client needs to draw one restrained subject, and nothing else.
 *
 * <p>{@code escortEntityId} is an entity id rather than a UUID because the rope is drawn every frame
 * and an id resolves in constant time; {@code -1} means nobody is holding them, which is also what an
 * escort in another dimension collapses to. NPC captives are led by a real vanilla leash, so they
 * always carry {@code -1} and the mod draws no second rope over the top of it.
 */
public record RestraintVisualState(boolean restrained, RestraintVisualType type, int escortEntityId) {

    private static final RestraintVisualState NONE =
            new RestraintVisualState(false, RestraintVisualType.NONE, -1);

    public static RestraintVisualState none() {
        return NONE;
    }
}
