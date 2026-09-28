package dev.otectus.mcacrime.restraint;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * One restraint actually on one subject: an independent instance, never the registry object.
 *
 * <p>Every field here is per-instance for a reason. Two players in handcuffs have two durability
 * counters, two appliers and two enchantment sets; upstream shares one, because its deserialiser
 * hands back the registered definition ({@code RestraintAPI.getNewRestraintByKey}). {@link #load}
 * constructs a new record every time and cannot do that.
 *
 * <p>The item snapshot is kept as the {@link CompoundTag} {@code ItemStack.saveOptional} writes,
 * following {@code state/world/StolenGoodsRecord}: the tag is the persisted form, so the round trip
 * through the save file is provably lossless and can be asserted without an item registry, which only
 * exists inside a running game. {@link #stack(HolderLookup.Provider)} decodes it on the server at the
 * one moment the item has to become real again — at removal, when the configured return policy runs.
 *
 * <p>In 1.21 the decode needs a {@link HolderLookup.Provider}, because item components hold registry
 * references; the provider is threaded from the caller rather than looked up here, exactly as
 * {@code StolenGoodsRecord.stack} does. The persistence path needs none: the tag is already encoded,
 * so {@link #save()} and {@link #load(CompoundTag)} copy it verbatim.
 *
 * @param instanceId         identity of this piece of gear, stable across save/load
 * @param definitionId       which of the nine definitions it is
 * @param itemSnapshot       the applied stack exactly as it was, or null for system-issued gear
 * @param remainingDurability server-owned; a client never sends a durability delta
 * @param definitionRevision the definition revision this instance was applied under
 * @param applier            who applied it
 * @param context            under what authority
 * @param provenance         where the item came from, which decides what release may give back
 * @param returnPolicy       what happens to the item at removal
 * @param custodyId          the legal custody this application belongs to, when it belongs to one
 * @param appliedTick        the game time it went on
 * @param revision           bumped on every change, so a stale packet can be refused
 */
public record AppliedRestraint(
        UUID instanceId,
        ResourceLocation definitionId,
        @Nullable CompoundTag itemSnapshot,
        int remainingDurability,
        int definitionRevision,
        RestraintApplier applier,
        ApplicationContext context,
        Provenance provenance,
        ReturnPolicy returnPolicy,
        @Nullable UUID custodyId,
        long appliedTick,
        long revision) {

    /** Under what authority a restraint was applied. */
    public enum ApplicationContext {
        /** The subject asked for it. A self-applied hood is not a kidnapping. */
        VOLUNTARY,
        /** A kidnapping. */
        UNLAWFUL,
        /** An arrest or a sentence. */
        LAWFUL,
        /** An operator or a command. */
        ADMINISTRATIVE,
        /** A pillory, a bunk, a trap: nobody applied it, a device did. */
        DEVICE;

        public static ApplicationContext parse(String raw) {
            return parse(raw, UNLAWFUL);
        }

        static ApplicationContext parse(String raw, ApplicationContext fallback) {
            if (raw == null) {
                return fallback;
            }
            String name = raw.trim().toUpperCase(Locale.ROOT);
            for (ApplicationContext value : values()) {
                if (value.name().equals(name)) {
                    return value;
                }
            }
            return fallback;
        }
    }

    /** Where the physical item came from. */
    public enum Provenance {
        /** Taken from somebody's inventory. It exists, and release owes it back somewhere. */
        PLAYER_OWNED,
        /** Minted by the server for an arrest. Release owes nobody an item. */
        SYSTEM_ISSUED,
        /** Written by the schema 14 to 15 reconciliation from an old record (§3.18). */
        LEGACY_CONVERSION;

        public static Provenance parse(String raw) {
            if (raw == null) {
                return SYSTEM_ISSUED;
            }
            String name = raw.trim().toUpperCase(Locale.ROOT);
            for (Provenance value : values()) {
                if (value.name().equals(name)) {
                    return value;
                }
            }
            return SYSTEM_ISSUED;
        }
    }

    /**
     * What removal does with the item.
     *
     * <p>{@link #NONE} is the conservative answer and the one migration uses: an upgraded world must
     * not mint a free pair of cuffs for every prisoner it converts (§3.18).
     */
    public enum ReturnPolicy {
        NONE,
        /** Back to whoever applied it, if they are still around; dropped at the subject otherwise. */
        RETURN_TO_APPLIER,
        /** Dropped where the subject stands. */
        DROP_AT_SUBJECT;

        public static ReturnPolicy parse(String raw) {
            if (raw == null) {
                return NONE;
            }
            String name = raw.trim().toUpperCase(Locale.ROOT);
            for (ReturnPolicy value : values()) {
                if (value.name().equals(name)) {
                    return value;
                }
            }
            return NONE;
        }
    }

    public AppliedRestraint {
        // Defensive: an NBT compound is mutable, and gear everybody can edit in place is not evidence.
        itemSnapshot = itemSnapshot == null ? null : itemSnapshot.copy();
        applier = applier == null ? RestraintApplier.none() : applier;
        context = context == null ? ApplicationContext.UNLAWFUL : context;
        provenance = provenance == null ? Provenance.SYSTEM_ISSUED : provenance;
        returnPolicy = returnPolicy == null ? ReturnPolicy.NONE : returnPolicy;
        remainingDurability = Math.max(0, remainingDurability);
        definitionRevision = Math.max(1, definitionRevision);
        appliedTick = Math.max(0L, appliedTick);
        revision = Math.max(0L, revision);
    }

    @Override
    public CompoundTag itemSnapshot() {
        return itemSnapshot == null ? null : itemSnapshot.copy();
    }

    /**
     * A fresh instance of {@code definition}, at full configured durability.
     *
     * <p>The only constructor gameplay should use: it is what guarantees a new UUID and a durability
     * read from the definition rather than inherited from whatever was there before.
     */
    public static AppliedRestraint of(RestraintDefinition definition, @Nullable CompoundTag itemSnapshot,
                                      RestraintApplier applier, ApplicationContext context,
                                      Provenance provenance, ReturnPolicy returnPolicy,
                                      @Nullable UUID custodyId, long appliedTick) {
        return of(definition, itemSnapshot, applier, context, provenance, returnPolicy, custodyId,
                appliedTick, RestraintDurability.startingDurability(definition));
    }

    /**
     * The same, with the starting durability supplied rather than read from the config.
     *
     * <p>Exists so migration and tests can state the number they mean. Everything in gameplay should
     * use the eight-argument form, which reads {@code restraints.definitions.durability*} through
     * {@link RestraintDurability} — the one source of truth for that number.
     */
    public static AppliedRestraint of(RestraintDefinition definition, @Nullable CompoundTag itemSnapshot,
                                      RestraintApplier applier, ApplicationContext context,
                                      Provenance provenance, ReturnPolicy returnPolicy,
                                      @Nullable UUID custodyId, long appliedTick, int durability) {
        return new AppliedRestraint(UUID.randomUUID(), definition.id(), itemSnapshot,
                Math.max(0, durability), definition.revision(), applier, context, provenance,
                returnPolicy, custodyId, appliedTick, 1L);
    }

    /** The definition, or empty when a save names one this build does not have. */
    public Optional<RestraintDefinition> definition() {
        return RestraintDefinitions.get(definitionId);
    }

    /** True when this instance carries a real item to give back. */
    public boolean hasItem() {
        return itemSnapshot != null && !itemSnapshot.isEmpty();
    }

    /** Server-side only: decoding a stack needs the item registry the provider resolves. */
    public ItemStack stack(HolderLookup.Provider provider) {
        return hasItem() ? ItemStack.parseOptional(provider, itemSnapshot) : ItemStack.EMPTY;
    }

    /** Durability as a 0..1 fraction of the definition's configured maximum, for the client. */
    public float durabilityFraction() {
        // The configured maximum, not the definition's declared one: a server that raised handcuff
        // durability must not show every existing pair as full while it wears down more slowly.
        int max = RestraintDurability.startingDurability(definitionId);
        if (max <= 0) {
            return 1.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, (float) remainingDurability / (float) max));
    }

    /** This instance with one point of durability spent, and its revision advanced. */
    public AppliedRestraint damaged(int amount) {
        int spent = Math.max(0, amount);
        return new AppliedRestraint(instanceId, definitionId, itemSnapshot,
                Math.max(0, remainingDurability - spent), definitionRevision, applier, context,
                provenance, returnPolicy, custodyId, appliedTick, revision + 1L);
    }

    /** True when struggle work has used this instance up. */
    public boolean broken() {
        return remainingDurability <= 0 && definition()
                .map(definition -> definition.escape().durability() > 0)
                .orElse(false);
    }

    /** This instance attributed to {@code custodyId}, without touching anything else. */
    public AppliedRestraint withCustody(@Nullable UUID newCustodyId) {
        return new AppliedRestraint(instanceId, definitionId, itemSnapshot, remainingDurability,
                definitionRevision, applier, context, provenance, returnPolicy, newCustodyId,
                appliedTick, revision + 1L);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("instance", instanceId);
        tag.putString("definition", definitionId.toString());
        if (hasItem()) {
            tag.put("item", itemSnapshot.copy());
        }
        tag.putInt("durability", remainingDurability);
        tag.putInt("definitionRevision", definitionRevision);
        tag.put("applier", applier.save());
        tag.putString("context", context.name());
        tag.putString("provenance", provenance.name());
        tag.putString("returnPolicy", returnPolicy.name());
        if (custodyId != null) {
            tag.putUUID("custody", custodyId);
        }
        tag.putLong("appliedTick", appliedTick);
        tag.putLong("revision", revision);
        return tag;
    }

    /**
     * Reads one instance, or empty when the row has no instance id or names no known definition.
     *
     * <p>Empty rather than a substituted default, because an unreadable instance must be quarantined
     * by the caller: guessing a definition would put gear on somebody that nobody applied.
     */
    public static Optional<AppliedRestraint> load(@Nullable CompoundTag tag) {
        if (tag == null || !tag.hasUUID("instance")) {
            return Optional.empty();
        }
        ResourceLocation definitionId = ResourceLocation.tryParse(tag.getString("definition"));
        if (definitionId == null) {
            return Optional.empty();
        }
        return Optional.of(new AppliedRestraint(
                tag.getUUID("instance"),
                definitionId,
                tag.contains("item") ? tag.getCompound("item") : null,
                tag.getInt("durability"),
                tag.getInt("definitionRevision"),
                RestraintApplier.load(tag.getCompound("applier")),
                ApplicationContext.parse(tag.getString("context")),
                Provenance.parse(tag.getString("provenance")),
                ReturnPolicy.parse(tag.getString("returnPolicy")),
                tag.hasUUID("custody") ? tag.getUUID("custody") : null,
                tag.getLong("appliedTick"),
                tag.getLong("revision")));
    }
}
