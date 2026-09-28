package dev.otectus.mcacrime.restraint;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The session registry: its cap, its expiry, and every reason a session ends early. */
class SessionRegistryTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation PICK = new ResourceLocation("mcacrime", "lockpick");

    private static WorkSession session(SessionRegistry registry, UUID actor, UUID target, long expiry) {
        return new WorkSession(registry.allocateId(), actor, target, 1L, OVERWORLD, PICK, expiry,
                RestraintSlot.ARMS);
    }

    @Test
    void aSessionIsFoundByIdAndByItsActor() {
        SessionRegistry registry = new SessionRegistry(8);
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Session opened = registry.open(session(registry, actor, target, 100L)).orElseThrow();

        assertEquals(opened, registry.get(opened.id()).orElseThrow());
        assertEquals(opened, registry.forActor(actor).orElseThrow());
        assertEquals(1, registry.size());
    }

    @Test
    void idsAreUniqueAndAllocatedByTheRegistry() {
        SessionRegistry registry = new SessionRegistry(8);
        assertTrue(registry.allocateId() != registry.allocateId());
    }

    @Test
    void theCapRefusesTheNewcomerRatherThanEvictingSomebodyElsesWork() {
        SessionRegistry registry = new SessionRegistry(2);
        Session first = registry.open(session(registry, UUID.randomUUID(), UUID.randomUUID(), 100L)).orElseThrow();
        Session second = registry.open(session(registry, UUID.randomUUID(), UUID.randomUUID(), 100L)).orElseThrow();

        assertTrue(registry.open(session(registry, UUID.randomUUID(), UUID.randomUUID(), 100L)).isEmpty(),
                "a full registry has to refuse, or a busy server becomes a way to interrupt people");
        assertEquals(2, registry.size());
        assertTrue(registry.get(first.id()).isPresent(), "an existing session was evicted");
        assertTrue(registry.get(second.id()).isPresent());
    }

    @Test
    void oneActorHoldsOneSessionAndTheNewOneReplacesTheOld() {
        SessionRegistry registry = new SessionRegistry(8);
        UUID actor = UUID.randomUUID();
        Session first = registry.open(session(registry, actor, UUID.randomUUID(), 100L)).orElseThrow();
        Session second = registry.open(session(registry, actor, UUID.randomUUID(), 100L)).orElseThrow();

        assertTrue(registry.get(first.id()).isEmpty(), "the abandoned session must not leak");
        assertEquals(second, registry.forActor(actor).orElseThrow());
        assertEquals(1, registry.size());
    }

    @Test
    void validateRefusesAnotherActorsSessionAndAnExpiredOne() {
        SessionRegistry registry = new SessionRegistry(8);
        UUID actor = UUID.randomUUID();
        Session opened = registry.open(session(registry, actor, UUID.randomUUID(), 100L)).orElseThrow();

        assertTrue(registry.validate(opened.id(), actor, 99L).isPresent());
        assertTrue(registry.validate(opened.id(), UUID.randomUUID(), 99L).isEmpty(),
                "a session id is not an authorisation; the connection's player is");
        assertTrue(registry.validate(opened.id(), actor, 100L).isEmpty(), "expiry is exclusive");
        assertTrue(registry.validate(opened.id() + 1, actor, 99L).isEmpty());
        assertTrue(registry.validate(opened.id(), null, 99L).isEmpty());
    }

    @Test
    void expiryDropsOnlyTheSessionsThatAreOver() {
        SessionRegistry registry = new SessionRegistry(8);
        Session early = registry.open(session(registry, UUID.randomUUID(), UUID.randomUUID(), 50L)).orElseThrow();
        Session late = registry.open(session(registry, UUID.randomUUID(), UUID.randomUUID(), 500L)).orElseThrow();

        assertEquals(1, registry.expire(100L));

        assertTrue(registry.get(early.id()).isEmpty());
        assertTrue(registry.get(late.id()).isPresent());
        assertEquals(0, registry.expire(100L), "expiry is idempotent");
    }

    /**
     * The §3.5 cancellation matrix. Every cause has to reach the registry through one of these two
     * doors: something happened to the actor, or something happened to the target.
     */
    @Test
    void everyCancellationCauseEndsTheSessionItNames() {
        for (SessionCancelCause cause : SessionCancelCause.values()) {
            SessionRegistry registry = new SessionRegistry(8);
            UUID actor = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            Session opened = registry.open(session(registry, actor, target, 100L)).orElseThrow();

            boolean aboutTheTarget = cause == SessionCancelCause.TARGET_REMOVED
                    || cause == SessionCancelCause.TARGET_REPLACED
                    || cause == SessionCancelCause.CUSTODY_REPLACED;
            int cancelled = aboutTheTarget
                    ? registry.cancelForTarget(target, cause)
                    : registry.cancelForActor(actor, cause);

            assertEquals(1, cancelled, cause + " cancelled nothing");
            assertTrue(registry.get(opened.id()).isEmpty(), cause + " left the session behind");
            assertTrue(registry.forActor(actor).isEmpty(), cause + " left the actor index behind");
        }
    }

    @Test
    void oneTargetsRemovalEndsEverySessionAgainstThem() {
        SessionRegistry registry = new SessionRegistry(8);
        UUID prisoner = UUID.randomUUID();
        registry.open(session(registry, UUID.randomUUID(), prisoner, 100L));
        registry.open(session(registry, UUID.randomUUID(), prisoner, 100L));
        registry.open(session(registry, UUID.randomUUID(), UUID.randomUUID(), 100L));

        assertEquals(2, registry.cancelForTarget(prisoner, SessionCancelCause.TARGET_REMOVED),
                "a frisker and a lockpicker both lose their session when the prisoner goes");
        assertEquals(1, registry.size());
    }

    @Test
    void cancellingNothingIsHarmless() {
        SessionRegistry registry = new SessionRegistry(8);
        assertFalse(registry.cancel(42L, SessionCancelCause.CANCELLED));
        assertEquals(0, registry.cancelForActor(null, SessionCancelCause.LOGOUT));
        assertEquals(0, registry.cancelForTarget(null, SessionCancelCause.DEATH));
        assertTrue(registry.open(null).isEmpty());
    }

    @Test
    void shutdownClearsEverything() {
        SessionRegistry registry = new SessionRegistry(8);
        registry.open(session(registry, UUID.randomUUID(), UUID.randomUUID(), 100L));
        registry.clear(SessionCancelCause.SERVER_STOPPING);
        assertEquals(0, registry.size());
    }

    // ------------------------------------------------------------------ work sessions

    @Test
    void anInputMustAdvanceAndRespectTheMinimumInterval() {
        SessionRegistry registry = new SessionRegistry(8);
        WorkSession work = (WorkSession) registry
                .open(session(registry, UUID.randomUUID(), UUID.randomUUID(), 1000L)).orElseThrow();

        assertTrue(work.acceptInput(1, 100L, 4), "the first input counts");
        assertFalse(work.acceptInput(1, 110L, 4), "a replayed sequence number is refused");
        assertFalse(work.acceptInput(2, 102L, 4), "an input inside the interval is refused");
        assertTrue(work.acceptInput(2, 104L, 4), "and accepted once the interval has passed");
        assertEquals(2, work.progress(), "only accepted inputs count as work");
        assertEquals(104L, work.lastInputTick(), "the cooldown is stored as well as compared");
    }

    @Test
    void aSessionKnowsWhatItWasOpenedAgainst() {
        SessionRegistry registry = new SessionRegistry(8);
        UUID target = UUID.randomUUID();
        Session opened = registry.open(session(registry, UUID.randomUUID(), target, 100L)).orElseThrow();

        assertEquals(target, opened.target());
        assertEquals(1L, opened.targetRevision());
        assertEquals(OVERWORLD, opened.dimension());
        assertEquals(PICK, opened.sourceItem());
        assertEquals(SessionKind.WORK, opened.kind());
        assertTrue(opened.live(99L));
        assertFalse(opened.live(100L));
    }
}
