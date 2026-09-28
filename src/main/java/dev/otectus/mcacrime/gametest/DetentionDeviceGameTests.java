package dev.otectus.mcacrime.gametest;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.block.GuillotineBlock;
import dev.otectus.mcacrime.block.PilloryBlock;
import dev.otectus.mcacrime.block.entity.GuillotineBlockEntity;
import dev.otectus.mcacrime.detention.BunkRespawnPolicy;
import dev.otectus.mcacrime.detention.DetentionKind;
import dev.otectus.mcacrime.detention.DetentionRecord;
import dev.otectus.mcacrime.detention.DetentionService;
import dev.otectus.mcacrime.detention.ExecutionAuthorization;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The detention devices on a real server (0.7.5 P4, plan section 7.2).
 *
 * <p>Two invariants only a running world can show. Occupancy is an atomic claim, so two closers on
 * one pillory produce one success and one refusal at the block level rather than at the record level.
 * And the guillotine takes a life <b>only</b> while a live authorisation names that subject, that
 * device and that actor — with none, the blade falls and the occupant walks away.
 *
 * <p>Its own batch, and each test clears what it armed. {@code ExecutionAuthorization} is a
 * process-wide table and {@code CrimeWorldData.detentions} is a world-wide one; a leftover order or a
 * leftover occupancy would decide the next test in the batch rather than the next test deciding it.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DetentionDeviceGameTests {

    private DetentionDeviceGameTests() {
    }

    /**
     * Two closers, one pillory, one occupant.
     *
     * <p>The device is real and both halves stand, so this is the claim as a player meets it: the
     * second close finds the device taken and refuses, and the boards close once.
     */
    @GameTest(template = "platform", timeoutTicks = 200, batch = "detention_devices")
    public static void twoClosersOnOnePilloryProduceOneOccupant(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        BlockPos device = pillory(helper, new BlockPos(2, 1, 2));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        // Both closers arrive in the same tick. The claim is one conditional write against the table,
        // so the order they arrive in decides who is held, not whether two people are.
        DetentionService.Refusal won = DetentionService.claim(data, first, true, DetentionKind.PILLORY,
                level.dimension().location(), device, DetentionKind.PILLORY.id());
        DetentionService.Refusal lost = DetentionService.claim(data, second, true, DetentionKind.PILLORY,
                level.dimension().location(), device, DetentionKind.PILLORY.id());

        helper.assertTrue(won == DetentionService.Refusal.NONE, "the first close was refused: " + won);
        helper.assertTrue(lost == DetentionService.Refusal.DEVICE_OCCUPIED,
                "the second close was not refused as occupied: " + lost);
        helper.assertTrue(DetentionService.at(data, level.dimension().location(), device)
                        .map(record -> first.equals(record.subject()))
                        .orElse(false),
                "the device is not holding the first closer");

        // Breaking one half releases the occupant exactly once, whatever the breakout toggle says.
        helper.setBlock(new BlockPos(2, 2, 2), net.minecraft.world.level.block.Blocks.AIR);
        helper.setBlock(new BlockPos(2, 1, 2), net.minecraft.world.level.block.Blocks.AIR);
        helper.assertTrue(DetentionService.at(data, level.dimension().location(), device).isEmpty(),
                "a destroyed device is still holding somebody");

        clear(helper, data);
        helper.succeed();
    }

    /**
     * With no authorisation the blade falls and the occupant lives.
     *
     * <p>The specification's rule stated as a running device: an unlawful, unsentenced or merely
     * restrained subject in a guillotine can still be released, never executed. No timer, no
     * comparator and no packet reaches the order, because there is no order.
     */
    @GameTest(template = "platform", timeoutTicks = 300, batch = "detention_devices")
    public static void anUnauthorisedGuillotineReleasesRatherThanKills(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        ExecutionAuthorization.clearAll();
        ExecutionAuthorization.bind(null); // nobody is condemned, which is the default answer

        BlockPos device = pillory(helper, new BlockPos(2, 1, 2));
        BlockPos frame = guillotine(helper, new BlockPos(2, 3, 2), device);
        LivingEntity occupant = detain(helper, data, device, DetentionKind.GUILLOTINE);
        ServerPlayer actor = mockPlayer(helper);

        strike(helper, level, frame, actor);

        helper.assertTrue(occupant.isAlive(), "an unauthorised guillotine took a life");
        helper.assertTrue(ExecutionAuthorization.all().isEmpty(),
                "pulling the lever created an order out of nothing");

        DetentionService.releaseSubject(level.getServer(), data, occupant.getUUID(),
                DetentionService.ReleaseReason.ADMINISTRATIVE);
        occupant.discard();
        actor.discard();
        clear(helper, data);
        helper.succeed();
    }

    /**
     * With a live authorisation the blade lands once, and only once.
     *
     * <p>Upstream runs {@code kill()} and {@code hurt(..., Float.MAX_VALUE)} through a dangling else
     * ({@code blocks/entity/GuillotineBlockEntity.java:308-313}). Here there is one attributed damage
     * action and a persisted completion marker, so a second activation finds a device that has already
     * struck.
     */
    @GameTest(template = "platform", timeoutTicks = 300, batch = "detention_devices")
    public static void anAuthorisedGuillotineStrikesExactlyOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        ExecutionAuthorization.clearAll();

        BlockPos device = pillory(helper, new BlockPos(2, 1, 2));
        BlockPos frame = guillotine(helper, new BlockPos(2, 3, 2), device);
        LivingEntity occupant = detain(helper, data, device, DetentionKind.GUILLOTINE);
        ServerPlayer actor = mockPlayer(helper);
        UUID condemned = occupant.getUUID();

        // The legal half arrives in P6; this stands in for it, which is exactly the seam the
        // CondemnedSource interface exists for.
        ExecutionAuthorization.bind((server, subject) -> condemned.equals(subject));
        try {
            GuillotineBlockEntity blade = strike(helper, level, frame, actor);
            helper.assertTrue(!occupant.isAlive(), "an authorised execution left the condemned alive");
            helper.assertTrue(blade.completed(), "the device did not record that it had struck");
            helper.assertTrue(ExecutionAuthorization.all().isEmpty(),
                    "the order outlived the execution it authorised");
            helper.assertTrue(DetentionService.forSubject(data, condemned).isEmpty(),
                    "the device is still holding somebody who is dead");

            // A second activation cannot kill again: there is nobody left and no order left either.
            int before = level.getEntities(EntityType.PIG, e -> true).size();
            strike(helper, level, frame, actor);
            helper.assertTrue(level.getEntities(EntityType.PIG, e -> true).size() == before,
                    "a second activation changed the world");
        } finally {
            ExecutionAuthorization.bind(null);
            ExecutionAuthorization.clearAll();
        }

        actor.discard();
        clear(helper, data);
        helper.succeed();
    }

    /**
     * A cancelled death resolves nothing.
     *
     * <p>Invulnerability stands in for the totem, the revive mod and the other mod's damage handler:
     * the blow is refused, the subject lives, and the head, the bounty and the custody closure that
     * only a confirmed death may trigger do not happen. The order survives, because none of its seven
     * clearing rules has fired.
     */
    @GameTest(template = "platform", timeoutTicks = 300, batch = "detention_devices")
    public static void aCancelledDeathResolvesNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        ExecutionAuthorization.clearAll();

        BlockPos device = pillory(helper, new BlockPos(2, 1, 2));
        BlockPos frame = guillotine(helper, new BlockPos(2, 3, 2), device);
        LivingEntity occupant = detain(helper, data, device, DetentionKind.GUILLOTINE);
        occupant.setInvulnerable(true);
        ServerPlayer actor = mockPlayer(helper);
        UUID condemned = occupant.getUUID();

        ExecutionAuthorization.bind((server, subject) -> condemned.equals(subject));
        try {
            strike(helper, level, frame, actor);
            helper.assertTrue(occupant.isAlive(), "an invulnerable subject was executed anyway");
            helper.assertTrue(DetentionService.forSubject(data, condemned).isPresent(),
                    "a cancelled death released the condemned from the device");
            helper.assertTrue(!ExecutionAuthorization.all().isEmpty(),
                    "a cancelled death cleared the order, which none of the seven rules says it may");
        } finally {
            ExecutionAuthorization.bind(null);
            ExecutionAuthorization.clearAll();
        }

        occupant.setInvulnerable(false);
        occupant.discard();
        actor.discard();
        clear(helper, data);
        helper.succeed();
    }

    /**
     * A bunk gives a respawn point back only while this system still owns the override.
     *
     * <p>The snapshot rule from M4.7, against a real {@code ServerPlayer}'s actual respawn state: a
     * prisoner who has since chosen a newer spawn keeps it, and one who has not gets their old one
     * back. Both halves, because the wrong one is a mod silently rewriting somebody's bed.
     */
    @GameTest(template = "platform", timeoutTicks = 200, batch = "detention_devices")
    public static void aBunkReturnsTheOldSpawnOnlyWhileItStillOwnsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bunkPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos ownBed = helper.absolutePos(new BlockPos(3, 1, 3));

        ServerPlayer prisoner = mockPlayer(helper);
        prisoner.setRespawnPosition(level.dimension(), ownBed, 0.0F, false, false);

        BunkRespawnPolicy.take(prisoner, bunkPos);
        prisoner.setRespawnPosition(level.dimension(), bunkPos, 0.0F, false, false);
        helper.assertTrue(BunkRespawnPolicy.release(prisoner),
                "the bunk did not give back a spawn point it still owned");
        helper.assertTrue(ownBed.equals(prisoner.getRespawnPosition()),
                "the prisoner's own bed was not restored");

        // Now the prisoner chooses a newer spawn while in the bunk: the snapshot is discarded.
        BlockPos newerBed = helper.absolutePos(new BlockPos(4, 1, 4));
        BunkRespawnPolicy.take(prisoner, bunkPos);
        prisoner.setRespawnPosition(level.dimension(), bunkPos, 0.0F, false, false);
        prisoner.setRespawnPosition(level.dimension(), newerBed, 0.0F, false, false);
        helper.assertTrue(!BunkRespawnPolicy.release(prisoner),
                "the bunk overwrote a spawn point the player had chosen since");
        helper.assertTrue(newerBed.equals(prisoner.getRespawnPosition()),
                "the newer bed did not survive the release");
        helper.assertTrue(BunkRespawnPolicy.snapshot(prisoner.getUUID()).isEmpty(),
                "the discarded snapshot was kept for a later release to apply");

        BunkRespawnPolicy.forget(prisoner.getUUID());
        prisoner.discard();
        helper.succeed();
    }

    // --- scaffolding ------------------------------------------------------------------------------

    /** Builds a whole pillory and returns its canonical (lower-half) absolute position. */
    private static BlockPos pillory(GameTestHelper helper, BlockPos lower) {
        BlockState base = CrimeBlocks.PILLORY.get().defaultBlockState()
                .setValue(PilloryBlock.FACING, Direction.NORTH)
                .setValue(PilloryBlock.HALF, DoubleBlockHalf.LOWER);
        helper.setBlock(lower, base);
        helper.setBlock(lower.above(), base.setValue(PilloryBlock.HALF, DoubleBlockHalf.UPPER));
        return helper.absolutePos(lower);
    }

    /** Caps a pillory with a guillotine frame and returns the frame's absolute position. */
    private static BlockPos guillotine(GameTestHelper helper, BlockPos frame, BlockPos device) {
        helper.setBlock(frame, CrimeBlocks.GUILLOTINE.get().defaultBlockState()
                .setValue(GuillotineBlock.FACING, Direction.NORTH));
        BlockPos abs = helper.absolutePos(frame);
        helper.assertTrue(GuillotineBlock.devicePos(abs).equals(device),
                "the frame does not cap the device under it");
        return abs;
    }

    /** A pig in the device, claimed by id so the test needs no restrainable tag entry. */
    private static LivingEntity detain(GameTestHelper helper, CrimeWorldData data, BlockPos device,
                                       DetentionKind kind) {
        ServerLevel level = helper.getLevel();
        LivingEntity occupant = level.getServer() == null ? null
                : EntityType.PIG.create(level);
        helper.assertTrue(occupant != null, "no occupant could be created");
        occupant.moveTo(device.getX() + 0.5D, device.getY(), device.getZ() + 0.5D, 0.0F, 0.0F);
        level.addFreshEntity(occupant);
        DetentionService.Refusal refusal = DetentionService.claim(data, occupant.getUUID(), false, kind,
                level.dimension().location(), device, kind.id());
        helper.assertTrue(refusal == DetentionService.Refusal.NONE,
                "the occupant could not be put in the device: " + refusal);
        return occupant;
    }

    /** Pulls the lever and runs the persisted delay out, returning the device that did the work. */
    private static GuillotineBlockEntity strike(GameTestHelper helper, ServerLevel level, BlockPos frame,
                                                ServerPlayer actor) {
        helper.assertTrue(level.getBlockEntity(frame) instanceof GuillotineBlockEntity,
                "the frame has no block entity");
        GuillotineBlockEntity blade = (GuillotineBlockEntity) level.getBlockEntity(frame);
        blade.pullLever(level, frame, level.getBlockState(frame), actor);
        for (int tick = 0; tick < 64 && blade.chopDelay() > 0; tick++) {
            GuillotineBlockEntity.serverTick(level, frame, level.getBlockState(frame), blade);
        }
        helper.assertTrue(blade.chopDelay() == 0, "the blade never landed");
        return blade;
    }

    /**
     * A server player for the tests that need one, built rather than placed.
     *
     * <p>Deliberately not {@code GameTestHelper.makeMockServerPlayerInLevel}: that goes through
     * {@code PlayerList.placeNewPlayer}, which fires the login event, and MCA answers it by opening
     * its destiny screen on a connection this player does not have. The connection here records what
     * it is given and sends nothing, which is all any of these tests needs.
     */
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "mcacrime-detention");
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile,
                ClientInformation.createDefault());
        player.connection = new ServerGamePacketListenerImpl(level.getServer(),
                new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(profile, false)) {
            @Override
            public void send(Packet<?> packet) {
                // Swallowed: nothing is on the other end, and a real send would refuse the packet.
            }
        };
        return player;
    }

    /** Leaves the detention table and the order table as this test found them. */
    private static void clear(GameTestHelper helper, CrimeWorldData data) {
        for (DetentionRecord record : data.detentions()) {
            DetentionService.release(helper.getLevel().getServer(), data, record.id(),
                    DetentionService.ReleaseReason.ADMINISTRATIVE);
        }
        ExecutionAuthorization.clearAll();
    }
}
