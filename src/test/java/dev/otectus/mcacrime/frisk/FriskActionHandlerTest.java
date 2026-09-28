package dev.otectus.mcacrime.frisk;

import dev.otectus.mcacrime.action.ActionAvailability;
import dev.otectus.mcacrime.action.handler.FriskActionHandler;
import dev.otectus.mcacrime.TestPaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who sees the Search row, and why not when not (M5.2, reworked onto the crime menu).
 *
 * <p>The pure half of the handler is what is asserted: the facts are gathered from live state by
 * {@code evaluate} and handed to {@link FriskActionHandler#availability}, so the whole rule is a
 * table over four booleans and every row of it is below.
 */
class FriskActionHandlerTest {

    @Test
    void yourOwnPocketsAreNotARow() {
        ActionAvailability self = FriskActionHandler.availability(true, true, true, true);
        assertEquals(ActionAvailability.Status.HIDDEN, self.status(),
                "searching yourself moves nothing anywhere and is not offered");
    }

    @Test
    void somethingTheEngineCannotHoldIsNotARowEither() {
        assertEquals(ActionAvailability.Status.HIDDEN,
                FriskActionHandler.availability(false, false, false, true).status());
    }

    @Test
    void aFreeSubjectIsShownWhyTheyCannotBeSearched() {
        ActionAvailability free = FriskActionHandler.availability(false, true, false, true);
        assertEquals(ActionAvailability.Status.BLOCKED, free.status());
        assertEquals("mcacrime.msg.frisk.not_restrained", free.reason());
    }

    @Test
    void aSubjectOutOfReachIsBlockedNotHidden() {
        ActionAvailability far = FriskActionHandler.availability(false, true, true, false);
        assertEquals(ActionAvailability.Status.BLOCKED, far.status());
        assertEquals("mcacrime.msg.frisk.out_of_reach", far.reason());
    }

    @Test
    void aRestrainedSubjectInReachMayBeSearched() {
        assertTrue(FriskActionHandler.availability(false, true, true, true).isAvailable());
    }

    @Test
    void theRowIsNotCoerciveAndOpensThroughTheService() {
        assertFalse(new FriskActionHandler().coercive(),
                "the subject is already restrained; there is nothing for a bystander to freeze at");
        String source = read(TestPaths.sources("dev", "otectus", "mcacrime", "action",
                "handler", "FriskActionHandler.java"));
        assertTrue(source.contains("FriskingService.open(actor.asPlayer(), target)"),
                "the handler opens the same server-owned session the box used to");
        assertFalse(source.contains("POSSESSIONS_BOX"), "no item is needed to search");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
