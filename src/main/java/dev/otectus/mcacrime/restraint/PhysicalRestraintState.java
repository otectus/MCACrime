package dev.otectus.mcacrime.restraint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything physically on one subject, and what that subject is physically attached to.
 *
 * <p>One row per subject, for players and MCA villagers alike, living in
 * {@code state/world/CrimeWorldData}'s {@code physicalRestraints} table. Not a capability and not
 * entity persistent data: MCA villagers are not ours and may be unloaded or despawned by MCA, world
 * data survives that, and a capability on {@code Player} would be player-only and copied on death —
 * which would duplicate every restraint the player was wearing (§3.2).
 *
 * <p>{@code generation} distinguishes successive holds of the same subject: two captures of one
 * person must not accept each other's delayed packets. {@code revision} advances on every change and
 * is what makes the client sync ordered and a stale action refusable.
 *
 * <p>Immutable. Every mutator returns a new state with {@code revision + 1}, so a caller cannot
 * change what is on somebody without going through the table that guards writes with
 * {@code frozen()}.
 */
public record PhysicalRestraintState(
        UUID subject,
        boolean subjectIsPlayer,
        @Nullable ResourceLocation dimension,
        long generation,
        long revision,
        Map<RestraintSlot, AppliedRestraint> slots,
        @Nullable UUID tetherId,
        @Nullable UUID escortId,
        @Nullable UUID detentionId) {

    public PhysicalRestraintState {
        Map<RestraintSlot, AppliedRestraint> copy = new EnumMap<>(RestraintSlot.class);
        if (slots != null) {
            slots.forEach((slot, restraint) -> {
                if (slot != null && restraint != null) {
                    copy.put(slot, restraint);
                }
            });
        }
        slots = Map.copyOf(copy);
        generation = Math.max(1L, generation);
        revision = Math.max(0L, revision);
    }

    /** A subject with nothing on them and nothing attached, at generation 1. */
    public static PhysicalRestraintState empty(UUID subject, boolean subjectIsPlayer,
                                               @Nullable ResourceLocation dimension) {
        return new PhysicalRestraintState(subject, subjectIsPlayer, dimension, 1L, 0L, Map.of(),
                null, null, null);
    }

    public Optional<AppliedRestraint> slot(@Nullable RestraintSlot slot) {
        return slot == null ? Optional.empty() : Optional.ofNullable(slots.get(slot));
    }

    public boolean occupied(@Nullable RestraintSlot slot) {
        return slot != null && slots.containsKey(slot);
    }

    /** True when nothing is worn and nothing is attached: the row may be dropped. */
    public boolean vacant() {
        return slots.isEmpty() && tetherId == null && escortId == null && detentionId == null;
    }

    /** True when any slot carries gear. A tether alone is not a restraint. */
    public boolean restrained() {
        return !slots.isEmpty();
    }

    /**
     * This state with {@code restraint} in {@code slot}.
     *
     * <p>Replacing an occupied slot is the caller's decision, not this record's: the application
     * transaction refuses a second restraint on an occupied slot, and the removal path empties it
     * first. What this guarantees is only that the other two slots are untouched.
     */
    public PhysicalRestraintState with(RestraintSlot slot, AppliedRestraint restraint) {
        if (slot == null || restraint == null) {
            return this;
        }
        Map<RestraintSlot, AppliedRestraint> next = new EnumMap<>(RestraintSlot.class);
        next.putAll(slots);
        next.put(slot, restraint);
        return copyWith(next, tetherId, escortId, detentionId);
    }

    /** This state with {@code slot} emptied. Removing one restraint is not a release. */
    public PhysicalRestraintState without(@Nullable RestraintSlot slot) {
        if (slot == null || !slots.containsKey(slot)) {
            return this;
        }
        Map<RestraintSlot, AppliedRestraint> next = new EnumMap<>(RestraintSlot.class);
        next.putAll(slots);
        next.remove(slot);
        return copyWith(next, tetherId, escortId, detentionId);
    }

    public PhysicalRestraintState withTether(@Nullable UUID id) {
        return copyWith(slots, id, escortId, detentionId);
    }

    public PhysicalRestraintState withEscort(@Nullable UUID id) {
        return copyWith(slots, tetherId, id, detentionId);
    }

    public PhysicalRestraintState withDetention(@Nullable UUID id) {
        return copyWith(slots, tetherId, escortId, id);
    }

    public PhysicalRestraintState withDimension(@Nullable ResourceLocation newDimension) {
        return new PhysicalRestraintState(subject, subjectIsPlayer, newDimension, generation,
                revision + 1L, slots, tetherId, escortId, detentionId);
    }

    /**
     * The next generation of this subject's physical state, emptied.
     *
     * <p>What a new hold starts from. The generation bump is what makes every packet and session
     * bound to the previous hold refuse itself, without needing to hunt them down.
     */
    public PhysicalRestraintState nextGeneration() {
        return new PhysicalRestraintState(subject, subjectIsPlayer, dimension, generation + 1L,
                revision + 1L, Map.of(), null, null, null);
    }

    private PhysicalRestraintState copyWith(Map<RestraintSlot, AppliedRestraint> nextSlots,
                                            @Nullable UUID nextTether, @Nullable UUID nextEscort,
                                            @Nullable UUID nextDetention) {
        return new PhysicalRestraintState(subject, subjectIsPlayer, dimension, generation,
                revision + 1L, nextSlots, nextTether, nextEscort, nextDetention);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("subject", subject);
        tag.putBoolean("player", subjectIsPlayer);
        if (dimension != null) {
            tag.putString("dim", dimension.toString());
        }
        tag.putLong("generation", generation);
        tag.putLong("revision", revision);
        CompoundTag slotTag = new CompoundTag();
        slots.forEach((slot, restraint) -> slotTag.put(slot.id(), restraint.save()));
        tag.put("slots", slotTag);
        if (tetherId != null) tag.putUUID("tether", tetherId);
        if (escortId != null) tag.putUUID("escort", escortId);
        if (detentionId != null) tag.putUUID("detention", detentionId);
        return tag;
    }

    /** Reads one row, or empty when it names no subject. The caller quarantines an empty answer. */
    public static Optional<PhysicalRestraintState> load(@Nullable CompoundTag tag) {
        if (tag == null || !tag.hasUUID("subject")) {
            return Optional.empty();
        }
        Map<RestraintSlot, AppliedRestraint> slots = new LinkedHashMap<>();
        CompoundTag slotTag = tag.getCompound("slots");
        for (String key : slotTag.getAllKeys()) {
            Optional<RestraintSlot> slot = RestraintSlot.parse(key);
            if (slot.isEmpty()) {
                continue; // a slot this build does not have; the row itself is still readable
            }
            AppliedRestraint.load(slotTag.getCompound(key)).ifPresent(restraint -> slots.put(slot.get(), restraint));
        }
        return Optional.of(new PhysicalRestraintState(
                tag.getUUID("subject"),
                tag.getBoolean("player"),
                tag.contains("dim") ? ResourceLocation.tryParse(tag.getString("dim")) : null,
                tag.getLong("generation"),
                tag.getLong("revision"),
                slots,
                tag.hasUUID("tether") ? tag.getUUID("tether") : null,
                tag.hasUUID("escort") ? tag.getUUID("escort") : null,
                tag.hasUUID("detention") ? tag.getUUID("detention") : null));
    }
}
