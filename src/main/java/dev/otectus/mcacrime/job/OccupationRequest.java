package dev.otectus.mcacrime.job;

import dev.otectus.mcacrime.state.world.WorksiteRef;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One occupation transition, described completely before any of it is applied (0.7.2 §9.3).
 *
 * @param worksite        the station this transition binds to, or {@code null} for the documented
 *                        unbound states. Dimension-qualified, per spec §10.2.
 * @param adoptExisting   true when the villager already holds the native ticket for {@link #worksite}
 *                        — the native-rebinding path, which must <em>not</em> take a second one
 * @param rebindOnly      true when the villager is already a committed Thief and only its claim and
 *                        record are changing. Spec §9.3: a same-profession rebind needs memory and
 *                        record changes, not another profession setter and another brain refresh.
 * @param establishedAlready whether the occupation has already passed the establishment milestone, so
 *                        a rebinding established Thief is not demoted to novice by getting a station
 */
public record OccupationRequest(UUID villager, OccupationSource source, @Nullable WorksiteRef worksite,
                                boolean adoptExisting, boolean rebindOnly, boolean establishedAlready,
                                boolean wildOrigin) {

    public static OccupationRequest station(UUID villager, OccupationSource source, WorksiteRef worksite,
                                            boolean adoptExisting, boolean establishedAlready) {
        return new OccupationRequest(villager, source, worksite, adoptExisting, false, establishedAlready, false);
    }

    /** The stationless exception (spec §10.5) and the migration/grandfathering path. */
    public static OccupationRequest unbound(UUID villager, OccupationSource source, boolean wildOrigin) {
        return new OccupationRequest(villager, source, null, false, false, true, wildOrigin);
    }

    /** The state this request commits to when it succeeds. */
    public OccupationStatus targetStatus() {
        if (worksite == null) {
            return OccupationStatus.ESTABLISHED_UNBOUND;
        }
        return establishedAlready ? OccupationStatus.ACTIVE_BOUND_ESTABLISHED : OccupationStatus.ACTIVE_BOUND_NOVICE;
    }
}
