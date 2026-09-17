package dev.otectus.mcacrime.captivity;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.compat.TownsteadBridge;
import dev.otectus.mcacrime.compat.TownsteadCapability;
import dev.otectus.mcacrime.compat.TownsteadNeedsView;
import dev.otectus.mcacrime.facility.CustodyCarePolicy;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps a lawfully held villager alive while they serve, and stops the sentence when it cannot.
 *
 * <h2>Why custody has to do this at all</h2>
 *
 * <p>A settlement mod that tracks hunger and thirst tracks them for prisoners too, and custody is
 * precisely the state that takes away the travel a villager would otherwise feed themselves with. So
 * either custody carries its own care, or a long sentence becomes a slow execution — and the second is
 * not a sentence any legal system in this mod is allowed to hand down.
 *
 * <p>The decision is {@link CustodyCarePolicy}'s and is pure. This class is the plumbing around it:
 * read the needs, look in the cell for something the prisoner can use, hand it over through
 * Townstead's own consumption flow, and — when none of that is possible — enter the custody-recovery
 * state, which suspends confinement and keeps the sentence.
 *
 * <h2>Cost</h2>
 *
 * <p>Care is evaluated on a long interval rather than every custody tick. A prisoner's hunger moves
 * over minutes, and the container scan is the only expensive part: a bounded box around the hold
 * position, only while the chunk is loaded, only for a prisoner the settlement actually tracks needs
 * for. With no settlement mod installed there is no reading at all and this class does nothing.
 */
public final class CustodyCareService {

    /** How often one prisoner's needs are looked at. Five seconds of game time. */
    public static final int CARE_INTERVAL_TICKS = 100;

    /** How far from the hold position a supply container may be. */
    private static final int SUPPLY_RADIUS = 2;

    private static final int SUPPLY_BELOW = 1;
    private static final int SUPPLY_ABOVE = 2;

    /** Next evaluation per captive. Memory-only: a restart costs one extra check, which is idempotent. */
    private static final Map<UUID, Long> NEXT_CHECK = new LinkedHashMap<>();

    private CustodyCareService() {
    }

    /** What one evaluation did, for the caller and for tests of the wiring. */
    public enum Result {
        /** Not evaluated this tick, or nothing to evaluate. */
        SKIPPED,
        /** Needs are fine, or were never tracked. */
        WELL,
        /** The prisoner was handed a supply from the cell. */
        FED,
        /** A supply is present and could not be delivered; recovery is left to run on its own. */
        FEED_UNAVAILABLE,
        /** Confinement is suspended so the prisoner can recover. */
        RECOVERING,
        /** The prisoner recovered and is back in ordinary custody. */
        RECOVERED
    }

    /**
     * Evaluates one lawfully held NPC.
     *
     * <p>Returns {@link Result#SKIPPED} far more often than anything else, by design: the interval, an
     * unloaded chunk, an untracked villager and a player captive all land there, and none of them is a
     * problem.
     */
    public static Result tick(@Nullable ServerLevel level, @Nullable CrimeWorldData data,
                              @Nullable CustodyRecord record, @Nullable LivingEntity prisoner) {
        if (level == null || data == null || record == null || prisoner == null
                || record.isCaptivePlayer() || !record.isLawful()) {
            return Result.SKIPPED;
        }
        long now = level.getGameTime();
        UUID captive = record.getCaptive();
        Long next = NEXT_CHECK.get(captive);
        if (next != null && now < next) {
            return Result.SKIPPED;
        }
        NEXT_CHECK.put(captive, now + CARE_INTERVAL_TICKS);

        TownsteadNeedsView needs = TownsteadBridge.needs(prisoner).orElse(null);
        if (needs == null || !needs.tracked()) {
            // Nothing tracks this villager's needs, so custody has nothing to answer for. A record that
            // somehow entered recovery before the capability went away is let back out rather than left
            // suspended forever.
            return record.exitRecovery() ? Result.RECOVERED : Result.SKIPPED;
        }

        BlockPos hold = record.getHoldPos() == null ? prisoner.blockPosition() : record.getHoldPos();
        List<Supply> supplies = suppliesNear(level, hold);
        List<CustodyCarePolicy.Supply> offered = new ArrayList<>(supplies.size());
        for (int i = 0; i < supplies.size(); i++) {
            Supply supply = supplies.get(i);
            offered.add(new CustodyCarePolicy.Supply(i, supply.food(), supply.drink()));
        }

        CustodyCarePolicy.Decision decision = CustodyCarePolicy.decide(needs, offered,
                TownsteadBridge.has(TownsteadCapability.CONSUMPTION_IN_CUSTODY), null);

        return switch (decision.action()) {
            case OK -> {
                if (record.exitRecovery()) {
                    data.setDirty();
                    McaCrime.LOGGER.debug("MCA: Crime ended custody recovery for {}; the sentence resumes.",
                            captive);
                    yield Result.RECOVERED;
                }
                yield Result.WELL;
            }
            case FEED -> feed(level, prisoner, supplies, decision.slot()) ? Result.FED : Result.WELL;
            case FEED_UNAVAILABLE -> Result.FEED_UNAVAILABLE;
            case CUSTODY_RECOVERY -> {
                if (record.enterRecovery(decision.reason(), now)) {
                    data.setDirty();
                    // Once, at the transition. An operator has to be able to find out why a sentence
                    // stopped counting down, and a line per tick would bury it.
                    McaCrime.LOGGER.info("MCA: Crime suspended confinement for {}: {}. The sentence and "
                            + "its cases are unchanged; this is not a release.", captive, decision.reason());
                }
                yield Result.RECOVERING;
            }
        };
    }

    /**
     * Hands one unit of a supply to the prisoner and takes it out of the container.
     *
     * <p>Both halves, together. Townstead's consumption flow copies a single unit out of the stack it is
     * given and never touches the container it came from, so without the shrink a cell with one loaf in
     * it would feed a prisoner forever.
     */
    private static boolean feed(ServerLevel level, LivingEntity prisoner, List<Supply> supplies, int slot) {
        if (slot < 0 || slot >= supplies.size()) {
            return false;
        }
        Supply supply = supplies.get(slot);
        ItemStack stack = supply.container().getItem(supply.slot());
        if (stack.isEmpty()) {
            return false;
        }
        Boolean started = TownsteadBridge.feedInCustody(prisoner, stack, supply.pos()).orElse(Boolean.FALSE);
        if (!Boolean.TRUE.equals(started)) {
            return false;
        }
        supply.container().removeItem(supply.slot(), 1);
        supply.container().setChanged();
        return true;
    }

    /** One consumable in one container inside the cell. */
    private record Supply(Container container, int slot, BlockPos pos, boolean food, boolean drink) {
    }

    /**
     * Everything edible or drinkable in a container inside the cell.
     *
     * <p>Only containers inside the hold area are considered, which is what §8.6 asks for: a prisoner
     * eats what is in their cell, not what is in the village's granary, and evidence and restitution
     * chests are somewhere else entirely by construction.
     */
    private static List<Supply> suppliesNear(ServerLevel level, BlockPos hold) {
        List<Supply> supplies = new ArrayList<>();
        for (int dx = -SUPPLY_RADIUS; dx <= SUPPLY_RADIUS; dx++) {
            for (int dz = -SUPPLY_RADIUS; dz <= SUPPLY_RADIUS; dz++) {
                for (int dy = -SUPPLY_BELOW; dy <= SUPPLY_ABOVE; dy++) {
                    BlockPos pos = hold.offset(dx, dy, dz);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (!(blockEntity instanceof Container container)) {
                        continue;
                    }
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        ItemStack stack = container.getItem(slot);
                        if (stack.isEmpty()) {
                            continue;
                        }
                        boolean food = stack.isEdible();
                        boolean drink = isDrink(stack);
                        if (food || drink) {
                            supplies.add(new Supply(container, slot, pos, food, drink));
                        }
                    }
                }
            }
        }
        return supplies;
    }

    /**
     * Whether an item restores thirst.
     *
     * <p>Vanilla drinks only. MCA: Crime has no way to ask Townstead's thirst bridge what a modded
     * bottle is worth, and guessing from an item's name would be worse than under-reporting: a
     * prisoner offered something that turns out not to help is still a prisoner who has not drunk, and
     * the critical branch of the policy is what catches that.
     */
    private static boolean isDrink(ItemStack stack) {
        return stack.is(Items.POTION) || stack.is(Items.MILK_BUCKET) || stack.is(Items.HONEY_BOTTLE);
    }

    /** Drops the interval table. Called on server stop, beside the other memory-only registries. */
    public static void clearAll() {
        NEXT_CHECK.clear();
    }

    /** Drops one captive's entry, so a released prisoner leaves nothing behind. */
    public static void forget(@Nullable UUID captive) {
        if (captive != null) {
            NEXT_CHECK.remove(captive);
        }
    }
}
