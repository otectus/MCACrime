package dev.otectus.mcacrime.detect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a recruited relative is currently doing to who notices a crime.
 *
 * <p>Two effects, and they work on different halves of witness selection. A <b>lookout</b> shrinks the
 * radius the scan collects candidates from at all, because somebody watching the street lets you pick
 * your moment — it is not a per-villager exemption, and applying it as one would leave the crowd size
 * and the "scanned" count telling a different story from the witness set. A <b>distraction</b> removes
 * individual civilians who are looking at the accomplice instead, which is exactly a per-villager
 * exemption. Responders are never distracted: a guard who can be talked away from a crime by a
 * villager waving is not a guard.
 *
 * <p>Memory-only, like {@code NpcCriminalPursuit} and the thief controllers. An effect lasts a couple
 * of minutes; a restart that lost one lost a lookout who also stopped existing. Nothing here touches
 * Minecraft, so the arithmetic — composition, clamping, exact expiry — is testable on its own, and the
 * geometry that needs a loaded entity stays in {@link WitnessChecker}.
 */
public final class WitnessModifiers {

    /** What an accomplice is doing. */
    public enum Kind {
        /** Watching for guards; shrinks the offender's witness radius. */
        LOOKOUT,
        /** Making a scene; civilians near the accomplice see nothing else. */
        DISTRACTION
    }

    /**
     * One active effect.
     *
     * @param radius     blocks around the accomplice a distraction covers; unused by a lookout
     * @param multiplier what a lookout multiplies the witness radius by; unused by a distraction
     */
    public record Modifier(UUID offender, UUID accomplice, Kind kind, double radius, double multiplier,
                           long expiresAtTick) {
    }

    /** The floor a composed lookout multiplier is clamped to: a crime is never wholly unwitnessable. */
    public static final double MIN_RADIUS_MULTIPLIER = 0.1D;

    /** offender -> their active effects. Bounded by how many relatives one player can recruit. */
    private static final Map<UUID, List<Modifier>> ACTIVE = new ConcurrentHashMap<>();

    private WitnessModifiers() {
    }

    /** Adds an effect, replacing any the same accomplice already had of the same kind. */
    public static void put(Modifier modifier) {
        if (modifier == null || modifier.offender() == null || modifier.accomplice() == null) {
            return;
        }
        ACTIVE.compute(modifier.offender(), (offender, existing) -> {
            List<Modifier> next = existing == null ? new ArrayList<>(2) : new ArrayList<>(existing);
            next.removeIf(m -> m.accomplice().equals(modifier.accomplice()) && m.kind() == modifier.kind());
            next.add(modifier);
            return next;
        });
    }

    /** Drops one accomplice's effects, of one kind or (with a null kind) of every kind. */
    public static void remove(UUID accomplice, Kind kind) {
        if (accomplice == null) {
            return;
        }
        for (UUID offender : List.copyOf(ACTIVE.keySet())) {
            ACTIVE.computeIfPresent(offender, (id, existing) -> {
                List<Modifier> next = new ArrayList<>(existing);
                next.removeIf(m -> m.accomplice().equals(accomplice) && (kind == null || m.kind() == kind));
                return next.isEmpty() ? null : next;
            });
        }
    }

    /** Every effect still running for this offender at {@code now}, in the order they were added. */
    public static List<Modifier> active(UUID offender, long now) {
        List<Modifier> held = offender == null ? null : ACTIVE.get(offender);
        if (held == null || held.isEmpty()) {
            return List.of();
        }
        List<Modifier> live = new ArrayList<>(held.size());
        for (Modifier modifier : held) {
            if (modifier.expiresAtTick() > now) {
                live.add(modifier);
            }
        }
        return List.copyOf(live);
    }

    /** The active distractions covering this offender's crime. */
    public static List<Modifier> distractions(UUID offender, long now) {
        List<Modifier> out = new ArrayList<>(2);
        for (Modifier modifier : active(offender, now)) {
            if (modifier.kind() == Kind.DISTRACTION) {
                out.add(modifier);
            }
        }
        return List.copyOf(out);
    }

    /**
     * What the witness radius is multiplied by for this offender right now: the product of every
     * posted lookout, clamped to {@code [MIN_RADIUS_MULTIPLIER, 1.0]}.
     *
     * <p>A product rather than a minimum, so two lookouts are better than one, and a clamp rather than
     * an unbounded product, so five are not a cloaking device.
     */
    public static double witnessRadiusMultiplier(UUID offender, long now) {
        return composeRadiusMultiplier(active(offender, now));
    }

    /** The pure half of {@link #witnessRadiusMultiplier}, over an explicit effect list. */
    public static double composeRadiusMultiplier(Collection<Modifier> modifiers) {
        double product = 1.0D;
        for (Modifier modifier : modifiers) {
            if (modifier.kind() == Kind.LOOKOUT) {
                product *= modifier.multiplier();
            }
        }
        return Math.max(MIN_RADIUS_MULTIPLIER, Math.min(1.0D, product));
    }

    /**
     * Whether this one distraction takes this candidate out of the witness set.
     *
     * <p>Pure, and separated from the entity lookup for exactly that reason: "civilians only, inside
     * the radius" is the rule worth pinning, and it does not need a loaded level to state.
     *
     * @param distanceSqr squared distance from the candidate to the accomplice making the scene
     */
    public static boolean distracts(Modifier modifier, double distanceSqr, boolean candidateIsResponder) {
        if (modifier == null || modifier.kind() != Kind.DISTRACTION || candidateIsResponder) {
            return false;
        }
        double radius = modifier.radius();
        return distanceSqr <= radius * radius;
    }

    /**
     * Drops everything that has expired and returns it, so the ticker can tell the offender their
     * relative has gone back to what they were doing. Called from the accomplice ticker.
     */
    public static List<Modifier> prune(long now) {
        List<Modifier> dropped = new ArrayList<>();
        for (UUID offender : List.copyOf(ACTIVE.keySet())) {
            List<Modifier> held = ACTIVE.get(offender);
            if (held == null) {
                continue;
            }
            List<Modifier> live = new ArrayList<>(held.size());
            for (Modifier modifier : held) {
                if (modifier.expiresAtTick() > now) {
                    live.add(modifier);
                } else {
                    dropped.add(modifier);
                }
            }
            if (live.isEmpty()) {
                ACTIVE.remove(offender, held);
            } else if (live.size() != held.size()) {
                ACTIVE.put(offender, live);
            }
        }
        return List.copyOf(dropped);
    }

    /** Drops every effect. Called on server stop, so a restart never inherits a lookout. */
    public static void clearAll() {
        ACTIVE.clear();
    }
}
