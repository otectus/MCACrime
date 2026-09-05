package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.item.weapon.WeaponDetector;
import dev.otectus.mcacrime.item.weapon.WeaponPolicySnapshot;
import dev.otectus.mcacrime.item.weapon.WeaponRules;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * The server's weapon policy, as the client last heard it (0.5.1).
 *
 * <p>Display-only, like every other client cache here, and the reason it exists at all is that the
 * client's own COMMON config file is <em>its</em> file: on a multiplayer server it is not what the
 * gate is being evaluated against. The Crime button therefore asks this, never the local config, so
 * the greyed-out button and the server's rejection always mean the same thing.
 *
 * <p>Until the login packet arrives there is no policy and nothing qualifies. That is the honest
 * answer rather than a guess -- a button briefly disabled is a click that does nothing, while a
 * button wrongly enabled is a crime the server silently refuses.
 */
public final class ClientWeaponPolicy {

    @Nullable
    private static volatile WeaponPolicySnapshot policy;
    @Nullable
    private static volatile WeaponRules rules;

    private ClientWeaponPolicy() {
    }

    /** Replaces the policy and recompiles the rules once, rather than on every button repaint. */
    public static void set(@Nullable WeaponPolicySnapshot snapshot) {
        policy = snapshot;
        rules = snapshot == null ? null : snapshot.toRules();
    }

    /** Dropped on logout and on disconnect: the next server may gate on entirely different lists. */
    public static void clear() {
        policy = null;
        rules = null;
    }

    /** Whether the off-hand counts, so the tooltip can say which hand the weapon has to be in. */
    public static boolean allowOffHand() {
        WeaponPolicySnapshot current = policy;
        return current != null && current.allowOffHand();
    }

    /**
     * Whether the server gates the Crime menu on a drawn weapon at all.
     *
     * <p>True until told otherwise: an unknown policy is treated as the stricter one, so the worst
     * case is a button that enables a moment late rather than one that promises what the server refuses.
     */
    public static boolean crimeMenuRequiresWeapon() {
        WeaponPolicySnapshot current = policy;
        return current == null || current.requireWeaponForCrimeMenu();
    }

    /**
     * The client's read of {@code WeaponDetector.drawnWeapon} against the synced rules: main hand
     * first, off-hand only when the server said it counts.
     *
     * <p>A server with {@code requireWeaponForCrimeMenu} off answers yes regardless of what is in
     * either hand. That is not a lie about the player's hands, it is the honest answer to the only
     * question the caller is asking -- whether the weapon gate stands between them and the menu.
     */
    public static boolean hasQualifyingDrawnWeapon(@Nullable Player player) {
        if (!crimeMenuRequiresWeapon()) {
            return true;
        }
        WeaponRules current = rules;
        if (player == null || current == null) {
            return false;
        }
        ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (WeaponDetector.classify(main, current).isWeapon()) {
            return true;
        }
        if (!allowOffHand()) {
            return false;
        }
        ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
        return WeaponDetector.classify(off, current).isWeapon();
    }
}
