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
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The physical-restraint payloads, over a real buffer.
 *
 * <p>Worth asserting because the field order is the only thing holding the wire format together. A
 * reader and a writer that disagree by one field do not throw: they produce a plausible wrong answer
 * — the holder entity id shifts, and the rope is drawn to whatever entity happens to hold that id.
 * Hence the protocol bump to 15, and hence this.
 *
 * <p>0.7.5 M2.11 deleted the single-slot {@code RestraintSyncS2CPacket} and its bulk twin along with
 * the rest of the legacy engine, so the three messages below are the whole restraint wire format.
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

        PhysicalStateS2CPacket back = roundTrip(PhysicalStateS2CPacket.STREAM_CODEC,
                new PhysicalStateS2CPacket(List.of(first, second)));

        assertEquals(List.of(first, second), back.subjects());
        assertEquals(0.5F, back.subjects().get(0).slot(RestraintSlot.ARMS).orElseThrow().durabilityFraction());
        assertEquals(3L, back.subjects().get(0).generation());
        assertEquals(4242, back.subjects().get(0).tetherHolderEntityId());
        assertTrue(back.subjects().get(0).detained());
    }

    @Test
    void anEmptySnapshotIsLegalAndSoIsAVacantSubject() {
        assertEquals(List.of(), roundTrip(PhysicalStateS2CPacket.STREAM_CODEC,
                new PhysicalStateS2CPacket(List.of())).subjects());

        PhysicalRestraintView vacant = PhysicalRestraintView.empty(SUBJECT, 1L, 5L);
        PhysicalRestraintView back = roundTrip(PhysicalStateDeltaS2CPacket.STREAM_CODEC,
                new PhysicalStateDeltaS2CPacket(vacant)).subject();

        assertEquals(vacant, back);
        assertTrue(back.vacant());
        assertEquals(PhysicalRestraintView.NO_HOLDER, back.tetherHolderEntityId(),
                "the no-holder sentinel has to survive the VarInt");
    }

    @Test
    void aDeltaRoundTripsOneSubject() {
        PhysicalRestraintView sent = view(SUBJECT, 1L, 9L);
        assertEquals(sent, roundTrip(PhysicalStateDeltaS2CPacket.STREAM_CODEC,
                new PhysicalStateDeltaS2CPacket(sent)).subject());
    }

    @Test
    void aRemovalCarriesTheRevisionItWasIssuedAt() {
        PhysicalStateRemoveS2CPacket sent = new PhysicalStateRemoveS2CPacket(SUBJECT, 12L);
        assertEquals(sent, roundTrip(PhysicalStateRemoveS2CPacket.STREAM_CODEC, sent));
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

    private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        codec.encode(buf, value);
        T back = codec.decode(buf);
        assertEquals(0, buf.readableBytes(), "the decoder must consume exactly what the encoder wrote");
        return back;
    }
}
