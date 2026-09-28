package dev.otectus.mcacrime.report;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.UUID;

/** Durable acceptance receipt. Case and custody state remain authoritative for later outcomes. */
public record PlayerReportReceipt(UUID reportId, UUID reporterId, UUID evidenceId, UUID caseId,
                                  @Nullable UUID suspectId, UUID responderId,
                                  @Nullable CrimeCommunityKey receivingAuthority,
                                  long acceptedAt, Dispatch dispatch, long nextDispatchAt) {
    public enum Dispatch { RECORDED, AWAITING_GUARD, SEARCHING }

    public PlayerReportReceipt {
        if (reportId == null || reporterId == null || evidenceId == null || caseId == null
                || responderId == null) throw new IllegalArgumentException("report receipt identity is required");
        dispatch = dispatch == null ? Dispatch.RECORDED : dispatch;
        nextDispatchAt = Math.max(0L, nextDispatchAt);
    }

    public PlayerReportReceipt withDispatch(Dispatch next, long nextAttemptAt) {
        long scheduled = Math.max(0L, nextAttemptAt);
        return next == dispatch && scheduled == nextDispatchAt ? this
                : new PlayerReportReceipt(reportId, reporterId, evidenceId, caseId,
                suspectId, responderId, receivingAuthority, acceptedAt, next, scheduled);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("report", reportId);
        tag.putUUID("reporter", reporterId);
        tag.putUUID("evidence", evidenceId);
        tag.putUUID("case", caseId);
        if (suspectId != null) tag.putUUID("suspect", suspectId);
        tag.putUUID("responder", responderId);
        if (receivingAuthority != null) tag.put("authority", receivingAuthority.save());
        tag.putLong("acceptedAt", acceptedAt);
        tag.putString("dispatch", dispatch.name());
        tag.putLong("nextDispatchAt", nextDispatchAt);
        return tag;
    }

    public static PlayerReportReceipt load(CompoundTag tag) {
        if (!tag.hasUUID("report") || !tag.hasUUID("reporter") || !tag.hasUUID("evidence")
                || !tag.hasUUID("case") || !tag.hasUUID("responder")) {
            throw new IllegalArgumentException("malformed player report receipt");
        }
        Dispatch dispatch;
        try { dispatch = Dispatch.valueOf(tag.getString("dispatch")); }
        catch (IllegalArgumentException ignored) { dispatch = Dispatch.RECORDED; }
        CrimeCommunityKey authority = tag.contains("authority", Tag.TAG_COMPOUND)
                ? CrimeCommunityKey.load(tag.getCompound("authority")).orElse(null) : null;
        return new PlayerReportReceipt(tag.getUUID("report"), tag.getUUID("reporter"),
                tag.getUUID("evidence"), tag.getUUID("case"),
                tag.hasUUID("suspect") ? tag.getUUID("suspect") : null,
                tag.getUUID("responder"), authority, tag.getLong("acceptedAt"), dispatch,
                tag.contains("nextDispatchAt", Tag.TAG_LONG) ? tag.getLong("nextDispatchAt")
                        : tag.getLong("acceptedAt"));
    }
}
