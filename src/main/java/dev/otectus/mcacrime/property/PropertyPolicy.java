package dev.otectus.mcacrime.property;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.facility.TownsteadBuildingRef;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * One statement of ownership: this container, or this building, belongs to somebody and these are the
 * terms on which it may be taken from.
 *
 * <h2>Why ownership is explicit and never inferred</h2>
 *
 * <p>§10.1 is blunt about it: a recognised settlement building does not create ownership, and an
 * existing player container stays unclaimed until somebody claims it. The reason is the failure mode
 * on the other side of the guess — a world where every chest a settlement mod happened to generate
 * becomes a crime scene the first time a player opens it, retroactively, on a save that has been
 * played for a year. So a policy exists only because an operator, a datapack, or MCA: Crime's own
 * bounded auto-protection put it there, and {@link PropertyAccess} answers {@code UNKNOWN} — never
 * "denied" — where none exists.
 *
 * <h2>The revision</h2>
 *
 * <p>Bumped whenever the terms change, and stamped onto every {@link PropertyReceipt} at the moment of
 * loss. That is what makes §10.3's "retain history when property is later transferred" true rather
 * than aspirational: the receipt remembers who owned it when it was taken, so changing the owner
 * afterwards cannot rewrite who was robbed or who restitution is owed to.
 *
 * @param id                       stable identity, printed abbreviated by {@code /crime property}
 * @param revision                 bumped on every change to the terms
 * @param dimension                the level this policy is in; never inferred from a caller's position
 * @param scope                    container or building
 * @param position                 the container block, for {@link PropertyScope#CONTAINER}
 * @param building                 the settlement building, for {@link PropertyScope#BUILDING}
 * @param ownerKind                who it belongs to
 * @param ownerId                  the owner's UUID where the kind has one; null for a village or facility
 * @param rule                     what is permitted
 * @param protectedFromAutoSourcing whether settlement workers must not source from it at all
 * @param source                   who created this policy
 * @param declaredBy               the operator name, for the record an operator reads
 * @param gameTime                 when it was declared
 */
public record PropertyPolicy(UUID id, int revision, ResourceLocation dimension, PropertyScope scope,
                             @Nullable BlockPos position, TownsteadBuildingRef building,
                             PropertyOwnerKind ownerKind, @Nullable UUID ownerId,
                             PropertyAccessRule rule, boolean protectedFromAutoSourcing,
                             PropertySource source, String declaredBy, long gameTime) {

    public PropertyPolicy {
        if (id == null) {
            throw new IllegalArgumentException("a property policy must have an id");
        }
        if (dimension == null) {
            throw new IllegalArgumentException("a property policy must name a dimension");
        }
        if (scope == null || ownerKind == null || rule == null || source == null) {
            throw new IllegalArgumentException("a property policy must be fully specified");
        }
        if (scope == PropertyScope.CONTAINER && position == null) {
            throw new IllegalArgumentException("a container policy must name a position");
        }
        if (building == null) {
            building = TownsteadBuildingRef.unbound(dimension);
        }
        if (scope == PropertyScope.BUILDING && !building.bound()) {
            throw new IllegalArgumentException("a building policy must name a building");
        }
        revision = Math.max(0, revision);
        declaredBy = declaredBy == null || declaredBy.isBlank() ? "unknown" : declaredBy;
    }

    /** A hand-made container policy, with a fresh id. */
    public static PropertyPolicy container(ResourceLocation dimension, BlockPos position,
                                           TownsteadBuildingRef building, PropertyOwnerKind ownerKind,
                                           @Nullable UUID ownerId, PropertyAccessRule rule,
                                           boolean protectedFromAutoSourcing, PropertySource source,
                                           String declaredBy, long gameTime) {
        UUID id = source.regenerable()
                ? generatedId(dimension, PropertyScope.CONTAINER, position.asLong())
                : UUID.randomUUID();
        return new PropertyPolicy(id, 1, dimension, PropertyScope.CONTAINER, position.immutable(), building,
                ownerKind, ownerId, rule, protectedFromAutoSourcing, source, declaredBy, gameTime);
    }

    /** A building policy, with a fresh id. */
    public static PropertyPolicy building(ResourceLocation dimension, TownsteadBuildingRef building,
                                          PropertyOwnerKind ownerKind, @Nullable UUID ownerId,
                                          PropertyAccessRule rule, boolean protectedFromAutoSourcing,
                                          PropertySource source, String declaredBy, long gameTime) {
        UUID id = source.regenerable()
                ? generatedId(dimension, PropertyScope.BUILDING,
                        ((long) building.villageId() << 32) | (building.buildingId() & 0xffffffffL))
                : UUID.randomUUID();
        return new PropertyPolicy(id, 1, dimension, PropertyScope.BUILDING, null, building,
                ownerKind, ownerId, rule, protectedFromAutoSourcing, source, declaredBy, gameTime);
    }

    /**
     * The id a generated policy always gets for the same place.
     *
     * <p>Derived rather than random so that re-running the auto-protection sweep replaces the policy it
     * wrote last time instead of laying a second one beside it. Without this, a server that restarts
     * daily would fill its property table with duplicates of the same evidence chest and then start
     * refusing new ones because the table was full.
     */
    public static UUID generatedId(ResourceLocation dimension, PropertyScope scope, long key) {
        return UUID.nameUUIDFromBytes(("mcacrime:property:" + dimension + ":" + scope.id() + ":" + key)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The community whose law governs this property, when one is known.
     *
     * <p>The reason the whole typed-context work exists: a theft inside a settlement building is that
     * settlement's business, not the offender's home village's and not the victim's. An unbound
     * building reference produces no community, and empty is the honest answer rather than a fallback
     * to whoever happens to be standing nearby.
     */
    public Optional<CrimeCommunityKey> jurisdiction() {
        return building.bound() ? CrimeCommunityKey.of(dimension, building.villageId()) : Optional.empty();
    }

    /** Whether this policy covers {@code pos} in {@code level}. Building scope always answers false. */
    public boolean covers(ResourceLocation level, BlockPos pos) {
        return scope == PropertyScope.CONTAINER && position != null
                && dimension.equals(level) && position.equals(pos);
    }

    /** Whether this policy is the one for {@code ref}. Container scope always answers false. */
    public boolean names(@Nullable TownsteadBuildingRef ref) {
        return scope == PropertyScope.BUILDING && ref != null && ref.bound()
                && building.villageId() == ref.villageId() && building.buildingId() == ref.buildingId()
                && dimension.equals(ref.dimension());
    }

    /** The same policy under new terms, one revision later. */
    public PropertyPolicy withRule(PropertyAccessRule newRule, boolean protectFromSourcing, long now) {
        if (newRule == rule && protectFromSourcing == protectedFromAutoSourcing) {
            return this;
        }
        return new PropertyPolicy(id, revision + 1, dimension, scope, position, building, ownerKind,
                ownerId, newRule, protectFromSourcing, source, declaredBy, now);
    }

    /** The same policy under a new owner, one revision later. Receipts already written are untouched. */
    public PropertyPolicy withOwner(PropertyOwnerKind newKind, @Nullable UUID newOwner, long now) {
        return new PropertyPolicy(id, revision + 1, dimension, scope, position, building, newKind,
                newOwner, rule, protectedFromAutoSourcing, source, declaredBy, now);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putInt("rev", revision);
        tag.putString("dim", dimension.toString());
        tag.putString("scope", scope.id());
        if (position != null) {
            tag.putLong("pos", position.asLong());
        }
        tag.put("building", building.save());
        tag.putString("ownerKind", ownerKind.id());
        if (ownerId != null) {
            tag.putUUID("owner", ownerId);
        }
        tag.putString("rule", rule.id());
        tag.putBoolean("noSourcing", protectedFromAutoSourcing);
        tag.putString("source", source.id());
        tag.putString("by", declaredBy);
        tag.putLong("at", gameTime);
        return tag;
    }

    /**
     * Loads a policy, or null when it is unreadable; the caller quarantines the row.
     *
     * <p>Null rather than a repair. A policy whose rule or dimension cannot be read is a policy nobody
     * can evaluate, and defaulting one would either charge somebody under terms nobody wrote or
     * silently unprotect a village's stores — both worse than an operator seeing a quarantined row.
     */
    @Nullable
    public static PropertyPolicy load(CompoundTag tag) {
        if (tag == null || !tag.hasUUID("id")) {
            return null;
        }
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dim"));
        PropertyScope scope = PropertyScope.parse(tag.getString("scope")).orElse(null);
        PropertyAccessRule rule = PropertyAccessRule.parse(tag.getString("rule")).orElse(null);
        PropertyOwnerKind ownerKind = PropertyOwnerKind.parse(tag.getString("ownerKind")).orElse(null);
        PropertySource source = PropertySource.parse(tag.getString("source")).orElse(null);
        if (dimension == null || scope == null || rule == null || ownerKind == null || source == null) {
            return null;
        }
        TownsteadBuildingRef building = TownsteadBuildingRef.load(tag.getCompound("building"));
        if (building == null) {
            building = TownsteadBuildingRef.unbound(dimension);
        }
        BlockPos position = tag.contains("pos") ? BlockPos.of(tag.getLong("pos")) : null;
        if (scope == PropertyScope.CONTAINER && position == null) {
            return null;
        }
        if (scope == PropertyScope.BUILDING && !building.bound()) {
            return null;
        }
        return new PropertyPolicy(tag.getUUID("id"), tag.getInt("rev"), dimension, scope, position,
                building, ownerKind, tag.hasUUID("owner") ? tag.getUUID("owner") : null, rule,
                tag.getBoolean("noSourcing"), source, tag.getString("by"), tag.getLong("at"));
    }

    /** The first eight characters of the id, which is what an operator types back at a command. */
    public String shortId() {
        return id.toString().substring(0, 8);
    }

    /** One line for {@code /crime property list}. */
    public String describe() {
        String where = scope == PropertyScope.CONTAINER && position != null
                ? position.getX() + "," + position.getY() + "," + position.getZ()
                : building.describe();
        return shortId() + " " + scope.id() + " at " + where + " — " + rule.id()
                + ", owner " + ownerKind.id() + (ownerId == null ? "" : " " + ownerId)
                + ", " + source.id() + " rev " + revision
                + (protectedFromAutoSourcing ? ", not sourced by workers" : "");
    }
}
