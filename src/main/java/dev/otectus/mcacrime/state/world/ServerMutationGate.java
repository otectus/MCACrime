package dev.otectus.mcacrime.state.world;

import net.minecraft.server.MinecraftServer;

/**
 * The one question every write-side entry point asks first: may this server change crime state at all?
 *
 * <p>Two conditions close the gate, and they are different failures with the same correct response.
 * A store written by a newer jar was never parsed, so anything this build wrote over it would delete
 * structures it does not understand. A store whose load threw was parsed and came back wrong, so
 * anything written from it would persist a half-read world as if it were the truth. In both cases the
 * file is carried through verbatim and the session runs read-only.
 *
 * <p>{@link CrimeWorldData} already refuses individual mutations in that state, and that is the
 * backstop rather than the mechanism: a refusal deep inside a setter happens <em>after</em> a guard
 * has taken an item, an arrest has teleported somebody, or a fine has charged a player. Asking here,
 * before the operation begins, is what makes the refusal free.
 */
public final class ServerMutationGate {

    private ServerMutationGate() {
    }

    /** False when this server's crime store is read-only. A null server is never allowed to mutate. */
    public static boolean allows(MinecraftServer server) {
        return server != null && allows(CrimeWorldData.get(server));
    }

    /** The same question against a store directly, so the rule is testable without a server. */
    public static boolean allows(CrimeWorldData data) {
        return data != null && !data.isReadOnlyFutureData() && !data.isLoadFailed();
    }
}
