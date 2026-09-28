package dev.otectus.mcacrime.frisk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

/**
 * A short number that changes whenever a slot's contents change (M5.2, spec §11.2 condition 5).
 *
 * <p>The transfer packet echoes the revision the client was shown. If the subject ate the apple, put
 * the sword away or was searched by somebody else in the meantime, the revision no longer matches
 * and the transfer is refused — so a stale screen can never take something other than what it was
 * looking at, and two searchers cannot both take the same stack.
 *
 * <p>It is a fingerprint, not a secret: it says nothing about what the item is beyond what the
 * searcher can already see, and forging one only reproduces the check the server is about to make
 * against the live slot anyway.
 */
public final class FriskRevision {

    /** The revision of an empty slot. Distinct from every occupied one. */
    public static final int EMPTY = 0;

    private FriskRevision() {
    }

    /**
     * The revision built from a stack's three identifying facts.
     *
     * <p>Pure, so the mixing is testable without a registry: a change in any of the three has to
     * change the result, and an empty stack has to map to {@link #EMPTY}.
     */
    public static int combine(int itemHash, int count, int tagHash) {
        if (count <= 0) {
            return EMPTY;
        }
        int hash = 17;
        hash = hash * 31 + itemHash;
        hash = hash * 31 + count;
        hash = hash * 31 + tagHash;
        return hash == EMPTY ? 1 : hash;
    }

    /** The revision of a live stack. */
    public static int of(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return EMPTY;
        }
        var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        CompoundTag tag = stack.getTag();
        return combine(id == null ? 0 : id.hashCode(), stack.getCount(), tag == null ? 0 : tag.hashCode());
    }
}
