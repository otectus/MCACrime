package dev.otectus.mcacrime;

import dev.otectus.mcacrime.network.PhysicalStateDeltaS2CPacket;
import dev.otectus.mcacrime.network.PhysicalStateRemoveS2CPacket;
import dev.otectus.mcacrime.network.PhysicalStateS2CPacket;
import dev.otectus.mcacrime.restraint.AppliedRestraint;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import dev.otectus.mcacrime.restraint.PhysicalRestraintView;
import dev.otectus.mcacrime.restraint.RestraintApplier;
import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every restraint packet, over a real buffer.
 *
 * <p>Worth asserting because field order is the only thing holding a wire format together. A reader
 * and a writer that disagree by one byte do not throw: they produce a plausible wrong answer — the
 * holder entity id shifts, and the rope is drawn to whatever entity happens to hold that id.
 *
 * <p>Rewritten for 0.7.5 M2.11. The single-slot {@code RestraintSyncS2CPacket} and its bulk twin are
 * gone with the legacy engine, and the three multi-slot messages that replaced them are what is
 * asserted here: a full snapshot, a per-subject delta and an explicit removal. Each exists because
 * the others cannot say what it says, so each round-trips on its own.
 */
class RestraintSyncPacketTest {

    private static final UUID SUBJECT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    // ------------------------------------------------------------------ 0.7.5 physical state

    private static PhysicalRestraintView view(UUID subject, long generation, long revision) {
        Map<RestraintSlot, PhysicalRestraintView.SlotView> slots = new LinkedHashMap<>();
        slots.put(RestraintSlot.ARMS, new PhysicalRestraintView.SlotView(
                RestraintDefinitions.HANDCUFFS_ARMS, 0.5F, false));
        slots.put(RestraintSlot.LEGS, new PhysicalRestraintView.SlotView(
                RestraintDefinitions.SHACKLES_LEGS, 1.0F, false));
        slots.put(RestraintSlot.HEAD, new PhysicalRestraintView.SlotView(
                RestraintDefinitions.BUNDLE, 0.0F, true));
        return new PhysicalRestraintView(subject, generation, revision, slots, 4242, true);
    }

    @Test
    void theFullSnapshotRoundTripsWithEverySlotOccupied() {
        PhysicalRestraintView first = view(SUBJECT, 3L, 17L);
        PhysicalRestraintView second = PhysicalRestraintView.empty(
                UUID.fromString("66666666-7777-8888-9999-000000000000"), 1L, 2L);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PhysicalStateS2CPacket.encode(new PhysicalStateS2CPacket(List.of(first, second)), buf);
        PhysicalStateS2CPacket back = PhysicalStateS2CPacket.decode(buf);

        assertEquals(List.of(first, second), back.subjects());
        assertEquals(0, buf.readableBytes(), "the decoder must consume exactly what the encoder wrote");
        assertEquals(0.5F, back.subjects().get(0).slot(RestraintSlot.ARMS).orElseThrow().durabilityFraction());
        assertEquals(3L, back.subjects().get(0).generation());
        assertEquals(4242, back.subjects().get(0).tetherHolderEntityId());
        assertTrue(back.subjects().get(0).detained());
    }

    @Test
    void anEmptySnapshotIsLegalAndSoIsAVacantSubject() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PhysicalStateS2CPacket.encode(new PhysicalStateS2CPacket(List.of()), buf);
        assertEquals(List.of(), PhysicalStateS2CPacket.decode(buf).subjects());

        PhysicalRestraintView vacant = PhysicalRestraintView.empty(SUBJECT, 1L, 5L);
        FriendlyByteBuf single = new FriendlyByteBuf(Unpooled.buffer());
        PhysicalStateDeltaS2CPacket.encode(new PhysicalStateDeltaS2CPacket(vacant), single);
        PhysicalRestraintView back = PhysicalStateDeltaS2CPacket.decode(single).subject();

        assertEquals(vacant, back);
        assertTrue(back.vacant());
        assertEquals(PhysicalRestraintView.NO_HOLDER, back.tetherHolderEntityId(),
                "the no-holder sentinel has to survive the VarInt");
    }

    @Test
    void aDeltaRoundTripsOneSubject() {
        PhysicalRestraintView sent = view(SUBJECT, 1L, 9L);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PhysicalStateDeltaS2CPacket.encode(new PhysicalStateDeltaS2CPacket(sent), buf);

        assertEquals(sent, PhysicalStateDeltaS2CPacket.decode(buf).subject());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void aRemovalCarriesTheRevisionItWasIssuedAt() {
        PhysicalStateRemoveS2CPacket sent = new PhysicalStateRemoveS2CPacket(SUBJECT, 12L);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PhysicalStateRemoveS2CPacket.encode(sent, buf);

        assertEquals(sent, PhysicalStateRemoveS2CPacket.decode(buf));
        assertEquals(0, buf.readableBytes());
    }

    /** Render data only (§1.6): nothing here can carry an applier, a custody id or a lock. */
    @Test
    void thePhysicalViewCarriesNothingPrivate() {
        PhysicalRestraintView projected = PhysicalRestraintView.of(
                PhysicalRestraintState.empty(SUBJECT, true, null)
                        .with(RestraintSlot.ARMS, AppliedRestraint.of(
                                RestraintDefinitions.get(RestraintDefinitions.HANDCUFFS_ARMS).orElseThrow(),
                                null, RestraintApplier.player(UUID.randomUUID()),
                                AppliedRestraint.ApplicationContext.UNLAWFUL,
                                AppliedRestraint.Provenance.PLAYER_OWNED,
                                AppliedRestraint.ReturnPolicy.RETURN_TO_APPLIER, UUID.randomUUID(), 5L)),
                PhysicalRestraintView.NO_HOLDER, false);

        assertEquals(1, projected.slots().size());
        PhysicalRestraintView.SlotView slot = projected.slot(RestraintSlot.ARMS).orElseThrow();
        assertEquals(RestraintDefinitions.HANDCUFFS_ARMS, slot.definitionId());
        assertEquals(1.0F, slot.durabilityFraction(), "the fraction, never the raw durability");
        assertEquals(3, PhysicalRestraintView.SlotView.class.getRecordComponents().length,
                "a slot view is a definition, a fraction and a flag -- nothing else may be added "
                        + "without deciding it is safe to broadcast");
    }

}
