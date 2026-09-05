package dev.otectus.mcacrime.ai;

import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.item.weapon.DrawnWeapon;
import dev.otectus.mcacrime.item.weapon.WeaponDetector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;

/**
 * Whether an entity can be threatened into compliance, or will resist (0.5.1, spec §"threat compliance").
 *
 * <p>The whole point is that there is one answer and one precedence order. A guard is not made
 * compliant by putting a flower in his hand, and an archer is not made compliant by taking the bow out
 * of it — the role decides before the hands do. Conversely a farmer holding an axe is armed, because
 * the axe is what the victim of the threat is looking at.
 *
 * <p>Order, first match wins:
 * <ol>
 *   <li>{@code mcacrime:never_resists_weapon_threats} — the datapack's absolute override, so a pack
 *       can make a specific NPC compliant no matter what it is or holds;</li>
 *   <li>{@code mcacrime:always_resists_weapon_threats} — the opposite override;</li>
 *   <li>law role: an MCA guard or archer;</li>
 *   <li>a weapon in the main hand, then the off-hand (honouring the same off-hand key every other
 *       gate in the mod honours, because it is the same {@link WeaponDetector#drawnWeapon} call);</li>
 *   <li>{@code mcacrime:armed_villager_roles} — armed by role with empty hands;</li>
 *   <li>unarmed.</li>
 * </ol>
 *
 * <p>{@link #evaluate} is the pure core and holds all of that ordering; {@link #classify} does nothing
 * but read the five facts off a live entity.
 */
public final class ArmedResolver {

    /** MCA's archer profession — a bow-carrying role that stays a role with the bow put away. */
    private static final ResourceLocation ARCHER = ResourceLocation.fromNamespaceAndPath("mca", "archer");

    /** Which rule produced the verdict. Reported by {@code /crime debug} and read by the decider. */
    public enum WeaponSource {
        EXEMPT_TAG,
        ROLE,
        MAIN_HAND,
        OFF_HAND,
        COMBATANT_TAG,
        NONE
    }

    /** The verdict plus what it was based on. {@code hand}/{@code weapon} are set only for a held weapon. */
    public record ArmedStatus(boolean armed, WeaponSource source, @Nullable InteractionHand hand,
                              @Nullable ItemStack weapon) {

        public static ArmedStatus unarmed() {
            return new ArmedStatus(false, WeaponSource.NONE, null, null);
        }
    }

    private ArmedResolver() {
    }

    /** Reads the live entity and applies {@link #evaluate}. */
    public static ArmedStatus classify(LivingEntity entity) {
        if (entity == null) {
            return ArmedStatus.unarmed();
        }
        boolean neverResists = entity.getType().is(CrimeEntityTags.NEVER_RESISTS);
        boolean alwaysResists = entity.getType().is(CrimeEntityTags.ALWAYS_RESISTS);
        boolean lawRole = McaCompat.isGuard(entity)
                || McaCompat.getProfessionId(entity).map(ARCHER::equals).orElse(false);
        boolean combatantTag = entity.getType().is(CrimeEntityTags.ARMED_VILLAGER_ROLES);
        return evaluate(neverResists, alwaysResists, lawRole, WeaponDetector.drawnWeapon(entity), combatantTag);
    }

    /**
     * Pure: the precedence above, over facts already gathered.
     *
     * <p>{@code drawn} carries the off-hand decision with it — an entity whose only weapon is in the
     * off-hand while {@code allowOffHand} is off arrives here as empty, so this method never has to
     * know about the setting and can never disagree with the gate that produced it.
     */
    public static ArmedStatus evaluate(boolean neverResists, boolean alwaysResists, boolean lawRole,
                                       Optional<DrawnWeapon> drawn, boolean combatantTag) {
        if (neverResists) {
            return ArmedStatus.unarmed();
        }
        if (alwaysResists) {
            return new ArmedStatus(true, WeaponSource.EXEMPT_TAG, null, null);
        }
        if (lawRole) {
            return new ArmedStatus(true, WeaponSource.ROLE, null, null);
        }
        if (drawn != null && drawn.isPresent()) {
            DrawnWeapon weapon = drawn.get();
            WeaponSource source = weapon.hand() == InteractionHand.OFF_HAND
                    ? WeaponSource.OFF_HAND
                    : WeaponSource.MAIN_HAND;
            return new ArmedStatus(true, source, weapon.hand(), weapon.stack());
        }
        if (combatantTag) {
            return new ArmedStatus(true, WeaponSource.COMBATANT_TAG, null, null);
        }
        return ArmedStatus.unarmed();
    }
}
