package dev.otectus.mcacrime.property;

import dev.otectus.mcacrime.facility.TownsteadBuildingRef;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The permission table, row by row, and the one rule that outranks all of them.
 *
 * <p>{@code UNKNOWN} never charges anyone. §10.1 says an unknown or disputed owner does not authorise
 * a theft charge and §10.2 says an unsupported source is reported rather than charged, and both of
 * those are one property of this class: no combination of inputs may produce a chargeable decision
 * unless a real policy denied a real, identified actor.
 */
class PropertyAccessTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final BlockPos CHEST = new BlockPos(10, 64, 10);
    private static final int VILLAGE = 3;

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static PropertyPolicy policy(PropertyAccessRule rule, PropertyOwnerKind ownerKind,
                                         @Nullable UUID ownerId) {
        return PropertyPolicy.container(OVERWORLD, CHEST,
                new TownsteadBuildingRef(OVERWORLD, VILLAGE, 7, 12), ownerKind, ownerId, rule, false,
                PropertySource.MANUAL, "operator", 100L);
    }

    private static PropertyActor resident() {
        return PropertyActor.villager(STRANGER, VILLAGE, true, null);
    }

    private static PropertyActor outsider() {
        return PropertyActor.villager(STRANGER, 9, true, null);
    }

    private static PropertyActor worker() {
        return PropertyActor.villager(STRANGER, VILLAGE, true, "worker");
    }

    private static PropertyActor player() {
        return PropertyActor.player(STRANGER, PropertyActor.NO_VILLAGE, false);
    }

    // --- the rule matrix --------------------------------------------------------------------------

    @Test
    void publicPropertyIsTakeableByAnybody() {
        PropertyPolicy policy = policy(PropertyAccessRule.PUBLIC, PropertyOwnerKind.VILLAGE, null);

        assertTrue(PropertyAccess.decide(policy, player(), PropertyAccess.Operation.TAKE).allowed());
        assertTrue(PropertyAccess.decide(policy, outsider(), PropertyAccess.Operation.TAKE).allowed());
    }

    @Test
    void residentsRuleAdmitsResidentsAndRefusesEverybodyElse() {
        PropertyPolicy policy = policy(PropertyAccessRule.RESIDENTS, PropertyOwnerKind.VILLAGE, null);

        assertTrue(PropertyAccess.decide(policy, resident(), PropertyAccess.Operation.TAKE).allowed());
        assertTrue(PropertyAccess.decide(policy, outsider(), PropertyAccess.Operation.TAKE).chargeable());
        // A player is never recorded anywhere as living in a village, so they are never a resident.
        assertTrue(PropertyAccess.decide(policy, player(), PropertyAccess.Operation.TAKE).chargeable());
    }

    @Test
    void workersRuleAdmitsOnlyAVillagerActuallyWorkingForThatSettlement() {
        PropertyPolicy policy = policy(PropertyAccessRule.WORKERS, PropertyOwnerKind.VILLAGE, null);

        assertTrue(PropertyAccess.decide(policy, worker(), PropertyAccess.Operation.TAKE).allowed());
        assertTrue(PropertyAccess.decide(policy, resident(), PropertyAccess.Operation.TAKE).chargeable(),
                "living there is not working there");
        assertTrue(PropertyAccess.decide(policy,
                PropertyActor.villager(STRANGER, 9, true, "worker"), PropertyAccess.Operation.TAKE)
                .chargeable(), "working for another settlement is not working for this one");
    }

    @Test
    void ownerOnlyAdmitsTheNamedOwnerAndNobodyElse() {
        PropertyPolicy policy = policy(PropertyAccessRule.OWNER_ONLY, PropertyOwnerKind.PLAYER, OWNER);

        assertTrue(PropertyAccess.decide(policy,
                PropertyActor.player(OWNER, PropertyActor.NO_VILLAGE, false),
                PropertyAccess.Operation.TAKE).allowed());
        assertTrue(PropertyAccess.decide(policy, player(), PropertyAccess.Operation.TAKE).chargeable());
        // A villager whose UUID happens to equal the owning player's is still not that player.
        assertTrue(PropertyAccess.decide(policy,
                PropertyActor.villager(OWNER, VILLAGE, true, null), PropertyAccess.Operation.TAKE)
                .chargeable());
    }

    @Test
    void aFacilityOwnerMatchesNobodyAtAll() {
        PropertyPolicy policy = policy(PropertyAccessRule.OWNER_ONLY, PropertyOwnerKind.FACILITY,
                UUID.randomUUID());

        assertTrue(PropertyAccess.decide(policy, worker(), PropertyAccess.Operation.TAKE).chargeable(),
                "evidence storage belongs to the law rather than to a person");
        assertTrue(PropertyAccess.decide(policy, player(), PropertyAccess.Operation.TAKE).chargeable());
    }

    @Test
    void forbiddenRefusesEvenTheOwningSettlementsOwnWorkers() {
        PropertyPolicy policy = policy(PropertyAccessRule.FORBIDDEN, PropertyOwnerKind.VILLAGE, null);

        assertTrue(PropertyAccess.decide(policy, worker(), PropertyAccess.Operation.TAKE).chargeable());
        assertTrue(PropertyAccess.decide(policy, resident(), PropertyAccess.Operation.TAKE).chargeable());
    }

    // --- operations -------------------------------------------------------------------------------

    @Test
    void openingIsNeverARestrictedOperationExceptOnASealedContainer() {
        assertTrue(PropertyAccess.decide(policy(PropertyAccessRule.OWNER_ONLY, PropertyOwnerKind.PLAYER,
                OWNER), player(), PropertyAccess.Operation.OPEN).allowed(),
                "§10.1: opening or inspecting a container is not completed theft");
        assertTrue(PropertyAccess.decide(policy(PropertyAccessRule.FORBIDDEN, PropertyOwnerKind.VILLAGE,
                null), player(), PropertyAccess.Operation.OPEN).chargeable(),
                "a sealed container refuses the open too -- but a TAKE is what an incident is built on");
    }

    @Test
    void depositingIsAlwaysPermittedShortOfASealedContainer() {
        assertTrue(PropertyAccess.decide(policy(PropertyAccessRule.OWNER_ONLY, PropertyOwnerKind.PLAYER,
                OWNER), player(), PropertyAccess.Operation.PUT).allowed(),
                "§10.1: deposits, deliveries, restitution and permitted care are legitimate");
        assertTrue(PropertyAccess.decide(policy(PropertyAccessRule.RESIDENTS, PropertyOwnerKind.VILLAGE,
                null), outsider(), PropertyAccess.Operation.PUT).allowed());
    }

    // --- the rule that outranks the table ---------------------------------------------------------

    @Test
    void noPolicyIsUnknownRatherThanDenied() {
        PropertyAccess.Decision decision =
                PropertyAccess.decide(null, player(), PropertyAccess.Operation.TAKE);

        assertTrue(decision.unknown());
        assertFalse(decision.chargeable(),
                "§10.1: an unknown or disputed owner does not authorise a theft charge");
    }

    @Test
    void anUnidentifiedActorIsUnknownWhateverTheRuleSays() {
        for (PropertyAccessRule rule : PropertyAccessRule.values()) {
            PropertyAccess.Decision decision = PropertyAccess.decide(
                    policy(rule, PropertyOwnerKind.VILLAGE, null), PropertyActor.unknown(),
                    PropertyAccess.Operation.TAKE);

            assertTrue(decision.unknown(), rule + " charged an actor nobody could identify");
            assertFalse(decision.chargeable());
        }
    }

    @Test
    void aNullActorIsTreatedExactlyAsAnUnidentifiedOne() {
        assertTrue(PropertyAccess.decide(policy(PropertyAccessRule.OWNER_ONLY, PropertyOwnerKind.PLAYER,
                OWNER), null, PropertyAccess.Operation.TAKE).unknown());
    }

    @Test
    void noOperationIsUnknownRatherThanADefault() {
        assertTrue(PropertyAccess.decide(policy(PropertyAccessRule.FORBIDDEN, PropertyOwnerKind.VILLAGE,
                null), player(), null).unknown());
    }

    @Test
    void everyDecisionCarriesAReasonSomebodyCanRead() {
        for (PropertyAccessRule rule : PropertyAccessRule.values()) {
            for (PropertyAccess.Operation operation : PropertyAccess.Operation.values()) {
                PropertyAccess.Decision decision = PropertyAccess.decide(
                        policy(rule, PropertyOwnerKind.VILLAGE, null), player(), operation);

                assertFalse(decision.reason().isBlank(),
                        rule + "/" + operation + " produced a decision with no reason");
            }
        }
    }

    @Test
    void exactlyOneVerdictIsChargeable() {
        assertTrue(PropertyAccess.Decision.denied("x").chargeable());
        assertFalse(PropertyAccess.Decision.allowed("x").chargeable());
        assertFalse(PropertyAccess.Decision.unknown("x").chargeable());
        assertEquals(PropertyAccess.Verdict.UNKNOWN,
                new PropertyAccess.Decision(null, null).verdict(),
                "a decision built with nothing must be unknown, never allowed");
    }
}
