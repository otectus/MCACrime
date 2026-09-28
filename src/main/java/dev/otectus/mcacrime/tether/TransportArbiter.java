package dev.otectus.mcacrime.tether;

import dev.otectus.mcacrime.activity.CrimeActivityRegistry;
import dev.otectus.mcacrime.activity.CrimeActivityView;
import dev.otectus.mcacrime.detention.DetentionService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Exactly one thing moves a subject at a time (0.7.5 M4.3, §3.6).
 *
 * <p>The specification's §9.4 ladder, in order: an occupied detention device, then a seat or mount,
 * then a close escort, then chain tension, then the subject's own movement filtered by whatever their
 * limbs allow. The arbiter resolves that once per subject per tick and acts on <b>one</b> level.
 *
 * <p>Lower levels are marked {@code suspended} in their record rather than deleted, which is the
 * difference between "the chain waits while you are in the pillory" and "the chain fell on the floor
 * when somebody closed the boards". Releasing the higher authority resumes them.
 *
 * <p>The {@code activity/} claim is the handover mechanism. A subject whose claim generation has
 * changed under the arbiter is no longer this transport's to move — a guard was reassigned, a captor
 * died, an arrest pre-empted a kidnapping — and the arbiter stands down rather than fighting the new
 * owner for the same entity.
 */
public final class TransportArbiter {

    /** Which level of the ladder owns this subject this tick. */
    public enum Authority {
        /** A device is holding them in place. Nothing else moves them. */
        DETENTION,
        /** They are in a seat, a boat or on a mount. The vehicle decides where they go. */
        VEHICLE,
        /** A holder is walking them. Bounded correction plus navigation, never a teleport. */
        ESCORT,
        /** A chain or an anchor is the only thing acting on them, and only when it is taut. */
        CHAIN,
        /** Nothing is transporting them; their own movement stands, limb restrictions aside. */
        FREE
    }

    private TransportArbiter() {
    }

    /**
     * The ladder, as a pure function.
     *
     * <p>Every branch is a rule somebody could otherwise get wrong by adding a second mover: a
     * pilloried prisoner in a boat is held by the pillory; a chained passenger goes where the boat
     * goes; an escorted subject who is also chained to a fence is walked by the escort, and the chain
     * waits.
     */
    public static Authority resolve(boolean detained, boolean riding, boolean escorted,
                                    boolean chained) {
        if (detained) {
            return Authority.DETENTION;
        }
        if (riding) {
            return Authority.VEHICLE;
        }
        if (escorted) {
            return Authority.ESCORT;
        }
        return chained ? Authority.CHAIN : Authority.FREE;
    }

    /**
     * Whether a tether of this kind should be suspended while {@code authority} owns the subject.
     *
     * <p>Pure, and the complement of {@link #resolve}: the winning level's own record stays live and
     * everything below it is suspended. An escort suspends chains; a device and a vehicle suspend
     * both.
     */
    public static boolean suspends(Authority authority, TetherKind kind) {
        if (kind == null) {
            return false;
        }
        return switch (authority) {
            case DETENTION, VEHICLE -> true;
            case ESCORT -> kind != TetherKind.ESCORT;
            case CHAIN, FREE -> false;
        };
    }

    /**
     * Whether the claim this transport started under is still the live one.
     *
     * <p>Pure. {@link CrimeActivityRegistry#REFUSED} and a changed generation both answer no, which is
     * how a guard reassignment, a custody transfer and a captor's death all hand over cleanly without
     * any of them having to tell the arbiter directly.
     */
    public static boolean claimStillOurs(long claimedGeneration, long liveGeneration) {
        return claimedGeneration != CrimeActivityRegistry.REFUSED
                && claimedGeneration == liveGeneration;
    }

    /**
     * One tick for one subject.
     *
     * <p>Reads the four facts, resolves the ladder, suspends what lost and acts on what won. The
     * common case — a subject with no tether and no device — never reaches here at all, because the
     * caller iterates the tether index rather than the world.
     */
    public static Authority tick(@Nullable MinecraftServer server, @Nullable CrimeWorldData data,
                                 @Nullable LivingEntity subject) {
        if (server == null || data == null || subject == null || !subject.isAlive()) {
            return Authority.FREE;
        }
        UUID subjectId = subject.getUUID();
        List<TetherRecord> tethers = TetherService.forSubject(data, subjectId);
        boolean detained = DetentionService.forSubject(data, subjectId).isPresent();
        boolean riding = subject.isPassenger();
        TetherRecord escort = null;
        TetherRecord chain = null;
        for (TetherRecord tether : tethers) {
            if (tether.kind() == TetherKind.ESCORT) {
                escort = tether;
            } else if (chain == null) {
                chain = tether;
            }
        }
        Authority authority = resolve(detained, riding, escort != null, chain != null);

        for (TetherRecord tether : tethers) {
            TetherService.suspend(data, tether.id(), suspends(authority, tether.kind()));
        }
        switch (authority) {
            case ESCORT -> EscortTransport.tick(server, data, subject, escort);
            case CHAIN -> EscortTransport.tickChain(server, data, subject, chain);
            default -> {
                // A device holds the subject through its own block-entity tick; a vehicle moves them
                // itself; a free subject moves themselves. None of the three wants a second mover.
            }
        }
        return authority;
    }

    /**
     * Ticks every tether whose subject is loaded, and nothing else.
     *
     * <p>Index-driven: the subjects are taken from {@link TetherIndex}, so an empty world costs one
     * map read and a world with three chained prisoners costs three lookups. This is the replacement
     * for the source's per-interaction world scan.
     */
    public static int tickAll(@Nullable MinecraftServer server) {
        CrimeWorldData data = TetherService.data(server);
        if (data == null) {
            return 0;
        }
        int acted = 0;
        java.util.Set<UUID> seen = new java.util.LinkedHashSet<>();
        for (TetherRecord tether : TetherService.index(data).all()) {
            if (!seen.add(tether.subject())) {
                continue; // one arbitration per subject per tick, whatever they are tied to
            }
            Entity subject = TetherService.find(server, tether.subject());
            if (subject instanceof LivingEntity living) {
                tick(server, data, living);
                acted++;
            }
        }
        return acted;
    }

    /** The claim an escort takes on a villager, so ordinary behaviours yield to it (§3.3). */
    public static long claimEscort(@Nullable Entity subject, long now) {
        if (subject == null) {
            return CrimeActivityRegistry.REFUSED;
        }
        return CrimeActivityRegistry.claim(subject.getUUID(), subject.level().dimension().location(),
                CrimeActivityView.Kind.ESCORT, "tether/EscortTransport", now);
    }

    /** The claim a chain or a device takes: they are not free to walk away. */
    public static long claimCustody(@Nullable Entity subject, long now) {
        if (subject == null) {
            return CrimeActivityRegistry.REFUSED;
        }
        return CrimeActivityRegistry.claim(subject.getUUID(), subject.level().dimension().location(),
                CrimeActivityView.Kind.CUSTODY, "tether/TetherService", now);
    }
}
