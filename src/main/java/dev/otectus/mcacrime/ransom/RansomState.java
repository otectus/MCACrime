package dev.otectus.mcacrime.ransom;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One ransom demand (spec §8.5): who is held, by whom, who pays (null for the village fallback), the tier,
 * the amount, and the lifecycle status. Pure data + NBT so it round-trips in unit tests; {@link #load} of a
 * partial tag never throws.
 */
public final class RansomState {

    private UUID demandId;
    private UUID victim;
    private UUID captor;
    @Nullable
    private UUID payer;
    private PayerTier tier = PayerTier.VILLAGE_AUTHORITY;
    private long amount;
    private long openedAtGameTime;
    private long expiresAtGameTime;
    private RansomStatus status = RansomStatus.OPEN;

    public RansomState() {
    }

    public RansomState(UUID demandId, UUID victim, UUID captor, @Nullable UUID payer, PayerTier tier,
                       long amount, long openedAtGameTime, long expiresAtGameTime) {
        this.demandId = demandId;
        this.victim = victim;
        this.captor = captor;
        this.payer = payer;
        this.tier = tier;
        this.amount = amount;
        this.openedAtGameTime = openedAtGameTime;
        this.expiresAtGameTime = expiresAtGameTime;
    }

    public UUID getDemandId() {
        return demandId;
    }

    public UUID getVictim() {
        return victim;
    }

    public UUID getCaptor() {
        return captor;
    }

    @Nullable
    public UUID getPayer() {
        return payer;
    }

    public PayerTier getTier() {
        return tier;
    }

    public long getAmount() {
        return amount;
    }

    public long getExpiresAtGameTime() {
        return expiresAtGameTime;
    }

    public RansomStatus getStatus() {
        return status;
    }

    public void setStatus(RansomStatus status) {
        this.status = status;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (demandId != null) {
            tag.putUUID("id", demandId);
        }
        if (victim != null) {
            tag.putUUID("victim", victim);
        }
        if (captor != null) {
            tag.putUUID("captor", captor);
        }
        if (payer != null) {
            tag.putUUID("payer", payer);
        }
        tag.putString("tier", tier.name());
        tag.putLong("amount", amount);
        tag.putLong("opened", openedAtGameTime);
        tag.putLong("expires", expiresAtGameTime);
        tag.putString("status", status.name());
        return tag;
    }

    public static RansomState load(CompoundTag tag) {
        RansomState s = new RansomState();
        s.demandId = tag.hasUUID("id") ? tag.getUUID("id") : null;
        s.victim = tag.hasUUID("victim") ? tag.getUUID("victim") : null;
        s.captor = tag.hasUUID("captor") ? tag.getUUID("captor") : null;
        s.payer = tag.hasUUID("payer") ? tag.getUUID("payer") : null;
        s.tier = PayerTier.parse(tag.getString("tier"));
        s.amount = tag.getLong("amount");
        s.openedAtGameTime = tag.getLong("opened");
        s.expiresAtGameTime = tag.getLong("expires");
        s.status = RansomStatus.parse(tag.getString("status"));
        return s;
    }
}
