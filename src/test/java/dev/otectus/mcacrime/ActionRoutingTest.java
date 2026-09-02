package dev.otectus.mcacrime;

import dev.otectus.mcacrime.action.ActionDescriptor;
import dev.otectus.mcacrime.action.ActionHandlerRegistry;
import dev.otectus.mcacrime.action.ActionTargetKind;
import dev.otectus.mcacrime.action.CrimeActionHandler;
import dev.otectus.mcacrime.action.CrimeActionIds;
import dev.otectus.mcacrime.action.CrimeActionService;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The spec §26 Phase 1 gate, expressed as a test rather than a promise.
 *
 * <p>Before 0.4.0 four of the nine ids in {@link CrimeActionIds} — {@code escape}, {@code surrender},
 * {@code settle_case} and {@code pay_ransom} — were declared constants that nothing referenced, while
 * those flows mutated state directly from the command layer. Nothing caught it, because "an id with no
 * handler" is not a compile error. This is what catches it now: every declared action must be
 * reachable through the one server contract, and must carry the presentation the screen needs.
 */
class ActionRoutingTest {

    @BeforeAll
    static void bootstrapHandlers() {
        // bootstrap() is idempotent and touches no world state, so it is safe to call from a unit test.
        CrimeActionService.bootstrap();
    }

    /** Every {@code ResourceLocation} constant declared on {@link CrimeActionIds}. */
    private static List<ResourceLocation> declaredActionIds() {
        List<ResourceLocation> ids = new ArrayList<>();
        for (Field field : CrimeActionIds.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != ResourceLocation.class) continue;
            try {
                ids.add((ResourceLocation) field.get(null));
            } catch (IllegalAccessException e) {
                throw new AssertionError("Could not read " + field.getName(), e);
            }
        }
        return ids;
    }

    @Test
    void everyDeclaredActionHasARegisteredHandler() {
        List<ResourceLocation> unrouted = new ArrayList<>();
        for (ResourceLocation id : declaredActionIds()) {
            if (ActionHandlerRegistry.get(id) == null) unrouted.add(id);
        }
        assertTrue(unrouted.isEmpty(),
                "These action ids are declared but reach no handler, so they cannot enter the action "
                        + "engine and any command path for them bypasses locks, nonces and cooldowns: " + unrouted);
    }

    @Test
    void declaredActionsCoverEveryCoreFlow() {
        // Named explicitly rather than counted, so deleting one is a failure and not a silently
        // smaller list. These six are the flows the Phase 1 gate names by hand.
        for (ResourceLocation id : List.of(CrimeActionIds.MUG, CrimeActionIds.RESTRAIN, CrimeActionIds.ESCAPE,
                CrimeActionIds.SETTLE_CASE, CrimeActionIds.SURRENDER, CrimeActionIds.RANSOM)) {
            assertNotNull(ActionHandlerRegistry.get(id), id + " must enter through the action engine");
        }
    }

    @Test
    void everyHandlerDescribesItselfConsistently() {
        for (ResourceLocation id : declaredActionIds()) {
            CrimeActionHandler handler = ActionHandlerRegistry.get(id);
            if (handler == null) continue;
            ActionDescriptor descriptor = handler.descriptor();
            assertNotNull(descriptor, id + " has no descriptor");
            assertEquals(id, descriptor.id(),
                    "Descriptor id disagrees with the id the handler is registered under, so the screen "
                            + "would label one action and start another");
            assertNotNull(descriptor.category(), id + " has no category");
            assertNotNull(descriptor.legality(), id + " has no legality marker");
            assertNotNull(descriptor.duration(), id + " has no duration marker");
            assertNotNull(descriptor.targetKind(), id + " has no target kind");
            assertTrue(descriptor.labelKey().startsWith("gui.mcacrime.action."), id + " has an off-scheme label key");
            assertTrue(descriptor.descriptionKey().endsWith(".desc"), id + " has an off-scheme description key");
        }
    }

    @Test
    void requirementKeysAreStablyOrdered() {
        // Set iteration order is not guaranteed; the screen must draw the same markers in the same
        // order every time a menu is opened, or the strip visibly reshuffles between openings.
        for (ResourceLocation id : declaredActionIds()) {
            CrimeActionHandler handler = ActionHandlerRegistry.get(id);
            if (handler == null) continue;
            List<String> first = handler.descriptor().requirementKeys();
            List<String> second = handler.descriptor().requirementKeys();
            assertEquals(first, second, id + " orders its requirement markers unstably");
            assertEquals(new HashSet<>(first).size(), first.size(), id + " repeats a requirement marker");
        }
    }

    @Test
    void hostileActionsAreTheCriminalOnes() {
        // The confirmation prompt keys off `hostile`, and the legality marker is what the player reads.
        // If those two ever disagree, the game warns about one set of actions and colours another.
        for (ResourceLocation id : declaredActionIds()) {
            CrimeActionHandler handler = ActionHandlerRegistry.get(id);
            if (handler == null) continue;
            ActionDescriptor descriptor = handler.descriptor();
            if (descriptor.hostile()) {
                assertNotEquals(dev.otectus.mcacrime.action.ActionLegality.LAWFUL, descriptor.legality(),
                        id + " asks for a hostile confirmation but is marked lawful");
            }
        }
    }

    @Test
    void selfActionsAreDistinctFromTargetedOnes() {
        Set<ResourceLocation> expectedSelf = Set.of(CrimeActionIds.ESCAPE, CrimeActionIds.SURRENDER,
                CrimeActionIds.SETTLE_CASE, CrimeActionIds.PAY_RANSOM, CrimeActionIds.BAIL);
        for (ResourceLocation id : declaredActionIds()) {
            CrimeActionHandler handler = ActionHandlerRegistry.get(id);
            if (handler == null) continue;
            ActionTargetKind kind = handler.descriptor().targetKind();
            assertEquals(expectedSelf.contains(id) ? ActionTargetKind.SELF : ActionTargetKind.ENTITY, kind,
                    id + " is in the wrong menu: a self action offered against a villager, or the reverse");
        }
    }
}
