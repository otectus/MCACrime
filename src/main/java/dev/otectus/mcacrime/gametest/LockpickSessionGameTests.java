package dev.otectus.mcacrime.gametest;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.block.entity.SafeBlockEntity;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.lockpick.LockpickService;
import dev.otectus.mcacrime.lockpick.LockpickSession;
import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.locks.LockService;
import dev.otectus.mcacrime.network.LockpickBeginS2CPacket;
import dev.otectus.mcacrime.restraint.SessionKind;
import dev.otectus.mcacrime.restraint.SessionRegistry;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lockpicking, validated where it is actually decided: on a running server (plan section 7.2).
 *
 * <p>Two of the specification's exploit cases need a real world rather than a value test. A
 * <b>forged attempt</b> — one player naming somebody else's session id — has to be refused by the
 * connection's identity and not by anything in the message. And a <b>stale session</b> against a lock
 * that was replaced at the same coordinates has to fail even when the angle sent is the exact one the
 * server asked for, because the session is pinned to a lock identity and a binding revision rather
 * than to a position.
 *
 * <p>The target angle is read out of the begin payload the fake connection captured, so the "correct"
 * attempt below really is the one the server is asking for. That is what makes the refusal meaningful:
 * nothing about the aim is wrong, only the thing being aimed at.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LockpickSessionGameTests {

    private LockpickSessionGameTests() {
    }

    /** Places a safe, binds a lock to it and locks it. Returns the block entity and its lock. */
    private record LockedSafe(SafeBlockEntity safe, LockRecord lock, BlockPos pos) {
    }

    private static LockedSafe lockedSafe(GameTestHelper helper, CrimeWorldData data, BlockPos at) {
        helper.setBlock(at, CrimeBlocks.SAFE.get());
        BlockPos abs = helper.absolutePos(at);
        SafeBlockEntity safe = (SafeBlockEntity) helper.getLevel().getBlockEntity(abs);
        helper.assertTrue(safe != null, "the safe has no block entity");
        LockRecord lock = LockService.getOrCreate(data, safe.lockTarget(), UUID.randomUUID())
                .orElseThrow(() -> new AssertionError("no lock could be created for the safe"));
        helper.assertTrue(safe.bindLock(lock.lockId()), "the safe refused its own lock");
        LockRecord locked = LockService.setLocked(data, lock.lockId(), true)
                .orElseThrow(() -> new AssertionError("the lock could not be locked"));
        return new LockedSafe(safe, locked, abs);
    }

    private static LockpickBeginS2CPacket begin(GameTestHelper helper, List<Packet<?>> sent) {
        for (Packet<?> packet : sent) {
            if (packet instanceof ClientboundCustomPayloadPacket payload
                    && payload.payload() instanceof LockpickBeginS2CPacket lockpick) {
                return lockpick;
            }
        }
        helper.fail("no lockpick begin payload reached the connection");
        throw new AssertionError("unreachable");
    }

    /**
     * A session belongs to the connection that opened it, and to nobody else.
     *
     * <p>The forged attempt names a real, live session and a real phase; the only thing wrong with it
     * is the sender. The session has to survive it untouched, because a refusal that also cancelled
     * the victim's session would be its own denial of service.
     */
    @GameTest(template = "platform", timeoutTicks = 200)
    public static void aForgedAttemptIsRejectedAndLeavesTheSessionAlone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        List<Packet<?>> sent = new ArrayList<>();
        ServerPlayer picker = player(level, helper, new BlockPos(1, 1, 1), sent);
        ServerPlayer stranger = player(level, helper, new BlockPos(1, 1, 3), null);
        picker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CrimeItems.LOCKPICK.get()));
        stranger.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CrimeItems.LOCKPICK.get()));

        LockedSafe target = lockedSafe(helper, data, new BlockPos(2, 1, 2));
        try {
            InteractionResult opened = LockpickService.begin(picker, target.safe(), target.lock());
            helper.assertTrue(opened == InteractionResult.SUCCESS,
                    "the session did not open: " + opened);
            LockpickBeginS2CPacket dial = begin(helper, sent);

            // The stranger knows the session id and the phase, and sends the exact angle the server
            // is asking for. Identity is the connection, so this is refused outright.
            LockpickSession.AttemptResult forged = LockpickService.attempt(stranger, dial.sessionId(),
                    dial.phase(), dial.targetMilliDegrees());
            helper.assertTrue(forged == LockpickSession.AttemptResult.WRONG_PHASE,
                    "a forged attempt was scored as " + forged);
            helper.assertTrue(SessionRegistry.server().forActor(picker.getUUID())
                            .filter(session -> session.kind() == SessionKind.LOCKPICK).isPresent(),
                    "the forged attempt cancelled the picker's own session");
            helper.assertTrue(LockService.byId(data, target.lock().lockId())
                            .map(LockRecord::locked).orElse(false),
                    "the lock opened for somebody who never held a session");

            LockpickService.cancel(picker, dial.sessionId());
        } finally {
            LockService.forget(data, target.lock().lockId());
            picker.discard();
            stranger.discard();
        }
        helper.succeed();
    }

    /**
     * A lock replaced at the same coordinates is a different lock, and the old session cannot open it.
     *
     * <p>The session pins {@code (lockId, bindingRevision)}, so this is refused twice over: the id the
     * session names is gone, and the new lock's revision is its own. Coordinates never enter into it,
     * which is the whole reason the pin is not a position.
     */
    @GameTest(template = "platform", timeoutTicks = 200)
    public static void aStaleSessionCannotOpenAReplacedLock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        List<Packet<?>> sent = new ArrayList<>();
        ServerPlayer picker = player(level, helper, new BlockPos(1, 1, 1), sent);
        picker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CrimeItems.LOCKPICK.get()));

        LockedSafe target = lockedSafe(helper, data, new BlockPos(2, 1, 2));
        UUID replacement = null;
        try {
            helper.assertTrue(LockpickService.begin(picker, target.safe(), target.lock())
                    == InteractionResult.SUCCESS, "the session did not open");
            LockpickBeginS2CPacket dial = begin(helper, sent);

            // The owner tears the lock out and fits a new one on the same block.
            target.safe().unbind();
            LockService.forget(data, target.lock().lockId());
            LockRecord fresh = LockService.getOrCreate(data, target.safe().lockTarget(), UUID.randomUUID())
                    .orElseThrow(() -> new AssertionError("no replacement lock"));
            replacement = fresh.lockId();
            helper.assertTrue(target.safe().bindLock(fresh.lockId()), "the safe refused the new lock");
            LockService.setLocked(data, fresh.lockId(), true);

            // The picker's aim is perfect; the thing they were aiming at is gone.
            LockpickSession.AttemptResult stale = LockpickService.attempt(picker, dial.sessionId(),
                    dial.phase(), dial.targetMilliDegrees());
            helper.assertTrue(stale == LockpickSession.AttemptResult.WRONG_PHASE,
                    "a stale session was scored as " + stale);
            helper.assertTrue(LockService.byId(data, fresh.lockId()).map(LockRecord::locked).orElse(false),
                    "the replacement lock was opened by a session aimed at its predecessor");
            helper.assertTrue(SessionRegistry.server().forActor(picker.getUUID()).isEmpty(),
                    "the stale session was left open instead of being ended");
        } finally {
            if (replacement != null) {
                LockService.forget(data, replacement);
            }
            LockService.forget(data, target.lock().lockId());
            picker.discard();
        }
        helper.succeed();
    }

    /**
     * A server player with a real packet listener whose sends are recorded.
     *
     * <p>Not added to the player list: these are actors for server-side calls, and a mock in the list
     * would be visible to every other test running on the same server.
     */
    private static ServerPlayer player(ServerLevel level, GameTestHelper helper, BlockPos at,
                                       List<Packet<?>> sent) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "mcacrime-lockpick");
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile,
                ClientInformation.createDefault());
        BlockPos abs = helper.absolutePos(at);
        player.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        List<Packet<?>> sink = sent == null ? new ArrayList<>() : sent;
        player.connection = new ServerGamePacketListenerImpl(level.getServer(),
                new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(profile, false)) {
            @Override
            public void send(Packet<?> packet) {
                sink.add(packet);
            }

            @Override
            public void send(Packet<?> packet, PacketSendListener listener) {
                sink.add(packet);
            }
        };
        return player;
    }
}
