package dev.otectus.mcacrime.property;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An authorised settlement work withdrawal: this worker, this task, this source, this purpose, this
 * operation.
 *
 * <h2>Why it exists</h2>
 *
 * <p>§10.4 asks for a scoped context owned by the task doing the work, created only by server-side
 * task code, and used to permit exactly the collection a task genuinely needs — seeds, fuel, a tool,
 * the deposit of its output — without turning "is a worker" into a blanket licence for arbitrary NPC
 * theft. {@link TransferAttribution} spends it: a withdrawal covered by a live context is authorised
 * work and is never theft, whatever the access rule would otherwise have said.
 *
 * <h2>Where the authority actually lives</h2>
 *
 * <p>Not in the constructor. A record's canonical constructor cannot be less accessible than the
 * record, so the value itself is constructible by anybody; making the type package-private instead
 * would only move the problem, because the settlement task code that has to declare one is not in this
 * package. So the gate is the <em>register</em>: {@link #issue} and {@link #declare} both demand a
 * live server on its own thread, and {@link TransferAttribution} consults nothing but the register.
 * A context built anywhere else is an inert value object that exempts nobody.
 *
 * <p>The register is memory-only and every entry expires. An authorisation that outlived the task that
 * asked for it — a crash, an unloaded chunk, a cancelled recipe — would quietly exempt a villager from
 * property law for the rest of the save, so the worst a lost context can cost is its lease.
 *
 * @param worker      the villager doing the work
 * @param taskId      the settlement task the withdrawal belongs to
 * @param purpose     what the items are for, in the task's own vocabulary
 * @param operationId one withdrawal, so a receipt can name it
 * @param dimension   the level the source is in
 * @param source      the container being drawn from
 * @param issuedAt    the game time it was declared
 * @param expiresAt   the game time it lapses at
 */
public record WorkTransferContext(UUID worker, ResourceLocation taskId, String purpose, UUID operationId,
                                  ResourceLocation dimension, BlockPos source, long issuedAt,
                                  long expiresAt) {

    /** How long an authorisation lives when the caller does not say. One minute of work. */
    public static final long DEFAULT_LEASE_TICKS = 1200L;

    /** How many authorisations may be live at once. A settlement has nothing like this many tasks. */
    private static final int MAX_LIVE = 256;

    private static final Map<UUID, WorkTransferContext> LIVE = new ConcurrentHashMap<>();

    public WorkTransferContext {
        if (worker == null || taskId == null || operationId == null || dimension == null || source == null) {
            throw new IllegalArgumentException("a work transfer context must name a worker, task and source");
        }
        purpose = purpose == null || purpose.isBlank() ? "work" : purpose;
        source = source.immutable();
    }

    /**
     * Declares an authorisation from server task code, and registers it.
     *
     * <p>Empty rather than a throw for every refusal. This is called from task code that has work to do
     * either way, and a context it could not get simply means the withdrawal is evaluated under the
     * ordinary access rules rather than as authorised work.
     */
    public static Optional<WorkTransferContext> issue(@Nullable MinecraftServer server, @Nullable UUID worker,
                                                      @Nullable ResourceLocation taskId, String purpose,
                                                      @Nullable ResourceLocation dimension,
                                                      @Nullable BlockPos source, long leaseTicks) {
        if (server == null || !server.isSameThread() || worker == null || taskId == null
                || dimension == null || source == null) {
            return Optional.empty();
        }
        long now = server.overworld() == null ? 0L : server.overworld().getGameTime();
        long lease = leaseTicks <= 0 ? DEFAULT_LEASE_TICKS : leaseTicks;
        WorkTransferContext context = new WorkTransferContext(worker, taskId, purpose, UUID.randomUUID(),
                dimension, source, now, now + lease);
        return declare(server, context) ? Optional.of(context) : Optional.empty();
    }

    /**
     * Registers an authorisation built elsewhere. The server thread is the credential.
     *
     * @return false when there is no server, the caller is off-thread, or the register is full
     */
    public static boolean declare(@Nullable MinecraftServer server, @Nullable WorkTransferContext context) {
        if (server == null || !server.isSameThread() || context == null) {
            return false;
        }
        if (LIVE.size() >= MAX_LIVE && !LIVE.containsKey(context.worker())) {
            return false;
        }
        LIVE.put(context.worker(), context);
        return true;
    }

    /** The register entry point tests use, where there is no server to be on the thread of. */
    static boolean declareForTest(WorkTransferContext context) {
        if (context == null) {
            return false;
        }
        LIVE.put(context.worker(), context);
        return true;
    }

    /** Whether this authorisation is still in force. */
    public boolean live(long now) {
        return expiresAt > now;
    }

    /**
     * Whether this authorisation covers a withdrawal from {@code pos} in {@code level}.
     *
     * <p>The source has to match. §10.4 is explicit that a role label is not a blanket exemption: the
     * worker, the source and the operation all have to line up with an active task, or the withdrawal
     * is judged like anybody else's.
     */
    public boolean covers(@Nullable ResourceLocation level, @Nullable BlockPos pos, long now) {
        return live(now) && dimension.equals(level) && source.equals(pos);
    }

    /** The live authorisation for one worker at a source, or empty. */
    public static Optional<WorkTransferContext> current(@Nullable UUID worker, @Nullable ResourceLocation level,
                                                        @Nullable BlockPos pos, long now) {
        if (worker == null) {
            return Optional.empty();
        }
        WorkTransferContext context = LIVE.get(worker);
        if (context == null) {
            return Optional.empty();
        }
        if (!context.live(now)) {
            LIVE.remove(worker, context);
            return Optional.empty();
        }
        return context.covers(level, pos, now) ? Optional.of(context) : Optional.empty();
    }

    /** Drops one worker's authorisation, which a finishing task should do explicitly. */
    public static void release(@Nullable UUID worker) {
        if (worker != null) {
            LIVE.remove(worker);
        }
    }

    /** Drops every authorisation. Server stop, and tests. */
    public static void clearAll() {
        LIVE.clear();
    }

    /** How many authorisations are live. Diagnostics and tests. */
    public static int liveCount() {
        return LIVE.size();
    }
}
