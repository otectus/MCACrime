package dev.otectus.mcacrime.api.model;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * One lock, as a companion mod may see it (0.7.5 M6.4).
 *
 * <p>Carries the lock's identity and state and <b>no secret</b>: not the key binding, not the
 * combination, not the pick difficulty roll. A companion that could read a binding could mint a key,
 * and the one thing a lock is for is that only a bound key opens it.
 *
 * @param lockId      the lock's own id, which keys survive a block being replaced by
 * @param locked      whether it is locked right now
 * @param reinforced  whether it is the diamond-reinforced kind
 * @param dimension   where it is
 * @param pos         the canonical position, as x/y/z, for a block lock
 */
public record LockView(UUID lockId, boolean locked, boolean reinforced,
                       Optional<ResourceLocation> dimension, Optional<long[]> pos) {

    public LockView {
        dimension = dimension == null ? Optional.empty() : dimension;
        pos = pos == null ? Optional.empty() : pos;
    }
}
