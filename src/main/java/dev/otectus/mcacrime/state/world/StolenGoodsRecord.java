package dev.otectus.mcacrime.state.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * One theft, with provenance (0.5.1): who took what from whom, and when.
 *
 * <p>Provenance is the whole reason this is persisted rather than left on the thief's entity. A
 * stolen item has to be returnable to its actual owner on arrest or confirmed death, and a
 * villager that unloads with the loot in an inventory nobody can query would make both impossible.
 *
 * <p>Exactly one of {@code stackTag} and {@code currency} is meaningful per record: theft takes
 * currency first and falls back to a single item, never both. A record with neither is a record of
 * nothing and callers treat it as such.
 *
 * <p>The item is held as its saved {@link CompoundTag} rather than as an {@link ItemStack}, and
 * decoded on demand by {@link #stack(HolderLookup.Provider)}. That is not a stylistic choice: the tag
 * <em>is</em> the persisted form, so keeping it verbatim means the round-trip through the save file is
 * provably lossless — a returned sword is byte-for-byte the sword that was taken, enchantments,
 * custom name and all. In 1.21 the encode and decode both need a {@link HolderLookup.Provider},
 * because item components hold registry references, so the provider is threaded from
 * {@code CrimeWorldData.save}/{@code load} rather than looked up here.
 */
public record StolenGoodsRecord(UUID transactionId, UUID thief, UUID owner, @Nullable CompoundTag stackTag,
                                long currency, long stolenAt, String providerId) {

    public StolenGoodsRecord {
        // Defensive: an NBT compound is mutable, and a record everybody can edit in place is not a
        // ledger entry.
        stackTag = stackTag == null ? null : stackTag.copy();
        providerId = providerId == null ? "" : providerId;
    }

    /** Legacy records did not identify their currency provider. */
    public StolenGoodsRecord(UUID transactionId, UUID thief, UUID owner, @Nullable CompoundTag stackTag,
                             long currency, long stolenAt) {
        this(transactionId, thief, owner, stackTag, currency, stolenAt, "");
    }

    @Override public CompoundTag stackTag() { return stackTag == null ? null : stackTag.copy(); }

    /** Builds a record around a stack that has just been removed from somebody's inventory. */
    public static StolenGoodsRecord ofStack(HolderLookup.Provider provider, UUID transactionId, UUID thief,
                                            UUID owner, @Nullable ItemStack stack, long currency,
                                            long stolenAt) {
        // saveOptional rather than save: an empty stack is a legitimate input here (a currency-only
        // theft) and plain save throws on one.
        CompoundTag saved = stack == null || stack.isEmpty() ? null : (CompoundTag) stack.saveOptional(provider);
        return new StolenGoodsRecord(transactionId, thief, owner, saved, currency, stolenAt);
    }

    /** True when this record carries an item rather than currency. Touches no registry. */
    public boolean hasStack() {
        return stackTag != null && !stackTag.isEmpty();
    }

    /**
     * The stolen item, decoded from its saved form. Server-side only — the item registry has to exist
     * — and never called by the persistence path.
     */
    public ItemStack stack(HolderLookup.Provider provider) {
        return hasStack() ? ItemStack.parseOptional(provider, stackTag) : ItemStack.EMPTY;
    }

    public CompoundTag save(HolderLookup.Provider provider) { return save(); }

    /** Copies the already encoded item tag without resolving a registry. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("transactionId", transactionId);
        tag.putUUID("thief", thief);
        tag.putUUID("owner", owner);
        // A currency-only theft writes no stack at all: absent already means "no item" on load.
        if (hasStack()) {
            tag.put("stack", stackTag.copy());
        }
        tag.putLong("currency", currency);
        tag.putLong("stolenAt", stolenAt);
        if (!providerId.isEmpty()) tag.putString("provider", providerId);
        return tag;
    }

    /** Throws on a tag with no transaction/thief/owner id; the caller skips that one entry. */
    public static StolenGoodsRecord load(HolderLookup.Provider provider, CompoundTag tag) {
        return new StolenGoodsRecord(
                tag.getUUID("transactionId"),
                tag.getUUID("thief"),
                tag.getUUID("owner"),
                tag.contains("stack") ? tag.getCompound("stack") : null,
                tag.getLong("currency"),
                tag.getLong("stolenAt"), tag.getString("provider"));
    }
}
