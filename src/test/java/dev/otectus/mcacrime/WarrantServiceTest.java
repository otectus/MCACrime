package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.ledger.Warrant;
import dev.otectus.mcacrime.ledger.WarrantService;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The warrant state machine, tested as the pure thing it is.
 *
 * <p>The subscribers around it are wiring — read the world, persist, post. What matters is the four
 * transitions, because a warrant that opens twice, or that revises while closed, breaks the claim key
 * every bounty in the mod is built on.
 */
class WarrantServiceTest {

    private static final UUID OFFENDER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final ResourceLocation THEFT = ResourceLocation.fromNamespaceAndPath("mcacrime", "theft");
    private static final ResourceLocation MURDER = ResourceLocation.fromNamespaceAndPath("mcacrime", "murder");

    private static Warrant becameWanted() {
        return WarrantService.applyWanted(null, OFFENDER, true, UUID.randomUUID(), THEFT, null, 100L)
                .orElseThrow();
    }

    @Test
    void becomingWantedOpensAWarrantAtRevisionOne() {
        Warrant warrant = becameWanted();
        assertTrue(warrant.open());
        assertEquals(1L, warrant.revision());
        assertEquals(OFFENDER, warrant.offender());
        assertEquals(THEFT, warrant.topOffense());
    }

    @Test
    void becomingWantedAgainWhileAlreadyOpenChangesNothing() {
        // Heat can cross the threshold more than once without a second document being opened; the
        // revision is what tracks new offences, not the flag flapping.
        Warrant warrant = becameWanted();
        assertEquals(Optional.empty(),
                WarrantService.applyWanted(warrant, OFFENDER, true, UUID.randomUUID(), MURDER, null, 200L));
    }

    @Test
    void aCrimeWithHeatWhileOpenBumpsTheRevision() {
        Warrant warrant = becameWanted();
        Warrant revised = WarrantService.applyCrime(warrant, 12L, UUID.randomUUID(), MURDER, 200L).orElseThrow();
        assertEquals(2L, revised.revision());
        assertEquals(MURDER, revised.topOffense());
        assertEquals(warrant.id(), revised.id(), "a revision is the same warrant, not a new one");
        assertTrue(revised.open());
    }

    @Test
    void aCrimeThatGeneratedNoHeatIsNotARevision() {
        assertEquals(Optional.empty(),
                WarrantService.applyCrime(becameWanted(), 0L, UUID.randomUUID(), MURDER, 200L));
    }

    @Test
    void ceasingToBeWantedClosesTheWarrantWithoutDeletingIt() {
        Warrant warrant = becameWanted();
        Warrant closed = WarrantService.applyWanted(warrant, OFFENDER, false, UUID.randomUUID(), null, null, 300L)
                .orElseThrow();
        assertFalse(closed.open());
        assertEquals(warrant.id(), closed.id(), "the id survives so an old claim key still resolves");
        assertEquals(warrant.revision(), closed.revision());
        assertEquals(300L, closed.closedAt());
    }

    @Test
    void aCrimeWhileClosedChangesNothing() {
        Warrant closed = becameWanted().closed(300L);
        assertEquals(Optional.empty(),
                WarrantService.applyCrime(closed, 40L, UUID.randomUUID(), MURDER, 400L));
        assertEquals(Optional.empty(),
                WarrantService.applyCrime(null, 40L, UUID.randomUUID(), MURDER, 400L));
    }

    @Test
    void closingSomethingAlreadyClosedIsNotACloseEvent() {
        assertEquals(Optional.empty(),
                WarrantService.applyWanted(null, OFFENDER, false, UUID.randomUUID(), null, null, 100L));
        assertEquals(Optional.empty(), WarrantService.applyWanted(becameWanted().closed(200L), OFFENDER,
                false, UUID.randomUUID(), null, null, 300L));
    }

    @Test
    void theWarrantIsNamedAfterTheHeaviestOutstandingCase() {
        List<CrimeRecord> unresolved = List.of(record(MURDER, 30L), record(THEFT, 5L));
        assertEquals(MURDER, WarrantService.topOffense(unresolved, THEFT));
        assertEquals(THEFT, WarrantService.topOffense(List.of(), THEFT), "an empty ledger keeps the fallback");
    }

    private static CrimeRecord record(ResourceLocation type, long heat) {
        return new CrimeRecord(UUID.randomUUID(), OFFENDER, null, type, OptionalInt.empty(), true,
                0L, heat, 0L, 0L, 0L, Resolution.UNRESOLVED);
    }
}
