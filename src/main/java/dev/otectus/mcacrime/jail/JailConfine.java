package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.CrimeDetector;
import dev.otectus.mcacrime.detect.WitnessResult;
import dev.otectus.mcacrime.ledger.SentenceResolutionService;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

/**
 * Soft-confinement and breakout handling (spec §7.3), called on the throttled (~1/s) tick while jailed.
 *
 * <ul>
 *   <li><b>CONTAINMENT / REINFORCED</b> — a prisoner who strays outside the region is teleported back
 *       (the default safety net that keeps a CONTAINMENT prisoner in even past ender pearls etc.).</li>
 *   <li><b>PHYSICAL</b> — leaving the region is a legitimate breakout: flag {@code escaped} (→ Legal Target)
 *       and commit a {@code jailbreak} crime (witnessed by the law). The player is NOT teleported back and
 *       sentence credit pauses until the prisoner returns or is recaptured.</li>
 *   <li><b>A breached cell door</b> — a generated holding cell has one intended way out: the padlock
 *       on its door. Once that lock has been picked, detached or turned, walking out is an escape in
 *       <em>every</em> mode, because the door was the containment and it has been defeated. The
 *       teleport-back of the containment modes is a guard against pearls and mined walls, not against
 *       a lock that is already open.</li>
 * </ul>
 *
 * <p>A built cell comes down the moment its prisoner escapes, exactly as it would on release, but with
 * none of what a release means: no event, no served sentence, no custody stand-down. The sentence is
 * kept, marked escaped, and only recapture or surrender resumes it -- never walking back onto the
 * patch of ground where the cell used to stand.
 *
 * All anchor/dimension resolution is fail-safe; a vanished dimension just disables confinement (the
 * captivity cap / login reconcile free the player), never a crash.
 */
public final class JailConfine {

    private JailConfine() {
    }

    /**
     * Whether leaving the jail region is an escape rather than something to teleport back from.
     *
     * <p>Pure, and the whole of the rule: PHYSICAL mode always says yes, and a breached door says yes
     * whatever the mode. Shared with the villager side, so a thief and a player walking out of the
     * same open door are treated the same.
     */
    public static boolean exitIsEscape(@Nullable JailContainmentMode mode, boolean breached) {
        return mode == JailContainmentMode.PHYSICAL || breached;
    }

    public static void tick(ServerPlayer player, PlayerCrimeData data) {
        JailState jail = data.getJail();
        if (jail == null || !jail.hasValidAnchor()) {
            return;
        }
        if (jail.isCuffEscape()) return; // Recapture/surrender, not proximity, resumes this sentence.
        MinecraftServer server = player.getServer();
        ServerLevel level = JailService.resolveLevel(server, jail.getJailDim());
        if (level == null) {
            return; // dimension gone — cap/reconcile handles release
        }
        HoldingCell cell = HoldingCellService.existingFor(server, player.getUUID());
        if (jail.isTemporaryCell() && cell == null) {
            // The cell this sentence was being served in has come down -- the prisoner escaped it.
            // What stands there now is ground, and standing on it is not serving; only a recapture
            // raises a new cell and resumes the term (JailService.recapture, ArrestService).
            return;
        }
        ResourceLocation posDim = player.level().dimension().location();
        boolean inRegion = JailRegion.contains(jail.getJailAnchor(), jail.getJailRadius(), jail.getJailDim(),
                player.blockPosition(), posDim);
        if (inRegion) {
            if (jail.isEscaped()) {
                jail.setEscaped(false);
                CrimeNetwork.sendSelfStatus(player);
            }
            return;
        }
        // Player is OUTSIDE the jail region.
        boolean breached = cell != null && server != null && cell.breached(CrimeWorldData.get(server));
        if (exitIsEscape(jail.getModeSnapshot(), breached)) {
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
                if (cell != null) {
                    // The cell is over, the sentence is not. No teleport: the prisoner is already
                    // outside, which is the one time restoring the cell around them is safe.
                    HoldingCellService.dismantle(server, player.getUUID());
                }
                CrimeNetwork.sendSelfStatus(player);
                player.sendSystemMessage(Component.translatable("mcacrime.jail.escaped"));
            }
            return; // legitimate escape; sentence credit pauses while outside
        }
        // CONTAINMENT / REINFORCED, and the door still holds: soft-confine back to the anchor.
        JailService.teleportToAnchor(player, jail);
    }
}
