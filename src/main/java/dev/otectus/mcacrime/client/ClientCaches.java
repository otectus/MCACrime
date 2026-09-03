package dev.otectus.mcacrime.client;

import java.util.List;

/**
 * Every static client cache, in one list.
 *
 * <p>The logout handler used to name each cache itself, which made adding an eighth cache a change in
 * two places with nothing connecting them: the cache that nobody remembered to clear kept the previous
 * server's data and showed it on the next one. The list below is the single place that knows, and
 * {@code ClientCachesTest} fails the build if a cache under {@code client/} declares a {@code clear()}
 * and is not in it.
 *
 * <p>Order is the order a player would notice: their own standing first, then what is being done to
 * them, then the screens' own data.
 *
 * @see CrimeClientSetup#onLoggedOut which is the only caller
 */
public final class ClientCaches {

    /** The clear action of every client cache. */
    public static final List<Runnable> ALL = List.of(
            ClientSelfData::clear,
            ClientBandData::clear,
            ClientCaptiveData::clear,
            ClientActionData::clear,
            ClientChallengeData::clear,
            ClientCaseData::clear,
            ClientRestraintData::clear);

    private ClientCaches() {
    }

    /** Empties every cache in {@link #ALL}. */
    public static void clearAll() {
        for (Runnable clear : ALL) {
            clear.run();
        }
    }
}
