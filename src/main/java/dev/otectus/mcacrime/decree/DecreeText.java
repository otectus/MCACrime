package dev.otectus.mcacrime.decree;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.enforcement.Jurisdictions;
import dev.otectus.mcacrime.util.TickFormat;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;

/**
 * The words a royal decree is announced in (0.7.8). Every player-facing line travels as a translatable
 * component so it localises on the client; only the chronicle line, which Capitals stores as flat text,
 * is rendered on the server, and it carries an English fallback for a server without the language file.
 */
public final class DecreeText {

    private DecreeText() {
    }

    public static Component village(@Nullable MinecraftServer server, @Nullable CrimeCommunityKey community) {
        return Jurisdictions.label(server, community);
    }

    public static Component reason(DecreeReason reason) {
        return Component.translatable(reason.labelKey());
    }

    public static Component fine(long amount) {
        return Currencies.active().format(amount);
    }

    public static String time(long ticks) {
        return TickFormat.compact(Math.max(0L, ticks));
    }

    public static Component name(String name) {
        return Component.literal(name == null || name.isBlank() ? "?" : name);
    }

    /** The chronicle's line for a new decree, flat server-locale text. */
    public static String chronicleIssued(MinecraftServer server, Decree decree) {
        return Component.translatableWithFallback("mcacrime.decree.chronicle.issued",
                "By decree of %s, %s is wanted in %s for %s.",
                decree.issuedByName(), decree.offenderName(), village(server, decree.community()).getString(),
                decree.reason().fallback()).getString();
    }

    /** The chronicle's line for a withdrawn decree. */
    public static String chronicleWithdrawn(MinecraftServer server, Decree decree, String rulerName) {
        return Component.translatableWithFallback("mcacrime.decree.chronicle.withdrawn",
                "%s withdrew the decree against %s in %s.",
                rulerName, decree.offenderName(), village(server, decree.community()).getString()).getString();
    }
}
