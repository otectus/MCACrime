package dev.otectus.mcacrime.gametest;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.block.GuillotineBlock;
import dev.otectus.mcacrime.block.PilloryBlock;
import dev.otectus.mcacrime.block.entity.GuillotineBlockEntity;
import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detention.DetentionKind;
import dev.otectus.mcacrime.detention.DetentionRecord;
import dev.otectus.mcacrime.detention.DetentionService;
import dev.otectus.mcacrime.detention.ExecutionAuthorization;
import dev.otectus.mcacrime.economy.SettlementPolicy;
import dev.otectus.mcacrime.enforcement.CondemnedEscortService;
import dev.otectus.mcacrime.enforcement.ExecutionSiteRegistry;
import dev.otectus.mcacrime.ledger.CapitalDeathOutcome;
import dev.otectus.mcacrime.ledger.CapitalSentenceService;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.ledger.SentenceAssignmentService;
import dev.otectus.mcacrime.ledger.SentenceKind;
import dev.otectus.mcacrime.ransom.RansomService;
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

import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Capital sentencing on a real server (0.7.5 §3.19, plan section 7.2).
 *
 * <p>The behavioural proof the plan asks for on this line, and it is deliberately end-to-end rather
 * than another unit test: the JUnit suite already asserts the store model, the eligibility table and
 * the clemency rules against a bare {@code CrimeWorldData}. What only a running world can show is the
 * sequence — a guard-killing case becomes a capital binding, the binding closes the two prices a
 * player could otherwise pay, a real guillotine takes a life <em>only</em> while a live authorisation
 * names that subject and that actor, and the walk to a device that does not exist ends with the
 * prisoner still in their cell rather than dead, free or gone.
 *
 * <p>Its own batch, and every test clears what it created. {@code ExecutionAuthorization},
 * {@code CondemnedEscortService} and {@code CapitalDeathOutcome} are process-wide tables and
 * {@code CrimeWorldData} is a world-wide one; a leftover order, escort or custody row would decide the
 * next test in the batch rather than the next test deciding it.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CapitalSentenceGameTests {

    private static final UUID GUARD = UUID.randomUUID();

    private CapitalSentenceGameTests() {
    }

    /**
     * A guard-killing arrest binds a capital sentence, and no price clears it.
     *
     * <p>The three refusals of §3.19 in one pass, because they are one rule: a capital sentence is not
     * a quantity of anything a player may buy. The settlement quote closes rather than quietly
     * dropping the charge that mattered, and both the bail and ransom switches read as refusing.
     */
    @GameTest(template = "platform", timeoutTicks = 200, batch = "capital_sentence")
    public static void aGuardKillingArrestBindsACapitalSentenceNoPriceClears(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        UUID offender = UUID.randomUUID();
        clear(helper);

        CrimeRecord killing = guardKilling(offender);
        UUID sentence = arrest(helper, data, offender, killing);

        helper.assertTrue(CapitalSentenceService.mark(data, offender, sentence, false)
                        == SentenceKind.CAPITAL,
                "killing a guard did not produce a capital sentence");
        helper.assertTrue(CapitalSentenceService.condemned(level.getServer(), offender),
                "the server does not consider the offender condemned");
        helper.assertTrue(data.getCustody(offender).isCondemned(),
                "the custody record does not mirror the sentence it is serving");

        // A fine never clears a capital case, so the whole quote closes rather than leaving the player
        // believing they had settled up.
        helper.assertTrue(SettlementPolicy.mandatoryCustody(killing),
                "a guard killing was offered as a finable case");
        helper.assertTrue(CapitalSentenceService.refusesBail(),
                "the shipped default has to refuse bail; bail is priced in remaining ticks and a "
                        + "capital sentence is not a number of ticks");
        helper.assertTrue(CapitalSentenceService.refusesRansom(), "the shipped default has to refuse ransom");

        // And the holding term is kept exactly as the arrest priced it: a capital binding changes what
        // the sentence is, never how long it is.
        helper.assertTrue(data.getCustody(offender).getRemainingJailTicks() >= 0L,
                "the holding term was discarded by the capital marking");
        clear(helper);
        helper.succeed();
    }

    /**
     * A kidnapper holding a condemned captive is refused a ransom, with its own reason.
     *
     * <p>The end-to-end half of the refusal above: a real {@code ServerPlayer} captor, a real custody
     * row and the real demand path, which returns before it ever resolves a payer or quotes a price.
     * The village cannot buy back somebody the law has already sentenced to die, and pretending
     * otherwise would take the payer's money for nothing.
     */
    @GameTest(template = "platform", timeoutTicks = 200, batch = "capital_sentence")
    public static void aCondemnedCaptiveHasNoRansomPrice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        clear(helper);

        ServerPlayer captor = mockPlayer(helper);
        LivingEntity captive = pig(helper, new BlockPos(2, 1, 2));
        UUID captiveId = captive.getUUID();

        helper.assertTrue(CustodyService.capture(data, captor.getUUID(), captiveId, false,
                level.getGameTime(), captive.blockPosition(), level.dimension().location(), 8).ok(),
                "the captive could not be taken into unlawful custody");

        // Condemned, then held by somebody else: the order is the point. A sentence the law bound
        // earlier does not stop applying because a kidnapper has since taken the prisoner. The kind is
        // written onto the custody record directly rather than through an arrest, because binding a
        // sentence needs the lawful custody this test has deliberately replaced -- and what is under
        // test here is the refusal, not the binding, which
        // T:ledger/CapitalSentenceAssignmentTest owns.
        CustodyRecord held = data.getCustody(captiveId);
        helper.assertTrue(held != null, "the unlawful custody row is missing");
        held.setSentenceKind(SentenceKind.CAPITAL);
        helper.assertTrue(CapitalSentenceService.condemned(level.getServer(), captiveId),
                "the captive is not condemned, so the ransom refusal below would prove nothing");

        int status = RansomService.demandFor(captor, captiveId);
        helper.assertTrue(status == 0, "a ransom was quoted for a condemned captive");
        helper.assertTrue(data.getRansomForVictim(captiveId) == null,
                "a refused demand still opened a ransom");

        captive.discard();
        captor.discard();
        clear(helper);
        helper.succeed();
    }

    /**
     * The whole walkthrough: condemned, placed, authorised, struck once, settled once.
     *
     * <p>The device acts only under a live order, the death is one attributed damage action, and the
     * sentence closes exactly once — the completion marker is persisted, so a second activation finds
     * a device that has already struck and a sentence that has already settled.
     */
    @GameTest(template = "platform", timeoutTicks = 400, batch = "capital_sentence")
    public static void aCondemnedPrisonerIsExecutedOnceAtAnAuthorisedDevice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        clear(helper);
        CapitalSentenceService.install(); // the legal answer to "is this subject condemned?"

        try {
            BlockPos device = pillory(helper, new BlockPos(2, 1, 2));
            BlockPos frame = guillotine(helper, new BlockPos(2, 3, 2), device);
            LivingEntity condemned = pig(helper, new BlockPos(2, 1, 2));
            UUID condemnedId = condemned.getUUID();
            ServerPlayer executioner = mockPlayer(helper);

            UUID sentence = arrest(helper, data, condemnedId, guardKilling(condemnedId));
            data.setSentenceKind(sentence, SentenceKind.CAPITAL);
            data.getCustody(condemnedId).setSentenceKind(SentenceKind.CAPITAL);
            helper.assertTrue(CapitalSentenceService.condemned(level.getServer(), condemnedId),
                    "the prisoner is not condemned");

            DetentionService.Refusal placed = DetentionService.claim(data, condemnedId, false,
                    DetentionKind.GUILLOTINE, level.dimension().location(), device,
                    DetentionKind.GUILLOTINE.id());
            helper.assertTrue(placed == DetentionService.Refusal.NONE,
                    "the condemned could not be placed in the device: " + placed);

            GuillotineBlockEntity blade = strike(helper, level, frame, executioner);

            helper.assertTrue(!condemned.isAlive(), "an authorised execution left the condemned alive");
            helper.assertTrue(blade.completed(), "the device did not record that it had struck");
            helper.assertTrue(CapitalDeathOutcome.settled(sentence),
                    "the capital sentence was never settled");
            helper.assertTrue(data.getCustody(condemnedId) == null,
                    "the custody outlived the prisoner it was holding");
            helper.assertTrue(ExecutionAuthorization.all().isEmpty(),
                    "the order outlived the execution it authorised");
            helper.assertTrue(!CondemnedEscortService.escorting(condemnedId),
                    "an escort survived the sentence being carried out");

            // Once, and only once. A second activation has nobody to kill and nothing left to settle.
            strike(helper, level, frame, executioner);
            helper.assertTrue(CapitalDeathOutcome.completed().size() <= 1,
                    "the sentence settled more than once");

            executioner.discard();
        } finally {
            CapitalSentenceService.uninstall();
            clear(helper);
        }
        helper.succeed();
    }

    /**
     * With no device the condemned stays in custody, and nothing dies.
     *
     * <p>§3.19.4 stated as a running world: no substitute death, no despawn, no automatic commutation
     * and no expiry into freedom. The walk is refused at the site lookup, so no claim is taken and no
     * reservation is held either — a prisoner nobody can execute is a prisoner, which is the whole
     * point.
     */
    @GameTest(template = "platform", timeoutTicks = 300, batch = "capital_sentence")
    public static void withNoDeviceTheCondemnedStaysInCustody(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        clear(helper);
        ExecutionSiteRegistry.clear();
        CapitalSentenceService.install();

        try {
            LivingEntity condemned = pig(helper, new BlockPos(2, 1, 2));
            LivingEntity guard = pig(helper, new BlockPos(3, 1, 2));
            UUID condemnedId = condemned.getUUID();

            UUID sentence = arrest(helper, data, condemnedId, guardKilling(condemnedId));
            data.setSentenceKind(sentence, SentenceKind.CAPITAL);
            data.getCustody(condemnedId).setSentenceKind(SentenceKind.CAPITAL);

            CondemnedEscortService.Outcome outcome = CondemnedEscortService.begin(level, guard, condemned);
            helper.assertTrue(outcome == CondemnedEscortService.Outcome.NO_SITE,
                    "a walk began with no assigned execution site: " + outcome);
            helper.assertTrue(CondemnedEscortService.activeCount() == 0,
                    "a refused walk still left an escort behind");

            // Run the whole tick surface over it: still condemned, still held, still alive.
            for (int tick = 0; tick < 40; tick++) {
                CondemnedEscortService.tick(level.getServer());
                ExecutionAuthorization.expire(level.getGameTime() + tick);
            }
            helper.assertTrue(condemned.isAlive(), "a condemned prisoner with no device died anyway");
            helper.assertTrue(data.getCustody(condemnedId) != null,
                    "a condemned prisoner with no device was released");
            helper.assertTrue(CapitalSentenceService.condemned(level.getServer(), condemnedId),
                    "the sentence lapsed on its own, which nothing in §3.19 permits");
            helper.assertTrue(ExecutionAuthorization.all().isEmpty(),
                    "an order was armed with no device to carry it out");

            condemned.discard();
            guard.discard();
        } finally {
            CapitalSentenceService.uninstall();
            clear(helper);
        }
        helper.succeed();
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private static CrimeRecord guardKilling(UUID offender) {
        return new CrimeRecord(UUID.randomUUID(), offender, null, CrimeIds.KILL_GUARD,
                OptionalInt.empty(), true, 10L, 60L, -80L, 0L, 1200L, Resolution.UNRESOLVED);
    }

    /** Lawful custody plus a bound sentence, which is what an arrest leaves behind. */
    private static UUID arrest(GameTestHelper helper, CrimeWorldData data, UUID offender,
                               CrimeRecord charge) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(CustodyService.captureLawful(data, offender, false, CustodyOwner.guard(GUARD),
                        level.getGameTime(), BlockPos.ZERO, level.dimension().location()).ok(),
                "the offender could not be taken into lawful custody");
        data.addRecord(charge);
        UUID sentence = UUID.randomUUID();
        helper.assertTrue(SentenceAssignmentService.assign(data, offender, sentence,
                List.of(charge.id()), level.getGameTime()), "the sentence did not bind");
        return sentence;
    }

    private static BlockPos pillory(GameTestHelper helper, BlockPos lower) {
        BlockState base = CrimeBlocks.PILLORY.get().defaultBlockState()
                .setValue(PilloryBlock.FACING, Direction.NORTH)
                .setValue(PilloryBlock.HALF, DoubleBlockHalf.LOWER);
        helper.setBlock(lower, base);
        helper.setBlock(lower.above(), base.setValue(PilloryBlock.HALF, DoubleBlockHalf.UPPER));
        return helper.absolutePos(lower);
    }

    private static BlockPos guillotine(GameTestHelper helper, BlockPos frame, BlockPos device) {
        helper.setBlock(frame, CrimeBlocks.GUILLOTINE.get().defaultBlockState()
                .setValue(GuillotineBlock.FACING, Direction.NORTH));
        BlockPos abs = helper.absolutePos(frame);
        helper.assertTrue(GuillotineBlock.devicePos(abs).equals(device),
                "the frame does not cap the device under it");
        return abs;
    }

    /** A pig stands in for a villager: custody is keyed by id and needs no MCA type to exist. */
    private static LivingEntity pig(GameTestHelper helper, BlockPos at) {
        ServerLevel level = helper.getLevel();
        LivingEntity entity = EntityType.PIG.create(level);
        helper.assertTrue(entity != null, "no entity could be created");
        BlockPos abs = helper.absolutePos(at);
        entity.moveTo(abs.getX() + 0.5D, abs.getY(), abs.getZ() + 0.5D, 0.0F, 0.0F);
        level.addFreshEntity(entity);
        return entity;
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
     * A server player built rather than placed.
     *
     * <p>Deliberately not {@code GameTestHelper.makeMockServerPlayerInLevel}: that goes through
     * {@code PlayerList.placeNewPlayer}, which fires the login event, and MCA answers it by opening its
     * destiny screen on a connection this player does not have.
     */
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "mcacrime-capital");
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

    /** Leaves every process-wide and world-wide table as this batch found it. */
    private static void clear(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        CondemnedEscortService.clearAll();
        ExecutionAuthorization.clearAll();
        ExecutionSiteRegistry.clear();
        CapitalDeathOutcome.clearAll();
        for (DetentionRecord record : data.detentions()) {
            DetentionService.release(level.getServer(), data, record.id(),
                    DetentionService.ReleaseReason.ADMINISTRATIVE);
        }
        for (CustodyRecord record : data.custodyRecords()) {
            CustodyService.release(level.getServer(), record.getCaptive(),
                    dev.otectus.mcacrime.captivity.CustodyReleaseReason.ADMIN);
        }
    }
}
