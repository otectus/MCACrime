package dev.otectus.mcacrime.frisk;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyOwnerType;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.enforcement.ContrabandSearchService;
import dev.otectus.mcacrime.menu.FriskingMenu;
import dev.otectus.mcacrime.network.ActionValidation;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.FriskSnapshotS2CPacket;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import dev.otectus.mcacrime.restraint.RestraintService;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import dev.otectus.mcacrime.restraint.Session;
import dev.otectus.mcacrime.restraint.SessionCancelCause;
import dev.otectus.mcacrime.restraint.SessionRegistry;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Searching somebody who cannot stop you (M5.2, M5.3, spec §11).
 *
 * <p>The server owns everything: the session, the ordered list of slots, the revision of each one,
 * the delay between takes, and what a take legally is. The screen is a projection of that and
 * nothing more — its slots cannot be clicked into, out of, dragged, shift-moved or double-clicked,
 * and the only thing that moves an item is {@code FriskTransferC2SPacket}, which carries a session
 * id, a position in the view, the revision the searcher was shown and a count.
 *
 * <p>Authority and helplessness are checked separately, which is the specification's own
 * requirement: being able to reach somebody's pockets is a physical question, and being allowed to
 * is a legal one. {@link #classify} answers the second, and its two answers are two different
 * events with two different records. A search opens from the crime menu
 * ({@code action/handler/FriskActionHandler}); what it takes goes to the searcher's own inventory,
 * or into the property escrow when a lawful search seals it as evidence.
 */
public final class FriskingService {

    private FriskingService() {
    }

    // --- configuration ------------------------------------------------------------------------------

    public static double maxRange() {
        try {
            return McaCrimeConfig.COMMON.friskMaxRangeBlocks.get();
        } catch (IllegalStateException notLoaded) {
            return 5.0D;
        }
    }

    public static int sessionTimeoutTicks() {
        try {
            return McaCrimeConfig.COMMON.friskSessionTimeoutTicks.get();
        } catch (IllegalStateException notLoaded) {
            return 1200;
        }
    }

    public static int transferIntervalTicks() {
        try {
            return McaCrimeConfig.COMMON.friskTransferIntervalTicks.get();
        } catch (IllegalStateException notLoaded) {
            return 5;
        }
    }

    public static boolean requiresArmRestraint() {
        try {
            return McaCrimeConfig.COMMON.friskRequiresArmRestraint.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    // --- opening ------------------------------------------------------------------------------------

    /**
     * Whether a subject is physically searchable right now.
     *
     * <p>Pure in its input so the rule is checkable: arms restrained, or a device holding them, or
     * the requirement switched off. A hood is not enough — somebody who cannot see you can still
     * stop you taking their sword.
     */
    public static boolean restraintSatisfied(@Nullable PhysicalRestraintState state, boolean required) {
        if (!required) {
            return true;
        }
        if (state == null) {
            return false;
        }
        return state.occupied(RestraintSlot.ARMS) || state.detentionId() != null;
    }

    /**
     * Which of the two events this search is.
     *
     * <p>Pure, so every combination is testable. A lawful search needs a live lawful custody record
     * whose owner is this searcher, or a jail or village authority the searcher is acting for;
     * anything else is theft. Searching yourself is not a search and never reaches here.
     */
    public static SeizureKind classify(boolean custodyExists, boolean custodyLawful,
                                       boolean searcherIsCustodian, boolean searcherIsOnDutyGuard) {
        if (custodyExists && custodyLawful && (searcherIsCustodian || searcherIsOnDutyGuard)) {
            return SeizureKind.LAWFUL_SEARCH;
        }
        return SeizureKind.CRIMINAL_SEIZURE;
    }

    /** The same question against live state. */
    private static SeizureKind classify(ServerPlayer searcher, LivingEntity subject,
                                        @Nullable CustodyRecord custody) {
        boolean exists = custody != null;
        boolean lawful = exists && custody.isLawful();
        boolean custodian = exists && custody.getOwner().ownerUuid()
                .map(owner -> owner.equals(searcher.getUUID())).orElse(false);
        boolean authority = exists && (custody.getOwner().type() == CustodyOwnerType.JAIL
                || custody.getOwner().type() == CustodyOwnerType.AUTHORITY);
        // A player acting for the village authority still has to be the one holding them: an
        // authority-owned custody with no guard present is a cell, not a warrant to rummage.
        boolean onDuty = authority && McaCompat.isGuard(searcher);
        return classify(exists, lawful, custodian, onDuty);
    }

    /**
     * Opens a search.
     *
     * @return true when the screen was opened
     */
    public static boolean open(@Nullable ServerPlayer searcher, @Nullable LivingEntity subject) {
        if (searcher == null || subject == null || !(searcher.level() instanceof ServerLevel level)) {
            return false;
        }
        if (searcher.getUUID().equals(subject.getUUID())) {
            // Your own pockets: nothing would move anywhere. Not a search.
            return false;
        }
        if (subject.level() != searcher.level() || !ActionValidation.inReach(searcher, subject, maxRange())) {
            return false;
        }
        PhysicalRestraintState state = RestraintService.state(subject);
        if (!restraintSatisfied(state, requiresArmRestraint())) {
            searcher.displayClientMessage(
                    Component.translatable(FriskRefusal.NOT_RESTRAINED.messageKey()), true);
            return false;
        }
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        CustodyRecord custody = data == null ? null : data.getCustody(subject.getUUID());
        SeizureKind kind = classify(searcher, subject, custody);

        List<FriskSlotRef> view = InventoryProviders.view(subject);
        if (view.isEmpty()) {
            searcher.displayClientMessage(
                    Component.translatable("mcacrime.msg.frisk.nothing_to_search"), true);
            return false;
        }
        SessionRegistry registry = SessionRegistry.server();
        long now = level.getGameTime();
        FriskSession session = new FriskSession(registry.allocateId(), searcher.getUUID(),
                subject.getUUID(), subject.getId(), state == null ? 0L : state.revision(),
                level.dimension().location(),
                custody == null ? null : custody.getCustodyId(),
                custody == null ? 0L : custody.getGeneration(), view, kind,
                now + sessionTimeoutTicks());
        if (registry.open(session).isEmpty()) {
            searcher.displayClientMessage(Component.translatable("mcacrime.msg.frisk.busy"), true);
            return false;
        }

        Component title = subject.getDisplayName().copy();
        // 1.21.1 has no NetworkHooks: the extra-data form of openMenu is the platform's own route,
        // and its buffer is a RegistryFriendlyByteBuf.
        searcher.openMenu(new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return title;
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inventory, Player viewer) {
                return new FriskingMenu(id, inventory, session);
            }
        }, buffer -> {
            buffer.writeLong(session.id());
            buffer.writeVarInt(view.size());
        });
        // The menu bound itself to this session as it was constructed, so nothing has to guess which
        // container id it got.
        //
        // A lawful search is also a contraband discovery: the same gate that decides whether a guard
        // finds what somebody is carrying runs here, so opening the screen cannot become a way to see
        // every hidden property fact without the discovery ever happening.
        if (kind == SeizureKind.LAWFUL_SEARCH && subject instanceof ServerPlayer searched) {
            ContrabandSearchService.onFrisk(level, searcher, searched);
        }
        sendSnapshot(searcher, session, subject);
        return true;
    }

    // --- live state ---------------------------------------------------------------------------------

    /** The live session this player is searching in, or empty. */
    public static Optional<FriskSession> sessionOf(@Nullable ServerPlayer searcher, long sessionId) {
        if (searcher == null) {
            return Optional.empty();
        }
        long now = searcher.level().getGameTime();
        return SessionRegistry.server().validate(sessionId, searcher.getUUID(), now)
                .filter(FriskSession.class::isInstance)
                .map(FriskSession.class::cast);
    }

    /** The subject of a session, or null when it is gone, dead or in another world. */
    @Nullable
    public static LivingEntity subjectOf(@Nullable ServerPlayer searcher, @Nullable FriskSession session) {
        if (searcher == null || session == null) {
            return null;
        }
        var entity = searcher.level().getEntity(session.subjectEntityId());
        if (!(entity instanceof LivingEntity living) || living.isRemoved() || !living.isAlive()) {
            return null;
        }
        return session.target() != null && session.target().equals(living.getUUID()) ? living : null;
    }

    /** Gathers the six conditions from live state. Everything below reads this and nothing else. */
    public static FriskValidation.Facts facts(ServerPlayer searcher, @Nullable FriskSession session,
                                              int viewIndex, int expectedRevision, int count,
                                              boolean checkTransfer) {
        if (session == null) {
            return new FriskValidation.Facts(false, false, false, false, false, false, false, false,
                    false, false, false);
        }
        long now = searcher.level().getGameTime();
        LivingEntity subject = subjectOf(searcher, session);
        boolean alive = subject != null;
        boolean sameDimension = alive && ActionValidation.sameDimension(session.dimension(),
                searcher.level().dimension().location());
        boolean reach = alive && ActionValidation.inReach(searcher, subject, maxRange());
        boolean restrained = alive
                && restraintSatisfied(RestraintService.state(subject), requiresArmRestraint());
        boolean custodyMatches = custodyMatches(searcher, session, subject);
        boolean ownsMenu = ActionValidation.ownsMenu(searcher, session.containerId())
                || session.containerId() < 0;

        boolean slotMatches = false;
        boolean destination = false;
        boolean delay = true;
        UUID transferId = null;
        if (checkTransfer && alive) {
            FriskSlotRef ref = session.slotAt(viewIndex);
            ItemStack present = InventoryProviders.peek(subject, ref);
            slotMatches = ref != null && !present.isEmpty()
                    && FriskRevision.of(present) == expectedRevision
                    && count > 0 && count <= present.getCount();
            destination = slotMatches
                    && FriskTransaction.destinationWouldTake(session, searcher,
                            present.copyWithCount(count));
            delay = session.delayElapsed(now, transferIntervalTicks());
            transferId = SeizureLedger.transferId(session.id(), viewIndex, expectedRevision, count);
        }
        return new FriskValidation.Facts(session.live(now), ownsMenu, alive, sameDimension, reach,
                restrained, custodyMatches, slotMatches, destination, delay,
                transferId != null && session.alreadyApplied(transferId));
    }

    private static boolean custodyMatches(ServerPlayer searcher, FriskSession session,
                                          @Nullable LivingEntity subject) {
        if (subject == null) {
            return false;
        }
        CrimeWorldData data = searcher.getServer() == null ? null : CrimeWorldData.get(searcher.getServer());
        CustodyRecord record = data == null ? null : data.getCustody(subject.getUUID());
        if (session.custodyId() == null) {
            // Opened with nobody holding them. A custody that has since begun is a new situation and
            // the old session does not carry over into it.
            return record == null;
        }
        return ActionValidation.matchesCustody(record, session.custodyId(), session.custodyGeneration());
    }

    /** Whether a session's screen may stay open. Asked every tick by the menu. */
    public static boolean stillValid(@Nullable ServerPlayer searcher, @Nullable FriskSession session) {
        return searcher != null && session != null
                && FriskValidation.sessionStillValid(facts(searcher, session, -1, 0, 0, false));
    }

    // --- transferring -------------------------------------------------------------------------------

    /** One arriving transfer. Identity is the connection; everything else is checked here. */
    public static void transfer(@Nullable ServerPlayer searcher, long sessionId, int viewIndex,
                                int expectedRevision, int count) {
        if (searcher == null || !(searcher.level() instanceof ServerLevel level)) {
            return;
        }
        FriskSession session = sessionOf(searcher, sessionId).orElse(null);
        if (session == null) {
            searcher.displayClientMessage(
                    Component.translatable(FriskRefusal.NO_SESSION.messageKey()), true);
            return;
        }
        LivingEntity subject = subjectOf(searcher, session);
        FriskValidation.Facts facts = facts(searcher, session, viewIndex, expectedRevision, count, true);
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        FriskTransaction.Result result = subject == null
                ? FriskTransaction.Result.refused(FriskRefusal.SUBJECT_UNAVAILABLE)
                : FriskTransaction.commit(searcher, subject, session, viewIndex, expectedRevision,
                        count, facts, data, level.getGameTime());
        if (!result.committed()) {
            searcher.displayClientMessage(Component.translatable(result.refusal().messageKey()), true);
            if (!FriskValidation.sessionStillValid(facts)) {
                close(searcher, session, SessionCancelCause.TARGET_REPLACED);
            }
            return;
        }
        searcher.displayClientMessage(Component.translatable(session.seizureKind().messageKey(),
                result.moved().getCount(), result.moved().getHoverName()), true);
        if (subject instanceof ServerPlayer searched) {
            searched.displayClientMessage(Component.translatable("mcacrime.msg.frisk.taken_from_you",
                    result.moved().getCount(), result.moved().getHoverName()), true);
        }
        sendSnapshot(searcher, session, subject);
    }

    // --- closing ------------------------------------------------------------------------------------

    /** Ends a session, whatever ended it. */
    public static void close(@Nullable ServerPlayer searcher, @Nullable FriskSession session,
                             SessionCancelCause cause) {
        if (session == null) {
            return;
        }
        SessionRegistry.server().cancel(session.id(), cause);
        if (searcher != null && searcher.containerMenu instanceof FriskingMenu) {
            searcher.closeContainer();
        }
    }

    /** Ends every session this player is the searcher in. Called on logout, death and menu close. */
    public static void closeAll(@Nullable ServerPlayer searcher, SessionCancelCause cause) {
        if (searcher == null) {
            return;
        }
        SessionRegistry.server().cancelForActor(searcher.getUUID(), cause);
    }

    /** Ends every session searching this subject. Called when they die, are released or log out. */
    public static void closeFor(@Nullable UUID subject, SessionCancelCause cause) {
        if (subject == null) {
            return;
        }
        SessionRegistry.server().cancelForTarget(subject, cause);
    }

    // --- projection ---------------------------------------------------------------------------------

    /** Sends the searcher the per-slot revisions their screen has to echo back. */
    public static void sendSnapshot(@Nullable ServerPlayer searcher, @Nullable FriskSession session,
                                    @Nullable LivingEntity subject) {
        if (searcher == null || session == null || subject == null) {
            return;
        }
        List<Integer> revisions = new ArrayList<>(session.view().size());
        for (FriskSlotRef ref : session.view()) {
            revisions.add(FriskRevision.of(InventoryProviders.peek(subject, ref)));
        }
        CrimeNetwork.sendFriskSnapshot(searcher,
                new FriskSnapshotS2CPacket(session.id(), session.seizureKind(), revisions));
    }

    /** The session a menu belongs to, looked up by the menu rather than by the packet. */
    @Nullable
    public static FriskSession sessionFor(@Nullable Player player) {
        if (!(player instanceof ServerPlayer searcher)) {
            return null;
        }
        Optional<Session> session = SessionRegistry.server().forActor(searcher.getUUID());
        return session.filter(FriskSession.class::isInstance).map(FriskSession.class::cast).orElse(null);
    }
}
