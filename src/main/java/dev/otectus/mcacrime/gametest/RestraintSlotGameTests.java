package dev.otectus.mcacrime.gametest;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.network.PhysicalStateDeltaS2CPacket;
import dev.otectus.mcacrime.network.PhysicalStateS2CPacket;
import dev.otectus.mcacrime.restraint.AppliedRestraint;
import dev.otectus.mcacrime.restraint.ApplicationTransaction;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import dev.otectus.mcacrime.restraint.PhysicalRestraintView;
import dev.otectus.mcacrime.restraint.RemovalService;
import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import dev.otectus.mcacrime.restraint.RestraintService;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import dev.otectus.mcacrime.restraint.RestraintSyncService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Applying and removing every restraint definition on a real server (0.7.5 P2, plan section 7.2).
 *
 * <p>This is the port's replacement for {@code CuffParityGameTests}, whose subject — the Locks
 * Reforged cuff lockpicking menu — was deleted with the legacy engine (plan section 5.1). What took
 * its place is the physical engine itself, and what only a running server can show is that the
 * transaction, the world-data write and the published payload all agree about the same slot.
 *
 * <p>The connection is a real {@code ServerGamePacketListenerImpl} with {@code send} overridden, so
 * the payload is the one the network layer would actually put on the wire rather than an object the
 * test built.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RestraintSlotGameTests {

    private RestraintSlotGameTests() {
    }

    /** Every (item, slot) pair the 0.7.5 definition table declares, and the definition it must produce. */
    private record Case(Item item, RestraintSlot slot, ResourceLocation definitionId) {
    }

    private static List<Case> cases() {
        return List.of(
                new Case(CrimeItems.RESTRAINT_LOCKED_CUFFS.get(), RestraintSlot.ARMS,
                        RestraintDefinitions.HANDCUFFS_ARMS),
                new Case(CrimeItems.RESTRAINT_LOCKED_CUFFS.get(), RestraintSlot.LEGS,
                        RestraintDefinitions.HANDCUFFS_LEGS),
                new Case(CrimeItems.RESTRAINT_CUFFS.get(), RestraintSlot.ARMS,
                        RestraintDefinitions.SHACKLES_ARMS),
                new Case(CrimeItems.RESTRAINT_CUFFS.get(), RestraintSlot.LEGS,
                        RestraintDefinitions.SHACKLES_LEGS),
                new Case(CrimeItems.DUCK_TAPE.get(), RestraintSlot.ARMS,
                        RestraintDefinitions.DUCK_TAPE_ARMS),
                new Case(CrimeItems.DUCK_TAPE.get(), RestraintSlot.LEGS,
                        RestraintDefinitions.DUCK_TAPE_LEGS),
                new Case(CrimeItems.DUCK_TAPE.get(), RestraintSlot.HEAD,
                        RestraintDefinitions.DUCK_TAPE_HEAD));
    }

    /**
     * Each item lands the definition its slot names, costs exactly one item, and comes off again.
     *
     * <p>Run one pair at a time against a fresh subject: the point is the mapping, and leaving the
     * previous restraint on would turn every case after the first into a slot-occupied refusal.
     */
    @GameTest(template = "platform", timeoutTicks = 200)
    public static void eachDefinitionAppliesToItsOwnSlot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        ServerPlayer actor = player(level, helper, new BlockPos(1, 1, 1));

        for (Case pair : cases()) {
            ServerPlayer subject = player(level, helper, new BlockPos(2, 1, 2));
            ApplicationTransaction.clearDeliveries();
            actor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(pair.item(), 2));

            ApplicationTransaction.Result result = RestraintService.apply(actor, subject,
                    InteractionHand.MAIN_HAND, pair.slot(),
                    AppliedRestraint.ApplicationContext.UNLAWFUL);
            helper.assertTrue(result.applied(),
                    pair.item() + " on " + pair.slot() + " was refused: " + result.refusal());

            PhysicalRestraintState state = data.physicalRestraint(subject.getUUID());
            helper.assertTrue(state != null, "nothing was written for " + pair.definitionId());
            AppliedRestraint worn = state.slot(pair.slot()).orElse(null);
            helper.assertTrue(worn != null, pair.slot() + " is empty after a successful application");
            helper.assertTrue(pair.definitionId().equals(worn.definitionId()),
                    "expected " + pair.definitionId() + " but the slot holds " + worn.definitionId());
            helper.assertTrue(worn.remainingDurability() > 0,
                    pair.definitionId() + " was applied with no durability at all");
            helper.assertTrue(actor.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 1,
                    "one application spent " + (2 - actor.getItemInHand(InteractionHand.MAIN_HAND).getCount())
                            + " items");

            RemovalService.Result removed = RemovalService.remove(subject, pair.slot(),
                    RemovalService.Reason.ADMINISTRATIVE, null);
            helper.assertTrue(removed.removed(),
                    pair.definitionId() + " could not be taken off again: " + removed.refusal());
            PhysicalRestraintState after = data.physicalRestraint(subject.getUUID());
            helper.assertTrue(after == null || after.slot(pair.slot()).isEmpty(),
                    pair.slot() + " still holds gear after removal");
            subject.discard();
        }
        actor.discard();
        helper.succeed();
    }

    /**
     * The payload a client actually receives names the same slot and definition the store holds.
     *
     * <p>Captured from a real packet listener: a snapshot that agreed with the store in memory but
     * encoded something else is exactly the failure a unit test on the record cannot see.
     */
    @GameTest(template = "platform", timeoutTicks = 100)
    public static void appliedRestraintReachesTheClientPayload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        List<Packet<?>> sent = new ArrayList<>();
        ServerPlayer subject = player(level, helper, new BlockPos(2, 1, 2), sent);
        ServerPlayer actor = player(level, helper, new BlockPos(1, 1, 1));

        ApplicationTransaction.clearDeliveries();
        actor.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(CrimeItems.RESTRAINT_LOCKED_CUFFS.get()));
        ApplicationTransaction.Result result = RestraintService.apply(actor, subject,
                InteractionHand.MAIN_HAND, RestraintSlot.ARMS,
                AppliedRestraint.ApplicationContext.UNLAWFUL);
        helper.assertTrue(result.applied(), "the application was refused: " + result.refusal());

        sent.clear();
        RestraintSyncService.sendFullState(subject, data);
        PhysicalStateS2CPacket snapshot = sent.stream()
                .filter(p -> p instanceof ClientboundCustomPayloadPacket custom
                        && custom.payload() instanceof PhysicalStateS2CPacket)
                .map(p -> (PhysicalStateS2CPacket) ((ClientboundCustomPayloadPacket) p).payload())
                .reduce((a, b) -> b)
                .orElse(null);
        helper.assertTrue(snapshot != null, "no physical-state snapshot reached the connection");
        PhysicalRestraintView view = snapshot.subjects().stream()
                .filter(v -> subject.getUUID().equals(v.subject()))
                .findFirst()
                .orElse(null);
        helper.assertTrue(view != null, "the snapshot did not name the restrained subject");
        helper.assertTrue(view.slot(RestraintSlot.ARMS)
                        .map(slotView -> RestraintDefinitions.HANDCUFFS_ARMS.equals(slotView.definitionId()))
                        .orElse(false),
                "the published arms slot is not handcuffs_arms");

        sent.clear();
        RemovalService.remove(subject, RestraintSlot.ARMS, RemovalService.Reason.ADMINISTRATIVE, null);
        RestraintSyncService.sendOnTrackingStart(subject, subject, data);
        boolean stillClaimsGear = sent.stream()
                .anyMatch(p -> p instanceof ClientboundCustomPayloadPacket custom
                        && custom.payload() instanceof PhysicalStateDeltaS2CPacket delta
                        && delta.subject().slot(RestraintSlot.ARMS).isPresent());
        helper.assertTrue(!stillClaimsGear, "a removed restraint was still published as worn");

        actor.discard();
        subject.discard();
        helper.succeed();
    }

    /**
     * Two actors racing for one slot: one succeeds, one is refused, and one item is spent.
     *
     * <p>The invariant from specification section 7.2 that only a real commit order can show — the
     * unit test proves the transaction refuses, this proves the loser's item is still in their hand.
     */
    @GameTest(template = "platform", timeoutTicks = 100)
    public static void twoActorsCannotFillOneSlot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        ServerPlayer subject = player(level, helper, new BlockPos(2, 1, 2));
        ServerPlayer first = player(level, helper, new BlockPos(1, 1, 1));
        ServerPlayer second = player(level, helper, new BlockPos(3, 1, 1));

        ApplicationTransaction.clearDeliveries();
        first.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(CrimeItems.RESTRAINT_CUFFS.get()));
        second.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(CrimeItems.RESTRAINT_LOCKED_CUFFS.get()));

        ApplicationTransaction.Result won = RestraintService.apply(first, subject,
                InteractionHand.MAIN_HAND, RestraintSlot.ARMS,
                AppliedRestraint.ApplicationContext.UNLAWFUL);
        ApplicationTransaction.Result lost = RestraintService.apply(second, subject,
                InteractionHand.MAIN_HAND, RestraintSlot.ARMS,
                AppliedRestraint.ApplicationContext.UNLAWFUL);

        helper.assertTrue(won.applied(), "the first application was refused: " + won.refusal());
        helper.assertTrue(!lost.applied(), "both actors filled the same slot");
        helper.assertTrue(first.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(),
                "the successful application did not spend its item");
        helper.assertTrue(!second.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(),
                "a refused application still cost the actor their restraint");

        PhysicalRestraintState state = data.physicalRestraint(subject.getUUID());
        helper.assertTrue(state != null && state.slot(RestraintSlot.ARMS)
                        .map(worn -> RestraintDefinitions.SHACKLES_ARMS.equals(worn.definitionId()))
                        .orElse(false),
                "the winner's gear is not what the subject is wearing");

        RemovalService.remove(subject, RestraintSlot.ARMS, RemovalService.Reason.ADMINISTRATIVE, null);
        first.discard();
        second.discard();
        subject.discard();
        helper.succeed();
    }

    private static ServerPlayer player(ServerLevel level, GameTestHelper helper, BlockPos at) {
        return player(level, helper, at, null);
    }

    /**
     * A server player with a real packet listener whose sends are recorded.
     *
     * <p>Not added to the player list: these are subjects and actors for server-side calls, and a
     * mock in the list would be visible to every other test running on the same server.
     */
    private static ServerPlayer player(ServerLevel level, GameTestHelper helper, BlockPos at,
                                       List<Packet<?>> sent) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "mcacrime-restraint");
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
