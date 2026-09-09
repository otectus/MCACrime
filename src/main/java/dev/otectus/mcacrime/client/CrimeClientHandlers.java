package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.ActionMenuKind;
import dev.otectus.mcacrime.network.BandBulkSyncS2CPacket;
import dev.otectus.mcacrime.network.BandSyncS2CPacket;
import dev.otectus.mcacrime.network.CaptiveStatusS2CPacket;
import dev.otectus.mcacrime.network.CaseLedgerS2CPacket;
import dev.otectus.mcacrime.network.CriminalJobSyncS2CPacket;
import dev.otectus.mcacrime.network.GuardChallengeS2CPacket;
import dev.otectus.mcacrime.network.RestraintBulkSyncS2CPacket;
import dev.otectus.mcacrime.enforcement.RestraintVisualState;
import dev.otectus.mcacrime.network.RestraintSyncS2CPacket;
import dev.otectus.mcacrime.network.SelfStatusS2CPacket;
import dev.otectus.mcacrime.network.WeaponPolicyS2CPacket;
import dev.otectus.mcacrime.network.ActionMenuS2CPacket;
import dev.otectus.mcacrime.network.ActionProgressS2CPacket;
import dev.otectus.mcacrime.client.screen.CaptiveActionScreen;
import dev.otectus.mcacrime.client.screen.CrimeInteractionScreen;
import dev.otectus.mcacrime.client.screen.GuardChallengeScreen;
import net.minecraft.client.Minecraft;

/**
 * The single client-side landing point for the mod's S2C packets, reached only via
 * {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)} so a dedicated server never classloads any
 * client code. Each method just updates a client cache; rendering reads those caches.
 */
public final class CrimeClientHandlers {

    private CrimeClientHandlers() {
    }

    public static void onSelfStatus(SelfStatusS2CPacket msg) {
        ClientSelfData.update(msg.karma(), msg.heat(), msg.band(), msg.wanted(),
                msg.jailRemainingTicks(), msg.legalTarget());
    }

    public static void onBandSync(BandSyncS2CPacket msg) {
        ClientBandData.put(msg.player(), msg.band());
    }

    public static void onBandBulk(BandBulkSyncS2CPacket msg) {
        ClientBandData.putAll(msg.bands());
    }

    public static void onCaptiveStatus(CaptiveStatusS2CPacket msg) {
        ClientCaptiveData.update(msg.captive(), msg.lawful(), msg.captor(), msg.capRemainingTicks());
    }

    /**
     * Opens the panel the server asked for. The kind decides the screen: a captive gets the panel with
     * the countdown and the captor's name, everybody else gets the plain action list.
     *
     * <p>{@code captiveScreenToggle} is honoured here rather than server-side, because it is a
     * presentation preference — a player who turns it off still has every captive action available
     * through the ordinary panel and through commands.
     */
    public static void onActionMenu(ActionMenuS2CPacket msg) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean captivePanel = msg.kind() == ActionMenuKind.CAPTIVE
                && McaCrimeConfig.CLIENT.captiveScreenToggle.get();
        minecraft.setScreen(captivePanel
                ? new CaptiveActionScreen(msg, minecraft.screen)
                : new CrimeInteractionScreen(msg, minecraft.screen));
    }

    public static void onActionProgress(ActionProgressS2CPacket msg) {
        ClientActionData.apply(msg);
    }

    /**
     * A guard challenge opening or closing.
     *
     * <p>Opening replaces whatever screen is up. That is deliberate and it is the one place this mod
     * takes the screen away from the player: a challenge is a timed demand from something standing in
     * front of them, and delivering it into a chat line they might be scrolled away from is how the
     * window quietly expires without them ever seeing it.
     */
    public static void onGuardChallenge(GuardChallengeS2CPacket msg) {
        GuardChallengeS2CPacket previous = ClientChallengeData.current();
        ClientChallengeData.update(msg);
        Minecraft minecraft = Minecraft.getInstance();
        if (msg.open()) {
            // Refresh the existing screen's controls for requotes and payment acknowledgments.
            boolean sameEncounter = previous != null && previous.open()
                    && previous.encounterId().equals(msg.encounterId());
            if (!sameEncounter || !(minecraft.screen instanceof GuardChallengeScreen)) {
                minecraft.setScreen(new GuardChallengeScreen());
            } else if (minecraft.screen instanceof GuardChallengeScreen screen) {
                screen.refreshOffer();
            }
        } else if (minecraft.screen instanceof GuardChallengeScreen) {
            minecraft.setScreen(null);
        }
    }

    public static void onCaseLedger(CaseLedgerS2CPacket msg) {
        ClientCaseData.update(msg);
    }

    public static void onRestraint(RestraintSyncS2CPacket msg) {
        ClientRestraintData.put(msg.subject(),
                new RestraintVisualState(msg.restrained(), msg.type(), msg.guardEntityId()));
    }

    public static void onRestraintBulk(RestraintBulkSyncS2CPacket msg) {
        ClientRestraintData.putAll(msg.restrained());
    }

    /** The server's weapon rules, so the Crime button gates on the same lists the server does. */
    public static void onWeaponPolicy(WeaponPolicyS2CPacket msg) {
        ClientWeaponPolicy.set(msg.policy());
    }

    public static void onCriminalJob(CriminalJobSyncS2CPacket msg) {
        ClientCriminalJobData.put(msg.villager(), msg.job());
    }
}
