package dev.otectus.mcacrime.incident;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.ledger.CrimeContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where an incident happened, whose community it belongs to, and why that one rather than another.
 *
 * <h2>The problem it solves</h2>
 *
 * <p>Until property law, jurisdiction had one rule and it was right: a player's crime belongs to the
 * victim's home village, an NPC's belongs to the thief's. §10.3 breaks it. A player who empties a
 * settlement's granary has wronged that settlement, and the victim — if the policy even names one —
 * may live somewhere else entirely, while the offender's own home village has nothing to do with it.
 * Deriving the community from an entity therefore produces the wrong answer for exactly the crime this
 * release adds.
 *
 * <p>So the choice becomes explicit: three candidates are carried, one is selected, and the reason is
 * written into the crime record's context where an operator can read it. Nothing is guessed — a
 * candidate that is not known is absent, and a context with no candidate at all selects nothing, which
 * is the same answer the old rule gave for a victimless crime.
 *
 * <h2>What it is not</h2>
 *
 * <p>Not a replacement for the string provenance every existing caller passes. Those callers keep
 * working untouched: this is additive, the property path is the only one that supplies it today, and
 * an incident committed without one behaves exactly as it did before.
 *
 * @param dimension            the level the act happened in
 * @param position             where, which is not necessarily where the victim or the offender live
 * @param victimHome           the victim's home community, when there is a victim and it is readable
 * @param propertyJurisdiction the community that owns the property, when property was involved
 * @param eventCommunity       the community the position itself sits in, when one is known
 * @param propertyId           the policy involved, for the record
 * @param propertyRevision     the revision those terms were at
 */
public record IncidentContext(ResourceLocation dimension, BlockPos position,
                              @Nullable CrimeCommunityKey victimHome,
                              @Nullable CrimeCommunityKey propertyJurisdiction,
                              @Nullable CrimeCommunityKey eventCommunity,
                              @Nullable UUID propertyId, int propertyRevision) {

    /** Why the selected community was selected. Written into the record so it can be argued with. */
    public enum Basis {
        /** The property's owning settlement. Outranks everything: this is whose law was broken. */
        PROPERTY_JURISDICTION("property"),
        /** The victim's home community, which is the pre-0.7.4 rule for a player's crime. */
        VICTIM_HOME("victim_home"),
        /** The community the act physically happened in. */
        EVENT_LOCATION("event_location"),
        /** No community could be named, and none is invented. */
        NONE("none");

        private final String id;

        Basis(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public IncidentContext {
        if (dimension == null) {
            throw new IllegalArgumentException("an incident context must name a dimension");
        }
        if (position == null) {
            throw new IllegalArgumentException("an incident context must name a position");
        }
        position = position.immutable();
        propertyRevision = Math.max(0, propertyRevision);
    }

    /** The context for an act at a place, with no property and no readable victim home. */
    public static IncidentContext at(ResourceLocation dimension, BlockPos position) {
        return new IncidentContext(dimension, position, null, null, null, null, 0);
    }

    /** The same context with a victim's home community attached. */
    public IncidentContext withVictimHome(@Nullable CrimeCommunityKey home) {
        return new IncidentContext(dimension, position, home, propertyJurisdiction, eventCommunity,
                propertyId, propertyRevision);
    }

    /** The same context with the property's owning settlement attached. */
    public IncidentContext withProperty(@Nullable CrimeCommunityKey jurisdiction, @Nullable UUID property,
                                        int revision) {
        return new IncidentContext(dimension, position, victimHome, jurisdiction, eventCommunity,
                property, revision);
    }

    /** The same context with the community the position itself sits in attached. */
    public IncidentContext withEventCommunity(@Nullable CrimeCommunityKey community) {
        return new IncidentContext(dimension, position, victimHome, propertyJurisdiction, community,
                propertyId, propertyRevision);
    }

    /**
     * Which candidate wins.
     *
     * <p>Property first, and that ordering is the whole point of the type: the settlement whose stores
     * were emptied is the one whose law was broken, whoever the container's registered owner happens to
     * be and wherever the offender calls home. Victim home second, because that is the existing rule and
     * every existing incident must keep giving the same answer. Event location last, and only as
     * something better than nothing.
     */
    public Basis basis() {
        if (propertyJurisdiction != null) {
            return Basis.PROPERTY_JURISDICTION;
        }
        if (victimHome != null) {
            return Basis.VICTIM_HOME;
        }
        if (eventCommunity != null) {
            return Basis.EVENT_LOCATION;
        }
        return Basis.NONE;
    }

    /** The community this incident is recorded against, or empty when none could be named. */
    public Optional<CrimeCommunityKey> selected() {
        return switch (basis()) {
            case PROPERTY_JURISDICTION -> Optional.ofNullable(propertyJurisdiction);
            case VICTIM_HOME -> Optional.ofNullable(victimHome);
            case EVENT_LOCATION -> Optional.ofNullable(eventCommunity);
            case NONE -> Optional.empty();
        };
    }

    /**
     * What goes into the crime record's context map.
     *
     * <p>Bounded and flat, because that map is persisted on every record and re-read by operator tools:
     * the place, the basis, and the property the basis came from. Not the candidates that lost — a
     * record is not an audit log of a decision, and the basis plus the place is enough to reconstruct
     * one.
     */
    public Map<String, String> provenance() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(CrimeContext.INCIDENT_DIMENSION, dimension.toString());
        out.put(CrimeContext.INCIDENT_POSITION,
                position.getX() + "," + position.getY() + "," + position.getZ());
        out.put(CrimeContext.COMMUNITY_BASIS, basis().id());
        if (propertyId != null) {
            out.put(CrimeContext.PROPERTY_ID, propertyId.toString());
            out.put(CrimeContext.PROPERTY_REVISION, Integer.toString(propertyRevision));
        }
        return out;
    }
}
