package dev.otectus.mcacrime.property;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The durable record of one property loss: what left, whose it was at the time, who took it, and
 * whether it ever came back.
 *
 * <h2>Why a receipt rather than a flag on the crime record</h2>
 *
 * <p>Three things in §10 need the same fact and need it to survive a restart. Restitution has to know
 * exactly which lot was taken so returning it can resolve that loss and only that loss (§10.6).
 * Reconciliation has to be able to say "this outcome is uncertain" and keep saying it, rather than
 * repeating a debit or granting a reward twice after a crash (§10.2). And history has to stay correct
 * when the container changes hands afterwards (§10.3) — so the owner is copied in at the moment of
 * loss rather than read back off a policy that may since have been rewritten.
 *
 * <p>The transfer id is the identity. One committed transfer produces exactly one receipt however many
 * times the detector re-observes it, which is what makes replay after a reconnect safe.
 *
 * @param transferId     the committed transfer this receipt is for; the idempotency key
 * @param groupingKey    the incident this transfer was grouped into
 * @param policyId       the policy that covered the container
 * @param policyRevision the revision those terms were at when the loss happened
 * @param actor          who took it
 * @param actorKind      player or villager
 * @param ownerKindAtLoss who owned it at the moment of loss; never re-read afterwards
 * @param ownerAtLoss    the owner's UUID at the moment of loss, where the kind has one
 * @param dimension      the level
 * @param container      the container block
 * @param items          a bounded item summary, for an operator to read
 * @param fingerprint    the item identity the count is of, which is what a return has to match
 * @param count          how many items left
 * @param gameTime       when
 * @param outcome        lost, returned, or unresolved
 * @param incidentId     the crime record this loss was charged as, when it was charged at all
 * @param resolvedAt     the game time the outcome last changed
 */
public record PropertyReceipt(UUID transferId, String groupingKey, UUID policyId, int policyRevision,
                              UUID actor, PropertyActor.Kind actorKind, PropertyOwnerKind ownerKindAtLoss,
                              @Nullable UUID ownerAtLoss, ResourceLocation dimension, BlockPos container,
                              String items, String fingerprint, int count, long gameTime, Outcome outcome,
                              @Nullable UUID incidentId, long resolvedAt) {

    /** How long an item summary may be before it is truncated. Bounded metadata, per §10.2. */
    public static final int MAX_ITEMS_LENGTH = 120;

    /** Where a property loss stands. */
    public enum Outcome {
        /** Taken and not returned. */
        LOST("lost"),
        /** Returned to the same container, or answered by a paid fine (§10.6). */
        RESTORED("restored"),
        /**
         * Something happened and MCA: Crime does not know what.
         *
         * <p>The state §10.2 demands exist: a crash or a disconnect mid-transfer leaves a receipt that
         * says so, instead of a debit repeated on the next load or a restitution granted twice.
         */
        UNRESOLVED("unresolved");

        private final String id;

        Outcome(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Optional<Outcome> parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            String needle = raw.trim().toLowerCase(Locale.ROOT);
            return Arrays.stream(values())
                    .filter(outcome -> outcome.id.equals(needle)
                            || outcome.name().toLowerCase(Locale.ROOT).equals(needle))
                    .findFirst();
        }
    }

    public PropertyReceipt {
        if (transferId == null || policyId == null || actor == null || dimension == null
                || container == null) {
            throw new IllegalArgumentException("a property receipt must name a transfer, policy, actor "
                    + "and place");
        }
        if (actorKind == null) {
            actorKind = PropertyActor.Kind.UNKNOWN;
        }
        if (ownerKindAtLoss == null) {
            ownerKindAtLoss = PropertyOwnerKind.VILLAGE;
        }
        if (outcome == null) {
            outcome = Outcome.UNRESOLVED;
        }
        groupingKey = groupingKey == null || groupingKey.isBlank() ? transferId.toString() : groupingKey;
        items = items == null ? "" : items.length() > MAX_ITEMS_LENGTH
                ? items.substring(0, MAX_ITEMS_LENGTH) : items;
        // The fingerprint is what a returned lot is matched against, so an empty one can never match
        // and a receipt written without it simply cannot be settled by handing the goods back.
        fingerprint = fingerprint == null ? "" : fingerprint.length() > MAX_ITEMS_LENGTH
                ? fingerprint.substring(0, MAX_ITEMS_LENGTH) : fingerprint;
        count = Math.max(0, count);
        policyRevision = Math.max(0, policyRevision);
        container = container.immutable();
    }

    /** The receipt for a loss that has just been committed. */
    public static PropertyReceipt lost(TransferAttribution attribution, PropertyPolicy policy,
                                       @Nullable UUID incidentId) {
        TransferAttribution.CommittedTransfer transfer = attribution.transfer();
        return new PropertyReceipt(attribution.transferId(), attribution.groupingKey(), policy.id(),
                policy.revision(), transfer.actor(), transfer.actorKind(), policy.ownerKind(),
                policy.ownerId(), transfer.dimension(), transfer.container(), transfer.items(),
                transfer.fingerprint(), transfer.count(), transfer.gameTime(), Outcome.LOST, incidentId,
                transfer.gameTime());
    }

    /**
     * The same receipt marked returned, or this one unchanged when it already was.
     *
     * <p>Idempotent on purpose and §10.6's "prevent repeated turn-ins against the same lot" in one
     * line: returning the bread twice settles one loss, not two, and paying a fine after returning the
     * goods does not settle it a second time.
     */
    public PropertyReceipt restored(long now) {
        return outcome == Outcome.RESTORED ? this
                : new PropertyReceipt(transferId, groupingKey, policyId, policyRevision, actor, actorKind,
                        ownerKindAtLoss, ownerAtLoss, dimension, container, items, fingerprint, count,
                        gameTime, Outcome.RESTORED, incidentId, now);
    }

    /** The same receipt marked uncertain. Never overwrites a resolved one. */
    public PropertyReceipt unresolved(long now) {
        return outcome != Outcome.LOST ? this
                : new PropertyReceipt(transferId, groupingKey, policyId, policyRevision, actor, actorKind,
                        ownerKindAtLoss, ownerAtLoss, dimension, container, items, fingerprint, count,
                        gameTime, Outcome.UNRESOLVED, incidentId, now);
    }

    /** The same receipt bound to a crime record, once the incident is known. */
    public PropertyReceipt withIncident(@Nullable UUID incident) {
        return incident == null || incident.equals(incidentId) ? this
                : new PropertyReceipt(transferId, groupingKey, policyId, policyRevision, actor, actorKind,
                        ownerKindAtLoss, ownerAtLoss, dimension, container, items, fingerprint, count,
                        gameTime, outcome, incident, resolvedAt);
    }

    /** Whether this loss is still outstanding. */
    public boolean outstanding() {
        return outcome != Outcome.RESTORED;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("transfer", transferId);
        tag.putString("group", groupingKey);
        tag.putUUID("policy", policyId);
        tag.putInt("policyRev", policyRevision);
        tag.putUUID("actor", actor);
        tag.putString("actorKind", actorKind.name().toLowerCase(Locale.ROOT));
        tag.putString("ownerKind", ownerKindAtLoss.id());
        if (ownerAtLoss != null) {
            tag.putUUID("owner", ownerAtLoss);
        }
        tag.putString("dim", dimension.toString());
        tag.putLong("pos", container.asLong());
        tag.putString("items", items);
        tag.putString("fp", fingerprint);
        tag.putInt("count", count);
        tag.putLong("at", gameTime);
        tag.putString("outcome", outcome.id());
        if (incidentId != null) {
            tag.putUUID("incident", incidentId);
        }
        tag.putLong("resolvedAt", resolvedAt);
        return tag;
    }

    /** Loads a receipt, or null when it is unreadable; the caller quarantines the row. */
    @Nullable
    public static PropertyReceipt load(CompoundTag tag) {
        if (tag == null || !tag.hasUUID("transfer") || !tag.hasUUID("policy") || !tag.hasUUID("actor")) {
            return null;
        }
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dim"));
        Outcome outcome = Outcome.parse(tag.getString("outcome")).orElse(null);
        PropertyOwnerKind ownerKind = PropertyOwnerKind.parse(tag.getString("ownerKind")).orElse(null);
        if (dimension == null || outcome == null || ownerKind == null) {
            return null;
        }
        PropertyActor.Kind actorKind;
        try {
            actorKind = PropertyActor.Kind.valueOf(tag.getString("actorKind").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            actorKind = PropertyActor.Kind.UNKNOWN;
        }
        return new PropertyReceipt(tag.getUUID("transfer"), tag.getString("group"), tag.getUUID("policy"),
                tag.getInt("policyRev"), tag.getUUID("actor"), actorKind, ownerKind,
                tag.hasUUID("owner") ? tag.getUUID("owner") : null, dimension,
                BlockPos.of(tag.getLong("pos")), tag.getString("items"), tag.getString("fp"),
                tag.getInt("count"), tag.getLong("at"), outcome,
                tag.hasUUID("incident") ? tag.getUUID("incident") : null, tag.getLong("resolvedAt"));
    }

    /** One line for {@code /crime property inspect}. */
    public String describe() {
        return transferId.toString().substring(0, 8) + " " + outcome.id() + " " + count + "x " + items
                + " from " + container.getX() + "," + container.getY() + "," + container.getZ()
                + " by " + actor + " at tick " + gameTime;
    }
}
