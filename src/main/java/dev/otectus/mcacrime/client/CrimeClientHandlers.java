package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.network.BandBulkSyncS2CPacket;
import dev.otectus.mcacrime.network.BandSyncS2CPacket;
import dev.otectus.mcacrime.network.SelfStatusS2CPacket;

/**
 * The single client-side landing point for the mod's S2C packets, reached only via
 * {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)} so a dedicated server never classloads any
 * client code. Each method just updates a client cache; rendering reads those caches.
 */
public final class CrimeClientHandlers {

    private CrimeClientHandlers() {
    }

    public static void onSelfStatus(SelfStatusS2CPacket msg) {
        ClientSelfData.update(msg.karma(), msg.heat(), msg.band(), msg.wanted());
    }

    public static void onBandSync(BandSyncS2CPacket msg) {
        ClientBandData.put(msg.player(), msg.band());
    }

    public static void onBandBulk(BandBulkSyncS2CPacket msg) {
        ClientBandData.putAll(msg.bands());
    }
}
