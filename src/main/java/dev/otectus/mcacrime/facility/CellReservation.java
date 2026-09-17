package dev.otectus.mcacrime.facility;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One cell slot held for one prisoner, with the token that is the only way to spend or give it back.
 *
 * <h2>Why a token rather than a flag</h2>
 *
 * <p>Two arrests can reach the same cell in the same tick. With a boolean "occupied" flag, both read
 * "free", both write "occupied", and two prisoners walk to one cell — the second of whom arrives to
 * find the sentence already running for somebody else. The reservation is therefore an object that
 * either exists or does not, created under the capacity check, and the token is proof of which
 * creation succeeded. {@code consume} and {@code release} both demand it, so a caller that never got
 * one cannot accidentally free a slot it never held.
 *
 * <p>The lease matters as much as the token. An escort that never arrives — the prisoner logged out, the
 * guard died, the chunk unloaded — must not hold a village's only cell for the rest of the save, so
 * every reservation expires and the sweep collects it. Expiry is a silent, ordinary outcome.
 *
 * @param token      the capability handed to whoever won the slot
 * @param facilityId the {@link FacilityAssignment} this slot belongs to
 * @param slot       which slot of that facility's capacity, so two reservations cannot be the same one
 * @param prisoner   who it is being held for
 * @param reservedAt the game time it was taken
 * @param expiresAt  the game time it lapses at, after which the slot is free again
 */
public record CellReservation(UUID token, UUID facilityId, int slot, UUID prisoner,
                              long reservedAt, long expiresAt) {

    /** How long a reservation is held for by default: long enough for a walk across a village. */
    public static final long DEFAULT_LEASE_TICKS = 6000L;

    public CellReservation {
        if (token == null || facilityId == null || prisoner == null) {
            throw new IllegalArgumentException("a cell reservation needs a token, a facility and a prisoner");
        }
        slot = Math.max(0, slot);
    }

    /** Whether the reservation is still in force at {@code now}. */
    public boolean live(long now) {
        return expiresAt > now;
    }

    /** The same reservation with a later deadline; the token and the slot are untouched. */
    public CellReservation renewedUntil(long expiry) {
        return expiry <= expiresAt ? this
                : new CellReservation(token, facilityId, slot, prisoner, reservedAt, expiry);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("token", token);
        tag.putUUID("facility", facilityId);
        tag.putInt("slot", slot);
        tag.putUUID("prisoner", prisoner);
        tag.putLong("at", reservedAt);
        tag.putLong("until", expiresAt);
        return tag;
    }

    /** Loads a reservation, or null when any of the three identities is missing. */
    @Nullable
    public static CellReservation load(CompoundTag tag) {
        if (tag == null || !tag.hasUUID("token") || !tag.hasUUID("facility") || !tag.hasUUID("prisoner")) {
            return null;
        }
        return new CellReservation(tag.getUUID("token"), tag.getUUID("facility"), tag.getInt("slot"),
                tag.getUUID("prisoner"), tag.getLong("at"), tag.getLong("until"));
    }

    public String describe() {
        return "slot " + slot + " of facility " + facilityId.toString().substring(0, 8)
                + " for " + prisoner + " until " + expiresAt;
    }
}
