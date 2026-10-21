package dev.otectus.mcacrime.network;

import java.util.OptionalInt;

/**
 * The server settings a tooltip may quote (0.8.0), as the server last sent them in
 * {@link ServerRulesS2CPacket}. Common code, so an item's tooltip can read it without naming a client
 * class; only the client's packet handler writes it.
 *
 * <p>Empty until the server has said, and cleared on disconnect, so a tooltip never presents a client
 * default or another server's value as this server's rule.
 */
public final class ServerRulesView {

    private static volatile ServerRulesS2CPacket rules;

    private ServerRulesView() {
    }

    public static void set(ServerRulesS2CPacket received) {
        rules = received;
    }

    public static void clear() {
        rules = null;
    }

    /** The server's key ring capacity, or empty when the server has not said. */
    public static OptionalInt maxKeysPerRing() {
        ServerRulesS2CPacket current = rules;
        return current == null ? OptionalInt.empty() : OptionalInt.of(current.maxKeysPerRing());
    }
}
