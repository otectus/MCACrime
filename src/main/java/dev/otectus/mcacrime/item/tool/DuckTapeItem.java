package dev.otectus.mcacrime.item.tool;

import dev.otectus.mcacrime.item.RestraintItem;
import dev.otectus.mcacrime.restraint.RestraintFamily;

/**
 * Duct tape: the one item that can restrain all three slots.
 *
 * <p>{@code duck_tape} is the registry path and "Duct Tape" the display name (§3.1) — the path keeps
 * the source's spelling so a datapack written against either reads the same id, and the lang file
 * fixes the word players see.
 *
 * <p>A {@link RestraintItem} rather than a class of its own, because which of {@code duck_tape_arms},
 * {@code duck_tape_legs} and {@code duck_tape_head} is applied is a server decision about which body
 * region was aimed at, and an item that chose would be choosing from the client's aim alone.
 */
public class DuckTapeItem extends RestraintItem {

    public DuckTapeItem(Properties properties) {
        super(RestraintFamily.TAPE, properties);
    }
}
