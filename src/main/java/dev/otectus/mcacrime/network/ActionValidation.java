package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The checks {@link ServerPacketGuard} does not do, in one place.
 *
 * <p>{@code ServerPacketGuard} answers "who sent this, are they allowed to send it this often, and is
 * this the server". Everything else — are they close enough, in the same world, looking at it, still
 * holding the item they started with, still in the menu they opened, and is the target still the one
 * the session was opened against — has until now been each packet's own business, written out by
 * hand, or forgotten. The 0.7.5 action surface is far too large for that: every restraint, lock,
 * frisk and device packet needs the same six answers.
 *
 * <p>Every method here is pure or takes only what it needs, and the arithmetic ones are separated
 * from the entity ones so the rules can be asserted without a server.
 *
 * <p>The rule none of these can be allowed to become: a client-supplied identity. The actor is always
 * the connection's player, the target is always resolved server-side, and the outcome is always
 * computed here.
 */
public final class ActionValidation {

    /** Ordinary reach for an interaction the server is asked to perform on the player's behalf. */
    public static final double DEFAULT_REACH_BLOCKS = 5.0D;

    private ActionValidation() {
    }

    // --- pure geometry and clocks ---------------------------------------------------------------

    /**
     * Whether a squared distance is within {@code maxBlocks}.
     *
     * <p>Squared on both sides: no square root, and a non-finite input fails rather than comparing
     * true, which is the difference between "too far" and "unbounded teleport".
     */
    public static boolean withinRangeSquared(double distanceSquared, double maxBlocks) {
        if (!Double.isFinite(distanceSquared) || !Double.isFinite(maxBlocks) || distanceSquared < 0.0D
                || maxBlocks < 0.0D) {
            return false;
        }
        return distanceSquared <= maxBlocks * maxBlocks;
    }

    /** Whether two dimension ids name the same world. A null on either side is never the same world. */
    public static boolean sameDimension(@Nullable ResourceLocation a, @Nullable ResourceLocation b) {
        return a != null && a.equals(b);
    }

    /**
     * Whether a session opened at {@code expiryTick} is still live at {@code now}.
     *
     * <p>Expiry is exclusive so a session cannot be used on the tick it dies, and a non-positive
     * expiry is dead rather than eternal.
     */
    public static boolean fresh(long now, long expiryTick) {
        return expiryTick > 0L && now < expiryTick;
    }

    /**
     * Whether a client's idea of a revision is still the current one.
     *
     * <p>Equality, not "at least": a packet issued against revision 4 must be refused at revision 5
     * <em>and</em> at revision 3. The first is a stale action, the second is a forged one.
     */
    public static boolean currentRevision(long expected, long actual) {
        return expected == actual;
    }

    /** Whether an input sequence number strictly advances. Replays and reorderings are refused. */
    public static boolean advances(int lastAccepted, int incoming) {
        return incoming > lastAccepted;
    }

    // --- identity of the thing being acted on ---------------------------------------------------

    /**
     * Whether an action naming {@code custodyId} at {@code generation} may act on {@code record}.
     *
     * <p>Both halves are required, and each catches a different mistake. The id refuses a packet from
     * a previous captivity of the same subject — two captures share a captive UUID and nothing else.
     * The generation refuses a packet from a previous <em>holder</em> of the same captivity, which is
     * what makes an escort handover clean without releasing the prisoner in between.
     */
    public static boolean matchesCustody(@Nullable CustodyRecord record, @Nullable UUID custodyId,
                                         long generation) {
        return record != null && custodyId != null && custodyId.equals(record.getCustodyId())
                && currentRevision(generation, record.getGeneration());
    }

    /** Whether an action naming a physical generation and revision may act on {@code state}. */
    public static boolean matchesPhysicalState(@Nullable PhysicalRestraintState state, long generation,
                                               long revision) {
        return state != null && currentRevision(generation, state.generation())
                && currentRevision(revision, state.revision());
    }

    // --- live server checks ---------------------------------------------------------------------

    /** Whether {@code actor} and {@code target} are in the same level and within {@code maxBlocks}. */
    public static boolean inReach(@Nullable ServerPlayer actor, @Nullable Entity target, double maxBlocks) {
        if (actor == null || target == null || actor.level() != target.level()) {
            return false;
        }
        return withinRangeSquared(actor.distanceToSqr(target), maxBlocks);
    }

    /** Whether the actor can see the target, for the actions that require it. */
    public static boolean hasLineOfSight(@Nullable ServerPlayer actor, @Nullable Entity target) {
        return actor != null && target != null && actor.level() == target.level()
                && actor.hasLineOfSight(target);
    }

    /**
     * Whether the actor is still holding the item the action is about.
     *
     * <p>Item identity, not stack identity: a player who put the pick down and picked up another is
     * still holding a pick, and a player who put it away is not. Checked at every meaningful step,
     * because "swap the item after the session opens" is the cheapest way to start an action with a
     * tool and finish it without one.
     */
    public static boolean holdsItem(@Nullable ServerPlayer actor, @Nullable InteractionHand hand,
                                    @Nullable ResourceLocation expectedItem) {
        if (actor == null || hand == null || expectedItem == null) {
            return false;
        }
        ItemStack held = actor.getItemInHand(hand);
        if (held.isEmpty()) {
            return false;
        }
        ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(held.getItem());
        return expectedItem.equals(id);
    }

    /** Whether the actor still has the menu this action belongs to open. */
    public static boolean ownsMenu(@Nullable ServerPlayer actor, int containerId) {
        AbstractContainerMenu menu = actor == null ? null : actor.containerMenu;
        return menu != null && menu.containerId == containerId;
    }

    /** Whether the actor is in a state where any of this is meaningful at all. */
    public static boolean actionable(@Nullable ServerPlayer actor) {
        return actor != null && actor.isAlive() && !actor.isRemoved() && !actor.isSpectator();
    }
}
