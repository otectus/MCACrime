package dev.otectus.mcacrime.economy.account;

import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

/** Append-only operator decision, including the receipt and property payload before correction. */
public record ReconciliationDecision(UUID id, UUID receiptId, String operator, String action, String note,
        CompoundTag receiptBefore, CompoundTag propertyBefore, long time) {
    public ReconciliationDecision {
        if (id == null || receiptId == null || operator == null || operator.isBlank() || operator.length() > 128
                || action == null || action.length() > 32 || note == null || note.isBlank() || note.length() > 256
                || receiptBefore == null) throw new IllegalArgumentException("Invalid reconciliation decision");
        receiptBefore = receiptBefore.copy();
        propertyBefore = propertyBefore == null ? new CompoundTag() : propertyBefore.copy();
    }
    @Override public CompoundTag receiptBefore() { return receiptBefore.copy(); }
    @Override public CompoundTag propertyBefore() { return propertyBefore.copy(); }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id); tag.putUUID("receiptId", receiptId);
        tag.putString("operator", operator); tag.putString("action", action); tag.putString("note", note);
        tag.put("receiptBefore", receiptBefore()); tag.put("propertyBefore", propertyBefore()); tag.putLong("time", time);
        return tag;
    }
    public static ReconciliationDecision load(CompoundTag tag) {
        return new ReconciliationDecision(tag.getUUID("id"), tag.getUUID("receiptId"), tag.getString("operator"),
                tag.getString("action"), tag.getString("note"), tag.getCompound("receiptBefore"),
                tag.getCompound("propertyBefore"), tag.getLong("time"));
    }
}
