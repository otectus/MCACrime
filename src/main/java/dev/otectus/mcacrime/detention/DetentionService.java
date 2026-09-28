package dev.otectus.mcacrime.detention;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import dev.otectus.mcacrime.restraint.RestraintService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.tether.TetherService;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Who is in which device, and the one place that answer changes (0.7.5 M4.5, §3.9).
 *
 * <p>Occupancy is a {@link DetentionRecord} in world data keyed by the device's canonical position,
 * with a back-reference on the subject's physical state — <b>not</b> a flag on the occupant, which is
 * how the source stores it ({@code mixin/PlayerMixin.java:76-79}). Two consequences fall straight out
 * of that and both are requirements: villagers are detainable, because nothing here is a player field;
 * and a chunk unload is not a destruction, because the row outlives the block entity that draws it.
 *
 * <p>Closing a device is an <b>atomic claim</b>. Two players clicking one pillory in the same tick
 * produce one success and one refusal, because the claim is a single conditional write against the
 * table rather than a read followed by a write.
 *
 * <p>Lifecycle cleanup is deliberately <em>outside</em> the breakout toggle. The source nests its
 * stale-occupancy release inside {@code ALLOW_BREAKING_OUT_OF_PILLORY}
 * ({@code mixin/PlayerMixin.java:168-178}), so a server that turns breaking out off also turns off
 * the release of prisoners whose device no longer exists.
 */
public final class DetentionService {

    /** Why a device let somebody go. Chooses the message and what the legal bridge is told. */
    public enum ReleaseReason {
        /** Somebody opened it. */
        OPENED,
        /** The occupant broke it open from the inside. */
        BROKE_OUT,
        /** The device was destroyed, or one half of it was. */
        DEVICE_GONE,
        /** The occupant died. */
        OCCUPANT_DIED,
        /** A command, or a legal transition that owns this detention. */
        ADMINISTRATIVE
    }

    /** Why a claim was refused. */
    public enum Refusal {
        NONE,
        NO_SUBJECT,
        NO_DEVICE,
        DEVICE_OCCUPIED,
        SUBJECT_DETAINED,
        STORE_REFUSED
    }

    private DetentionService() {
    }

    // --- queries ---------------------------------------------------------------------------------

    /** The detention at one device, or empty. */
    public static Optional<DetentionRecord> at(@Nullable CrimeWorldData data,
                                               @Nullable ResourceLocation dimension,
                                               @Nullable BlockPos devicePos) {
        if (data == null || devicePos == null) {
            return Optional.empty();
        }
        for (DetentionRecord record : data.detentions()) {
            if (devicePos.equals(record.devicePos())
                    && (dimension == null || dimension.equals(record.dimension()))) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    /** The detention holding {@code subject}, or empty. */
    public static Optional<DetentionRecord> forSubject(@Nullable CrimeWorldData data,
                                                       @Nullable UUID subject) {
        return data == null ? Optional.empty()
                : Optional.ofNullable(data.detentionForSubject(subject));
    }

    /** Whether this device currently holds anybody. */
    public static boolean occupied(@Nullable CrimeWorldData data, @Nullable ResourceLocation dimension,
                                   @Nullable BlockPos devicePos) {
        return at(data, dimension, devicePos).isPresent();
    }

    /**
     * Whether a claim would stand, as a pure function of the two existing answers.
     *
     * <p>Pure so the contested case can be asserted without a world: the whole point of the atomic
     * claim is what happens when two closers arrive together, and that is decidable from "is the
     * device taken" and "is the subject already held".
     */
    public static Refusal check(boolean deviceOccupied, boolean subjectDetained,
                                boolean subjectPresent, boolean devicePresent) {
        if (!subjectPresent) {
            return Refusal.NO_SUBJECT;
        }
        if (!devicePresent) {
            return Refusal.NO_DEVICE;
        }
        if (deviceOccupied) {
            return Refusal.DEVICE_OCCUPIED;
        }
        if (subjectDetained) {
            return Refusal.SUBJECT_DETAINED;
        }
        return Refusal.NONE;
    }

    /** The lang key that explains one refusal, enumerated by the coverage test. */
    public static String messageKey(@Nullable Refusal refusal) {
        if (refusal == null) {
            return "mcacrime.detention.refused";
        }
        return switch (refusal) {
            case NONE -> "mcacrime.detention.closed";
            case NO_SUBJECT -> "mcacrime.detention.no_subject";
            case NO_DEVICE -> "mcacrime.detention.no_device";
            case DEVICE_OCCUPIED -> "mcacrime.detention.occupied";
            case SUBJECT_DETAINED -> "mcacrime.detention.already_detained";
            case STORE_REFUSED -> "mcacrime.detention.refused";
        };
    }

    // --- claiming ---------------------------------------------------------------------------------

    /**
     * Puts {@code subject} in the device at {@code devicePos}, or refuses.
     *
     * <p>One conditional write. The refusal is returned rather than thrown because every branch of it
     * is something a player needs to be told, and because a device that silently does nothing is the
     * bug report this replaces.
     */
    public static Refusal claim(@Nullable CrimeWorldData data, @Nullable LivingEntity subject,
                                DetentionKind kind, @Nullable ResourceLocation dimension,
                                @Nullable BlockPos devicePos, String pose) {
        return claim(data, subject, kind, dimension, devicePos, pose, null, false);
    }

    /**
     * The same, naming the actor who closed the device (0.7.5 M6.3).
     *
     * <p>The actor exists for one reason: the subject's privacy policy is a rule about <em>who</em>
     * may do this, and a device that could not say who closed it would have to be exempt from it. A
     * lawful closure - a guard putting an arrested prisoner in a pillory, an operator command - passes
     * {@code lawful} and is outside the policy entirely.
     */
    public static Refusal claim(@Nullable CrimeWorldData data, @Nullable LivingEntity subject,
                                DetentionKind kind, @Nullable ResourceLocation dimension,
                                @Nullable BlockPos devicePos, String pose,
                                @Nullable java.util.UUID actor, boolean lawful) {
        if (subject == null || !subject.isAlive() || !RestraintService.restrainable(subject)) {
            return Refusal.NO_SUBJECT;
        }
        Refusal refusal = claim(data, subject.getUUID(),
                subject instanceof net.minecraft.world.entity.player.Player, kind, dimension, devicePos,
                pose);
        if (refusal == Refusal.NONE) {
            RestraintService.publish(subject, data);
        }
        return refusal;
    }

    /**
     * The claim itself, against the store rather than against an entity.
     *
     * <p>Split out so the contested case — two closers, one device, one tick — can be asserted with
     * no world to spawn anybody in. The entity overload above adds exactly two things: the eligibility
     * test, and telling the clients afterwards.
     */
    public static Refusal claim(@Nullable CrimeWorldData data, @Nullable UUID subject,
                                boolean subjectIsPlayer, DetentionKind kind,
                                @Nullable ResourceLocation dimension, @Nullable BlockPos devicePos,
                                String pose) {
        Refusal refusal = check(occupied(data, dimension, devicePos),
                subject != null && forSubject(data, subject).isPresent(),
                subject != null, data != null && devicePos != null);
        if (refusal != Refusal.NONE) {
            return refusal;
        }
        PhysicalRestraintState state = data.physicalRestraint(subject);
        if (state == null) {
            state = PhysicalRestraintState.empty(subject, subjectIsPlayer, dimension);
        }
        DetentionRecord record = DetentionRecord.of(UUID.randomUUID(), subject, kind, dimension,
                devicePos, state.generation(), pose);
        if (!data.putDetention(record)) {
            return Refusal.STORE_REFUSED;
        }
        if (!data.putPhysicalRestraint(state.withDetention(record.id()))) {
            data.removeDetention(record.id());
            return Refusal.STORE_REFUSED;
        }
        // A device outranks a chain: the chain is suspended, not dropped, so opening the device gives
        // it back rather than leaving an untied prisoner and a chain on the floor (§3.6).
        TetherService.forSubject(data, subject)
                .forEach(tether -> TetherService.suspend(data, tether.id(), true));
        return Refusal.NONE;
    }

    // --- releasing ---------------------------------------------------------------------------------

    /**
     * Lets one detention go, at most once.
     *
     * <p>Like the tether's detach, the table write is the gate: a device broken by a player and by an
     * explosion in the same tick releases one occupant, not two.
     *
     * @return the record that ended, or empty when somebody else had already ended it
     */
    public static Optional<DetentionRecord> release(@Nullable MinecraftServer server,
                                                    @Nullable CrimeWorldData data,
                                                    @Nullable UUID detentionId, ReleaseReason reason) {
        if (data == null || detentionId == null) {
            return Optional.empty();
        }
        DetentionRecord record = data.detention(detentionId);
        if (record == null || !data.removeDetention(detentionId)) {
            return Optional.empty();
        }
        PhysicalRestraintState state = data.physicalRestraint(record.subject());
        if (state != null && detentionId.equals(state.detentionId())) {
            data.putPhysicalRestraint(state.withDetention(null));
        }
        // Whatever the device suspended resumes. A prisoner taken out of a pillory is still chained
        // to the fence they were chained to when they went in.
        TetherService.forSubject(data, record.subject())
                .forEach(tether -> TetherService.suspend(data, tether.id(), false));
        Entity subject = TetherService.find(server, record.subject());
        if (subject instanceof LivingEntity living) {
            RestraintService.publish(living, data);
        }
        // Post-commit (M6.4). Every ending comes through here, including the ones nobody chose, so a
        // companion cannot be left believing a device still holds somebody it does not.
        dev.otectus.mcacrime.restraint.PhysicalApiEvents.detentionEnded(record.subject(),
                subject instanceof net.minecraft.world.entity.player.Player, record.kind().id(),
                outcome(reason), null);
        return Optional.of(record);
    }

    /** How one release reads to a companion mod (M6.4). */
    private static dev.otectus.mcacrime.api.event.DetentionOutcomeEvent.Outcome outcome(
            @Nullable ReleaseReason reason) {
        if (reason == null) {
            return dev.otectus.mcacrime.api.event.DetentionOutcomeEvent.Outcome.RELEASED;
        }
        return switch (reason) {
            case BROKE_OUT -> dev.otectus.mcacrime.api.event.DetentionOutcomeEvent.Outcome.BROKE_OUT;
            case DEVICE_GONE -> dev.otectus.mcacrime.api.event.DetentionOutcomeEvent.Outcome.DEVICE_GONE;
            case OCCUPANT_DIED ->
                    dev.otectus.mcacrime.api.event.DetentionOutcomeEvent.Outcome.OCCUPANT_DIED;
            case OPENED, ADMINISTRATIVE ->
                    dev.otectus.mcacrime.api.event.DetentionOutcomeEvent.Outcome.RELEASED;
        };
    }

    /** Releases whoever is in the device at this position. */
    public static Optional<DetentionRecord> releaseAt(@Nullable MinecraftServer server,
                                                      @Nullable CrimeWorldData data,
                                                      @Nullable ResourceLocation dimension,
                                                      @Nullable BlockPos devicePos,
                                                      ReleaseReason reason) {
        return at(data, dimension, devicePos)
                .flatMap(record -> release(server, data, record.id(), reason));
    }

    /** Releases whatever device is holding this subject. */
    public static Optional<DetentionRecord> releaseSubject(@Nullable MinecraftServer server,
                                                           @Nullable CrimeWorldData data,
                                                           @Nullable UUID subject,
                                                           ReleaseReason reason) {
        return forSubject(data, subject)
                .flatMap(record -> release(server, data, record.id(), reason));
    }

    // --- breaking out --------------------------------------------------------------------------------

    /** {@code detention.pilloryBreakoutTransitions}, defaulting to the documented 100. */
    public static int breakoutTransitions() {
        try {
            return McaCrimeConfig.COMMON.pilloryBreakoutTransitions.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return 100;
        }
    }

    /**
     * Whether this much accumulated work breaks the device open.
     *
     * <p>Pure, and zero means never: a server that has turned breaking out off has turned off exactly
     * that, and nothing else. The stale-occupancy sweep below is not gated on it.
     */
    public static boolean breaksOut(int work, int limit) {
        return limit > 0 && work >= limit;
    }

    /**
     * Records one crouch transition against a detention.
     *
     * @return true when that transition was the one that broke the device open
     */
    public static boolean addBreakoutWork(@Nullable CrimeWorldData data, @Nullable UUID detentionId) {
        DetentionRecord record = data == null ? null : data.detention(detentionId);
        if (record == null) {
            return false;
        }
        int limit = breakoutTransitions();
        if (limit <= 0) {
            return false;
        }
        DetentionRecord advanced = record.withEscapeWork(record.escapeWork() + 1);
        if (!data.putDetention(advanced)) {
            return false;
        }
        return breaksOut(advanced.escapeWork(), limit);
    }

    // --- lifecycle -----------------------------------------------------------------------------------

    /**
     * Releases every detention whose device is no longer there.
     *
     * <p>Runs regardless of the breakout toggle, which is the coupling the source gets wrong. Only
     * <b>loaded</b> positions are judged: an unloaded chunk is not a missing device, and treating it
     * as one would free every prisoner in a prison nobody had walked into this session.
     *
     * @return how many stale occupancies were released
     */
    public static int sweep(@Nullable MinecraftServer server, @Nullable CrimeWorldData data,
                            DevicePredicate stillThere) {
        if (server == null || data == null) {
            return 0;
        }
        int released = 0;
        for (DetentionRecord record : new ArrayList<>(data.detentions())) {
            if (!record.valid()) {
                release(server, data, record.id(), ReleaseReason.DEVICE_GONE);
                released++;
                continue;
            }
            ServerLevel level = levelOf(server, record.dimension());
            if (level == null || !level.isLoaded(record.devicePos())) {
                continue; // unloaded is not gone
            }
            if (!stillThere.test(level, record.devicePos(), record.kind())) {
                release(server, data, record.id(), ReleaseReason.DEVICE_GONE);
                released++;
            }
        }
        return released;
    }

    /** "Is the device of this kind still standing here?", so the sweep needs no block imports. */
    @FunctionalInterface
    public interface DevicePredicate {
        boolean test(Level level, BlockPos pos, DetentionKind kind);
    }

    @Nullable
    public static ServerLevel levelOf(@Nullable MinecraftServer server, @Nullable ResourceLocation id) {
        if (server == null || id == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().equals(id)) {
                return level;
            }
        }
        return null;
    }

    /** Every live detention, for {@code /crime debug} and the device tick. */
    public static List<DetentionRecord> all(@Nullable CrimeWorldData data) {
        return data == null ? List.of() : data.detentions();
    }
}
