package dev.otectus.mcacrime.action;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Non-sensitive result returned to every action entry point.
 *
 * <p>{@code code} stays a bare translation key, because that is what logging, the replay cache and
 * every equality check in the tests compare on. The {@code args} are carried alongside it purely so
 * {@link #message()} can fill them in: seven outcome keys hold a {@code %s} (a fine, a ransom, a
 * name), and the HUD used to render the key with no arguments and show the player the placeholder.
 *
 * <p>Arguments must be {@code long}, {@code int}, {@code String} or {@link Component}. Anything else
 * is not a thing a translation argument can be, and nothing here would be able to send it.
 */
public record ActionResult(boolean accepted, String code, List<Object> args) {

    public ActionResult {
        args = args == null ? List.of() : List.copyOf(args);
    }

    public ActionResult(boolean accepted, String code) { this(accepted, code, List.of()); }

    /** Accepted; {@code args} fill the key's placeholders, and are usually absent. */
    public static ActionResult accepted(String code, Object... args) {
        return new ActionResult(true, code, List.of(args));
    }

    /** Rejected; {@code args} fill the key's placeholders, and are usually absent. */
    public static ActionResult rejected(String code, Object... args) {
        return new ActionResult(false, code, List.of(args));
    }

    /** The key with its arguments filled in — what a player should actually be shown. */
    public Component message() {
        return Component.translatable(code, args.toArray());
    }
}
