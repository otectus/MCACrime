package dev.otectus.mcacrime.state;

import net.neoforged.bus.api.IEventBus;

/**
 * Registration hook for the player crime data attachment.
 *
 * <p>Phase 3 fills this in: the {@code DeferredRegister<AttachmentType<?>>}, the serializable
 * {@link PlayerCrimeData} attachment and the static accessor all land there. It exists now so the
 * entrypoint can call the shape it will keep.
 */
public final class CrimeAttachments {

    private CrimeAttachments() {
    }

    public static void register(IEventBus bus) {
        // Phase 3 fills this in
    }
}
