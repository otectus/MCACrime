package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ai.ArmedResolver;
import dev.otectus.mcacrime.ai.ArmedResolver.ArmedStatus;
import dev.otectus.mcacrime.ai.ArmedResolver.WeaponSource;
import dev.otectus.mcacrime.item.weapon.DrawnWeapon;
import dev.otectus.mcacrime.item.weapon.WeaponClass;
import dev.otectus.mcacrime.item.weapon.WeaponMatch;
import net.minecraft.world.InteractionHand;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one thing {@code ArmedResolver} is: a precedence order.
 *
 * <p>Everything asserted here is about which rule <em>wins</em>, because that is the only place the
 * class can be wrong in a way that matters in play. A guard made compliant by an empty hand and a
 * farmer made a combatant by a datapack tag they should have lost to are the two failure modes.
 */
class ArmedResolverTest {

    /** A held weapon in one hand. The stack itself is never inspected by the pure core. */
    private static Optional<DrawnWeapon> held(InteractionHand hand) {
        return Optional.of(new DrawnWeapon(hand, null, new WeaponMatch(WeaponClass.MELEE, "test")));
    }

    private static final Optional<DrawnWeapon> EMPTY_HANDS = Optional.empty();

    @Test
    void neverResistsBeatsEverything() {
        // Every other input says "armed" at once. The datapack override still has to win, or a pack
        // has no way to make a specific NPC safe to threaten.
        ArmedStatus status = ArmedResolver.evaluate(true, true, true, held(InteractionHand.MAIN_HAND), true);
        assertFalse(status.armed());
        assertEquals(WeaponSource.NONE, status.source());
    }

    @Test
    void alwaysResistsTagBeatsEmptyHands() {
        ArmedStatus status = ArmedResolver.evaluate(false, true, false, EMPTY_HANDS, false);
        assertTrue(status.armed());
        assertEquals(WeaponSource.EXEMPT_TAG, status.source());
    }

    @Test
    void lawRoleBeatsTheWeaponInTheHand() {
        // A guard is a guard whether or not the sword is out; the source has to say ROLE, because
        // taking the sword away must not turn the answer over.
        ArmedStatus status = ArmedResolver.evaluate(false, false, true, held(InteractionHand.MAIN_HAND), false);
        assertTrue(status.armed());
        assertEquals(WeaponSource.ROLE, status.source());
    }

    @Test
    void lawRoleIsArmedWithEmptyHands() {
        assertEquals(WeaponSource.ROLE, ArmedResolver.evaluate(false, false, true, EMPTY_HANDS, false).source());
    }

    @Test
    void mainHandWeaponArmsACivilian() {
        ArmedStatus status = ArmedResolver.evaluate(false, false, false, held(InteractionHand.MAIN_HAND), false);
        assertTrue(status.armed());
        assertEquals(WeaponSource.MAIN_HAND, status.source());
        assertEquals(InteractionHand.MAIN_HAND, status.hand());
    }

    @Test
    void offHandIsHonouredOnlyWhenTheDetectorSuppliedIt() {
        // The off-hand key is applied upstream, by WeaponDetector.drawnWeapon: with it off, the
        // off-hand weapon never reaches this method at all. Both halves of that are asserted here so
        // the two cannot drift into disagreeing about whose job the setting is.
        ArmedStatus allowed = ArmedResolver.evaluate(false, false, false, held(InteractionHand.OFF_HAND), false);
        assertEquals(WeaponSource.OFF_HAND, allowed.source());
        assertEquals(InteractionHand.OFF_HAND, allowed.hand());

        ArmedStatus disallowed = ArmedResolver.evaluate(false, false, false, EMPTY_HANDS, false);
        assertFalse(disallowed.armed());
    }

    @Test
    void combatantTagIsTheLastRuleBeforeUnarmed() {
        assertEquals(WeaponSource.COMBATANT_TAG,
                ArmedResolver.evaluate(false, false, false, EMPTY_HANDS, true).source());
        // ...and loses to a real weapon, which names the hand the threat is being made with.
        assertEquals(WeaponSource.MAIN_HAND,
                ArmedResolver.evaluate(false, false, false, held(InteractionHand.MAIN_HAND), true).source());
    }

    @Test
    void anEmptyHandedCivilianIsUnarmed() {
        ArmedStatus status = ArmedResolver.evaluate(false, false, false, EMPTY_HANDS, false);
        assertFalse(status.armed());
        assertEquals(WeaponSource.NONE, status.source());
        assertEquals(null, status.hand());
    }
}
