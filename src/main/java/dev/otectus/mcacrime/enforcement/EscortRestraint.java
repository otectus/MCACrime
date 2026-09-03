package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The per-tick half of the escort: the lead itself.
 *
 * <p>A restrained player is not merely slowed. Past a soft radius they are pulled back toward the
 * guard holding them, gently and with rising force, so walking away from an arrest feels like being
 * held rather than like hitting an invisible wall. The hard radius — {@code escortTetherBlocks} — is
 * still a real exit: a player determined enough to break out of the lead has decided to resist, and
 * {@code EscortService} treats that as the decision it is rather than dragging them back forever.
 *
 * <p>Driven from {@code CrimeDecayHandler}'s per-player pump rather than from a ticker of its own,
 * matching every other periodic job in this mod.
 */
public final class EscortRestraint {

    /** Hardest pull applied, in blocks per tick added to the player's own motion. */
    private static final double MAX_PULL = 0.28;

    private EscortRestraint() {
    }

    /**
     * How hard to pull, as a speed in blocks per tick toward the guard.
     *
     * <p>Pure. Zero inside the soft radius, so ordinary movement around the guard is untouched and the
     * player never fights the server for control; ramping linearly from there to a cap at the hard
     * radius, so the lead tightens rather than snapping.
     */
    public static double pullStrength(double distanceSqr, double softSqr, double hardSqr) {
        if (distanceSqr <= softSqr) {
            return 0.0;
        }
        if (hardSqr <= softSqr) {
            return MAX_PULL;
        }
        double progress = (distanceSqr - softSqr) / (hardSqr - softSqr);
        return MAX_PULL * Math.max(0.0, Math.min(1.0, progress));
    }

    /** One tick of restraint for one player. Called only while the phase says they are restrained. */
    public static void tick(ServerPlayer player, PlayerCrimeData data) {
        if (McaCrimeConfig.COMMON.restrainedPlayerRestrictions.get()) {
            player.setSprinting(false);
        }
        ArrestState state = data.getArrest();
        if (state == null || state.getGuard() == null || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        Entity guard = level.getEntity(state.getGuard());
        if (guard == null || !guard.isAlive()) {
            // The escort scan owns reassignment; doing nothing for a tick is correct here.
            return;
        }

        double soft = McaCrimeConfig.COMMON.escortLeashBlocks.get();
        double hard = McaCrimeConfig.COMMON.escortTetherBlocks.get();
        double distanceSqr = player.distanceToSqr(guard);
        double strength = pullStrength(distanceSqr, soft * soft, hard * hard);
        if (strength <= 0.0) {
            return;
        }

        Vec3 toGuard = guard.position().subtract(player.position());
        if (toGuard.lengthSqr() < 1.0E-4) {
            return;
        }
        // Horizontal only. A pull with a vertical component could lift a prisoner out of a hole or
        // press them into the floor, and neither is what a lead does.
        Vec3 pull = new Vec3(toGuard.x, 0.0, toGuard.z);
        if (pull.lengthSqr() < 1.0E-4) {
            return;
        }
        player.setDeltaMovement(player.getDeltaMovement().add(pull.normalize().scale(strength)));
        // Load-bearing. Without it the server's velocity change is never sent to the owning client and
        // the client's own prediction simply wins, so the player walks away through a pull that the
        // server believes it applied.
        player.hurtMarked = true;
    }
}
