package dev.otectus.mcacrime;

import dev.otectus.mcacrime.bounty.BountyService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who gets paid for a death, and — much more importantly — who does not.
 *
 * <p>Every empty answer here is one of the spec's anti-farm rules, and each of them is a way a server
 * could otherwise be drained: a lava pit that pays, an alt account that pays, or a machine that pays.
 */
class KillAttributionTest {

    private static final UUID VICTIM = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID HUNTER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID ARROW = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final UUID WOLF = UUID.fromString("00000000-0000-0000-0000-0000000000dd");

    private static BountyService.Attacker player(UUID id) {
        return new BountyService.Attacker(id, true, false);
    }

    private static BountyService.Attacker mob(UUID id) {
        return new BountyService.Attacker(id, false, false);
    }

    @Test
    void aPlayerWhoStruckTheBlowIsPaid() {
        assertEquals(Optional.of(HUNTER), BountyService.attribute(
                new BountyService.DamageSourceView(VICTIM, player(HUNTER), null)));
    }

    @Test
    void anEnvironmentalDeathPaysNobody() {
        // Lava, fall damage, suffocation: nothing is responsible, so nothing is owed.
        assertTrue(BountyService.attribute(
                new BountyService.DamageSourceView(VICTIM, null, null)).isEmpty());
    }

    @Test
    void suicidePaysNothingHoweverItIsArranged() {
        assertTrue(BountyService.attribute(
                new BountyService.DamageSourceView(VICTIM, player(VICTIM), null)).isEmpty());
        assertTrue(BountyService.attribute(
                new BountyService.DamageSourceView(VICTIM, mob(ARROW), player(VICTIM))).isEmpty(),
                "shooting yourself is still shooting yourself");
    }

    @Test
    void aFakePlayerIsAMachineAndIsNotPaid() {
        assertTrue(BountyService.attribute(new BountyService.DamageSourceView(VICTIM,
                new BountyService.Attacker(HUNTER, true, true), null)).isEmpty());
        assertTrue(BountyService.attribute(new BountyService.DamageSourceView(VICTIM, mob(ARROW),
                new BountyService.Attacker(HUNTER, true, true))).isEmpty(),
                "an automated turret does not become a bounty hunter by using an arrow");
    }

    @Test
    void aProjectilePaysTheShooter() {
        assertEquals(Optional.of(HUNTER), BountyService.attribute(
                new BountyService.DamageSourceView(VICTIM, mob(ARROW), player(HUNTER))));
    }

    @Test
    void aTamedPetPaysItsOwner() {
        assertEquals(Optional.of(HUNTER), BountyService.attribute(
                new BountyService.DamageSourceView(VICTIM, mob(WOLF), player(HUNTER))));
    }

    @Test
    void anOwnerlessMobPaysNobody() {
        assertTrue(BountyService.attribute(
                new BountyService.DamageSourceView(VICTIM, mob(WOLF), null)).isEmpty());
        assertTrue(BountyService.attribute(
                new BountyService.DamageSourceView(VICTIM, mob(WOLF), mob(HUNTER))).isEmpty(),
                "an owner that is not a player is not a claimant");
    }

    @Test
    void aPlayerAttackerIsNeverOverriddenByAnOwnerLookup() {
        // A hunter holding somebody else's tamed wolf's leash is still the one who swung the sword.
        assertEquals(Optional.of(HUNTER), BountyService.attribute(
                new BountyService.DamageSourceView(VICTIM, player(HUNTER), player(WOLF))));
    }

    @Test
    void aNullViewIsSafe() {
        assertTrue(BountyService.attribute(null).isEmpty());
    }
}
