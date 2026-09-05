package dev.otectus.mcacrime;

import dev.otectus.mcacrime.action.CrimeActor;
import dev.otectus.mcacrime.action.EconomicCrimeActor;
import dev.otectus.mcacrime.action.InventoryCrimeActor;
import dev.otectus.mcacrime.action.PlayerActor;
import dev.otectus.mcacrime.action.VillagerCrimeActor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each actor is, as the type system sees it.
 *
 * <p>No entity is constructed: the point of splitting the capabilities into interfaces was to make
 * "a villager has money but no inventory" a compile-time fact rather than a null check, and that fact
 * is exactly what can be asserted without a server. A regression here — a villager that claims an
 * inventory, or an economic interface a player stops implementing — is the one that would otherwise
 * surface as a {@code ClassCastException} in the middle of a theft.
 */
class VillagerCrimeActorContractTest {

    @Test
    void aVillagerHasMoneyButNoInventory() {
        assertTrue(EconomicCrimeActor.class.isAssignableFrom(VillagerCrimeActor.class),
                "a villager's purse is what bounds NPC theft; without it the thief steals from nowhere");
        assertFalse(InventoryCrimeActor.class.isAssignableFrom(VillagerCrimeActor.class),
                "an MCA villager has no player inventory, and pretending otherwise is a crash");
    }

    @Test
    void aPlayerHasBoth() {
        assertTrue(EconomicCrimeActor.class.isAssignableFrom(PlayerActor.class));
        assertTrue(InventoryCrimeActor.class.isAssignableFrom(PlayerActor.class));
    }

    @Test
    void bothCapabilitiesAreStillCrimeActors() {
        assertTrue(CrimeActor.class.isAssignableFrom(EconomicCrimeActor.class));
        assertTrue(CrimeActor.class.isAssignableFrom(InventoryCrimeActor.class));
    }

    @Test
    void theFactoryRefusesWhatIsNotAnMcaVillager() {
        assertTrue(VillagerCrimeActor.of(null).isEmpty(),
                "the factory is the only way in, so it is the only place the check can live");
    }
}
