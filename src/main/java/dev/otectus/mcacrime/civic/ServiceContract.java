package dev.otectus.mcacrime.civic;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import net.minecraft.nbt.CompoundTag;

import org.jetbrains.annotations.Nullable;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * One case, one offender, one piece of work, and where it has got to (reference §12.1).
 *
 * <h2>Why it is durable and why it is bound to a case</h2>
 *
 * <p>§12.1 point 2 asks for "a server-issued contract naming exact case IDs and obligations", and
 * point 5 that completion "resolves only the agreed fine/restitution component". Both require the
 * contract to outlive a reconnect: a player who logs out halfway through must come back to the same
 * obligation rather than a regenerated one, which is also the anti-farming rule in point 7 — "task
 * regeneration after reconnect" is listed there as a thing to prevent, not a feature.
 *
 * <p>The case id is the identity of what is being settled. It is carried rather than re-derived
 * because the alternative — "the offender's oldest open case at the moment of completion" — would
 * settle whatever they happened to have done since, which is the same mistake sentence membership was
 * introduced to fix in {@code SentenceResolutionService}.
 *
 * <h2>What it deliberately does not carry</h2>
 *
 * <p>No price. The fine is re-quoted at completion through {@code SettlementPolicy}, so a contract
 * cannot be a way to lock in yesterday's price, and a case that has moved under the contract refuses
 * the settlement instead of settling it at a stale figure.
 *
 * @param contractId    the contract's own identity; also the idempotency key of its civic effect
 * @param caseId        the single case this settles
 * @param offender      who owes the work
 * @param offenderIsPlayer whether the offender is a player; an NPC contract takes an activity claim
 * @param community     the settlement the work is owed to
 * @param task          what the work is
 * @param requiredUnits how many accepted outputs finish it
 * @param completedUnits how many have been credited; never above {@code requiredUnits}
 * @param deadline      the game time the contract lapses at
 * @param facilityId    the civic facility the work is attached to, when one was named
 * @param state         where it has got to
 * @param issuedAt      when it was offered
 * @param resolvedAt    when it last changed state
 */
public record ServiceContract(UUID contractId, UUID caseId, UUID offender, boolean offenderIsPlayer,
                              CrimeCommunityKey community, CivicTask task, int requiredUnits,
                              int completedUnits, long deadline, @Nullable UUID facilityId,
                              State state, long issuedAt, long resolvedAt) {

    /** The most work one contract may ask for. A ceiling on the obligation, not on the ledger. */
    public static final int MAX_UNITS = CivicTask.DEFAULT_MAX_UNITS;

    /** How long an offer stands before it lapses unaccepted: one in-game day. */
    public static final long OFFER_VALIDITY_TICKS = 24_000L;

    /** Where a contract has got to. */
    public enum State {
        /** Issued and waiting for the offender to take it. */
        OFFERED("offered"),
        /** Taken. Progress counts from here and nowhere else. */
        ACTIVE("active"),
        /** Finished, and the case it named has been settled exactly once. */
        COMPLETED("completed"),
        /** The deadline passed with the work unfinished. The original sentence stands. */
        FAILED("failed"),
        /** Withdrawn by an operator, or by the offender. The original sentence stands. */
        CANCELLED("cancelled");

        private final String id;

        State(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** Whether the contract can still change. The three terminal states never do. */
        public boolean open() {
            return this == OFFERED || this == ACTIVE;
        }

        /** Whether the offender still owes the original fine or sentence in full. */
        public boolean leavesSentenceStanding() {
            return this == FAILED || this == CANCELLED;
        }

        public static Optional<State> parse(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            String needle = raw.trim().toLowerCase(Locale.ROOT);
            return Arrays.stream(values())
                    .filter(state -> state.id.equals(needle)
                            || state.name().toLowerCase(Locale.ROOT).equals(needle))
                    .findFirst();
        }
    }

    public ServiceContract {
        if (contractId == null || caseId == null || offender == null || community == null
                || task == null) {
            throw new IllegalArgumentException("a service contract must name a contract, a case, an "
                    + "offender, a settlement and a task");
        }
        state = state == null ? State.OFFERED : state;
        requiredUnits = Math.max(1, Math.min(MAX_UNITS, requiredUnits));
        completedUnits = Math.max(0, Math.min(requiredUnits, completedUnits));
        issuedAt = Math.max(0L, issuedAt);
        resolvedAt = Math.max(issuedAt, resolvedAt);
    }

    /** A fresh offer. */
    public static ServiceContract offered(UUID contractId, UUID caseId, UUID offender,
                                          boolean offenderIsPlayer, CrimeCommunityKey community,
                                          CivicTask task, int requiredUnits, long now,
                                          long deadline, @Nullable UUID facilityId) {
        return new ServiceContract(contractId, caseId, offender, offenderIsPlayer, community, task,
                requiredUnits, 0, deadline, facilityId, State.OFFERED, now, now);
    }

    /** Whether anything can still happen to this contract. */
    public boolean open() {
        return state.open();
    }

    /** Whether work credited right now would count. */
    public boolean active() {
        return state == State.ACTIVE;
    }

    /** Whether the work is all in. True only once every unit has been credited. */
    public boolean satisfied() {
        return completedUnits >= requiredUnits;
    }

    /** How much is still owed. */
    public int remainingUnits() {
        return Math.max(0, requiredUnits - completedUnits);
    }

    /** Whether the deadline has passed. A still-open contract past it is failed by the sweep. */
    public boolean expired(long now) {
        return now >= deadline;
    }

    /**
     * The contract taken up.
     *
     * <p>Only from {@link State#OFFERED}. Re-accepting an active contract returns it unchanged rather
     * than restarting its progress, which is the reconnect case: a client that re-sends the
     * acceptance must not zero the work already done.
     */
    public ServiceContract accepted(long now) {
        return state == State.OFFERED ? withState(State.ACTIVE, completedUnits, now) : this;
    }

    /**
     * The contract with {@code units} more work credited.
     *
     * <p>Only while {@link State#ACTIVE}, and the total is clamped, so a burst of signals cannot
     * over-credit. Completion is a separate step: this records the work, and
     * {@link CivicWorkService} decides what a satisfied contract does to the case, exactly once.
     */
    public ServiceContract progressed(int units, long now) {
        if (state != State.ACTIVE || units <= 0 || satisfied()) {
            return this;
        }
        int credited = Math.min(requiredUnits, completedUnits + units);
        return credited == completedUnits ? this : withState(State.ACTIVE, credited, now);
    }

    /**
     * The contract marked finished.
     *
     * <p>Refused unless it is active <em>and</em> satisfied, which is what makes "reduces the sentence
     * exactly once" a property of the record rather than a discipline at the call site: a second
     * completion call finds {@link State#COMPLETED}, which is not active, and changes nothing.
     */
    public ServiceContract completed(long now) {
        return state == State.ACTIVE && satisfied()
                ? withState(State.COMPLETED, completedUnits, now) : this;
    }

    /** The contract marked failed. Only from an open state; the work already done is kept as history. */
    public ServiceContract failed(long now) {
        return state.open() ? withState(State.FAILED, completedUnits, now) : this;
    }

    /** The contract withdrawn. Only from an open state. */
    public ServiceContract cancelled(long now) {
        return state.open() ? withState(State.CANCELLED, completedUnits, now) : this;
    }

    private ServiceContract withState(State next, int units, long now) {
        return new ServiceContract(contractId, caseId, offender, offenderIsPlayer, community, task,
                requiredUnits, units, deadline, facilityId, next, issuedAt, Math.max(issuedAt, now));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", contractId);
        tag.putUUID("case", caseId);
        tag.putUUID("offender", offender);
        tag.putBoolean("player", offenderIsPlayer);
        tag.put("community", community.save());
        tag.putString("task", task.id());
        tag.putInt("required", requiredUnits);
        tag.putInt("done", completedUnits);
        tag.putLong("deadline", deadline);
        if (facilityId != null) {
            tag.putUUID("facility", facilityId);
        }
        tag.putString("state", state.id());
        tag.putLong("issuedAt", issuedAt);
        tag.putLong("resolvedAt", resolvedAt);
        return tag;
    }

    /** Loads a contract, or null when it is unreadable; the caller quarantines the row. */
    @Nullable
    public static ServiceContract load(@Nullable CompoundTag tag) {
        if (tag == null || !tag.hasUUID("id") || !tag.hasUUID("case") || !tag.hasUUID("offender")) {
            return null;
        }
        CrimeCommunityKey community = CrimeCommunityKey.load(tag.getCompound("community")).orElse(null);
        CivicTask task = CivicTask.parse(tag.getString("task")).orElse(null);
        State state = State.parse(tag.getString("state")).orElse(null);
        if (community == null || task == null || state == null) {
            return null;
        }
        return new ServiceContract(tag.getUUID("id"), tag.getUUID("case"), tag.getUUID("offender"),
                tag.getBoolean("player"), community, task, tag.getInt("required"), tag.getInt("done"),
                tag.getLong("deadline"), tag.hasUUID("facility") ? tag.getUUID("facility") : null,
                state, tag.getLong("issuedAt"), tag.getLong("resolvedAt"));
    }

    /** One line for {@code /crime service list}. */
    public String describe() {
        return contractId.toString().substring(0, 8) + " " + state.id() + " " + task.id()
                + " " + completedUnits + "/" + requiredUnits
                + " for " + offender + " in " + community.asString()
                + " case " + caseId.toString().substring(0, 8)
                + (state.open() ? " by tick " + deadline : "");
    }
}
