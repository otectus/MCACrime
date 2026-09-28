package dev.otectus.mcacrime.api;

import dev.otectus.mcacrime.frisk.FriskSlotRef;
import dev.otectus.mcacrime.frisk.InventoryProvider;
import dev.otectus.mcacrime.frisk.InventoryProviders;
import dev.otectus.mcacrime.restraint.RestraintDefinition;
import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import dev.otectus.mcacrime.restraint.RestraintFamily;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import dev.otectus.mcacrime.restraint.RestrictionPolicy;
import dev.otectus.mcacrime.restraint.RigProfile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The front door another mod adds a restraint through (0.7.5 M6.4).
 *
 * <p>Each assertion is one of the four rules the API documents, and each of them exists because the
 * alternative is a companion mod reflecting into a private map. The most important is the one that
 * looks least interesting: a registration may never replace an existing definition, ours or anybody
 * else's, because a replaced definition is a prisoner whose cuffs change behaviour when an unrelated
 * mod updates.
 */
class ThirdPartyDefinitionTest {

    private static final ResourceLocation FOREIGN = new ResourceLocation("someothermod", "zip_ties");

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        RestraintDefinitions.resetThirdParty();
        InventoryProviders.resetThirdParty();
    }

    private static RestraintDefinition definition(ResourceLocation id) {
        return new RestraintDefinition(id, Optional.of(RestraintSlot.ARMS),
                Optional.of(RestraintFamily.TAPE), Optional.empty(), Optional.empty(),
                RestrictionPolicy.deny().handActions().build(),
                new RestraintDefinition.EscapeProfile(20, true, 0.5D, false),
                RestraintDefinition.PickProfile.unpickable(),
                RestraintDefinition.RenderProfile.none(), RigProfile::arms, Set.of(),
                RestraintDefinition.Statistics.forPrefix("someothermod", "zip_ties"),
                Optional.empty(), 1);
    }

    @Test
    void aForeignDefinitionIsAcceptedAndVisibleEverywhere() {
        assertEquals(RestraintRegistrationApi.Result.ACCEPTED,
                RestraintRegistrationApi.registerDefinition(definition(FOREIGN)));

        assertTrue(RestraintDefinitions.exists(FOREIGN));
        assertEquals(FOREIGN, RestraintRegistrationApi.definition(FOREIGN).orElseThrow().id());
        assertTrue(RestraintDefinitions.all().stream().anyMatch(d -> d.id().equals(FOREIGN)),
                "a registered definition has to appear in the list everything else reads");
        assertEquals(1, RestraintRegistrationApi.thirdPartyDefinitionCount());
    }

    @Test
    void ourOwnDefinitionsComeFirstAndAreNotDisturbed() {
        List<ResourceLocation> shipped = RestraintDefinitions.all().stream()
                .map(RestraintDefinition::id).toList();
        RestraintRegistrationApi.registerDefinition(definition(FOREIGN));

        List<ResourceLocation> all = RestraintDefinitions.all().stream()
                .map(RestraintDefinition::id).toList();
        assertEquals(shipped.size() + 1, all.size());
        assertEquals(shipped, all.subList(0, shipped.size()),
                "the shipped definitions keep their ids and their declaration order");
        assertEquals(FOREIGN, all.get(all.size() - 1));
    }

    @Test
    void nothingMayClaimThisModsNamespace() {
        assertEquals(RestraintRegistrationApi.Result.BAD_ID, RestraintRegistrationApi
                .registerDefinition(definition(new ResourceLocation("mcacrime", "something_new"))));
        assertEquals(0, RestraintRegistrationApi.thirdPartyDefinitionCount());
    }

    @Test
    void nothingMayReplaceAnExistingDefinition() {
        assertEquals(RestraintRegistrationApi.Result.ACCEPTED,
                RestraintRegistrationApi.registerDefinition(definition(FOREIGN)));
        assertEquals(RestraintRegistrationApi.Result.DUPLICATE,
                RestraintRegistrationApi.registerDefinition(definition(FOREIGN)),
                "a second registration of the same id must be refused, not quietly win");
        assertEquals(1, RestraintRegistrationApi.thirdPartyDefinitionCount());
    }

    @Test
    void theWindowClosesAndStaysClosed() {
        RestraintDefinitions.closeRegistration();
        assertFalse(RestraintRegistrationApi.open());
        assertEquals(RestraintRegistrationApi.Result.TOO_LATE,
                RestraintRegistrationApi.registerDefinition(definition(FOREIGN)));
        assertFalse(RestraintDefinitions.exists(FOREIGN));
    }

    @Test
    void theThirdPartyCapIsEnforced() {
        for (int i = 0; i < RestraintRegistrationApi.MAX_THIRD_PARTY_DEFINITIONS; i++) {
            assertEquals(RestraintRegistrationApi.Result.ACCEPTED, RestraintRegistrationApi
                    .registerDefinition(definition(new ResourceLocation("someothermod", "tie_" + i))));
        }
        assertEquals(RestraintRegistrationApi.Result.FULL,
                RestraintRegistrationApi.registerDefinition(definition(FOREIGN)));
    }

    @Test
    void aDefinitionThatRestrainsNobodyIsRejected() {
        RestraintDefinition nothing = new RestraintDefinition(FOREIGN, Optional.of(RestraintSlot.ARMS),
                Optional.empty(), Optional.empty(), Optional.empty(), RestrictionPolicy.unrestricted(),
                RestraintDefinition.EscapeProfile.none(), RestraintDefinition.PickProfile.unpickable(),
                RestraintDefinition.RenderProfile.none(), rig -> true, Set.of(),
                RestraintDefinition.Statistics.forPrefix("someothermod", "nothing"),
                Optional.empty(), 1);
        assertEquals(RestraintRegistrationApi.Result.INVALID,
                RestraintRegistrationApi.registerDefinition(nothing),
                "it would occupy a slot, block the restraint that belongs there and do nothing");
    }

    // ------------------------------------------------------------------ inventory providers

    /** A provider that offers nothing: enough to register, and it cannot touch anybody's items. */
    private record EmptyProvider(String id) implements InventoryProvider {
        @Override
        public boolean supports(LivingEntity subject) {
            return false;
        }

        @Override
        public List<FriskSlotRef> slots(LivingEntity subject) {
            return List.of();
        }

        @Override
        public ItemStack peek(LivingEntity subject, FriskSlotRef ref) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack extract(LivingEntity subject, FriskSlotRef ref, int count) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean restore(LivingEntity subject, FriskSlotRef ref, ItemStack stack) {
            return false;
        }
    }

    @Test
    void aForeignInventoryProviderIsAcceptedOnce() {
        assertEquals(RestraintRegistrationApi.Result.ACCEPTED,
                RestraintRegistrationApi.registerInventoryProvider(new EmptyProvider("someothermod_bag")));
        assertTrue(InventoryProviders.ids().contains("someothermod_bag"));
        assertEquals(RestraintRegistrationApi.Result.DUPLICATE,
                RestraintRegistrationApi.registerInventoryProvider(new EmptyProvider("someothermod_bag")));
    }

    @Test
    void aShippedProviderIdMayNeverBeTakenOver() {
        String shipped = InventoryProviders.ids().get(0);
        assertEquals(RestraintRegistrationApi.Result.DUPLICATE,
                RestraintRegistrationApi.registerInventoryProvider(new EmptyProvider(shipped)),
                "taking over a shipped id would read somebody's inventory through foreign code");
    }

    @Test
    void aProviderWithNoUsableIdIsRefused() {
        assertEquals(RestraintRegistrationApi.Result.BAD_ID,
                RestraintRegistrationApi.registerInventoryProvider(new EmptyProvider("")));
        assertEquals(RestraintRegistrationApi.Result.BAD_ID,
                RestraintRegistrationApi.registerInventoryProvider(new EmptyProvider("Mixed_Case")));
    }
}
