package dev.otectus.mcacrime.restraint;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No session straddles a config reload (0.7.5 M7.1).
 *
 * <p>The mid-session change policy is one sentence: a session keeps the numbers it started with or it
 * ends. Ending is the answer this mod takes, because every session is transient work owned by one
 * actor, and a struggle that was priced against the old durability — or a frisk bounded by the old
 * payload cap — would otherwise finish under rules that are no longer written anywhere. Nothing is
 * lost by ending one: no restraint comes off, no lock relocks, and starting again costs one
 * interaction.
 */
class SessionReloadPolicyTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation PICK = new ResourceLocation("mcacrime", "lockpick");

    private static WorkSession session(SessionRegistry registry, UUID actor) {
        return new WorkSession(registry.allocateId(), actor, UUID.randomUUID(), 1L, OVERWORLD, PICK,
                10_000L, RestraintSlot.ARMS);
    }

    @Test
    void everyLiveSessionEndsAndEveryActorIsNamed() {
        SessionRegistry registry = new SessionRegistry(8);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        registry.open(session(registry, first));
        registry.open(session(registry, second));

        List<UUID> cancelled = SessionReloadPolicy.cancelAll(registry);

        assertEquals(List.of(first, second), cancelled, "the owners are returned so they can be told");
        assertEquals(0, registry.size(), "nothing is left running against numbers that changed");
    }

    @Test
    void aReloadWithNothingRunningIsSilent() {
        assertEquals(List.of(), SessionReloadPolicy.cancelAll(new SessionRegistry(8)));
        assertEquals(List.of(), SessionReloadPolicy.cancelAll((SessionRegistry) null));
    }

    /** The reason is its own, so a log says the reload ended the work rather than "cancelled". */
    @Test
    void theCancellationReasonIsNamed() {
        assertTrue(List.of(SessionCancelCause.values()).contains(SessionCancelCause.CONFIG_RELOADED));
        assertTrue(SessionReloadPolicy.MESSAGE_KEY.startsWith("mcacrime."),
                "the actor is told in their own language, not with a hard-coded sentence");
    }
}
