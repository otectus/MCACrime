package dev.otectus.mcacrime.action;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Non-sensitive result returned to every action entry point.
 *
 * <p>{@code args} exist because {@link #code} alone is not enough to show anyone: seven of the
 * outcome keys carry a {@code %s}, and the HUD used to render the raw key with the placeholder still
 * in it. The code stays the identity of the outcome, for logging and for the replay cache; the
 * arguments are what turns it into a sentence. Keep them network-safe — numbers, strings and
 * {@link Component}s — because {@link #message()} is what goes on the wire.
 */
public record ActionResult(boolean accepted, String code, List<Object> args) {

    public ActionResult {
        args = args == null ? List.of() : List.copyOf(args);
    }

    public static ActionResult accepted(String code, Object... args) {
        return new ActionResult(true, code, List.of(args));
    }

    public static ActionResult rejected(String code, Object... args) {
        return new ActionResult(false, code, List.of(args));
    }

    /** The outcome as a player reads it: the key resolved against its arguments. */
    public Component message() {
        return Component.translatable(code, args.toArray());
    }
}
