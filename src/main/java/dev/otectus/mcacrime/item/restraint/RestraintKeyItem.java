package dev.otectus.mcacrime.item.restraint;

import dev.otectus.mcacrime.restraint.RestraintFamily;
import net.minecraft.world.item.Item;

import org.jetbrains.annotations.Nullable;

/**
 * A key that opens one restraint {@link RestraintFamily} without work (§3.7).
 *
 * <p>A family rather than a definition, and that is the whole reason this type exists: one
 * {@code handcuffs_key} frees arm cuffs and leg cuffs, and a {@code shackles_key} frees neither of
 * them. Comparing definition ids would need a second key item for "the same cuffs on the
 * legs".
 *
 * <p>These are the restraint keys. The separate {@code item/lock/KeyItem} is the <em>lock</em> key,
 * bound to one lock identity; the two are different mechanics that happen to share a word, and the
 * source conflates them behind one base class.
 *
 * <p>No behaviour here either: {@code restraint/RemovalService} decides whether a held key opens
 * what the subject is wearing, on the server, after the interaction has been routed.
 */
public class RestraintKeyItem extends Item {

    private final RestraintFamily opens;

    public RestraintKeyItem(RestraintFamily opens, Properties properties) {
        super(properties);
        this.opens = opens;
    }

    /** The family this key opens. Never null: a key that opens nothing would be a trap item. */
    public RestraintFamily opens() {
        return opens;
    }

    /** The family {@code item} opens, or null when it is not a restraint key. */
    @Nullable
    public static RestraintFamily opensFamily(@Nullable net.minecraft.world.item.Item item) {
        return item instanceof RestraintKeyItem key ? key.opens() : null;
    }
}
