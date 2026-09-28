package dev.otectus.mcacrime.api.model;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * A device holding a subject: a pillory, a guillotine or a prison bunk (0.7.5 M6.4).
 *
 * <p>Occupancy is keyed by the device rather than flagged on the occupant, which is what makes a
 * chunk unload not a jailbreak and a broken device release exactly once. This view says the same
 * thing from the subject's side.
 *
 * @param detentionId the occupancy's own id
 * @param subject     who is in it
 * @param kind        {@code pillory}, {@code guillotine} or {@code bunk}
 * @param dimension   where the device is
 * @param device      the device's canonical position, as x/y/z
 * @param generation  the occupant generation, so a stale claim cannot act on a new occupant
 * @param condemned   whether a capital sentence names this occupant (0.7.5 §3.19); a device holding
 *                    somebody who is not condemned can release them and can never execute them
 */
public record DetentionView(UUID detentionId, UUID subject, String kind,
                            Optional<ResourceLocation> dimension, long[] device, long generation,
                            boolean condemned) {

    public DetentionView {
        kind = kind == null ? "" : kind;
        dimension = dimension == null ? Optional.empty() : dimension;
        device = device == null || device.length != 3 ? new long[] {0L, 0L, 0L} : device.clone();
        generation = Math.max(1L, generation);
    }

    @Override
    public long[] device() {
        return device.clone();
    }
}
