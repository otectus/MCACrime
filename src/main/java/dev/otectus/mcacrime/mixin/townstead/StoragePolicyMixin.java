package dev.otectus.mcacrime.mixin.townstead;

import dev.otectus.mcacrime.compat.TownsteadBridge;
import dev.otectus.mcacrime.compat.TownsteadMixinStatus;
import dev.otectus.mcacrime.compat.TownsteadTickContext;
import dev.otectus.mcacrime.property.PropertyRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

/**
 * Keeps settlement workers out of containers MCA: Crime is answerable for.
 *
 * <h2>The collision</h2>
 *
 * <p>Townstead's storage search asks one question of every candidate block — is this protected storage
 * — and answers it from a block-tag list. That is a sourcing exclusion, not an ownership check, and it
 * has never heard of evidence storage or of a cell's supply chest. So a worker looking for bread will
 * happily walk into an impound chest and take the bread a case is resting on, and the theft will look
 * to every part of MCA: Crime like the goods simply vanishing.
 *
 * <p>This forces the answer to {@code true} for any position a {@link PropertyRegistry} policy marks as
 * not to be sourced from. It never forces it the other way: a block Townstead already protects stays
 * protected, because "this mod thinks nobody owns it" is not a reason to overrule a settlement's own
 * rule about its own stores.
 *
 * <h2>Why this shape</h2>
 *
 * <p>{@code isProtectedStorage(BlockPos, BlockState)} is one of the few Townstead members whose whole
 * signature is vanilla, which is what makes an ordinary {@code @Inject} with captured arguments legal
 * here — no MCA type is named and nothing in this mod's bytecode refers to a Townstead class, because
 * the target is a dotted string. {@code remap = false} because every name in it is either Townstead's
 * own or a class name, neither of which is obfuscated on 1.20.1 Forge; {@code require = 0} so a
 * Townstead point release that moved the method degrades one capability instead of stopping the game.
 * There is no {@code @At} on a vanilla member, so nothing here needs the refmap.
 *
 * <h2>Whose level is this?</h2>
 *
 * <p>The search context holds its own {@code ServerLevel} in a private field, and shadowing it would
 * make a moved or renamed field a hard startup failure rather than a degraded capability — the one
 * thing this whole layer is built to avoid. So the level comes from {@link TownsteadTickContext}, the
 * villager whose tick this search is running inside, and falls back to the overworld only when a single
 * level is the only possible answer. Anything less certain than that leaves the value exactly as
 * Townstead computed it: not knowing is treated as not interfering.
 */
@Mixin(targets = "com.aetherianartificer.townstead.storage.StorageSearchContext", remap = false)
public abstract class StoragePolicyMixin {

    /**
     * Forces {@code true} for a container MCA: Crime has reserved.
     *
     * <p>Marks the hook as fired first and unconditionally, which is what lets
     * {@code /crime debug townstead} tell "Townstead moved the method" from "no worker has searched for
     * anything yet". Never throws: this runs inside another mod's per-block sourcing scan, where an
     * exception would be attributed to Townstead and would take its storage search down with it.
     */
    @Inject(method = "isProtectedStorage(Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;)Z",
            at = @At("RETURN"), cancellable = true, remap = false, require = 0, expect = 1)
    private void mcacrime$protectReservedStorage(BlockPos pos, BlockState state,
                                                 CallbackInfoReturnable<Boolean> cir) {
        try {
            TownsteadMixinStatus.injected(TownsteadMixinStatus.HOOK_STORAGE_POLICY);
            if (Boolean.TRUE.equals(cir.getReturnValue()) || pos == null) {
                return; // already protected; there is nothing to add
            }
            if (!PropertyRegistry.enabled() || !TownsteadBridge.integrationEnabled()) {
                // The two kill switches, read before any lookup, so a server with property law off pays
                // one boolean read per block and Townstead behaves exactly as it ships.
                return;
            }
            ServerLevel level = mcacrime$level();
            if (level == null) {
                return;
            }
            if (PropertyRegistry.protectedFromAutoSourcing(level, pos)) {
                cir.setReturnValue(Boolean.TRUE);
            }
        } catch (Throwable ignored) {
            // A refused source is a nicety; a crash inside a settlement mod's storage search is not.
        }
    }

    /**
     * The level this search is running in, or null when it cannot be established.
     *
     * <p>Null is a real answer and the common one on any server with more than one dimension in play:
     * the overworld fallback is taken only when the server has exactly one level, where it is not a
     * guess at all. Answering with the wrong dimension would protect a container at the same
     * coordinates in another world, which is a worse failure than not protecting this one.
     */
    @Nullable
    private static ServerLevel mcacrime$level() {
        ServerLevel ticking = TownsteadTickContext.currentServerLevel();
        if (ticking != null) {
            return ticking;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        ServerLevel only = null;
        for (ServerLevel level : server.getAllLevels()) {
            if (only != null) {
                return null;
            }
            only = level;
        }
        return only;
    }
}
