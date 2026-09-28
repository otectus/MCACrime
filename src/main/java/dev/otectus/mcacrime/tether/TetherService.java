package dev.otectus.mcacrime.tether;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import dev.otectus.mcacrime.restraint.RestraintService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every physical hold in the mod, in one table (0.7.5 M4.1, §3.3).
 *
 * <p>Before this, a kidnapped player was teleported back by {@code captivity/CustodyConfine}, a
 * kidnapped villager wore a vanilla lead, and an escorted prisoner was pulled by
 * {@code enforcement/EscortRestraint} — three mechanisms, three sets of edge cases, and none of them
 * able to express "tied to that fence". One {@link TetherRecord} expresses all four kinds, and this
 * is the only class that writes one.
 *
 * <h2>Chain ownership</h2>
 *
 * <p>The rule the source gets wrong: {@code setAnchoredTo(null)} spawns a fresh {@code Items.CHAIN}
 * <em>every time it is called with null</em>, from five separate call sites, with nothing consuming
 * the original and nothing guarding a double call
 * ({@code mixin/LivingEntityMixin.java:64-79}). Here the row is removed from the table <b>first</b>,
 * and the chain is returned only if that removal was this call's doing. Detaching twice therefore
 * yields one chain, and detaching a tether nobody paid for — a migrated legacy hold, an escort —
 * yields none at all, because {@code returnOnRelease} says so.
 */
public final class TetherService {

    /** How a tether ended, which decides who is owed the chain and what the subject is told. */
    public enum DetachReason {
        /** Somebody deliberately untied it. The chain goes back to whoever supplied it. */
        RELEASED,
        /** The subject got free by their own effort. The chain still exists and still drops. */
        ESCAPED,
        /** The holder, the anchor entity or the anchor block is gone. */
        HOLDER_LOST,
        /** The subject died. */
        SUBJECT_DIED,
        /** A command or a legal transition that owns this hold. */
        ADMINISTRATIVE
    }

    /** The reverse index, rebuilt from the table rather than persisted (see {@link TetherIndex}). */
    private static final TetherIndex INDEX = new TetherIndex();

    /** The store the index was built from, so a second world cannot inherit the first one's index. */
    @Nullable
    private static CrimeWorldData indexed;

    private TetherService() {
    }

    // --- store and index ---------------------------------------------------------------------------

    @Nullable
    public static CrimeWorldData data(@Nullable MinecraftServer server) {
        return server == null ? null : CrimeWorldData.get(server);
    }

    /** The store behind a level, or null on a client level. */
    @Nullable
    public static CrimeWorldData data(@Nullable Level level) {
        return level instanceof ServerLevel server ? CrimeWorldData.get(server.getServer()) : null;
    }

    /**
     * The index for this store, rebuilt when it is a store the index has not seen.
     *
     * <p>Identity comparison on the store, not a flag: a world unload and reload produces a different
     * {@code CrimeWorldData}, and inheriting the previous world's tethers would have subjects held by
     * holders that do not exist.
     */
    public static synchronized TetherIndex index(@Nullable CrimeWorldData data) {
        if (data == null) {
            return INDEX;
        }
        if (indexed != data) {
            INDEX.rebuild(data.tethers());
            indexed = data;
        }
        return INDEX;
    }

    /** Forces the next lookup to rebuild. Called on server stop and by tests. */
    public static synchronized void invalidate() {
        INDEX.clear();
        indexed = null;
    }

    // --- configuration ------------------------------------------------------------------------------

    /** {@code transport.maxChainLength}, defaulting to the documented 5.0. */
    public static double maxChainLength() {
        try {
            return McaCrimeConfig.COMMON.maxChainLength.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return 5.0D;
        }
    }

    /** {@code transport.overextensionLength}, defaulting to the documented 12.0. */
    public static double overextensionLength() {
        try {
            return McaCrimeConfig.COMMON.overextensionLength.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return 12.0D;
        }
    }

    private static int maxTethersPerHolder() {
        try {
            return McaCrimeConfig.COMMON.maxTethersPerHolder.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return 8;
        }
    }

    private static boolean anchorOnlyWhenRestrained() {
        try {
            return McaCrimeConfig.COMMON.anchorOnlyWhenRestrained.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return false;
        }
    }

    // --- eligibility ---------------------------------------------------------------------------------

    /** Why a proposed tether was refused. {@link #NONE} is the only one that attaches anything. */
    public enum Refusal {
        NONE,
        NO_SUBJECT,
        NO_HOLDER,
        NOT_CHAINABLE,
        ALREADY_HELD,
        HOLDER_FULL,
        CYCLE,
        WRONG_DIMENSION,
        NOT_RESTRAINED,
        STORE_REFUSED
    }

    /**
     * The lang key that explains one refusal.
     *
     * <p>A switch here rather than a literal at each call site, matching {@code LockAccess.messageKey}:
     * the coverage test enumerates the enum and checks every key exists, which a literal scattered
     * through a handler cannot be.
     */
    public static String messageKey(@Nullable Refusal refusal) {
        if (refusal == null) {
            return "mcacrime.transport.refused";
        }
        return switch (refusal) {
            case NONE -> "mcacrime.transport.attached";
            case NO_SUBJECT -> "mcacrime.transport.no_subject";
            case NO_HOLDER -> "mcacrime.transport.no_holder";
            case NOT_CHAINABLE -> "mcacrime.transport.not_chainable";
            case ALREADY_HELD -> "mcacrime.transport.already_held";
            case HOLDER_FULL -> "mcacrime.transport.holder_full";
            case CYCLE -> "mcacrime.transport.cycle";
            case WRONG_DIMENSION -> "mcacrime.transport.wrong_dimension";
            case NOT_RESTRAINED -> "mcacrime.transport.not_restrained";
            case STORE_REFUSED -> "mcacrime.transport.refused";
        };
    }

    /**
     * Whether {@code subject} may be chained at all.
     *
     * <p>The {@code mcacrime:chainable_entities} tag widens the set; players and restrainable
     * subjects are always in it, because something the mod can put cuffs on it can also lead.
     */
    public static boolean chainable(@Nullable Entity subject) {
        if (subject == null || !subject.isAlive() || !(subject instanceof LivingEntity)) {
            return false;
        }
        return RestraintService.restrainable(subject)
                || subject.getType().is(dev.otectus.mcacrime.ai.CrimeEntityTags.CHAINABLE);
    }

    /**
     * Whether this attachment would stand, without making it.
     *
     * <p>Pure enough to be worth having separately: every refusal here is a message a player needs,
     * and the alternative is a right-click that silently does nothing.
     */
    public static Refusal evaluate(@Nullable CrimeWorldData data, @Nullable Entity subject,
                                   @Nullable Entity holder, TetherKind kind) {
        return evaluate(data, subject, holder, kind,
                holder == null ? null : holder.getUUID(), false);
    }

    /** Permission check for moving an existing chain onto a fixed anchor. */
    public static Refusal evaluateAnchorTransfer(@Nullable CrimeWorldData data,
                                                 @Nullable Entity subject,
                                                 @Nullable Entity anchorEntity,
                                                 @Nullable UUID actor) {
        return evaluate(data, subject, anchorEntity, TetherKind.ANCHOR, actor, true);
    }

    /** The full permission check, including the actor whose consent or authority is relevant. */
    private static Refusal evaluate(@Nullable CrimeWorldData data, @Nullable Entity subject,
                                    @Nullable Entity holder, TetherKind kind,
                                    @Nullable UUID actor, boolean replacingSubjectHold) {
        if (data == null || !chainable(subject)) {
            return Refusal.NOT_CHAINABLE;
        }
        if (holder == null || !holder.isAlive()) {
            return Refusal.NO_HOLDER;
        }
        if (subject.getUUID().equals(holder.getUUID())) {
            return Refusal.CYCLE;
        }
        if (!subject.level().dimension().equals(holder.level().dimension())) {
            return Refusal.WRONG_DIMENSION;
        }
        TetherIndex index = index(data);
        List<TetherRecord> subjectTethers = index.forSubject(subject.getUUID());
        if (!replacingSubjectHold && !subjectTethers.isEmpty()) {
            return Refusal.ALREADY_HELD;
        }
        int heldBy = index.heldBy(holder.getUUID());
        if (replacingSubjectHold) {
            for (TetherRecord tether : subjectTethers) {
                if (holder.getUUID().equals(tether.holder())) {
                    heldBy--;
                }
            }
        }
        if (heldBy >= maxTethersPerHolder()) {
            return Refusal.HOLDER_FULL;
        }
        if (TetherPhysics.formsCycle(subject.getUUID(), holder.getUUID(), index::holderOf)) {
            return Refusal.CYCLE;
        }
        if (kind == TetherKind.CHAIN && anchorOnlyWhenRestrained()) {
            PhysicalRestraintState state = data.physicalRestraint(subject.getUUID());
            if (state == null || !state.restrained()) {
                return Refusal.NOT_RESTRAINED;
            }
        }
        return Refusal.NONE;
    }

    /** Whether this actor is the server-recognised authority currently holding the subject. */
    private static boolean lawfulAuthority(CrimeWorldData data, Entity subject, @Nullable UUID actor) {
        if (actor == null) {
            return false;
        }
        if (subject.getServer() != null) {
            net.minecraft.server.level.ServerPlayer player =
                    subject.getServer().getPlayerList().getPlayer(actor);
            if (player != null && player.hasPermissions(2)) {
                return true;
            }
        }
        if (subject instanceof net.minecraft.server.level.ServerPlayer player
                && dev.otectus.mcacrime.enforcement.ArrestStates.inProgress(player)
                && actor.equals(dev.otectus.mcacrime.enforcement.ArrestStates.owningGuard(player))) {
            return true;
        }
        CustodyRecord custody = data.getCustody(subject.getUUID());
        return custody != null && custody.isLawful()
                && custody.getOwner().ownerUuid().filter(actor::equals).isPresent();
    }

    // --- attaching -----------------------------------------------------------------------------------

    /**
     * Ties {@code subject} to {@code holder}.
     *
     * <p>{@code chainOwner} is whoever supplied the item, and is what {@code detach} pays back. An
     * escort supplies nothing, so it passes null and owes nothing — a guard who walks a prisoner to a
     * cell must not be able to farm chains by doing it.
     */
    public static Optional<TetherRecord> attach(@Nullable CrimeWorldData data, @Nullable Entity subject,
                                                @Nullable Entity holder, TetherKind kind,
                                                @Nullable UUID chainOwner, boolean returnOnRelease) {
        UUID actor = holder == null ? null : holder.getUUID();
        if (evaluate(data, subject, holder, kind, actor, false) != Refusal.NONE) {
            return Optional.empty();
        }
        TetherRecord tether = TetherRecord.toHolder(UUID.randomUUID(), subject.getUUID(), kind,
                holder.getUUID(), subject.level().dimension().location(), maxChainLength(),
                chainOwner, returnOnRelease);
        boolean lawful = lawfulAuthority(data, subject, actor);
        Optional<TetherRecord> attached = commit(data, subject, tether,
                kind == TetherKind.ESCORT, lawful);
        return attached;
    }

    /**
     * Ties {@code subject} to a fixed point that happens to be an entity: a fence knot or a weighted
     * anchor.
     *
     * <p>Both fields are filled, and deliberately. The anchor <em>entity</em> is what the chain is
     * drawn to and what tension pulls toward; the anchor <em>block</em> is what the reverse index is
     * keyed by, so "what is tied to this fence post?" is a map read rather than the source's
     * world-wide entity scan. Neither alone answers both questions.
     */
    public static Optional<TetherRecord> attachToAnchor(@Nullable CrimeWorldData data,
                                                        @Nullable Entity subject,
                                                        @Nullable Entity anchorEntity,
                                                        @Nullable BlockPos anchorPos,
                                                        @Nullable UUID chainOwner,
                                                        boolean returnOnRelease) {
        return attachToAnchor(data, subject, anchorEntity, anchorPos, chainOwner, chainOwner,
                returnOnRelease);
    }

    /** Anchor attachment with the actual actor kept separate from the physical anchor entity. */
    public static Optional<TetherRecord> attachToAnchor(@Nullable CrimeWorldData data,
                                                        @Nullable Entity subject,
                                                        @Nullable Entity anchorEntity,
                                                        @Nullable BlockPos anchorPos,
                                                        @Nullable UUID actor,
                                                        @Nullable UUID chainOwner,
                                                        boolean returnOnRelease) {
        if (evaluate(data, subject, anchorEntity, TetherKind.ANCHOR, actor, false) != Refusal.NONE) {
            return Optional.empty();
        }
        TetherRecord tether = new TetherRecord(UUID.randomUUID(), subject.getUUID(), TetherKind.ANCHOR,
                anchorEntity.getUUID(), subject.level().dimension().location(),
                anchorPos == null ? anchorEntity.blockPosition() : anchorPos,
                maxChainLength(), false, chainOwner, returnOnRelease, 1L);
        Optional<TetherRecord> attached = commit(data, subject, tether, false,
                lawfulAuthority(data, subject, actor));
        return attached;
    }

    /** The single write. Records the tether and points the subject's physical state at it. */
    private static Optional<TetherRecord> commit(CrimeWorldData data, Entity subject,
                                                 TetherRecord tether, boolean escort, boolean lawful) {
        if (!data.putTether(tether)) {
            return Optional.empty();
        }
        index(data).put(tether);
        if (!link(data, subject, tether.id(), escort)) {
            data.removeTether(tether.id());
            index(data).remove(tether.id());
            return Optional.empty();
        }
        publish(subject, data);
        // Post-commit (M6.4): the hold exists, and a companion is told which kind it is and whether
        // it is the law's. Whether it is also a kidnapping is CustodyTransitionService's answer.
        dev.otectus.mcacrime.restraint.PhysicalApiEvents.seized(subject,
                tether.holderId().orElse(null), tether.kind(), lawful);
        return Optional.of(tether);
    }

    /**
     * Points a subject's physical state at the tether holding them, creating the row if needed.
     *
     * <p>The row is what the client snapshot is built from, so without this a chain would exist in
     * the table and be drawn by nobody.
     */
    private static boolean link(CrimeWorldData data, Entity subject, @Nullable UUID tetherId,
                                boolean escort) {
        PhysicalRestraintState state = data.physicalRestraint(subject.getUUID());
        if (state == null) {
            if (tetherId == null) {
                return true;
            }
            state = PhysicalRestraintState.empty(subject.getUUID(),
                    subject instanceof net.minecraft.world.entity.player.Player,
                    subject.level().dimension().location());
        }
        PhysicalRestraintState next = escort ? state.withEscort(tetherId) : state.withTether(tetherId);
        return data.putPhysicalRestraint(next);
    }

    // --- detaching -----------------------------------------------------------------------------------

    /**
     * Whether ending this tether owes anybody the chain item back.
     *
     * <p>Pure, and the rule the source gets wrong: a chain is owed only when one was actually taken
     * from somebody, and never to a subject who died holding it — their inventory is already being
     * resolved by the death path, and adding an item there would be a second, unaccounted drop. An
     * escort and a migrated legacy hold took nothing from anybody and owe nothing.
     */
    public static boolean owes(@Nullable TetherRecord tether, @Nullable DetachReason reason) {
        return tether != null && tether.returnOnRelease() && reason != DetachReason.SUBJECT_DIED;
    }

    /**
     * Unties one tether, at most once.
     *
     * <p>The removal from the table is the gate: a second call finds nothing to remove and returns
     * empty, so the chain is minted once however many paths reach here in one tick. That is the whole
     * fix for the source's duplication, and it is why the table write comes before the item.
     *
     * @return the chain item that was owed and dropped, or empty when none was owed
     */
    public static Optional<ItemStack> detach(@Nullable MinecraftServer server, @Nullable CrimeWorldData data,
                                             @Nullable UUID tetherId, DetachReason reason) {
        if (data == null || tetherId == null) {
            return Optional.empty();
        }
        TetherRecord tether = data.tether(tetherId);
        if (tether == null || !data.removeTether(tetherId)) {
            return Optional.empty(); // somebody else already untied it; they owed the chain, not us
        }
        index(data).remove(tetherId);

        Entity subject = server == null ? null : find(server, tether.subject());
        PhysicalRestraintState state = data.physicalRestraint(tether.subject());
        if (state != null) {
            PhysicalRestraintState next = tetherId.equals(state.escortId()) ? state.withEscort(null)
                    : tetherId.equals(state.tetherId()) ? state.withTether(null) : state;
            if (next != state) {
                data.putPhysicalRestraint(next);
            }
        }
        if (subject != null) {
            publish(subject, data);
        }
        return payBack(server, subject, tether, reason);
    }

    /**
     * The chain item, to whoever supplied it.
     *
     * <p>Nothing is owed when nobody supplied one — an escort, a migrated legacy hold — and nothing
     * is owed to a subject who died holding it, because their inventory is already being resolved by
     * the death path and adding an item to it here would be a second, unaccounted drop.
     */
    private static Optional<ItemStack> payBack(@Nullable MinecraftServer server, @Nullable Entity subject,
                                               TetherRecord tether, DetachReason reason) {
        if (!owes(tether, reason)) {
            return Optional.empty();
        }
        net.minecraft.server.level.ServerPlayer owner = server == null || tether.chainOwner() == null
                ? null : server.getPlayerList().getPlayer(tether.chainOwner());
        if (owner == null && subject == null) {
            // Nobody is loaded to hand it to. The row is gone either way; minting an item into a world
            // nobody is standing in is how a chain ends up in the void. The stack is not even built
            // here, so the decision costs nothing when there is nobody to pay.
            return Optional.empty();
        }
        ItemStack chain = new ItemStack(Items.CHAIN);
        if (owner != null && owner.getInventory().add(chain.copy())) {
            return Optional.of(chain);
        }
        if (subject != null) {
            subject.spawnAtLocation(chain.copy());
            return Optional.of(chain);
        }
        owner.drop(chain.copy(), false);
        return Optional.of(chain);
    }

    /** Unties everything holding {@code subject}. Returns how many tethers ended. */
    public static int detachAll(@Nullable MinecraftServer server, @Nullable UUID subject,
                                DetachReason reason) {
        return detachAll(server, data(server), subject, reason);
    }

    /**
     * As above, against a store the caller already has.
     *
     * <p>The overload exists because the store is reachable from a level as well as from a server,
     * and because a test has one without the other.
     */
    public static int detachAll(@Nullable MinecraftServer server, @Nullable CrimeWorldData data,
                                @Nullable UUID subject, DetachReason reason) {
        if (data == null || subject == null) {
            return 0;
        }
        int ended = 0;
        for (TetherRecord tether : index(data).forSubject(subject)) {
            if (detach(server, data, tether.id(), reason).isPresent() || data.tether(tether.id()) == null) {
                ended++;
            }
        }
        return ended;
    }

    /** Unties everything {@code holder} is leading: their death, their logout, their dimension change. */
    public static int detachHeldBy(@Nullable MinecraftServer server, @Nullable UUID holder,
                                   DetachReason reason) {
        return detachHeldBy(server, data(server), holder, reason);
    }

    /** As above, against a store the caller already has. */
    public static int detachHeldBy(@Nullable MinecraftServer server, @Nullable CrimeWorldData data,
                                   @Nullable UUID holder, DetachReason reason) {
        if (data == null || holder == null) {
            return 0;
        }
        int ended = 0;
        for (TetherRecord tether : index(data).forHolder(holder)) {
            detach(server, data, tether.id(), reason);
            ended++;
        }
        return ended;
    }

    /**
     * Unties everything tied to one fixed point.
     *
     * <p>Index-driven, which is the point: the source rebuilds the same answer by iterating every
     * entity on the server each time somebody right-clicks a block.
     */
    public static int detachAtAnchor(@Nullable MinecraftServer server, @Nullable ResourceLocation dimension,
                                     @Nullable BlockPos pos, DetachReason reason) {
        return detachAtAnchor(server, data(server), dimension, pos, reason);
    }

    /** As above, against a store the caller already has. */
    public static int detachAtAnchor(@Nullable MinecraftServer server, @Nullable CrimeWorldData data,
                                     @Nullable ResourceLocation dimension, @Nullable BlockPos pos,
                                     DetachReason reason) {
        if (data == null || pos == null) {
            return 0;
        }
        int ended = 0;
        for (TetherRecord tether : index(data).forAnchor(dimension, pos)) {
            detach(server, data, tether.id(), reason);
            ended++;
        }
        return ended;
    }

    // --- queries --------------------------------------------------------------------------------------

    /** Every tether holding {@code subject}. */
    public static List<TetherRecord> forSubject(@Nullable CrimeWorldData data, @Nullable UUID subject) {
        return data == null ? List.of() : index(data).forSubject(subject);
    }

    /** The one tether currently governing {@code subject}, ignoring suspended ones. */
    public static Optional<TetherRecord> active(@Nullable CrimeWorldData data, @Nullable UUID subject) {
        for (TetherRecord tether : forSubject(data, subject)) {
            if (!tether.suspended()) {
                return Optional.of(tether);
            }
        }
        return Optional.empty();
    }

    /** Whether anything at all is tied to this block. */
    public static boolean anchored(@Nullable CrimeWorldData data, @Nullable ResourceLocation dimension,
                                   @Nullable BlockPos pos) {
        return data != null && index(data).anchored(dimension, pos);
    }

    /** Marks one tether suspended or resumed. Arbitration's only write. */
    public static boolean suspend(@Nullable CrimeWorldData data, @Nullable UUID tetherId, boolean suspended) {
        TetherRecord tether = data == null ? null : data.tether(tetherId);
        if (tether == null || tether.suspended() == suspended) {
            return false;
        }
        TetherRecord next = tether.suspended(suspended);
        if (!data.putTether(next)) {
            return false;
        }
        index(data).put(next);
        return true;
    }

    // --- escort ---------------------------------------------------------------------------------------

    /**
     * Starts a guard walking a subject, replacing the vanilla lead and the old per-tick pull.
     *
     * <p>Idempotent for the same pair: re-issuing an escort that already exists is what the enforcement
     * scan does every pass, and it must not stack tethers or mint anything.
     */
    public static Optional<TetherRecord> escort(@Nullable Entity guard, @Nullable Entity subject) {
        if (guard == null || subject == null) {
            return Optional.empty();
        }
        CrimeWorldData data = data(subject.level());
        if (data == null) {
            return Optional.empty();
        }
        Optional<TetherRecord> existing = active(data, subject.getUUID());
        if (existing.isPresent() && existing.get().kind() == TetherKind.ESCORT
                && guard.getUUID().equals(existing.get().holder())) {
            return existing;
        }
        List<TetherRecord> prior = index(data).forSubject(subject.getUUID());
        // Another escort is an authority conflict, not a chain takeover. Its owner must hand custody
        // over first; otherwise ending either escort could release the other one's hold.
        if (prior.stream().anyMatch(tether -> tether.kind() == TetherKind.ESCORT)) {
            return Optional.empty();
        }
        UUID actor = guard.getUUID();
        if (evaluate(data, subject, guard, TetherKind.ESCORT, actor, true) != Refusal.NONE) {
            return Optional.empty();
        }

        TetherRecord escort = TetherRecord.toHolder(UUID.randomUUID(), subject.getUUID(),
                TetherKind.ESCORT, actor, subject.level().dimension().location(), maxChainLength(),
                null, false);
        // Commit the replacement before suspending the old chain. A failed insert or state link leaves
        // the existing hold active, rather than stranding the subject with a suspended chain and no
        // escort. The server thread serialises these writes, so this is one atomic takeover to callers.
        if (!data.putTether(escort)) {
            return Optional.empty();
        }
        index(data).put(escort);
        if (!link(data, subject, escort.id(), true)) {
            data.removeTether(escort.id());
            index(data).remove(escort.id());
            return Optional.empty();
        }
        for (TetherRecord tether : prior) {
            if (!tether.suspended()) {
                suspend(data, tether.id(), true);
            }
        }
        publish(subject, data);
        boolean lawful = lawfulAuthority(data, subject, actor);
        dev.otectus.mcacrime.restraint.PhysicalApiEvents.seized(subject, actor,
                TetherKind.ESCORT, lawful);
        return Optional.of(escort);
    }

    /** Ends the escort holding {@code subject}, and resumes whatever it suspended. */
    public static boolean endEscort(@Nullable MinecraftServer server, @Nullable UUID subject,
                                    DetachReason reason) {
        CrimeWorldData data = data(server);
        if (data == null || subject == null) {
            return false;
        }
        boolean ended = false;
        for (TetherRecord tether : index(data).forSubject(subject)) {
            if (tether.kind() == TetherKind.ESCORT) {
                detach(server, data, tether.id(), reason);
                ended = true;
            }
        }
        if (ended) {
            for (TetherRecord tether : index(data).forSubject(subject)) {
                suspend(data, tether.id(), false);
            }
        }
        return ended;
    }

    // --- helpers ---------------------------------------------------------------------------------------

    /** The entity under this id across every loaded level, or null when nobody is loaded. */
    @Nullable
    public static Entity find(@Nullable MinecraftServer server, @Nullable UUID id) {
        if (server == null || id == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private static void publish(@Nullable Entity subject, @Nullable CrimeWorldData data) {
        if (subject instanceof LivingEntity living) {
            RestraintService.publish(living, data);
        }
    }
}
