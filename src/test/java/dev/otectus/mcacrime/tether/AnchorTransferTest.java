package dev.otectus.mcacrime.tether;

import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holder to fence hook to a second hook: one tether and one chain throughout (0.7.5 M4.2).
 *
 * <p>The acceptance test of specification §21.3. A subject is led by a player, tied to a fence knot,
 * moved onto another knot and untied again, and at no point does the world contain two tethers
 * for them or two chain-ownership records — which is the failure the source produces on every
 * transfer, because it detaches by minting a fresh chain and then attaches by taking another.
 */
class AnchorTransferTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private CrimeWorldData data;
    private UUID subject;
    private UUID player;
    private UUID knot;
    private UUID secondKnot;
    private BlockPos post;
    private BlockPos secondPost;

    @BeforeEach
    void freshWorld() {
        data = new CrimeWorldData();
        subject = UUID.randomUUID();
        player = UUID.randomUUID();
        knot = UUID.randomUUID();
        secondKnot = UUID.randomUUID();
        post = new BlockPos(4, 64, 4);
        secondPost = new BlockPos(9, 64, 9);
        TetherService.invalidate();
    }

    /** The transfer as {@code ChainKnotEntity} performs it. */
    private TetherRecord transfer(TetherRecord from, UUID toEntity, BlockPos toBlock) {
        UUID owner = from.chainOwner();
        boolean owed = from.returnOnRelease();
        // Carried across, never paid back and taken again: the row is removed without payment, then
        // re-made against the new anchor with the same owner. Exactly one chain exists throughout.
        assertTrue(data.removeTether(from.id()));
        TetherService.index(data).remove(from.id());
        TetherRecord moved = new TetherRecord(UUID.randomUUID(), from.subject(), TetherKind.ANCHOR,
                toEntity, OVERWORLD, toBlock, 5.0D, false, owner, owed, 1L);
        assertTrue(data.putTether(moved));
        TetherService.index(data).put(moved);
        return moved;
    }

    private TetherRecord led() {
        TetherRecord tether = TetherRecord.toHolder(UUID.randomUUID(), subject, TetherKind.CHAIN,
                player, OVERWORLD, 5.0D, player, true);
        assertTrue(data.putTether(tether));
        TetherService.index(data).put(tether);
        return tether;
    }

    @Test
    void holderFenceHookSecondHook() {
        TetherRecord onPlayer = led();
        assertEquals(1, data.tethers().size());
        assertEquals(player, onPlayer.chainOwner());

        TetherRecord onKnot = transfer(onPlayer, knot, post);
        assertEquals(1, data.tethers().size(), "one tether, still");
        assertEquals(player, onKnot.chainOwner(), "and it is still the player's chain");
        assertTrue(onKnot.returnOnRelease());
        assertTrue(TetherService.anchored(data, OVERWORLD, post));
        assertTrue(TetherService.index(data).forHolder(player).isEmpty(),
                "the player is no longer leading them");

        TetherRecord onAnchor = transfer(onKnot, secondKnot, secondPost);
        assertEquals(1, data.tethers().size());
        assertEquals(player, onAnchor.chainOwner());
        assertFalse(TetherService.anchored(data, OVERWORLD, post), "the fence post is free again");
        assertTrue(TetherService.anchored(data, OVERWORLD, secondPost));

        assertTrue(TetherService.owes(onAnchor, TetherService.DetachReason.RELEASED),
                "and untying it at the end still owes the player exactly one chain");
        TetherService.detach(null, data, onAnchor.id(), TetherService.DetachReason.RELEASED);
        assertEquals(0, data.tethers().size());
    }

    @Test
    void oneKnotHoldsSeveralSubjectsAndEachIsItsOwnRow() {
        UUID second = UUID.randomUUID();
        TetherRecord first = transfer(led(), knot, post);
        TetherRecord other = new TetherRecord(UUID.randomUUID(), second, TetherKind.ANCHOR, knot,
                OVERWORLD, post, 5.0D, false, player, true, 1L);
        assertTrue(data.putTether(other));
        TetherService.index(data).put(other);

        List<TetherRecord> atPost = TetherService.index(data).forAnchor(OVERWORLD, post);
        assertEquals(2, atPost.size(), "a knot is multi-subject");

        // Breaking the post unties both, once each.
        int ended = 0;
        for (TetherRecord tether : atPost) {
            TetherService.detach(null, data, tether.id(), TetherService.DetachReason.HOLDER_LOST);
            ended++;
        }
        assertEquals(2, ended);
        assertEquals(0, data.tethers().size());
        assertFalse(TetherService.anchored(data, OVERWORLD, post));
        assertNull(data.tether(first.id()));
        assertNotNull(other);
    }

    @Test
    void theSubjectsPhysicalRowFollowsTheTetherAcrossTheTransfer() {
        TetherRecord onPlayer = led();
        var state = dev.otectus.mcacrime.restraint.PhysicalRestraintState
                .empty(subject, true, OVERWORLD).withTether(onPlayer.id());
        assertTrue(data.putPhysicalRestraint(state));
        assertEquals(onPlayer.id(), data.physicalRestraint(subject).tetherId());

        TetherRecord onKnot = transfer(onPlayer, knot, post);
        assertTrue(data.putPhysicalRestraint(data.physicalRestraint(subject).withTether(onKnot.id())));
        assertEquals(onKnot.id(), data.physicalRestraint(subject).tetherId(),
                "the client is told about the knot, not about a tether that no longer exists");
    }
}
