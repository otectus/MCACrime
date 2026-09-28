package dev.otectus.mcacrime.gametest;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.entity.ChainKnotEntity;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.tether.EscortTransport;
import dev.otectus.mcacrime.tether.TetherKind;
import dev.otectus.mcacrime.tether.TetherRecord;
import dev.otectus.mcacrime.tether.TetherService;
import dev.otectus.mcacrime.tether.TransportArbiter;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The transport engine on a real server (0.7.5 P4, plan section 7.2).
 *
 * <p>Three things a unit test on the records cannot show, because all three are about entities in a
 * live world: a chain that is minted exactly once however many paths untie it, a taut tether that
 * actually moves the subject it holds, and an anchor transfer that carries the same chain across
 * rather than paying one back and taking another.
 *
 * <p>The subjects are pigs, which the shipped {@code mcacrime:chainable_entities} tag names, and the
 * chain is counted as dropped items rather than as inventory. That is deliberate: a real
 * {@code ServerPlayer} here would have to be placed through {@code PlayerList}, which fires the login
 * event and makes MCA open its destiny screen on a connection that does not exist.
 *
 * <p>Its own batch. Every test here writes to the one {@code CrimeWorldData} tether table and to the
 * index built from it, so running them beside another batch's tests in the same world would let one
 * test's leftovers decide another's arbitration. Each test also unties what it tied, for the same
 * reason.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TetherLifecycleGameTests {

    private TetherLifecycleGameTests() {
    }

    /**
     * Detaching twice yields one chain.
     *
     * <p>Upstream mints a fresh {@code Items.CHAIN} on every {@code setAnchoredTo(null)} call, from
     * five call sites with nothing guarding a double call ({@code mixin/LivingEntityMixin.java:64-79}).
     * Here the table write is the gate, and this is the proof at the level the bug lives at: what is
     * lying on the floor after two detaches of one tether.
     */
    @GameTest(template = "platform", timeoutTicks = 200, batch = "tether_lifecycle")
    public static void detachingTwiceYieldsOneChain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        LivingEntity holder = pig(helper, new BlockPos(1, 2, 1));
        LivingEntity subject = pig(helper, new BlockPos(2, 2, 2));

        Optional<TetherRecord> tether = TetherService.attach(data, subject, holder, TetherKind.CHAIN,
                UUID.randomUUID(), true);
        helper.assertTrue(tether.isPresent(), "the chain would not go on");

        helper.assertTrue(TetherService.detach(level.getServer(), data, tether.get().id(),
                TetherService.DetachReason.RELEASED).isPresent(), "the first detach owed a chain");
        helper.assertTrue(TetherService.detach(level.getServer(), data, tether.get().id(),
                TetherService.DetachReason.RELEASED).isEmpty(), "a second detach paid a second chain");

        int chains = chainsOnTheFloor(helper);
        helper.assertTrue(chains == 1, "two detaches of one tether produced " + chains + " chains");
        helper.assertTrue(TetherService.forSubject(data, subject.getUUID()).isEmpty(),
                "the tether row outlived its detach");

        cleanUp(helper, data, holder, subject);
        helper.succeed();
    }

    /**
     * A taut tether pulls, a slack one does not, and neither teleports anybody.
     *
     * <p>The arbiter is asked first, so this also asserts the ladder: one authority, the chain, and a
     * bounded correction added to the subject's own motion rather than substituted for it.
     */
    @GameTest(template = "platform", timeoutTicks = 200, batch = "tether_lifecycle")
    public static void aTautTetherPullsTheSubjectBackWithoutTeleportingThem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        LivingEntity holder = pig(helper, new BlockPos(1, 2, 1));
        LivingEntity subject = pig(helper, new BlockPos(2, 2, 2));

        Optional<TetherRecord> tether = TetherService.attach(data, subject, holder, TetherKind.CHAIN,
                UUID.randomUUID(), true);
        helper.assertTrue(tether.isPresent(), "the chain would not go on");

        // Slack: inside the pull length nothing at all happens to the subject's motion.
        subject.setDeltaMovement(Vec3.ZERO);
        TransportArbiter.Authority slack = TransportArbiter.tick(level.getServer(), data, subject);
        helper.assertTrue(slack == TransportArbiter.Authority.CHAIN,
                "the chain is not the authority holding this subject: " + slack);
        helper.assertTrue(subject.getDeltaMovement().lengthSqr() == 0.0D,
                "a slack chain moved the subject");

        // Taut: eight blocks out, past the default five-block pull length.
        Vec3 before = subject.position();
        subject.setPos(before.x + 8.0D, before.y, before.z);
        subject.setDeltaMovement(Vec3.ZERO);
        EscortTransport.Step step = EscortTransport.tickChain(level.getServer(), data, subject,
                data.tether(tether.get().id()));
        helper.assertTrue(step == EscortTransport.Step.PULLED || step == EscortTransport.Step.STRAINED,
                "a tether eight blocks out did not pull: " + step);
        Vec3 correction = subject.getDeltaMovement();
        helper.assertTrue(correction.lengthSqr() > 0.0D, "the pull applied no velocity at all");
        helper.assertTrue(correction.length() <= 0.29D,
                "the pull exceeded one tick's cap: " + correction.length());
        helper.assertTrue(correction.y == 0.0D, "the pull had a vertical component");
        helper.assertTrue(subject.position().x == before.x + 8.0D,
                "the subject was teleported rather than pulled");

        cleanUp(helper, data, holder, subject);
        helper.succeed();
    }

    /**
     * Tying a held subject to a fence carries the chain across; it does not mint or consume one.
     *
     * <p>The knot is a real entity on a real fence, and the tether that comes out of it is keyed by
     * the block, which is what makes "what is tied to this post?" a map read rather than upstream's
     * world-wide entity scan.
     */
    @GameTest(template = "platform", timeoutTicks = 200, batch = "tether_lifecycle")
    public static void anchoringAHeldSubjectCarriesTheChainAcross(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        LivingEntity holder = pig(helper, new BlockPos(1, 2, 1));
        LivingEntity subject = pig(helper, new BlockPos(2, 2, 2));
        UUID owner = UUID.randomUUID();
        BlockPos fence = new BlockPos(3, 1, 3);
        helper.setBlock(fence, Blocks.OAK_FENCE);
        BlockPos fenceAbs = helper.absolutePos(fence);

        helper.assertTrue(TetherService.attach(data, subject, holder, TetherKind.CHAIN, owner, true)
                .isPresent(), "the chain would not go on");

        ChainKnotEntity knot = ChainKnotEntity.getOrCreate(level, fenceAbs);
        helper.assertTrue(knot != null, "no knot could be tied to the fence");
        helper.assertTrue(knot.transferFrom(data, holder) == 1,
                "the held subject was not moved onto the knot");

        List<TetherRecord> held = TetherService.forSubject(data, subject.getUUID());
        helper.assertTrue(held.size() == 1, "the transfer left " + held.size() + " tethers on one subject");
        helper.assertTrue(held.get(0).kind() == TetherKind.ANCHOR, "the transferred tether is not an anchor");
        helper.assertTrue(fenceAbs.equals(held.get(0).anchorPos()), "the anchor is not the fence");
        helper.assertTrue(owner.equals(held.get(0).chainOwner()),
                "the chain changed hands during the transfer");
        helper.assertTrue(chainsOnTheFloor(helper) == 0,
                "the transfer paid back a chain nobody had handed in");
        helper.assertTrue(TetherService.anchored(data, level.dimension().location(), fenceAbs),
                "the anchor index does not know about the knot");

        // Untying the knot pays the one chain back, once.
        helper.assertTrue(TetherService.detachAtAnchor(level.getServer(), data,
                level.dimension().location(), fenceAbs, TetherService.DetachReason.RELEASED) == 1,
                "the anchor released the wrong number of subjects");
        helper.assertTrue(chainsOnTheFloor(helper) == 1,
                "untying the knot produced " + chainsOnTheFloor(helper) + " chains");
        knot.discardKnot();

        cleanUp(helper, data, holder, subject);
        helper.succeed();
    }

    /**
     * A holder who is lost releases everybody they were leading, and pays for each of them once.
     *
     * <p>The other half of the same rule: the death and logout paths end the hold from the holder's
     * side, and a second pass over a holder who is already gone finds nothing to end.
     */
    @GameTest(template = "platform", timeoutTicks = 200, batch = "tether_lifecycle")
    public static void aHoldersDeathReleasesEverybodyTheyWereLeading(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        LivingEntity holder = pig(helper, new BlockPos(1, 2, 1));
        LivingEntity first = pig(helper, new BlockPos(2, 2, 2));
        LivingEntity second = pig(helper, new BlockPos(3, 2, 2));

        helper.assertTrue(TetherService.attach(data, first, holder, TetherKind.CHAIN,
                UUID.randomUUID(), true).isPresent(), "the first chain would not go on");
        helper.assertTrue(TetherService.attach(data, second, holder, TetherKind.CHAIN,
                UUID.randomUUID(), true).isPresent(), "the second chain would not go on");
        helper.assertTrue(TetherService.index(data).heldBy(holder.getUUID()) == 2,
                "the holder is not leading both subjects");

        int ended = TetherService.detachHeldBy(level.getServer(), data, holder.getUUID(),
                TetherService.DetachReason.HOLDER_LOST);
        helper.assertTrue(ended == 2, "a lost holder released " + ended + " of two subjects");
        helper.assertTrue(TetherService.forSubject(data, first.getUUID()).isEmpty()
                        && TetherService.forSubject(data, second.getUUID()).isEmpty(),
                "somebody is still tied to a holder who is gone");
        helper.assertTrue(TetherService.detachHeldBy(level.getServer(), data, holder.getUUID(),
                TetherService.DetachReason.HOLDER_LOST) == 0, "the release ran twice");
        int chains = chainsOnTheFloor(helper);
        helper.assertTrue(chains == 2, "two chains went out and " + chains + " came back");

        cleanUp(helper, data, holder, first, second);
        helper.succeed();
    }

    /** A pig with no free will: chainable by the shipped tag, and it stays where it is put. */
    private static LivingEntity pig(GameTestHelper helper, BlockPos pos) {
        return helper.spawnWithNoFreeWill(EntityType.PIG, pos);
    }

    /** Every chain lying inside the test structure. The engine's only currency. */
    private static int chainsOnTheFloor(GameTestHelper helper) {
        List<? extends Entity> items = helper.getLevel()
                .getEntities(EntityType.ITEM, helper.getBounds(), Entity::isAlive);
        int chains = 0;
        for (Entity entity : items) {
            if (entity instanceof ItemEntity item && item.getItem().is(Items.CHAIN)) {
                chains += item.getItem().getCount();
            }
        }
        return chains;
    }

    /** Leaves the tether table and the floor as this test found them, for the next test in the batch. */
    private static void cleanUp(GameTestHelper helper, CrimeWorldData data, LivingEntity... subjects) {
        for (LivingEntity subject : subjects) {
            TetherService.detachAll(helper.getLevel().getServer(), data, subject.getUUID(),
                    TetherService.DetachReason.ADMINISTRATIVE);
            TetherService.detachHeldBy(helper.getLevel().getServer(), data, subject.getUUID(),
                    TetherService.DetachReason.ADMINISTRATIVE);
            subject.discard();
        }
        helper.getLevel().getEntities(EntityType.ITEM, helper.getBounds(), Entity::isAlive)
                .forEach(Entity::discard);
    }
}
