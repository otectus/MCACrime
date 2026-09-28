package dev.otectus.mcacrime.block;

import dev.otectus.mcacrime.locks.KeyBinding;
import dev.otectus.mcacrime.locks.LockAccess;
import dev.otectus.mcacrime.locks.LockInteractions;
import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.locks.LockService;
import dev.otectus.mcacrime.locks.LockTarget;
import dev.otectus.mcacrime.locks.LockTargetNormalizer;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cell door's lock, from the first blank key to the operator's rekey (M3.4, ledger L05).
 *
 * <p>The routing order is the substance. One table decides what a click on a lock means, for the
 * door, the safe and the padlock alike, and it is asserted here rather than repeated three times in
 * three blocks with three slightly different orderings — which is what upstream ships.
 */
class CellDoorLockTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final BlockPos LOWER = new BlockPos(3, 64, 3);
    private static final BlockPos UPPER = LOWER.above();

    private CrimeWorldData data;

    @BeforeEach
    void freshWorld() {
        data = new CrimeWorldData();
        LockService.invalidate();
    }

    @Test
    void aBlankKeyOnAnUnboundDoorBindsIt() {
        assertEquals(LockInteractions.Route.BIND, LockInteractions.route(false, false, false, true,
                false, false, false));
        // And nothing else does: a pick on an unbound door is not a lock interaction at all.
        assertEquals(LockInteractions.Route.PASS, LockInteractions.route(false, false, false, false,
                true, false, false));
    }

    @Test
    void theRoutingOrderPutsAuthorityFirstAndThePickLast() {
        // A bind breaker beats everything, but only for somebody authorised.
        assertEquals(LockInteractions.Route.BREAK_BINDING,
                LockInteractions.route(true, false, true, false, true, true, true));
        assertEquals(LockInteractions.Route.PASS,
                LockInteractions.route(true, false, false, false, false, true, false),
                "an unauthorised bind breaker does nothing");

        // A master key works for an operator and not for anybody else.
        assertEquals(LockInteractions.Route.TOGGLE,
                LockInteractions.route(false, true, false, false, false, true, true));
        assertEquals(LockInteractions.Route.PASS,
                LockInteractions.route(false, true, false, false, false, true, false));

        // A matching key beats a pick, so a player holding both opens the door rather than picking it.
        assertEquals(LockInteractions.Route.TOGGLE,
                LockInteractions.route(false, false, true, false, true, true, false));
        assertEquals(LockInteractions.Route.PICK,
                LockInteractions.route(false, false, false, false, true, true, false));
        assertEquals(LockInteractions.Route.PASS,
                LockInteractions.route(false, false, false, false, false, true, false));
    }

    @Test
    void bothHalvesOfADoorAreOneLock() {
        LockTarget fromLower = LockTargetNormalizer.canonical(OVERWORLD, LOWER, UPPER);
        LockTarget fromUpper = LockTargetNormalizer.canonical(OVERWORLD, UPPER, LOWER);
        LockRecord lock = LockService.create(data, fromLower, UUID.randomUUID()).orElseThrow();

        assertEquals(lock.lockId(), LockService.at(data, fromUpper).orElseThrow().lockId(),
                "clicking the top of a door works the same lock as clicking the bottom");
    }

    @Test
    void aLockedDoorRefusesTheWrongKeyAndOpensForTheRightOne() {
        LockRecord lock = LockService.create(data,
                LockTargetNormalizer.canonical(OVERWORLD, LOWER, UPPER), null).orElseThrow();
        KeyBinding mine = KeyBinding.forLock(lock, "Cell 3");
        KeyBinding yours = KeyBinding.of(UUID.randomUUID(), 1L);

        assertTrue(lock.locked(), "a door binds locked");
        assertTrue(LockAccess.opens(lock, mine));
        assertFalse(LockAccess.opens(lock, yours));

        LockRecord unlocked = LockService.setLocked(data, lock.lockId(), false).orElseThrow();
        assertFalse(unlocked.locked());
        assertTrue(LockAccess.evaluate(unlocked, null, false).allowed(),
                "an unlocked cell door is an ordinary door");
    }

    @Test
    void anOperatorRekeyLocksEverybodyOutIncludingThePreviousOwner() {
        LockRecord lock = LockService.create(data,
                LockTargetNormalizer.canonical(OVERWORLD, LOWER, UPPER), UUID.randomUUID()).orElseThrow();
        KeyBinding ownersKey = KeyBinding.forLock(lock, "Cell 3");

        LockRecord rekeyed = LockService.rekey(data, lock.lockId()).orElseThrow();
        assertFalse(ownersKey.opens(rekeyed));
        assertEquals(LockAccess.Decision.DENY_REKEYED, LockAccess.evaluate(rekeyed, ownersKey, false));
        assertEquals(lock.lockId(), rekeyed.lockId(), "the door still points at the same lock");
    }
}
