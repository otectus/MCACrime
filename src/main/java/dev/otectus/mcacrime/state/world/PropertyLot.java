package dev.otectus.mcacrime.state.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Property this mod owes somebody and could not hand over yet (0.6.0).
 *
 * <p>Recovery used to be a single act: take the record out of the ledger and give the item to the
 * owner in the same breath. That reads as atomic and is not — the owner may be offline, in another
 * dimension, or simply out of inventory slots — and the ledger row was gone either way, so the
 * property it described stopped existing. A lot is what the row becomes instead: a durable statement
 * that this player is owed this exact stack, kept until somebody actually takes delivery of it.
 *
 * <p>Exactly one of {@code stackTag} and {@code currency} is meaningful, the same rule
 * {@link StolenGoodsRecord} follows and for the same reason. The item is held as its saved
 * {@link CompoundTag} so the round-trip through the save file is lossless and so the record can be
 * asserted on without an item registry.
 *
 * <p>{@code sourceRecordId} names where the lot came from — a theft's transaction id — so an
 * investigation can still connect the delivery to the crime after the stolen-goods row is gone.
 */
public record PropertyLot(UUID lotId, UUID owner, @Nullable CompoundTag stackTag, long currency,
                          String providerId, @Nullable UUID sourceRecordId, DeliveryState state,
                          long createdAt) {

    /** How far a lot has got towards its owner. Persisted by name; ordinals are never written. */
    public enum DeliveryState {
        /** Nothing has been handed over yet. */
        PENDING,
        /** Some of it arrived and the remainder is still held here. */
        PARTIAL,
        /** All of it arrived. A delivered lot is removed rather than kept. */
        DELIVERED
    }

    public PropertyLot {
        // An NBT compound is mutable, and a lot everybody can edit in place is not a promise.
        stackTag = stackTag == null ? null : stackTag.copy();
        state = state == null ? DeliveryState.PENDING : state;
        providerId = providerId == null ? "" : providerId;
        currency = Math.max(0L, currency);
    }

    /** A lot holding one item stack. */
    public static PropertyLot ofStack(UUID lotId, UUID owner, @Nullable ItemStack stack,
                                      @Nullable UUID sourceRecordId, long createdAt) {
        CompoundTag saved = stack == null || stack.isEmpty() ? null : stack.save(new CompoundTag());
        return new PropertyLot(lotId, owner, saved, 0L, "", sourceRecordId, DeliveryState.PENDING, createdAt);
    }

    /** A lot holding an amount of the named currency. */
    public static PropertyLot ofCurrency(UUID lotId, UUID owner, long amount, String providerId,
                                         @Nullable UUID sourceRecordId, long createdAt) {
        return new PropertyLot(lotId, owner, null, amount, providerId, sourceRecordId,
                DeliveryState.PENDING, createdAt);
    }

    /** True when this lot carries an item rather than currency. Touches no registry. */
    public boolean hasStack() {
        return stackTag != null && !stackTag.isEmpty();
    }

    /** True when there is nothing left to hand over. */
    public boolean empty() {
        return !hasStack() && currency <= 0L;
    }

    /**
     * The owed item, decoded from its saved form. Server-side only — {@code ItemStack.of} needs the
     * item registry — and never called by the persistence path.
     */
    public ItemStack stack() {
        return hasStack() ? ItemStack.of(stackTag) : ItemStack.EMPTY;
    }

    /** The same lot holding only what is still owed after a partial handover. */
    public PropertyLot remaining(@Nullable CompoundTag stillOwedStack, long stillOwedCurrency) {
        boolean anything = (stillOwedStack != null && !stillOwedStack.isEmpty()) || stillOwedCurrency > 0L;
        return new PropertyLot(lotId, owner, stillOwedStack, stillOwedCurrency, providerId, sourceRecordId,
                anything ? DeliveryState.PARTIAL : DeliveryState.DELIVERED, createdAt);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("lotId", lotId);
        tag.putUUID("owner", owner);
        if (hasStack()) {
            tag.put("stack", stackTag.copy());
        }
        tag.putLong("currency", currency);
        tag.putString("provider", providerId);
        if (sourceRecordId != null) {
            tag.putUUID("source", sourceRecordId);
        }
        tag.putString("state", state.name());
        tag.putLong("created", createdAt);
        return tag;
    }

    /** Throws when the row names no lot or no owner; the caller quarantines that one entry. */
    public static PropertyLot load(CompoundTag tag) {
        if (tag == null || !tag.hasUUID("lotId") || !tag.hasUUID("owner")) {
            throw new IllegalArgumentException("property lot has no id or no owner");
        }
        return new PropertyLot(tag.getUUID("lotId"), tag.getUUID("owner"),
                tag.contains("stack") ? tag.getCompound("stack") : null,
                tag.getLong("currency"), tag.getString("provider"),
                tag.hasUUID("source") ? tag.getUUID("source") : null,
                parseState(tag.getString("state")), tag.getLong("created"));
    }

    /** An unknown name reads as {@link DeliveryState#PENDING}: still owed is the safe answer. */
    private static DeliveryState parseState(String name) {
        for (DeliveryState state : DeliveryState.values()) {
            if (state.name().equals(name)) {
                return state;
            }
        }
        return DeliveryState.PENDING;
    }
}
