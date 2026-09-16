package dev.otectus.mcacrime.job;

import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.compat.OccupationCompat;
import dev.otectus.mcacrime.compat.OccupationSnapshot;
import dev.otectus.mcacrime.state.world.WorksiteRef;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * {@link OccupationMutator} against a real loaded villager (0.7.2).
 *
 * <p>Every method here is a thin call into {@code OccupationCompat}. The ordering, the decisions and
 * the rollback live in {@link OccupationTransaction}; this class knows only how to perform one step
 * and how to say whether it worked.
 */
final class EntityOccupationMutator implements OccupationMutator {

    private final ServerLevel level;
    private final Entity entity;
    private final ResourceLocation profession;
    /** A ticket Crime already took for this villager and persisted on its record, or null. */
    @Nullable
    private final WorksiteRef ownedReservation;

    EntityOccupationMutator(ServerLevel level, Entity entity, ResourceLocation profession,
                            @Nullable WorksiteRef ownedReservation) {
        this.level = level;
        this.entity = entity;
        this.profession = profession;
        this.ownedReservation = ownedReservation;
    }

    @Override
    public Optional<OccupationSnapshot> snapshot() {
        return OccupationCompat.capture(entity);
    }

    @Override
    public boolean reserveTicket(WorksiteRef site) {
        return sameLevel(site)
                && OccupationCompat.takeExact(level, site.pos(), CrimePoiTypes.MASK_STATION_KEY).isPresent();
    }

    /**
     * The native-rebinding path's claim check.
     *
     * <p>There is no "who owns this ticket" API — a POI record carries a count, not a holder — so
     * ownership can only ever be inferred from a successful reservation or from the entity's own
     * native potential claim. That is exactly what this asks: the station exists, it is out of free
     * tickets, and this villager is the one standing at it holding the potential-site memory.
     */
    @Override
    public boolean adoptTicket(WorksiteRef site) {
        if (!sameLevel(site) || !OccupationCompat.poiExists(level, site.pos(), CrimePoiTypes.MASK_STATION_KEY)) {
            return false;
        }
        if (OccupationCompat.freeTickets(level, site.pos()) != 0) {
            return false;
        }
        return site.equals(ownedReservation)
                || site.sameAs(OccupationCompat.potentialJobSite(entity).orElse(null))
                || site.sameAs(OccupationCompat.jobSite(entity).orElse(null));
    }

    @Override
    public void releaseTicket(WorksiteRef site) {
        if (sameLevel(site)) {
            OccupationCompat.releaseTicket(level, site.pos());
        }
    }

    @Override
    public boolean releaseOldJobSite(WorksiteRef site) {
        return sameLevel(site) && OccupationCompat.releaseTicket(level, site.pos());
    }

    @Override
    public void clearOccupationalMemories() {
        OccupationCompat.clearOccupationalMemories(entity);
    }

    @Override
    public boolean applyProfession() {
        if (!OccupationCompat.applyProfessionVerified(entity, profession)) {
            return false;
        }
        // MCA's own setter keeps the family tree in step; doing it again is harmless and covers a
        // build whose setter changed the entity but not the node.
        OccupationCompat.syncFamilyProfession(entity, profession);
        return true;
    }

    @Override
    public boolean clearOffers() {
        return OccupationCompat.clearOffers(entity);
    }

    @Override
    public boolean setJobSite(WorksiteRef site) {
        GlobalPos global = site.toGlobalPos(level);
        if (global == null) {
            return false;
        }
        OccupationCompat.setJobSite(entity, global);
        return site.sameAs(OccupationCompat.jobSite(entity).orElse(null));
    }

    @Override
    public boolean applyXpFloor() {
        return OccupationCompat.applyXpFloor(entity) && OccupationCompat.villagerXp(entity) >= 1;
    }

    @Override
    public boolean verify(@Nullable WorksiteRef site) {
        if (!profession.equals(McaCompat.getProfessionId(entity).orElse(null))) {
            return false;
        }
        if (site == null) {
            return true;
        }
        return sameLevel(site)
                && site.sameAs(OccupationCompat.jobSite(entity).orElse(null))
                && OccupationCompat.poiExists(level, site.pos(), CrimePoiTypes.MASK_STATION_KEY)
                && OccupationCompat.freeTickets(level, site.pos()) == 0;
    }

    @Override
    public boolean restore(OccupationSnapshot snapshot) {
        return OccupationCompat.restore(entity, snapshot, level);
    }

    /**
     * A site in another dimension is never operated on from here.
     *
     * <p>Reaching a POI manager belonging to a level this entity is not in would either force-load a
     * chunk or silently act on the wrong world; both are forbidden by spec §10.2.
     */
    private boolean sameLevel(@Nullable WorksiteRef site) {
        return site != null && site.matches(level);
    }
}
