package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.CrimeDetector;
import dev.otectus.mcacrime.detect.WitnessResult;
import dev.otectus.mcacrime.ledger.SentenceResolutionService;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Soft-confinement and breakout handling (spec §7.3), called on the throttled (~1/s) tick while jailed.
 *
 * <ul>
 *   <li><b>CONTAINMENT / REINFORCED</b> — a prisoner who strays outside the region is teleported back
 *       (the default safety net that keeps a CONTAINMENT prisoner in even past ender pearls etc.).</li>
 *   <li><b>PHYSICAL</b> — leaving the region is a legitimate breakout: flag {@code escaped} (→ Legal Target)
 *       and commit a {@code jailbreak} crime (witnessed by the law). The player is NOT teleported back and
 *       the sentence continues.</li>
 * </ul>
 *
 * All anchor/dimension resolution is fail-safe; a vanished dimension just disables confinement (the
 * captivity cap / login reconcile free the player), never a crash.
 */
public final class JailConfine {

    private JailConfine() {
    }

    public static void tick(ServerPlayer player, PlayerCrimeData data) {
        JailState jail = data.getJail();
        if (jail == null || !jail.hasValidAnchor()) {
            return;
        }
        ServerLevel level = JailService.resolveLevel(player.getServer(), jail.getJailDim());
        if (level == null) {
            return; // dimension gone — cap/reconcile handles release
        }
        ResourceLocation posDim = player.level().dimension().location();
        boolean inRegion = JailRegion.contains(jail.getJailAnchor(), jail.getJailRadius(), jail.getJailDim(),
                player.blockPosition(), posDim);
        if (inRegion) {
            return;
        }
        // Player is OUTSIDE the jail region.
        if (jail.getModeSnapshot() == JailContainmentMode.PHYSICAL) {
            if (!jail.isEscaped()) {
                jail.setEscaped(true); // becomes a Legal Target (escaped prisoner)
                if (player.level() instanceof ServerLevel here) {
                    // Mark the cases this sentence was being served for as ESCAPED. That is a status,
                    // not a resolution: an escaped case stays fully actionable and can still be fined
                    // or served later. What it buys is that the ledger can now say why it is open.
                    //
                    // This runs BEFORE the jailbreak charge is filed, and the order is load-bearing:
                    // the sweep takes every actionable case, so filing first would immediately mark
                    // the brand-new jailbreak charge as escaped by its own escape.
                    if (here.getServer() != null) {
                        SentenceResolutionService.markEscaped(here.getServer(), player.getUUID(),
                                jail.getSentenceId());
                    }
                    // Witnessed by the authority itself, with no villager named: nobody has to have
                    // seen a prisoner leave for the jail to know they are gone.
                    CrimeDetector.commitDirect(player, CrimeIds.JAILBREAK, null, here,
                            WitnessResult.official(), "jailbreak");
                }
                CrimeNetwork.sendSelfStatus(player);
            }
            return; // legitimate escape — do not teleport back; sentence continues
        }
        // CONTAINMENT / REINFORCED: soft-confine back to the anchor.
        JailService.teleportToAnchor(player, jail);
    }
}
