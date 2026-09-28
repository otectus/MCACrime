package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.integration.CrimeIntegrationPump;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCA: Reputation capability handshake follows the companion through a session.
 *
 * <p>Until 0.7.5 it was asked once, at server start, and cached for the life of the server. MCA:
 * Reputation advertises its profile features only while profiles are switched on and a pack has
 * published content, and both can change mid-session ({@code /reload}, a config reload), so a
 * profiled delivery kept the stale answer until a restart. These tests pin the refresh rules with a
 * fake companion and a controllable clock; the pump's reload hook is checked by its signature, since
 * firing a real datapack sync needs a running server.
 */
class ReputationBridgeNegotiateTest {

    private final AtomicInteger asked = new AtomicInteger();
    private volatile ReputationCapabilitySnapshot answer = snapshot(false);
    private long now;
    private LongSupplier savedClock;

    @BeforeEach
    void installFakeCompanion() {
        ReputationBridge.reset();
        savedClock = ReputationBridge.clock;
        ReputationBridge.clock = () -> now;
        ReputationBridge.setOps(fakeCompanion());
    }

    @AfterEach
    void restore() {
        ReputationBridge.reset();
        ReputationBridge.clock = savedClock;
    }

    @Test
    void negotiatingAgainWithTheSameAnswerChangesNothing() {
        ReputationBridge.negotiate(null);
        ReputationCapabilitySnapshot first = ReputationBridge.capabilities();
        ReputationBridge.negotiate(null);

        assertEquals(first, ReputationBridge.capabilities());
        assertEquals(2, asked.get(), "each negotiation asks the companion once");
    }

    @Test
    void profilesGoingLiveOrDarkMidSessionAreSeenOnTheNextNegotiation() {
        ReputationBridge.negotiate(null);
        assertFalse(ReputationBridge.capabilities().supportsProfiledDelivery());

        answer = snapshot(true); // a /reload published profile content
        ReputationBridge.negotiate(null);
        assertTrue(ReputationBridge.capabilities().supportsProfiledDelivery());

        answer = snapshot(false); // and profiles were switched off again
        ReputationBridge.negotiate(null);
        assertFalse(ReputationBridge.capabilities().supportsProfiledDelivery(),
                "a withdrawn profile feature must stop being used, not linger until a restart");
    }

    @Test
    void aStaleAnswerIsRefreshedBeforeUseAndAFreshOneIsNot() {
        now = 0L;
        ReputationBridge.negotiate(null);
        answer = snapshot(true); // MCA: Reputation's config was reloaded; nothing told us

        now = ReputationBridge.REFRESH_INTERVAL_NANOS - 1L;
        ReputationBridge.refreshIfStale(null);
        assertEquals(1, asked.get(), "a fresh answer is used as it is");
        assertFalse(ReputationBridge.capabilities().supportsProfiledDelivery());

        now = ReputationBridge.REFRESH_INTERVAL_NANOS;
        ReputationBridge.refreshIfStale(null);
        assertEquals(2, asked.get());
        assertTrue(ReputationBridge.capabilities().supportsProfiledDelivery());
    }

    @Test
    void anAnswerNeverAskedForIsAskedOnFirstUse() {
        ReputationBridge.refreshIfStale(null);
        assertEquals(1, asked.get());
        assertTrue(ReputationBridge.capabilities().supportsDelivery());
    }

    @Test
    void withNoCompanionTheAnswerIsAbsent() {
        ReputationBridge.setOps(null);
        ReputationBridge.negotiate(null);
        assertEquals(ReputationCapabilitySnapshot.absent(), ReputationBridge.capabilities());
        assertEquals(0, asked.get());
    }

    @Test
    void theOutboxPumpNegotiatesAgainWhenAReloadFinishes() throws Exception {
        Method hook = CrimeIntegrationPump.class.getMethod("onDatapackSync", OnDatapackSyncEvent.class);
        assertNotNull(hook.getAnnotation(SubscribeEvent.class),
                "the reload hook must be a FORGE-bus subscriber, or /reload never reaches the handshake");
    }

    private ReputationOps fakeCompanion() {
        return (ReputationOps) Proxy.newProxyInstance(ReputationOps.class.getClassLoader(),
                new Class<?>[] {ReputationOps.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "capabilities" -> {
                        asked.incrementAndGet();
                        yield answer;
                    }
                    case "apiVersion" -> ReputationBridge.REQUIRED_API_VERSION;
                    case "acceptsWrites", "holdsAuthority", "claimAuthority" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "fake MCA: Reputation";
                    default -> null;
                });
    }

    /** What an MCA: Reputation 0.6.1 advertises, with or without its profile layer live. */
    private static ReputationCapabilitySnapshot snapshot(boolean profilesLive) {
        Set<String> features = new LinkedHashSet<>(List.of(
                ReputationCapabilitySnapshot.FEATURE_SUPERSEDE, ReputationCapabilitySnapshot.FEATURE_RECEIPTS,
                ReputationCapabilitySnapshot.FEATURE_DELIVERY, ReputationCapabilitySnapshot.FEATURE_READ_ONLY_LOOKUP,
                ReputationCapabilitySnapshot.FEATURE_BOUND_RESOLUTION,
                ReputationCapabilitySnapshot.FEATURE_INCIDENT_EXEMPTIONS));
        if (profilesLive) {
            features.addAll(ReputationCapabilitySnapshot.PROFILE_FEATURES);
        }
        return new ReputationCapabilitySnapshot(1, true, features, Set.of("MCA_VILLAGER_ASSAULT"), "");
    }
}
