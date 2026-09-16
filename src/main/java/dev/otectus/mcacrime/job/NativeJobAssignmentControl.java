package dev.otectus.mcacrime.job;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.OccupationCompat;
import dev.otectus.mcacrime.state.world.CriminalVillagerRecord;
import dev.otectus.mcacrime.state.world.WorksiteRef;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;

import org.jetbrains.annotations.Nullable;

/**
 * Routes vanilla's {@code AssignProfessionFromJobSite} through Crime's transaction for Mask Stations
 * only (0.7.2 §10.1).
 *
 * <p>MCA keeps vanilla's assignment behaviour in its own occupational task list, so this is the path
 * by which a villager holding a potential job site becomes employed by it. It is also the only path
 * that could produce "a visible Thief who has no corresponding Crime role", which spec §10.1 forbids.
 *
 * <p>For every other point of interest this object is invisible: all five {@link BehaviorControl}
 * methods hand straight to the behaviour vanilla built, so a farmer claiming a composter is unaffected
 * down to its debug string.
 *
 * <p>For a Mask Station nothing is delegated at all. {@link #tryStart} returns {@code false} in that
 * branch, which is what suppresses vanilla's own memory mutation, its profession setter, its brain
 * refresh and its success particles — Crime does the equivalent work itself, in one transaction, or
 * refuses and cleans up the reservation.
 */
public final class NativeJobAssignmentControl implements BehaviorControl<Villager> {

    /** Vanilla's own arrival distance for this behaviour. */
    private static final double ARRIVAL_DISTANCE = 2.0D;

    private final BehaviorControl<Villager> delegate;

    public NativeJobAssignmentControl(BehaviorControl<Villager> delegate) {
        this.delegate = delegate;
    }

    @Override
    public Behavior.Status getStatus() {
        return delegate.getStatus();
    }

    @Override
    public boolean tryStart(ServerLevel level, Villager villager, long time) {
        GlobalPos potential = villager.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE).orElse(null);
        boolean station = potential != null
                && level.dimension().equals(potential.dimension())
                && OccupationCompat.poiExists(level, potential.pos(), CrimePoiTypes.MASK_STATION_KEY);
        if (!station) {
            return delegate.tryStart(level, villager, time);
        }
        boolean arrived = potential.pos().getCenter().closerThan(villager.position(), ARRIVAL_DISTANCE);
        WorldCriminalJobService jobs = WorldCriminalJobService.of(level.getServer());
        CriminalVillagerRecord record = jobs.record(villager.getUUID()).orElse(null);
        boolean committed = record != null && record.job() == CriminalJob.THIEF
                && record.status() != OccupationStatus.RETIRED;
        switch (NativeOccupationPolicy.decide(true, arrived, committed,
                McaCrimeConfig.COMMON.enableThieves.get())) {
            case REBIND -> rebind(level, villager, potential, jobs, record);
            case REJECT -> reject(level, villager, potential);
            default -> { }
        }
        return false;
    }

    /**
     * Commits the claim the villager already holds, through the one transaction.
     *
     * <p>{@code adoptExisting} rather than a fresh reservation: MCA's search took the native ticket
     * before it wrote the potential-site memory, so taking another would consume the station's only
     * ticket twice and leave one permanently unreleased.
     */
    private void rebind(ServerLevel level, Villager villager, GlobalPos site,
                        WorldCriminalJobService jobs, @Nullable CriminalVillagerRecord record) {
        boolean established = record != null && record.status().established();
        OccupationTransitionResult result = jobs.requestThiefOccupation(
                OccupationRequest.station(villager.getUUID(), OccupationSource.NATIVE_REBIND, WorksiteRef.of(site),
                        true, established));
        if (!result.committed()) {
            reject(level, villager, site);
            McaCrime.LOGGER.debug("MCA: Crime refused a native Mask Station rebind for {}: {}",
                    villager.getUUID(), result.reason());
        }
    }

    /**
     * Undoes exactly this reservation and nothing else.
     *
     * <p>The memory is erased first so that nothing re-enters this behaviour with a site that is about
     * to lose its ticket, and only the one position is released — a villager's other claims, and any
     * ticket somebody else holds, are none of this branch's business.
     */
    private void reject(ServerLevel level, Villager villager, GlobalPos site) {
        villager.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
        if (level.dimension().equals(site.dimension())) {
            OccupationCompat.releaseTicket(level, site.pos());
        }
    }

    @Override
    public void tickOrStop(ServerLevel level, Villager villager, long time) {
        delegate.tickOrStop(level, villager, time);
    }

    @Override
    public void doStop(ServerLevel level, Villager villager, long time) {
        delegate.doStop(level, villager, time);
    }

    @Override
    public String debugString() {
        return delegate.debugString();
    }
}
