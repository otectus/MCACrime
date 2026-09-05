package dev.otectus.mcacrime.network;

import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Objects;

/**
 * The one seam between the common registrar and the client (spec §9.4).
 *
 * <p>There is no client-only payload registration event in 1.21.1, so {@link CrimeNetwork} registers
 * the ten server→client payloads from common code — code a dedicated server runs. Naming
 * {@code CrimeClientHandlers} there would put a client class in the registrar's constant pool and
 * blow the server up at class-load time, which is exactly what {@code DedicatedServerIsolationTest}
 * checks for. So the registrar names this class instead, this class names nothing but payloads, and
 * the client entrypoint installs the implementation that does know about {@code Minecraft}.
 *
 * <p>The default handler does nothing, so a dedicated server that somehow receives one of these
 * drops it rather than failing.
 */
public final class CrimeClientPayloadRouter {

    /** Volatile: written once on the client thread that runs the mod constructor, read on the network side. */
    private static volatile Handler handler = Handler.NOOP;

    private CrimeClientPayloadRouter() {
    }

    /** Installed by the client entrypoint before any play payload can arrive. */
    public static void install(Handler clientHandler) {
        handler = Objects.requireNonNull(clientHandler, "clientHandler");
    }

    public static void handleSelfStatus(SelfStatusS2CPacket payload, IPayloadContext context) {
        handler.onSelfStatus(payload);
    }

    public static void handleBandSync(BandSyncS2CPacket payload, IPayloadContext context) {
        handler.onBandSync(payload);
    }

    public static void handleBandBulkSync(BandBulkSyncS2CPacket payload, IPayloadContext context) {
        handler.onBandBulkSync(payload);
    }

    public static void handleCaptiveStatus(CaptiveStatusS2CPacket payload, IPayloadContext context) {
        handler.onCaptiveStatus(payload);
    }

    public static void handleActionMenu(ActionMenuS2CPacket payload, IPayloadContext context) {
        handler.onActionMenu(payload);
    }

    public static void handleActionProgress(ActionProgressS2CPacket payload, IPayloadContext context) {
        handler.onActionProgress(payload);
    }

    public static void handleGuardChallenge(GuardChallengeS2CPacket payload, IPayloadContext context) {
        handler.onGuardChallenge(payload);
    }

    public static void handleCaseLedger(CaseLedgerS2CPacket payload, IPayloadContext context) {
        handler.onCaseLedger(payload);
    }

    public static void handleRestraintSync(RestraintSyncS2CPacket payload, IPayloadContext context) {
        handler.onRestraintSync(payload);
    }

    public static void handleRestraintBulkSync(RestraintBulkSyncS2CPacket payload, IPayloadContext context) {
        handler.onRestraintBulkSync(payload);
    }

    public static void handleWeaponPolicy(WeaponPolicyS2CPacket payload, IPayloadContext context) {
        handler.onWeaponPolicy(payload);
    }

    public static void handleCriminalJob(CriminalJobSyncS2CPacket payload, IPayloadContext context) {
        handler.onCriminalJob(payload);
    }

    /** What the client side supplies. Every method defaults to doing nothing, which is what a server does. */
    public interface Handler {

        Handler NOOP = new Handler() {
        };

        default void onSelfStatus(SelfStatusS2CPacket payload) {
        }

        default void onBandSync(BandSyncS2CPacket payload) {
        }

        default void onBandBulkSync(BandBulkSyncS2CPacket payload) {
        }

        default void onCaptiveStatus(CaptiveStatusS2CPacket payload) {
        }

        default void onActionMenu(ActionMenuS2CPacket payload) {
        }

        default void onActionProgress(ActionProgressS2CPacket payload) {
        }

        default void onGuardChallenge(GuardChallengeS2CPacket payload) {
        }

        default void onCaseLedger(CaseLedgerS2CPacket payload) {
        }

        default void onRestraintSync(RestraintSyncS2CPacket payload) {
        }

        default void onRestraintBulkSync(RestraintBulkSyncS2CPacket payload) {
        }

        default void onWeaponPolicy(WeaponPolicyS2CPacket payload) {
        }

        default void onCriminalJob(CriminalJobSyncS2CPacket payload) {
        }
    }
}
