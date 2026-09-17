package dev.otectus.mcacrime.property;

import org.jetbrains.annotations.Nullable;

/**
 * The whole permission table, as a pure function of a policy, an actor and one operation.
 *
 * <h2>Three answers, not two</h2>
 *
 * <p>{@code ALLOWED} and {@code DENIED} are the easy half. The one that carries the design is
 * {@code UNKNOWN}: no policy, an actor nobody could identify, an owner that cannot be compared
 * against anybody. §10.1 says an unknown or disputed owner does not authorise a theft charge, and
 * §10.2 says an unsupported source must be reported as unsupported rather than charged to the nearest
 * player. Both collapse into one rule here — <b>{@code UNKNOWN} never charges anyone</b> — and
 * {@link TransferAttribution} is where that is spent.
 *
 * <p>Nothing in this class touches the world, a config, or a server. That is what makes the table
 * exercisable row by row, which for a subsystem that can accuse a player of a crime is the difference
 * between a rule and a hope.
 */
public final class PropertyAccess {

    /** What an actor is trying to do with a container. */
    public enum Operation {
        /** Remove items. The only operation a theft charge can ever come from. */
        TAKE,
        /** Put items in. A deposit, a delivery, restitution — legitimate by default (§10.1). */
        PUT,
        /** Open or inspect. Never a completed theft, whatever the answer (§10.1). */
        OPEN
    }

    public enum Verdict {
        ALLOWED,
        DENIED,
        /** Nobody could decide. Reported, never charged. */
        UNKNOWN
    }

    /**
     * One decision and the sentence explaining it.
     *
     * <p>The reason is not decoration: it is what {@code /crime property inspect} prints and what a
     * receipt carries, and §10.2 requires an unsupported or unknown case to identify the gap rather
     * than to fail silently.
     */
    public record Decision(Verdict verdict, String reason) {

        public Decision {
            if (verdict == null) {
                verdict = Verdict.UNKNOWN;
            }
            reason = reason == null || reason.isBlank() ? "no reason recorded" : reason;
        }

        public static Decision allowed(String reason) {
            return new Decision(Verdict.ALLOWED, reason);
        }

        public static Decision denied(String reason) {
            return new Decision(Verdict.DENIED, reason);
        }

        public static Decision unknown(String reason) {
            return new Decision(Verdict.UNKNOWN, reason);
        }

        /** Whether this decision is the kind an offence can be built on. Only a denial ever is. */
        public boolean chargeable() {
            return verdict == Verdict.DENIED;
        }

        public boolean allowed() {
            return verdict == Verdict.ALLOWED;
        }

        public boolean unknown() {
            return verdict == Verdict.UNKNOWN;
        }
    }

    private PropertyAccess() {
    }

    /** The table. */
    public static Decision decide(@Nullable PropertyPolicy policy, @Nullable PropertyActor actor,
                                  @Nullable Operation operation) {
        if (operation == null) {
            return Decision.unknown("no operation was named");
        }
        if (policy == null) {
            // The ordinary state of nearly every container in every world. Not a denial: nobody has
            // claimed this, so there is nothing to steal from.
            return Decision.unknown("no property policy covers this container");
        }
        PropertyActor who = actor == null ? PropertyActor.unknown() : actor;
        if (!who.identified()) {
            return Decision.unknown("the actor could not be identified");
        }

        if (policy.rule() == PropertyAccessRule.FORBIDDEN) {
            // The one rule that refuses the open as well. It still is not a theft: only a committed
            // TAKE ever becomes an incident, which is what keeps looking into a sealed evidence chest
            // from being charged as having emptied it.
            return Decision.denied("this container is sealed: " + describeOwner(policy));
        }
        if (operation == Operation.OPEN || operation == Operation.PUT) {
            // Deposits, agreed deliveries, restitution and permitted care are legitimate operations
            // whoever performs them (§10.1). Refusing them would make returning stolen bread harder
            // than stealing it.
            return Decision.allowed(operation == Operation.OPEN
                    ? "opening is not a restricted operation" : "deposits are always permitted");
        }

        return switch (policy.rule()) {
            case PUBLIC -> Decision.allowed("this container is public");
            case RESIDENTS -> personalOwnerMatches(policy, who)
                    ? Decision.allowed("the actor owns this container")
                    : who.memberOf(policy.building().villageId())
                            ? Decision.allowed("the actor is a resident of the owning settlement")
                            : Decision.denied("only residents of " + describeOwner(policy) + " may take from this");
            case WORKERS -> personalOwnerMatches(policy, who)
                    ? Decision.allowed("the actor owns this container")
                    : who.worksFor(policy.building().villageId())
                            ? Decision.allowed("the actor is working for the owning settlement")
                            : Decision.denied("only settlement workers may take from this");
            case OWNER_ONLY -> ownerMatches(policy, who)
                    ? Decision.allowed("the actor owns this container")
                    : Decision.denied("this belongs to " + describeOwner(policy));
            // Handled above, before the operation split; listed so that adding a rule without
            // deciding what it means here fails to compile rather than falling through to a default.
            case FORBIDDEN -> Decision.denied("this container is sealed");
        };
    }

    /**
     * Whether this actor is the owner <em>as a person</em>.
     *
     * <p>Village ownership is deliberately excluded. Under {@code RESIDENTS} and {@code WORKERS} the
     * rule is already the membership test, and letting "owned by the village" also count as an owner
     * match would collapse the two rules into one for every resident: a stores chest marked for workers
     * would be open to anybody who lives there, which is precisely the distinction an operator chose
     * {@code WORKERS} to express.
     */
    private static boolean personalOwnerMatches(PropertyPolicy policy, PropertyActor actor) {
        return switch (policy.ownerKind()) {
            case PLAYER -> actor.kind() == PropertyActor.Kind.PLAYER && policy.ownerId() != null
                    && policy.ownerId().equals(actor.id());
            case VILLAGER -> actor.kind() == PropertyActor.Kind.VILLAGER && policy.ownerId() != null
                    && policy.ownerId().equals(actor.id());
            case VILLAGE, FACILITY -> false;
        };
    }

    /**
     * Whether this actor <em>is</em> the owner.
     *
     * <p>A facility never matches anybody, and that is deliberate rather than an oversight: evidence
     * storage belongs to the law rather than to a person, so "owner only" on one means nobody at all.
     * An operator who wants guards to be able to reach it says so with
     * {@link PropertyAccessRule#WORKERS}.
     */
    private static boolean ownerMatches(PropertyPolicy policy, PropertyActor actor) {
        return switch (policy.ownerKind()) {
            case PLAYER -> actor.kind() == PropertyActor.Kind.PLAYER && policy.ownerId() != null
                    && policy.ownerId().equals(actor.id());
            case VILLAGER -> actor.kind() == PropertyActor.Kind.VILLAGER && policy.ownerId() != null
                    && policy.ownerId().equals(actor.id());
            case VILLAGE -> actor.memberOf(policy.building().villageId());
            case FACILITY -> false;
        };
    }

    private static String describeOwner(PropertyPolicy policy) {
        return switch (policy.ownerKind()) {
            case PLAYER -> "another player";
            case VILLAGER -> "a villager";
            case VILLAGE -> policy.building().bound()
                    ? "village " + policy.building().villageId() : "the settlement";
            case FACILITY -> "an MCA: Crime facility";
        };
    }
}
