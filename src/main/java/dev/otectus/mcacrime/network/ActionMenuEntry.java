package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.action.ActionCategory;
import dev.otectus.mcacrime.action.ActionDuration;
import dev.otectus.mcacrime.action.ActionLegality;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * One presentation row. Everything here is a translation key or an enum -- never a number the server
 * computed about the target, because spec 8.4 forbids exposing exact purse balances, compliance
 * rolls, witness confidence or guard-response odds to the client.
 *
 * <p>{@code available} is a server verdict at menu-build time and is re-derived when the row is
 * clicked, so a modified client that flips it gains nothing.
 */
public record ActionMenuEntry(ResourceLocation actionId,
                              String labelKey,
                              String descriptionKey,
                              ActionCategory category,
                              ActionLegality legality,
                              ActionDuration duration,
                              List<String> requirementKeys,
                              boolean hostile,
                              boolean available,
                              String reasonKey) {

    /** Caps the requirement strip so a malformed or hostile packet cannot make the client draw forever. */
    public static final int MAX_REQUIREMENTS = 8;

    public ActionMenuEntry {
        requirementKeys = List.copyOf(requirementKeys);
    }
}
