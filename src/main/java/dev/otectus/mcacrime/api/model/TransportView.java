package dev.otectus.mcacrime.api.model;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * One hold on a subject: a chain, an anchor or a lawful escort (0.7.5 M6.4).
 *
 * <p>One subject has at most one hold, arbitrated by {@code tether/TransportArbiter}, so this is a
 * single view rather than a list. The anchor is expressed twice on purpose — as the entity the chain
 * is drawn to, and as the block position the reverse index is keyed by — because neither alone
 * answers both "what is it tied to" and "what is tied to this fence post".
 *
 * @param tetherId   the hold's own id
 * @param subject    who is being held
 * @param kind       {@code chain}, {@code anchor} or {@code escort}
 * @param holder     the entity on the other end, when there is one
 * @param anchorPos  the block the chain is tied to, when it is tied to a block
 * @param dimension  where the hold is
 * @param maxLength  how far the subject may get before the hold starts pulling
 * @param lawful     whether this hold is a lawful escort rather than a private chain
 */
public record TransportView(UUID tetherId, UUID subject, String kind, Optional<UUID> holder,
                            Optional<long[]> anchorPos, Optional<ResourceLocation> dimension,
                            double maxLength, boolean lawful) {

    public TransportView {
        kind = kind == null ? "" : kind;
        holder = holder == null ? Optional.empty() : holder;
        anchorPos = anchorPos == null ? Optional.empty() : anchorPos;
        dimension = dimension == null ? Optional.empty() : dimension;
        maxLength = Double.isFinite(maxLength) ? Math.max(0.0D, maxLength) : 0.0D;
    }

    /** Whether this hold exists because somebody is being walked somewhere by the law. */
    public boolean escort() {
        return "escort".equalsIgnoreCase(kind);
    }
}
