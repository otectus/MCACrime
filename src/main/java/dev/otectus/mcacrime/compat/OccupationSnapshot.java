package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.job.HistoricalProfessionKind;
import dev.otectus.mcacrime.state.world.WorksiteRef;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.item.trading.MerchantOffers;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Everything one occupation transaction must be able to put back (0.7.2 §9.3 step 4).
 *
 * <p>Deliberately not a clone of the entity. Copying a villager would also copy its combat state, its
 * social memories and its relationship graph, and restoring that wholesale would undo whatever else
 * happened during the transaction. What is captured here is exactly the occupational surface the
 * transaction touches, plus the two pieces of MCA state a profession change alters as a side effect:
 * the clothing string MCA randomises and the family-tree profession it rewrites.
 *
 * <p>Two fields carry a readability flag alongside their value, because MCA's own accessors can be
 * absent. "This villager wears nothing recorded" and "we could not ask what it wears" must not both
 * arrive as {@code null}: the first is restorable and the second must be left alone.
 *
 * @param offersPresent whether the villager had <em>initialised</em> offers at all. {@code getOffers}
 *                      lazily builds them, so calling it to find out would itself create the thing it
 *                      was asked about; the nullable field is read directly instead and this flag is
 *                      the difference between restoring "no trades yet" and "an empty trade list".
 * @param offers        a detached deep copy of the initialised offers. A copy rather than the live
 *                      object because a {@code MerchantOffers} handed back by reference would be
 *                      mutated by the same profession change this snapshot exists to undo. The Forge
 *                      1.20.1 build serialised it to NBT instead; 1.21.1 removed
 *                      {@code MerchantOffers#createTag()} and its {@code CompoundTag} constructor in
 *                      favour of a registry-aware codec, and {@code MerchantOffers#copy()} copies
 *                      every offer, so the copy is as detached as the tag was and needs no registry.
 */
public record OccupationSnapshot(UUID villager,
                                 @Nullable VillagerData villagerData,
                                 int villagerXp,
                                 @Nullable WorksiteRef jobSite,
                                 @Nullable WorksiteRef potentialJobSite,
                                 boolean offersPresent,
                                 @Nullable MerchantOffers offers,
                                 HistoricalProfessionKind professionKind,
                                 @Nullable ResourceLocation professionId,
                                 boolean clothesReadable,
                                 @Nullable String clothes,
                                 boolean familyProfessionReadable,
                                 @Nullable ResourceLocation familyProfessionId,
                                 int despawnDelay) {

    /**
     * True when the snapshot is complete enough to be rolled back to.
     *
     * <p>The one condition is the <em>kind</em> of the previous profession. An
     * {@link HistoricalProfessionKind#UNREADABLE} capture knows a profession existed and does not know
     * which, so there is nothing to put back and the transition must refuse before it mutates anything
     * rather than strand a villager as a Thief. Every other missing piece — absent clothing, an
     * unreadable family entry — degrades the rollback rather than preventing it, and {@code restore}
     * reports that by returning false.
     */
    public boolean restorable() {
        return professionKind != HistoricalProfessionKind.UNREADABLE;
    }

    /** True for MCA's temporary inn occupants, which must never be recruited (verified §"7.6.20"). */
    public boolean temporaryOccupant() {
        return despawnDelay > 0;
    }
}
