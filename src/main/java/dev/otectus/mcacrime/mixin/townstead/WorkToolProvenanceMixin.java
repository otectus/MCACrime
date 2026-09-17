package dev.otectus.mcacrime.mixin.townstead;

import dev.otectus.mcacrime.compat.TownsteadEquipmentProvenance;
import dev.otectus.mcacrime.compat.TownsteadMixinStatus;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Learns which stack in a villager's hand is Townstead's prop and which is the villager's own.
 *
 * <h2>The duplicate hoe</h2>
 *
 * <p>Townstead's work-tool ticker dresses a villager for their shift. It stashes a copy of whatever
 * they were holding in a private map of its own, then puts a <em>copy</em> of the matching inventory
 * tool in their main hand. Neither copy is owned by an inventory slot, and both are invisible to
 * anything outside that class.
 *
 * <p>MCA: Crime's death loot decides what to drop by reference identity — a hand item that is not the
 * same object as an inventory stack is gear the villager owns and would otherwise lose. That rule is
 * right for MCA, which equips inventory items by reference, and it misreads both of Townstead's
 * copies: the display tool becomes a second hoe that never existed, and the stash becomes an item
 * nobody drops at all. Telling them apart needs the two identities, and the only place they exist is
 * the moment they are made.
 *
 * <h2>Why {@code copy()}, of all things</h2>
 *
 * <p>The obvious hook would be {@code setItemInHand}. It cannot be used: in Townstead's bytecode that
 * call's owner is MCA's villager class, so an {@code @At} naming it would put a relocated MCA
 * descriptor in this mod's constant pool — the linkage {@code NoMcaStaticLinkTest} exists to forbid.
 * The same is true of {@code getMainHandItem}.
 *
 * <p>{@code ItemStack.copy()} has a vanilla owner in that same method, so it can be named in full.
 * Every {@code remap} here is {@code false}, as everywhere else on this branch: NeoForge 1.21.1 ships
 * Mojang names in production, this mod builds with no Mixin annotation processor and no
 * {@code mcacrime.refmap.json} exists to write a mapping into, so the descriptor below is already the
 * one in the shipped Townstead jar. There are exactly two such calls in {@code tick} and they are the
 * two copies in question — the stash first, the display second, in that order in the instruction
 * stream. So they are told apart by {@code ordinal}, which {@code TownsteadMixinTargetTest} checks
 * against the real jar rather than leaving to a reading of the source.
 *
 * <h2>Whose villager</h2>
 *
 * <p>Capture Townstead's actual argument as a vanilla {@link LivingEntity}, using {@code @Coerce}
 * to accept either MCA package layout. Cleanup can run outside the entity's tick, so a previous
 * tick's context cannot identify the owner of a stash or display tool.
 *
 * <p>Every handler performs the original call first and returns its result unconditionally. Recording
 * is a side effect and is wrapped so that it cannot become anything else: an exception here would be
 * attributed to Townstead and would leave a villager holding the wrong item.
 */
@Mixin(targets = "com.aetherianartificer.townstead.tick.WorkToolTicker", remap = false)
public abstract class WorkToolProvenanceMixin {

    /**
     * The copy of whatever the villager was holding before the shift, taken at the stash site.
     *
     * <p>Ordinal 0. It is made inside a branch — Townstead skips it when the villager is already
     * holding a matching tool — so this handler runs less often than the display one, and a shift that
     * starts with the right tool in hand correctly records no stash at all.
     */
    @Redirect(method = "tick", remap = false, require = 0, expect = 1,
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Lnet/minecraft/world/item/ItemStack;copy()Lnet/minecraft/world/item/ItemStack;",
                    remap = false))
    private static ItemStack mcacrime$recordStash(ItemStack original, @Coerce LivingEntity entity) {
        ItemStack copy = original.copy();
        try {
            TownsteadMixinStatus.injected(TownsteadMixinStatus.HOOK_WORK_TOOL_COPY);
            UUID villager = mcacrime$villager(entity);
            if (villager != null) {
                TownsteadEquipmentProvenance.stashedOriginal(villager, copy);
            }
        } catch (Throwable ignored) {
            // Never propagate: the copy is Townstead's and has to be returned whatever happens here.
        }
        return copy;
    }

    /**
     * The copy that goes in the hand, taken at the equip site.
     *
     * <p>Ordinal 1, and the one that closes the duplicate drop: this exact object is what the villager
     * will be found holding when they die, and recognising it is what stops MCA: Crime adding a second
     * tool to the pile.
     */
    @Redirect(method = "tick", remap = false, require = 0, expect = 1,
            at = @At(value = "INVOKE", ordinal = 1,
                    target = "Lnet/minecraft/world/item/ItemStack;copy()Lnet/minecraft/world/item/ItemStack;",
                    remap = false))
    private static ItemStack mcacrime$recordDisplayTool(ItemStack tool, @Coerce LivingEntity entity) {
        ItemStack copy = tool.copy();
        try {
            TownsteadMixinStatus.injected(TownsteadMixinStatus.HOOK_WORK_TOOL_COPY);
            UUID villager = mcacrime$villager(entity);
            if (villager != null) {
                TownsteadEquipmentProvenance.displayTool(villager, copy);
            }
        } catch (Throwable ignored) {
            // As above. A missed recording degrades one drop; a throw here breaks somebody's shift.
        }
        return copy;
    }

    /**
     * The shift ended and Townstead is putting the stash back.
     *
     * <p>Both halves of the record stop being true at once — the display copy leaves the hand and the
     * stash goes back into it — so the whole entry goes. The target argument is captured as its
     * vanilla superclass; no MCA type is linked. Cleanup also runs while recording is disabled,
     * so re-enabling the integration cannot resurrect an obsolete stash.
     */
    @Inject(method = "restore", at = @At("HEAD"), remap = false, require = 0, expect = 1)
    private static void mcacrime$onRestore(@Coerce LivingEntity entity, CallbackInfo ci) {
        mcacrime$forget(entity, TownsteadMixinStatus.HOOK_WORK_TOOL_RESTORE);
    }

    /** Townstead dropping its own record — usually because the villager died or unloaded. */
    @Inject(method = "forget", at = @At("HEAD"), remap = false, require = 0, expect = 1)
    private static void mcacrime$onForget(@Coerce LivingEntity entity, CallbackInfo ci) {
        mcacrime$forget(entity, TownsteadMixinStatus.HOOK_WORK_TOOL_FORGET);
    }

    /**
     * Clears only the villager passed to Townstead, even when another villager ticked last.
     */
    private static void mcacrime$forget(LivingEntity entity, String hookId) {
        try {
            TownsteadMixinStatus.injected(hookId);
            if (entity != null) {
                TownsteadEquipmentProvenance.forget(entity.getUUID());
            }
        } catch (Throwable ignored) {
            // Never propagate; see the class comment.
        }
    }

    /**
     * Who is being ticked, or {@code null} when the answer is not certain.
     *
     * <p>The kill switch is read here rather than at each call site: an operator who turned the
     * integration off gets Townstead's behaviour and MCA: Crime's original equipment rule, with nothing
     * recorded in between to half-apply.
     */
    private static UUID mcacrime$villager(LivingEntity entity) {
        return entity != null && TownsteadEquipmentProvenance.active() ? entity.getUUID() : null;
    }
}
