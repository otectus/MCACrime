package dev.otectus.mcacrime.tether;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.entity.ChainKnotEntity;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import org.jetbrains.annotations.Nullable;
import java.util.List;

/**
 * Chains, knots and forced seating, from the player's side of the interaction (0.7.5 M4.1-M4.4).
 *
 * <p>Registered at {@link EventPriority#NORMAL}, deliberately below
 * {@code restraint/RestraintInteractHandler}'s {@code HIGH}: a player holding cuffs is applying a
 * restraint, and a chain must never take an interaction a restraint wanted.
 *
 * <p>Every branch either does something and consumes the interaction, or does nothing and lets it
 * through. Nothing here swallows a right-click silently, which is the difference between "the chain
 * would not go on" and "talking to that villager stopped working".
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class TetherInteractHandler {

    private TetherInteractHandler() {
    }

    // --- chaining an entity ---------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer actor)) {
            return;
        }
        if (handle(actor, event.getTarget(), event.getHand())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.CONSUME);
        }
    }

    private static boolean handle(ServerPlayer actor, @Nullable Entity target, InteractionHand hand) {
        if (!(target instanceof LivingEntity subject)) {
            return false;
        }
        CrimeWorldData data = TetherService.data(actor.level());
        if (data == null) {
            return false;
        }
        ItemStack held = actor.getItemInHand(hand);

        // Already leading somebody, and clicked something they could ride: put them in it (M4.4).
        if (held.isEmpty() && !actor.isCrouching() && seat(actor, data, subject)) {
            return true;
        }
        // A chain in hand, on something chainable: lead them.
        if (held.is(Items.CHAIN) && TetherService.chainable(subject)) {
            return attach(actor, data, subject, hand, held);
        }
        // An empty crouching hand on somebody this player is leading: let them go.
        if (held.isEmpty() && actor.isCrouching()) {
            return release(actor, data, subject);
        }
        return false;
    }

    /** Ties {@code subject} to the actor, spending one chain. */
    private static boolean attach(ServerPlayer actor, CrimeWorldData data, LivingEntity subject,
                                  InteractionHand hand, ItemStack held) {
        TetherService.Refusal refusal = TetherService.evaluate(data, subject, actor, TetherKind.CHAIN);
        if (refusal != TetherService.Refusal.NONE) {
            explain(actor, refusal);
            return false;
        }
        // The item is spent only after the tether stands, and the tether records who supplied it, so
        // release owes exactly one chain back to exactly one person.
        if (TetherService.attach(data, subject, actor, TetherKind.CHAIN, actor.getUUID(), true)
                .isEmpty()) {
            return false;
        }
        if (!actor.getAbilities().instabuild) {
            held.shrink(1);
        }
        TransportArbiter.claimCustody(subject, subject.level().getGameTime());
        CrimeSounds.chainAttached(subject, true);
        return true;
    }

    /** Unties a subject this actor is holding. Never anybody else's chain. */
    private static boolean release(ServerPlayer actor, CrimeWorldData data, LivingEntity subject) {
        List<TetherRecord> held = TetherService.forSubject(data, subject.getUUID());
        for (TetherRecord tether : held) {
            if (tether.kind() == TetherKind.ESCORT || !actor.getUUID().equals(tether.holder())) {
                continue;
            }
            TetherService.detach(actor.getServer(), data, tether.id(),
                    TetherService.DetachReason.RELEASED);
            CrimeSounds.chainAttached(subject, false);
            return true;
        }
        return false;
    }

    /** Puts whoever this actor is leading into the thing they clicked. */
    private static boolean seat(ServerPlayer actor, CrimeWorldData data, LivingEntity vehicle) {
        for (TetherRecord tether : TetherService.index(data).forHolder(actor.getUUID())) {
            Entity subject = TetherService.find(actor.getServer(), tether.subject());
            if (!(subject instanceof LivingEntity living) || living == vehicle) {
                continue;
            }
            MountTransfer.Refusal refusal = MountTransfer.force(living, vehicle);
            if (refusal == MountTransfer.Refusal.NONE) {
                CrimeSounds.chainAttached(living, true);
                return true;
            }
            if (refusal != MountTransfer.Refusal.NOT_A_VEHICLE) {
                actor.displayClientMessage(
                        Component.translatable(MountTransfer.messageKey(refusal)), true);
                return false;
            }
        }
        return false;
    }

    // --- chaining to a block --------------------------------------------------------------------------

    /**
     * Right-clicking a fence or a tripwire hook while leading somebody ties them to it.
     *
     * <p>Index-driven from end to end: whether anything is already tied here is a map read, and who
     * this player is leading is a map read. The source answers both by iterating every entity on the
     * server on <em>every</em> block right-click ({@code event/ModServerEvents.java:165-171}).
     */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onBlockInteract(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer actor)) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (!anchorable(level.getBlockState(pos))) {
            return;
        }
        CrimeWorldData data = TetherService.data(level);
        if (data == null) {
            return;
        }
        boolean leading = !TetherService.index(data).forHolder(actor.getUUID()).isEmpty();
        boolean anchored = TetherService.anchored(data, level.dimension().location(), pos);
        if (!leading && !anchored) {
            return; // an ordinary fence; nothing to do with us
        }
        ChainKnotEntity knot = ChainKnotEntity.getOrCreate(level, pos);
        if (knot == null) {
            return;
        }
        if (knot.interact(actor, event.getHand()) == InteractionResult.CONSUME) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.CONSUME);
        }
    }

    /** Whether a chain may be tied to this block, config permitting. */
    public static boolean anchorable(@Nullable BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)) {
            return allowFenceAnchors();
        }
        return state.is(Blocks.TRIPWIRE_HOOK) && allowTripwireHookAnchors();
    }

    private static boolean allowFenceAnchors() {
        try {
            return McaCrimeConfig.COMMON.allowFenceAnchors.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return true;
        }
    }

    private static boolean allowTripwireHookAnchors() {
        try {
            return McaCrimeConfig.COMMON.allowTripwireHookAnchors.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return true;
        }
    }

    /** Tells the actor why, rather than leaving a right-click that quietly did nothing. */
    private static void explain(ServerPlayer actor, TetherService.Refusal refusal) {
        if (refusal == TetherService.Refusal.NOT_CHAINABLE) {
            return; // an ordinary cow or a villager nobody may lead: let the interaction through
        }
        actor.displayClientMessage(Component.translatable(TetherService.messageKey(refusal)), true);
    }
}
