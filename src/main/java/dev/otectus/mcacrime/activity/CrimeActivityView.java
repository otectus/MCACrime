package dev.otectus.mcacrime.activity;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;

/**
 * One villager, one thing MCA: Crime is doing to them, and the terms it is being done on.
 *
 * <p>Immutable and entity-free. A claim is a fact about a few seconds, so it names the villager by
 * {@link UUID} and never holds a reference to them: an entity that unloads mid-escort must be
 * collectable, and a claim that outlived the entity would otherwise pin it.
 *
 * @param entity     who is claimed
 * @param dimension  where they were when the claim was taken; a claim never follows a villager
 *                   across dimensions, because the system that took it cannot either
 * @param kind       what MCA: Crime is doing
 * @param owner      which subsystem took it, for diagnostics and for release symmetry
 * @param generation the monotonic stamp that makes release and restore safe; see
 *                   {@link CrimeActivityRegistry}
 * @param authority  how strong the claim is against a competing one
 * @param allowedOperations what other systems may still do to this villager; everything outside this
 *                   set must yield while the claim is live
 * @param expiresAt  the game time the claim lapses at, so a subsystem that dies mid-action cannot
 *                   strand a villager
 */
public record CrimeActivityView(UUID entity,
                                @Nullable ResourceLocation dimension,
                                Kind kind,
                                String owner,
                                long generation,
                                Authority authority,
                                Set<CrimeActivityOperation> allowedOperations,
                                long expiresAt) {

    /** What MCA: Crime is doing with the villager. */
    public enum Kind {
        /** A responder is acting as law and must not be steered by civilian fear. */
        HOLD(Authority.ROUTINE),
        /** A guard is speaking charges to a suspect and must stay put and facing them. */
        CHALLENGE(Authority.ROUTINE),
        /** A guard is walking a prisoner to a cell. */
        ESCORT(Authority.ENFORCEMENT),
        /** An arrest is being committed right now. */
        ARREST(Authority.ENFORCEMENT),
        /** The villager is being held — in a cell, on a lead, or by a kidnapper. */
        CUSTODY(Authority.CUSTODY),
        /** A guard is chasing an offender. */
        PURSUIT(Authority.ENFORCEMENT),
        /** MCA: Crime's own fear/compliance controller owns the villager's movement. */
        REACTION(Authority.ADVISORY),
        /** An autonomous thief is running a crime action. */
        THIEF_ACTION(Authority.ROUTINE),
        /** An NPC mugging session is open. */
        MUGGING(Authority.ROUTINE);

        private final Authority authority;

        Kind(Authority authority) {
            this.authority = authority;
        }

        /** The strength a claim of this kind is taken at unless a caller asks for another. */
        public Authority authority() {
            return authority;
        }
    }

    /**
     * How strong a claim is against a competing one.
     *
     * <p>Ordered weakest first, and compared by {@link Enum#ordinal()} on purpose: the ordering
     * <em>is</em> the rule, so adding a level in the middle is a deliberate, visible change.
     */
    public enum Authority {
        /** A suggestion. Anything else outranks it, including another advisory claim. */
        ADVISORY,
        /** Ordinary business: a law hold, a conversation, a thief at work. */
        ROUTINE,
        /** An arrest, a pursuit or an escort in progress. */
        ENFORCEMENT,
        /** The villager is not free. Nothing pre-empts this. */
        CUSTODY;

        /** Whether this claim may take a villager from one already held at {@code other}. */
        public boolean outranks(Authority other) {
            return other == null || ordinal() > other.ordinal();
        }

        /** Whether this claim may take over from {@code other}, including a same-strength takeover. */
        public boolean atLeast(Authority other) {
            return other == null || ordinal() >= other.ordinal();
        }
    }

    public CrimeActivityView {
        if (entity == null) {
            throw new IllegalArgumentException("an activity claim must name an entity");
        }
        if (kind == null) {
            throw new IllegalArgumentException("an activity claim must have a kind");
        }
        owner = owner == null || owner.isBlank() ? "unknown" : owner;
        authority = authority == null ? kind.authority() : authority;
        allowedOperations = allowedOperations == null ? Set.of() : Set.copyOf(allowedOperations);
    }

    /** Whether the claim is still in force at {@code now}. */
    public boolean live(long now) {
        return expiresAt > now;
    }

    /** Whether {@code operation} must stand aside while this claim is live. */
    public boolean requiresYield(@Nullable CrimeActivityOperation operation) {
        return operation != null && !allowedOperations.contains(operation);
    }

    /** The same claim with a later deadline. Generation and identity are untouched. */
    public CrimeActivityView renewedUntil(long expiry) {
        return expiry <= expiresAt ? this
                : new CrimeActivityView(entity, dimension, kind, owner, generation, authority,
                        allowedOperations, expiry);
    }

    /** A short operator line for {@code /crime debug}. */
    public String describe() {
        return kind.name().toLowerCase(java.util.Locale.ROOT)
                + " (" + authority.name().toLowerCase(java.util.Locale.ROOT) + ") by " + owner
                + " gen " + generation;
    }
}
