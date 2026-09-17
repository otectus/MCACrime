package dev.otectus.mcacrime.property;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * One committed container transfer, judged: one transfer id, one incident grouping key, and one
 * outcome that either charges somebody or explicitly does not.
 *
 * <h2>The rules, and where they come from</h2>
 *
 * <p>§10.3 asks for an incident request carrying the actor and their kind, the actual location and the
 * property's community, the owner where one is known, the property reference and its revision, the
 * item fingerprint and count, and a server-verified action source. All of that is here; the
 * jurisdiction half is handed to {@code IncidentContext}, which is what decides that a theft inside a
 * settlement building is that settlement's business rather than the offender's or the victim's home
 * village's.
 *
 * <p>Five outcomes and only one of them charges anybody:
 *
 * <ul>
 *   <li>{@code CHARGED} — an identified actor took from a container whose policy refused them.</li>
 *   <li>{@code PERMITTED} — the policy allowed it. A deposit, a public ration chest, a resident taking
 *       from their own village's stores.</li>
 *   <li>{@code AUTHORISED_WORK} — a live {@link WorkTransferContext} covers exactly this worker, this
 *       source and this moment (§10.4). A role label alone never does.</li>
 *   <li>{@code UNATTRIBUTED} — nobody could be identified, or nothing claims this container. §10.1's
 *       "an unknown or disputed owner does not authorise a theft charge", made structural.</li>
 *   <li>{@code UNSUPPORTED} — the menu or the source is not one a transfer can be proved through.
 *       §10.2 requires this to be reported as a gap rather than charged to the nearest player.</li>
 * </ul>
 *
 * <p>Everything here is a pure function of its inputs. The detector is the part that can be wrong
 * about what happened; this is the part that must not be wrong about what it means.
 */
public record TransferAttribution(UUID transferId, String groupingKey, Outcome outcome, String reason,
                                  CommittedTransfer transfer, @Nullable UUID policyId, int policyRevision,
                                  PropertyAccess.Decision decision) {

    /** How wide a window groups several transfers into one incident. Five seconds of continuous work. */
    public static final long GROUPING_WINDOW_TICKS = 100L;

    /** Which way the items went. */
    public enum Direction {
        /** Out of the container and into the actor. The only direction a theft can be. */
        OUT,
        /** Into the container. A deposit, a delivery, a return. */
        IN
    }

    public enum Outcome {
        CHARGED,
        PERMITTED,
        AUTHORISED_WORK,
        UNATTRIBUTED,
        UNSUPPORTED;

        /** Whether this outcome produces a crime record. Exactly one does. */
        public boolean charges() {
            return this == CHARGED;
        }

        /** Whether this outcome produces a durable receipt. */
        public boolean receipted() {
            return this == CHARGED;
        }
    }

    /**
     * What the detector observed and is prepared to stand behind.
     *
     * <p>The {@code sequence} is what keeps two transfers of the same item by the same actor in the
     * same tick apart — a shift-click that empties two stacks produces two of these, and without an
     * ordinal they would collapse into one transfer id and one receipt for half the goods.
     *
     * @param actor      who did it
     * @param actorKind  player or villager
     * @param dimension  the level
     * @param container  the container block
     * @param direction  which way the items went
     * @param items      a bounded, human-readable item summary
     * @param fingerprint the item identity the count is of
     * @param count      how many items moved
     * @param gameTime   the tick it was observed committed
     * @param sequence   which transfer of that tick this is
     */
    public record CommittedTransfer(UUID actor, PropertyActor.Kind actorKind, ResourceLocation dimension,
                                    BlockPos container, Direction direction, String items,
                                    String fingerprint, int count, long gameTime, int sequence) {

        public CommittedTransfer {
            if (dimension == null || container == null) {
                throw new IllegalArgumentException("a committed transfer must name a place");
            }
            if (direction == null) {
                direction = Direction.OUT;
            }
            if (actorKind == null) {
                actorKind = PropertyActor.Kind.UNKNOWN;
            }
            fingerprint = fingerprint == null ? "" : fingerprint;
            items = items == null ? fingerprint : items;
            count = Math.max(0, count);
            sequence = Math.max(0, sequence);
            container = container.immutable();
        }
    }

    /**
     * Judges one observed transfer.
     *
     * @param transfer   what the detector saw
     * @param policy     the policy covering the container, or null when nothing claims it
     * @param decision   what {@link PropertyAccess} said about this actor and this operation
     * @param work       a live work authorisation covering this exact withdrawal, or null
     * @param supported  whether the menu and source are ones a transfer can be proved through
     */
    public static TransferAttribution of(CommittedTransfer transfer, @Nullable PropertyPolicy policy,
                                         @Nullable PropertyAccess.Decision decision,
                                         @Nullable WorkTransferContext work, boolean supported) {
        UUID id = transferId(transfer);
        String group = groupingKey(transfer, policy);
        PropertyAccess.Decision verdict = decision == null
                ? PropertyAccess.Decision.unknown("no access decision was made") : decision;

        if (!supported) {
            // Reported, never charged. An unsupported menu is a gap in this mod, not evidence about a
            // player, and §10.2 says so in as many words.
            return new TransferAttribution(id, group, Outcome.UNSUPPORTED,
                    "the source is not a container MCA: Crime can prove a transfer through", transfer,
                    policy == null ? null : policy.id(), policy == null ? 0 : policy.revision(), verdict);
        }
        if (transfer.count() <= 0) {
            return new TransferAttribution(id, group, Outcome.UNATTRIBUTED, "nothing was transferred",
                    transfer, policy == null ? null : policy.id(), policy == null ? 0 : policy.revision(),
                    verdict);
        }
        if (work != null) {
            // Checked before the access rule rather than after: authorised work is a different question
            // from whether a stranger may help themselves, and a task collecting its own inputs must not
            // depend on the container's rule happening to name workers.
            return new TransferAttribution(id, group, Outcome.AUTHORISED_WORK,
                    "an authorised settlement task (" + work.taskId() + ", " + work.purpose()
                            + ") covers this withdrawal", transfer,
                    policy == null ? null : policy.id(), policy == null ? 0 : policy.revision(), verdict);
        }
        if (transfer.direction() == Direction.IN) {
            return new TransferAttribution(id, group, Outcome.PERMITTED, "a deposit is not a taking",
                    transfer, policy == null ? null : policy.id(), policy == null ? 0 : policy.revision(),
                    verdict);
        }
        if (policy == null || verdict.unknown()) {
            return new TransferAttribution(id, group, Outcome.UNATTRIBUTED, verdict.reason(), transfer,
                    policy == null ? null : policy.id(), policy == null ? 0 : policy.revision(), verdict);
        }
        if (verdict.allowed()) {
            return new TransferAttribution(id, group, Outcome.PERMITTED, verdict.reason(), transfer,
                    policy.id(), policy.revision(), verdict);
        }
        return new TransferAttribution(id, group, Outcome.CHARGED, verdict.reason(), transfer,
                policy.id(), policy.revision(), verdict);
    }

    /**
     * The id one committed transfer always has.
     *
     * <p>Derived rather than random, because the same transfer can be judged twice — a reconnect, a
     * replayed receipt, a detector that observed the same tick from two directions — and §10.2 requires
     * reconciliation to prevent a duplicate debit rather than to hope one never happens. Two genuinely
     * different transfers differ in at least one of the six components below.
     */
    public static UUID transferId(CommittedTransfer transfer) {
        String seed = "mcacrime:transfer:" + transfer.actor() + ":" + transfer.dimension() + ":"
                + transfer.container().asLong() + ":" + transfer.direction() + ":"
                + transfer.fingerprint() + ":" + transfer.count() + ":" + transfer.gameTime() + ":"
                + transfer.sequence();
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The key that groups a continuous action into one incident.
     *
     * <p>§10.5: a continuous action is one bounded incident, not one charge per stack. Same actor, same
     * property, same five-second window — emptying a chest is a theft, not fourteen.
     */
    public static String groupingKey(CommittedTransfer transfer, @Nullable PropertyPolicy policy) {
        String property = policy != null ? policy.id().toString()
                : transfer.dimension() + "@" + transfer.container().asLong();
        return transfer.actor() + "/" + property + "/" + (transfer.gameTime() / GROUPING_WINDOW_TICKS);
    }

    /** Whether this attribution should produce a crime record. */
    public boolean charges() {
        return outcome.charges();
    }

    /** One line for a diagnostic. */
    public String describe() {
        return outcome.name().toLowerCase(java.util.Locale.ROOT) + " " + transfer.count() + "x "
                + transfer.items() + " (" + reason + ")";
    }
}
