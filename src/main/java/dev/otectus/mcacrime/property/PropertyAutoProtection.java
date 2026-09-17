package dev.otectus.mcacrime.property;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.compat.TownsteadBridge;
import dev.otectus.mcacrime.compat.TownsteadBuildingRoles;
import dev.otectus.mcacrime.compat.TownsteadBuildingView;
import dev.otectus.mcacrime.compat.TownsteadCapability;
import dev.otectus.mcacrime.facility.FacilityAssignment;
import dev.otectus.mcacrime.facility.FacilityRole;
import dev.otectus.mcacrime.facility.TownsteadBuildingRef;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;
import java.util.List;

/**
 * Writes the property policies MCA: Crime can honestly derive, and nothing else.
 *
 * <h2>Why this is a separate switch that is off by default</h2>
 *
 * <p>§10.1 allows automatic protection of generated village property only as a later, separate mode,
 * and requires it to distinguish generated property from player construction. Turning it on is an
 * operator saying "yes, treat the buildings this settlement generated as owned". Until they do, the
 * only policies in a world are ones somebody wrote.
 *
 * <h2>What it will derive, and what it will not</h2>
 *
 * <p>It derives from two sources, both of which are already explicit decisions rather than guesses:
 *
 * <ul>
 *   <li>Facilities an operator assigned whose role is one MCA: Crime holds goods in — evidence storage
 *       and jail cells. These are exactly the containers §10.2 wants excluded from ordinary settlement
 *       sourcing.</li>
 *   <li>Where building enumeration is available, the recognised building at each of those anchors, so
 *       the claim covers the building rather than one block of it.</li>
 * </ul>
 *
 * <p>It never walks the world looking for chests, and it never claims a building because it looks
 * valuable. Every policy it writes carries a derived id, so running it again rewrites the same rows
 * rather than filling the table with duplicates, and it never touches a policy an operator wrote.
 */
public final class PropertyAutoProtection {

    /** The operator name recorded on a policy nobody typed. */
    public static final String DECLARED_BY = "mcacrime (generated)";

    private PropertyAutoProtection() {
    }

    /**
     * Derives every policy that can be derived, once.
     *
     * @return how many policies were written or rewritten
     */
    public static int sweep(@Nullable MinecraftServer server) {
        if (server == null || !PropertyRegistry.autoProtectEnabled()) {
            return 0;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        int written = 0;
        for (FacilityAssignment facility : data.facilities()) {
            if (!protects(facility.role())) {
                continue;
            }
            ServerLevel level = levelOf(server, facility.ref().dimension());
            if (level == null) {
                continue; // a dimension this server no longer has; the assignment stays, unprotected
            }
            written += protect(server, level, facility);
        }
        if (written > 0) {
            McaCrime.LOGGER.info("MCA: Crime property law protected {} settlement container(s) or "
                    + "building(s) derived from assigned facilities. Nothing an operator declared by hand "
                    + "was changed.", written);
        }
        return written;
    }

    /**
     * Writes the policies for one facility.
     *
     * <p>Always the anchor, because that is a block MCA: Crime knows the position of and can therefore
     * keep settlement workers out of on the sourcing hot path. The building too, but only when
     * enumeration can name it: a building policy whose reference was never validated would claim a
     * building nobody looked at.
     */
    public static int protect(@Nullable MinecraftServer server, @Nullable ServerLevel level,
                              @Nullable FacilityAssignment facility) {
        if (server == null || level == null || facility == null || !PropertyRegistry.autoProtectEnabled()
                || !protects(facility.role())) {
            return 0;
        }
        ResourceLocation dimension = level.dimension().location();
        long now = level.getGameTime();
        int written = 0;

        PropertyPolicy container = PropertyPolicy.container(dimension, facility.anchor(), facility.ref(),
                PropertyOwnerKind.FACILITY, facility.id(), PropertyAccessRule.FORBIDDEN, true,
                PropertySource.GENERATED, DECLARED_BY, now);
        if (writeGenerated(server, container)) {
            written++;
        }

        if (TownsteadBridge.has(TownsteadCapability.BUILDING_ENUMERATION)) {
            for (TownsteadBuildingRef ref : recognisedBuildingsAt(level, facility.anchor())) {
                PropertyPolicy building = PropertyPolicy.building(dimension, ref,
                        PropertyOwnerKind.FACILITY, facility.id(), PropertyAccessRule.FORBIDDEN, true,
                        PropertySource.GENERATED, DECLARED_BY, now);
                if (writeGenerated(server, building)) {
                    written++;
                }
            }
        }
        return written;
    }

    /**
     * The buildings at a position whose type a datapack has given a protecting role.
     *
     * <p>"The types the datapack marks protected" is read off the existing
     * {@code townstead/building_roles} data rather than out of a new field: a pack that already says
     * "this building type is a jail cell" has said that MCA: Crime holds people and goods in it, and
     * inventing a second, parallel way to say the same thing would leave two places to get it wrong.
     */
    private static List<TownsteadBuildingRef> recognisedBuildingsAt(ServerLevel level, BlockPos anchor) {
        List<TownsteadBuildingView> here = TownsteadBridge.buildingsAt(level, anchor).orElse(List.of());
        return here.stream()
                .filter(building -> TownsteadBuildingRoles.recognise(building.type())
                        .map(recognition -> protects(recognition.role()))
                        .orElse(false))
                .map(building -> TownsteadBuildingRef.of(level.dimension().location(), building))
                .toList();
    }

    /**
     * Whether a facility role holds goods MCA: Crime is answerable for.
     *
     * <p>Evidence storage and cells, and nothing else. A guard post holds nobody and stores nothing; a
     * public notice board is meant to be read by everybody. Protecting those would make property law
     * look like it was doing something while protecting nothing worth protecting.
     */
    public static boolean protects(@Nullable FacilityRole role) {
        return role == FacilityRole.EVIDENCE_STORAGE || role == FacilityRole.JAIL_CELL;
    }

    /**
     * Writes a generated policy unless something else already claims that place.
     *
     * <p>The rule that keeps the sweep from being destructive: a generated id collides only with a
     * previous generation of itself, and a hand-declared policy at the same place has a random id and a
     * {@code MANUAL} source, so it is found and left exactly as the operator left it.
     */
    private static boolean writeGenerated(MinecraftServer server, PropertyPolicy policy) {
        CrimeWorldData data = CrimeWorldData.get(server);
        PropertyPolicy existing = policy.position() != null
                ? data.propertyPolicyAt(policy.dimension(), policy.position())
                : data.propertyPolicy(policy.id());
        if (existing != null && !existing.source().regenerable()) {
            return false;
        }
        if (existing != null && existing.id().equals(policy.id())
                && existing.rule() == policy.rule()
                && existing.protectedFromAutoSourcing() == policy.protectedFromAutoSourcing()) {
            return false; // already exactly this; rewriting it would only mark the store dirty
        }
        return data.putPropertyPolicy(policy);
    }

    @Nullable
    private static ServerLevel levelOf(MinecraftServer server, ResourceLocation dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().equals(dimension)) {
                return level;
            }
        }
        return null;
    }
}
