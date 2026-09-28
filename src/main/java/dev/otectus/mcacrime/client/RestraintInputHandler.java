package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.restraint.PhysicalRestraintView;
import dev.otectus.mcacrime.restraint.RestraintAction;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import dev.otectus.mcacrime.restraint.RestrictionPolicy;
import dev.otectus.mcacrime.restraint.RestrictionResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side movement suppression, so a denied movement feels immediate (0.7.5 M2.8).
 *
 * <p>Two things this is not. It is <b>not</b> enforcement: the server owns every restriction, and a
 * client that never ran this class is restrained exactly as much as one that did. And it is not the
 * source's approach, which blocks raw <em>key bindings</em> by code in a client screen — that names
 * other mods' bindings by string, misses a rebound key, and is invisible to the server.
 *
 * <p>A Forge event rather than a mixin, deliberately. {@code MovementInputUpdateEvent} exists for
 * exactly this, fires after vanilla has filled the input and before it is consumed, and needs no
 * injection point to stay valid across a Minecraft update.
 *
 * <p>Vision and chat are untouched here. Obscured vision is drawn by the hood overlay and a head
 * restraint does not gag typing (§3.4).
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class RestraintInputHandler {

    private RestraintInputHandler() {
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !(event.getEntity() instanceof LocalPlayer local) || local != player) {
            return;
        }
        RestrictionPolicy policy = localPolicy(player);
        if (policy.unrestrictedPolicy()) {
            return;
        }
        if (!policy.permits(RestraintAction.VOLUNTARY_MOVEMENT)) {
            event.getInput().leftImpulse = 0.0F;
            event.getInput().forwardImpulse = 0.0F;
            event.getInput().up = false;
            event.getInput().down = false;
            event.getInput().left = false;
            event.getInput().right = false;
        }
        if (!policy.permits(RestraintAction.JUMP)) {
            event.getInput().jumping = false;
        }
        if (!policy.permits(RestraintAction.SPRINT)) {
            // Only the request is dropped. Clearing an already-sprinting state here would fight the
            // server's own correction, and the server is the side that decides.
            player.setSprinting(false);
        }
    }

    /**
     * The local player's composed policy, from the slots the server told this client about.
     *
     * <p>Derived from definition ids rather than sent as a policy, because the policy is an
     * authorisation decision and shipping one to a client would invite treating the client's copy as
     * the answer. Definition ids are render data the client already needs.
     *
     * <p>The lawful-arrest fold is not applied here: the client is not told about arrest phases, and
     * guessing would either over- or under-restrict the input. The server's answer is the real one
     * either way, so the worst case is an input that feels live for one round trip.
     */
    public static RestrictionPolicy localPolicy(LocalPlayer player) {
        PhysicalRestraintView view = ClientPhysicalRestraintData.get(player.getUUID()).orElse(null);
        if (view == null || view.slots().isEmpty()) {
            return RestrictionPolicy.unrestricted();
        }
        List<net.minecraft.resources.ResourceLocation> active = new ArrayList<>(3);
        for (RestraintSlot slot : RestraintSlot.values()) {
            view.slot(slot).ifPresent(slotView -> active.add(slotView.definitionId()));
        }
        return RestrictionResolver.compose(active);
    }
}
