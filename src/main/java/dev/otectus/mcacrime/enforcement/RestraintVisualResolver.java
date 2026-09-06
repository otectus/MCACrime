package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.captivity.RestraintType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Optional;
import java.util.UUID;

/**
 * The one place that decides how a subject is restrained for rendering purposes.
 *
 * <p>Both restraint packets are built from this, which is the point: the login snapshot, the
 * broadcast and the start-tracking send used to be three separate readings of the same state, and
 * three readings are three chances to disagree. A late-joining client seeing a different restraint
 * from everybody else is the exact bug this collapses.
 *
 * <p>Server-side and display-only. It reads authoritative state ({@link RestraintPolicy} for players,
 * the custody table for NPCs) and never writes any.
 */
public final class RestraintVisualResolver {

    private RestraintVisualResolver() {
    }

    /**
     * Resolves what {@code subject} should look like, or {@link RestraintVisualState#none()} when it is
     * not restrained at all.
     *
     * <p>Players and NPCs reach restraint by different routes — an arrest phase for one, a custody
     * record for the other — and the two are combined here rather than at the call sites so a caller
     * only ever has to ask about an entity, not about what kind of entity it is.
     */
    public static RestraintVisualState resolve(MinecraftServer server, Entity subject) {
        if (server == null || !(subject instanceof LivingEntity)) {
            return RestraintVisualState.none();
        }
        if (subject instanceof ServerPlayer player) {
            return resolvePlayer(server, player);
        }
        return resolveNpc(server, subject.getUUID());
    }

    private static RestraintVisualState resolvePlayer(MinecraftServer server, ServerPlayer player) {
        // Both routes into chains, in one reading: the arrest phase and the custody record. Asking the
        // phase alone is what left a kidnapping victim rendered upright and empty-handed while the
        // record on them said "rope".
        Optional<RestraintType> effective = RestraintPolicy.effective(player);
        if (effective.isEmpty()) {
            return RestraintVisualState.none();
        }
        // The policy already resolved the kind, including the cuffs an arrest defaults to when the
        // phase moved ahead of the record. NONE here would be a restraint nobody can see, which for
        // rendering purposes is the same thing as not being restrained.
        RestraintVisualType type = RestraintVisualType.of(effective.get());
        if (type == RestraintVisualType.NONE) {
            return RestraintVisualState.none();
        }
        return new RestraintVisualState(true, type, escortEntityId(player));
    }

    private static RestraintVisualState resolveNpc(MinecraftServer server, UUID subject) {
        Optional<CustodyRecord> record = CustodyRegistry.get(server, subject);
        if (record.isEmpty()) {
            return RestraintVisualState.none();
        }
        RestraintVisualType type = RestraintVisualType.of(record.get().getRestraint());
        if (type == RestraintVisualType.NONE) {
            return RestraintVisualState.none();
        }
        // -1, always: an NPC captive is held by a real vanilla leash, which the client already draws.
        // A mod rope on top of it would be two ropes between the same two entities.
        return new RestraintVisualState(true, type, -1);
    }

    /**
     * The entity id of the guard escorting this player, or {@code -1}.
     *
     * <p>Resolved server-side so the client never scans the level for a UUID every frame the rope is
     * drawn. Ids are per level, so a guard the viewer cannot see simply does not resolve.
     */
    private static int escortEntityId(ServerPlayer player) {
        ArrestState state = ArrestStates.of(player);
        UUID guard = state == null ? null : state.getGuard();
        if (guard == null || !(player.level() instanceof ServerLevel level)) {
            return -1;
        }
        Entity entity = level.getEntity(guard);
        return entity == null ? -1 : entity.getId();
    }

    /** Convenience for a subject known only by id — used when the entity itself is not loaded. */
    public static RestraintVisualState resolve(MinecraftServer server, UUID subject) {
        if (server == null || subject == null) {
            return RestraintVisualState.none();
        }
        ServerPlayer player = server.getPlayerList().getPlayer(subject);
        return player != null ? resolvePlayer(server, player) : resolveNpc(server, subject);
    }
}
