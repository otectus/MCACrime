package dev.otectus.mcacrime.mixin.townstead.client;

import dev.otectus.mcacrime.compat.TownsteadBridge;
import dev.otectus.mcacrime.compat.TownsteadMixinStatus;
import dev.otectus.mcacrime.compat.townstead.client.TownsteadDialogueBridge;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts MCA: Crime's law action into Townstead's dialogue screen, and takes it out cleanly again.
 *
 * <h2>Two hooks, two different problems</h2>
 *
 * <p>{@code init()} is the easy half: a button, added at the tail so it lands on top of Townstead's own
 * layout rather than being cleared by it. {@code removed()} is the half the reference plan singles out
 * (§13.1). Townstead's dialogue owns the camera and the HUD and does not treat removal as an ending: it
 * queues the screen to reopen when a line is still typing or a late response is still expected, and only
 * the branch it takes after an explicit user close restores the camera, restores the HUD and tells its
 * own server side that the conversation is over. Replacing the screen from outside therefore does the
 * wrong thing twice — the conversation comes back on top of whatever replaced it, and the camera and HUD
 * are never put back.
 *
 * <h2>Why nothing is cancelled</h2>
 *
 * <p>The obvious shape is {@code ci.cancel()} at the head of {@code removed()}, and it is wrong: the
 * first instruction of that method is {@code super.removed()}, so cancelling would skip vanilla's own
 * screen teardown and this mixin would have to reimplement it. Instead {@link TownsteadDialogueBridge}
 * performs the three pieces of cleanup and then sets the screen's own "the player closed this" flag, so
 * the original method runs unchanged, calls {@code super.removed()}, reads its flag two instructions
 * later and returns without queueing anything. Nothing is skipped and nothing happens twice.
 *
 * <h2>How the target methods are named</h2>
 *
 * <p>{@code init} and {@code removed} are vanilla {@link Screen} members that Townstead overrides — the
 * one case in this package where the target names belong to Minecraft rather than to Townstead. On
 * NeoForge 1.21.1 that costs nothing: production runs on Mojang names, this build generates no refmap,
 * and the single selectors {@code init()V} and {@code removed()V} are the names in both a development
 * run and a shipped jar. (The Forge 1.20.1 baseline has to list an SRG spelling beside each of these;
 * here there is no second spelling to list.) {@code remap = false} throughout, like every other mixin in
 * this package, and {@code require = 0} keeps a Townstead release that moved the screen to a degraded
 * capability rather than a crash.
 *
 * <p>Client-only, listed in the Townstead config's {@code client} array: a dedicated server never loads
 * this class or anything it names.
 */
@Mixin(targets = "com.aetherianartificer.townstead.client.gui.dialogue.RpgDialogueScreen", remap = false)
public abstract class RpgDialogueEntryMixin extends Screen {

    /**
     * Never called. {@link Screen} has no accessible no-argument constructor, so the compiler needs one
     * here; Mixin does not merge mixin constructors into the target.
     */
    private RpgDialogueEntryMixin(Component title) {
        super(title);
    }

    /**
     * Adds the law button once Townstead has finished laying the screen out.
     *
     * <p>TAIL rather than HEAD so the button survives: {@code init()} rebuilds the widget list, and a
     * widget added before that has run is discarded without a trace.
     */
    @Inject(method = "init()V", at = @At("TAIL"), remap = false, require = 0, expect = 1)
    private void mcacrime$addLawAction(CallbackInfo ci) {
        try {
            // Unconditionally, and before the kill switch: this is the fact that separates "Townstead
            // moved the screen" from "nobody has opened a conversation yet" in /crime debug townstead.
            TownsteadMixinStatus.injected(TownsteadMixinStatus.HOOK_DIALOGUE_INIT);
            if (!TownsteadBridge.integrationEnabled()) {
                return;
            }
            Button law = TownsteadDialogueBridge.buildLawButton(this);
            if (law != null) {
                addRenderableWidget(law);
            }
        } catch (Throwable ignored) {
            // Runs inside Townstead's own screen setup. A dialogue without a Crime button is a missing
            // shortcut; an exception here would be a dialogue that does not open at all.
        }
    }

    /**
     * Runs the external-interruption close, when this removal is one.
     *
     * <p>Head, so the flag it sets is in place before the original reads it. An ordinary close — the
     * player pressing escape, or Townstead ending the conversation itself — is not marked, so this
     * returns immediately and Townstead's own behaviour is untouched.
     */
    @Inject(method = "removed()V", at = @At("HEAD"), remap = false, require = 0, expect = 1)
    private void mcacrime$externalInterruptionClose(CallbackInfo ci) {
        try {
            TownsteadMixinStatus.injected(TownsteadMixinStatus.HOOK_DIALOGUE_REMOVED);
            TownsteadDialogueBridge.onScreenRemoved(this);
        } catch (Throwable ignored) {
            // A failed cleanup costs a queued reopen. A thrown one would leave the player on a screen
            // that cannot be closed.
        }
    }
}
