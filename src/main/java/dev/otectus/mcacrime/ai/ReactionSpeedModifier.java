package dev.otectus.mcacrime.ai;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The movement-speed penalty a reacting civilian carries (0.5.1, spec §"threat compliance").
 *
 * <p>A frightened farmer who sprints away at trading speed reads as a bug, so a civilian under this
 * mod's navigation moves slower than MCA would move them. The mechanism is a <em>transient</em>
 * attribute modifier with a fixed id: transient so it is never written to the entity's NBT and cannot
 * survive a save, fixed-id so the one that is applied is the one that is removed. An orphaned
 * multiplier on a villager is invisible and permanent, which is why every controller exit path — state
 * change, release, death, leave-level — calls {@link #remove} unconditionally rather than only when it
 * believes it applied one.
 *
 * <p>Guards and other armed villagers are deliberately never slowed: they are resisting, not fleeing.
 */
public final class ReactionSpeedModifier {

    /**
     * Fixed for the life of the mod. Changing it would orphan every modifier applied by a running
     * server before the change, so {@code ReactionSpeedModifierIdTest} pins the literal.
     */
    public static final ResourceLocation MODIFIER_ID = McaCrime.id("civilian_reaction_speed");

    private ReactionSpeedModifier() {
    }

    /**
     * Applies (or retunes) the penalty. Idempotent: {@code addOrUpdateTransientModifier} replaces any
     * modifier already carrying this id, so the controller can call this on every navigation-owning
     * state entry without stacking multipliers.
     *
     * @param multiplier fraction of normal speed; 1.0 or above removes the modifier instead of adding
     *                   a no-op one
     */
    public static void apply(LivingEntity entity, double multiplier) {
        AttributeInstance instance = instanceOf(entity);
        if (instance == null) {
            return;
        }
        double amount = amountFor(multiplier);
        if (amount >= 0.0D) {
            // Nothing to slow. Leaving the attribute untouched is better than parking a zero modifier
            // on it, because a zero modifier still has to be found and removed later.
            instance.removeModifier(MODIFIER_ID);
            return;
        }
        instance.addOrUpdateTransientModifier(
                new AttributeModifier(MODIFIER_ID, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    /**
     * The {@code ADD_MULTIPLIED_TOTAL} amount for a target fraction of normal speed. Pure, so the
     * arithmetic that turns "move at 0% speed" into {@code -1.0} is assertable without an entity: a
     * sign error here is a villager that moves faster while being robbed.
     */
    public static double amountFor(double multiplier) {
        return multiplier - 1.0D;
    }

    /** Removes the penalty. Safe for an entity that never had one, which is most of the callers. */
    public static void remove(LivingEntity entity) {
        AttributeInstance instance = instanceOf(entity);
        if (instance != null) {
            instance.removeModifier(MODIFIER_ID);
        }
    }

    private static AttributeInstance instanceOf(LivingEntity entity) {
        if (entity == null) {
            return null;
        }
        try {
            return entity.getAttribute(Attributes.MOVEMENT_SPEED);
        } catch (Throwable t) {
            // An entity without the attribute is not an error; it simply cannot be slowed.
            return null;
        }
    }
}
