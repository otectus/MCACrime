package dev.otectus.mcacrime.api.event;

import dev.otectus.mcacrime.bounty.BountyResolution;
import net.neoforged.bus.api.Event;

/**
 * A bounty was claimed and paid (0.5.1). Fired once per {@code (target, warrantId, revision)}, ever.
 *
 * <p>Not a {@link CrimeEvent}: the subject of a bounty is the target, who at this point is usually
 * dead or in handcuffs, and the claimant is not committing a crime. Listeners get the whole {@link
 * BountyResolution} instead of a player, because the claim key in it is what a contract bridge needs
 * to avoid completing the same objective twice.
 *
 * <p>Posted <em>after</em> the claim has been written and the currency issued, so a listener can treat
 * it as settled fact rather than an intention.
 */
public final class BountyResolvedEvent extends Event {

    private final BountyResolution resolution;

    public BountyResolvedEvent(BountyResolution resolution) {
        this.resolution = resolution;
    }

    public BountyResolution getResolution() {
        return resolution;
    }
}
