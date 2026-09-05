package dev.otectus.mcacrime.state.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One theft, with provenance (0.5.1): who took what from whom, and when.
 *
 * <p>Provenance is the whole reason this is persisted rather than left on the thief's entity. A
 * stolen item has to be returnable to its actual owner on arrest and droppable on death, and a
 * villager that unloads with the loot in an inventory nobody can query would make both impossible.
 *
 * <p>Exactly one of {@code stackTag} and {@code currency} is meaningful per record: theft takes
 * currency first and falls back to a single item, never both. A record with neither is a record of
 * nothing and callers treat it as such.
 *
 * <p>The item is held as its saved {@link CompoundTag} rather than as an {@link ItemStack}, and
 * decoded on demand by {@link #stack()}. That is not a stylistic choice: the tag <em>is</em> the
 * persisted form, so keeping it verbatim means the round-trip through the save file is provably
 * lossless — a returned sword is byte-for-byte the sword that was taken, enchantments, custom name
 * and all — and it can be asserted as such without an item registry, which only exists inside a
 * running game. Decoding happens on the server, at the one moment the item has to become real again.
 */
public record StolenGoodsRecord(UUID transactionId, UUID thief, UUID owner, @Nullable CompoundTag stackTag,
                                long currency, long stolenAt) {

    public StolenGoodsRecord {
        // Defensive: an NBT compound is mutable, and a record everybody can edit in place is not a
        // ledger entry.
        stackTag = stackTag == null ? null : stackTag.copy();
    }

    /** Builds a record around a stack that has just been removed from somebody's inventory. */
    public static StolenGoodsRecord ofStack(UUID transactionId, UUID thief, UUID owner,
                                            @Nullable ItemStack stack, long currency, long stolenAt) {
        CompoundTag saved = stack == null || stack.isEmpty() ? null : stack.save(new CompoundTag());
        return new StolenGoodsRecord(transactionId, thief, owner, saved, currency, stolenAt);
    }

    /** True when this record carries an item rather than currency. Touches no registry. */
    public boolean hasStack() {
        return stackTag != null && !stackTag.isEmpty();
    }

    /**
     * The stolen item, decoded from its saved form. Server-side only — {@code ItemStack.of} needs the
     * item registry — and never called by the persistence path.
     */
    public ItemStack stack() {
        return hasStack() ? ItemStack.of(stackTag) : ItemStack.EMPTY;
    }

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
        return tag;
    }

    /** Throws on a tag with no transaction/thief/owner id; the caller skips that one entry. */
    public static StolenGoodsRecord load(CompoundTag tag) {
        return new StolenGoodsRecord(
                tag.getUUID("transactionId"),
                tag.getUUID("thief"),
                tag.getUUID("owner"),
                tag.contains("stack") ? tag.getCompound("stack") : null,
                tag.getLong("currency"),
                tag.getLong("stolenAt"));
    }
}
