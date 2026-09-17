package dev.otectus.mcacrime.property;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.compat.TownsteadRolePolicy;
import dev.otectus.mcacrime.facility.CrimeFacilityService;
import dev.otectus.mcacrime.facility.TownsteadBuildingRef;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Where property policies are looked up, and the one place that decides whether property law is
 * running at all.
 *
 * <h2>Inert by default, and inert cheaply</h2>
 *
 * <p>{@code townstead.propertyLaw} ships off, and off has to mean a single boolean read on every path
 * that could otherwise do work — a container opened, a server tick, a settlement worker deciding where
 * to source from. {@link #enabled()} is that read, every caller asks it first, and nothing below this
 * line runs on a server that has not switched property law on.
 *
 * <h2>Two lookups, deliberately unequal</h2>
 *
 * <p>A container policy is a map lookup on (dimension, position) and is safe to ask inside another
 * mod's sourcing scan. A building policy is not: answering it means asking the settlement mod which
 * building a position is in, which is a real query. So {@link #protectedFromAutoSourcing} consults only
 * the first, and {@link #policyAt} — reached once when a player opens a container, not per block —
 * consults both.
 */
public final class PropertyRegistry {

    private PropertyRegistry() {
    }

    /**
     * Whether explicit property law is switched on.
     *
     * <p>Any throw reads as off. This is asked from inside a mixin merged into another mod, where a
     * config that is not loaded yet must not become an exception in somebody else's method.
     */
    public static boolean enabled() {
        try {
            return McaCrimeConfig.COMMON.townsteadEnabled.get()
                    && McaCrimeConfig.COMMON.townsteadPropertyLaw.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Whether the bounded automatic protection of recognised property is switched on as well. */
    public static boolean autoProtectEnabled() {
        try {
            return enabled() && McaCrimeConfig.COMMON.townsteadAutoProtectGeneratedProperty.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // --- lookup -----------------------------------------------------------------------------------

    /**
     * The policy covering a position, or empty.
     *
     * <p>Three probes in a deliberate order. The exact block first, because a policy on the chest an
     * operator claimed is the unambiguous answer. Then the other half of a double chest, because a
     * player opening the left half of a claimed chest is opening the claimed chest and no operator
     * should have to say so twice. Then the building the position sits in, which is the broad claim.
     */
    public static Optional<PropertyPolicy> policyAt(@Nullable ServerLevel level, @Nullable BlockPos pos) {
        if (!enabled() || level == null || pos == null || level.getServer() == null) {
            return Optional.empty();
        }
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        PropertyPolicy exact = data.propertyPolicyAt(dimension, pos);
        if (exact != null) {
            return Optional.of(exact);
        }
        BlockPos partner = chestPartner(level, pos);
        if (partner != null) {
            PropertyPolicy half = data.propertyPolicyAt(dimension, partner);
            if (half != null) {
                return Optional.of(half);
            }
        }
        TownsteadBuildingRef ref = CrimeFacilityService.referenceAt(level, pos);
        if (!ref.bound()) {
            return Optional.empty();
        }
        List<PropertyPolicy> building =
                data.propertyPoliciesForBuilding(dimension, ref.villageId(), ref.buildingId());
        return building.isEmpty() ? Optional.empty() : Optional.of(building.get(0));
    }

    /**
     * Whether settlement workers must not source from this block at all.
     *
     * <p>The hot-path question, and the only one the storage hook asks. Container scope only: a
     * building-scope claim cannot be answered without asking the settlement mod which building this
     * block is in, and paying that inside its own sourcing scan would be a cost on every worker on
     * every search. An operator who wants a whole building excluded from sourcing marks its containers.
     */
    public static boolean protectedFromAutoSourcing(@Nullable ServerLevel level, @Nullable BlockPos pos) {
        if (!enabled() || level == null || pos == null || level.getServer() == null) {
            return false;
        }
        PropertyPolicy policy =
                CrimeWorldData.get(level.getServer()).propertyPolicyAt(level.dimension().location(), pos);
        return policy != null && policy.protectedFromAutoSourcing();
    }

    /** The other half of a double chest, or null. */
    @Nullable
    private static BlockPos chestPartner(ServerLevel level, BlockPos pos) {
        try {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof ChestBlock)) {
                return null;
            }
            Direction connected = ChestBlock.getConnectedDirection(state);
            return connected == null ? null : pos.relative(connected);
        } catch (Throwable ignored) {
            // A chunk that unloaded under us. Not knowing is not the same as not protected, but the
            // exact-position probe already ran and is the answer that matters.
            return null;
        }
    }

    // --- writes -----------------------------------------------------------------------------------

    /** Records a policy. False when the store is read-only or full. */
    public static boolean put(@Nullable MinecraftServer server, @Nullable PropertyPolicy policy) {
        return server != null && policy != null && CrimeWorldData.get(server).putPropertyPolicy(policy);
    }

    /** Forgets a policy. Receipts written against it stay, because they are history. */
    public static boolean remove(@Nullable MinecraftServer server, @Nullable UUID id) {
        return server != null && id != null && CrimeWorldData.get(server).removePropertyPolicy(id);
    }

    /** Every policy in the world. A copy. */
    public static List<PropertyPolicy> list(@Nullable MinecraftServer server) {
        return server == null ? List.of() : CrimeWorldData.get(server).propertyPolicies();
    }

    /** Resolves a full or abbreviated policy id, as {@code /crime property list} prints it. */
    public static Optional<PropertyPolicy> byId(@Nullable MinecraftServer server, String id) {
        if (server == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        String needle = id.trim().toLowerCase(Locale.ROOT);
        List<PropertyPolicy> matches = new ArrayList<>();
        for (PropertyPolicy policy : CrimeWorldData.get(server).propertyPolicies()) {
            if (policy.id().toString().toLowerCase(Locale.ROOT).startsWith(needle)) {
                matches.add(policy);
            }
        }
        // An ambiguous abbreviation resolves to nothing rather than to the first match, exactly as
        // /crime facility does: unprotecting the wrong container is not a recoverable mistake.
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    // --- actors -----------------------------------------------------------------------------------

    /**
     * What {@link PropertyAccess} needs to know about a player.
     *
     * <p>A player is never a resident and never a settlement worker, and that is a statement rather
     * than a gap: neither MCA nor the settlement mod records a player as living in or employed by a
     * village, so claiming either would be an invention that decided real charges. A player therefore
     * passes a {@code RESIDENTS} or {@code WORKERS} container only by owning it.
     */
    public static PropertyActor actorOf(@Nullable ServerPlayer player) {
        return player == null ? PropertyActor.unknown()
                : PropertyActor.player(player.getUUID(), PropertyActor.NO_VILLAGE, false);
    }

    /** What {@link PropertyAccess} needs to know about a villager. */
    public static PropertyActor actorOf(@Nullable LivingEntity villager) {
        if (villager == null) {
            return PropertyActor.unknown();
        }
        if (villager instanceof ServerPlayer player) {
            return actorOf(player);
        }
        OptionalInt village = McaCompat.getHomeVillageId(villager);
        TownsteadRolePolicy.Role role = TownsteadRolePolicy.of(villager);
        return PropertyActor.villager(villager.getUUID(),
                village.isPresent() ? village.getAsInt() : PropertyActor.NO_VILLAGE,
                village.isPresent(),
                role.protectedWorker() ? "worker" : null);
    }
}
