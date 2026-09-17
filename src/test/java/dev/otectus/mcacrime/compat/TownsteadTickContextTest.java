package dev.otectus.mcacrime.compat;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The staleness rules that make "whose tick is this?" a safe question to answer.
 *
 * <p>The context exists because the Townstead hooks are handed a {@code Brain} or a
 * {@code PathNavigation} and have no other way to learn whose they are. That makes a wrong answer
 * worse than no answer: MCA: Crime would suppress Townstead's behaviour on a villager it has no claim
 * on, every tick, silently. So every rule below is a rule about refusing to answer.
 *
 * <p>Asserted against the entity-free {@code set}/{@code at} pair rather than against a live entity,
 * because the decision is the same one either way — {@code currentEntityId()} reads the clock off the
 * entity's own level and then applies exactly these comparisons — and a unit test that needed a
 * bootstrapped Minecraft would not be run.
 */
class TownsteadTickContextTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation NETHER = new ResourceLocation("minecraft", "the_nether");

    private final UUID villager = UUID.nameUUIDFromBytes("villager".getBytes());

    @BeforeEach
    @AfterEach
    void reset() {
        TownsteadTickContext.clear();
    }

    @Test
    void aFreshContextAnswersForItsOwnTick() {
        TownsteadTickContext.set(villager, 100L, OVERWORLD);

        assertFalse(TownsteadTickContext.isEmpty());
        assertEquals(villager, TownsteadTickContext.at(100L, OVERWORLD).orElseThrow().entity());
    }

    /**
     * The failure the whole design is built against: a context left over from an earlier tick.
     *
     * <p>Townstead's tickers run inside the entity's own tick, so a context whose game time is not the
     * current one cannot be describing the villager now being processed — it is describing somebody
     * who finished. Answering anyway would move MCA: Crime's suppression onto an arbitrary bystander.
     */
    @Test
    void aContextFromAnEarlierTickIsIgnored() {
        TownsteadTickContext.set(villager, 100L, OVERWORLD);

        assertTrue(TownsteadTickContext.at(101L, OVERWORLD).isEmpty());
        assertTrue(TownsteadTickContext.at(99L, OVERWORLD).isEmpty());
    }

    /**
     * A context never crosses a level.
     *
     * <p>Dimensions tick one after another inside the same server tick and share the same game time,
     * so the clock alone cannot tell an overworld villager from a nether one. Without this check a
     * stale overworld context would look perfectly current to a hook running in the nether.
     */
    @Test
    void aContextNeverLeaksAcrossLevels() {
        TownsteadTickContext.set(villager, 100L, OVERWORLD);

        assertTrue(TownsteadTickContext.at(100L, NETHER).isEmpty());
        assertTrue(TownsteadTickContext.at(100L, null).isEmpty());
    }

    /** The server tick's END phase drops it, and after that nothing answers. */
    @Test
    void clearingEndsTheContext() {
        TownsteadTickContext.set(villager, 100L, OVERWORLD);
        TownsteadTickContext.clear();

        assertTrue(TownsteadTickContext.isEmpty());
        assertTrue(TownsteadTickContext.at(100L, OVERWORLD).isEmpty());
        assertEquals(null, TownsteadTickContext.currentEntityId());
    }

    /** A null id is a clear, not a context with a hole in it. */
    @Test
    void aNullEntityClearsRatherThanRecords() {
        TownsteadTickContext.set(villager, 100L, OVERWORLD);
        TownsteadTickContext.set(null, 100L, OVERWORLD);

        assertTrue(TownsteadTickContext.isEmpty());
    }

    /**
     * Only the thread that wrote the context may read it.
     *
     * <p>The context is a single static slot describing one entity's tick on the server thread. Any
     * other thread reading it — a worker, a chunk task, another dimension's dispatcher if that ever
     * changes — is by definition not inside that tick.
     */
    @Test
    void anotherThreadSeesNoContext() throws Exception {
        TownsteadTickContext.set(villager, 100L, OVERWORLD);

        AtomicReference<Boolean> seen = new AtomicReference<>();
        Thread other = new Thread(() -> seen.set(TownsteadTickContext.at(100L, OVERWORLD).isPresent()));
        other.start();
        other.join();

        assertEquals(Boolean.FALSE, seen.get());
    }

    /** With no live entity held, the mixin-facing accessor answers nothing rather than guessing. */
    @Test
    void theEntityFacingAccessorNeedsALiveEntity() {
        TownsteadTickContext.set(villager, 100L, OVERWORLD);

        assertEquals(null, TownsteadTickContext.currentEntityId());
    }

    /**
     * Recording is gated on a mixin having been applied.
     *
     * <p>Not an optimisation detail: {@code observe} is called for every villager on the server every
     * tick, and on an install with no Townstead there is no reader for any of it. The gate is what
     * makes "MCA: Crime behaves identically without Townstead" true of the hot path as well as of the
     * behaviour.
     */
    @Test
    void nothingIsRecordedWhileNoMixinIsApplied() {
        TownsteadMixinStatus.clear();

        TownsteadTickContext.observe(null);

        assertTrue(TownsteadTickContext.isEmpty());
        assertFalse(TownsteadMixinStatus.anyApplied());
    }
}
