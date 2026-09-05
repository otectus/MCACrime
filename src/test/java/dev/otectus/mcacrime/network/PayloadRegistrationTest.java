package dev.otectus.mcacrime.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The payload ids are the wire format now, so they are pinned here (spec §9.1).
 *
 * <p>Under the Forge {@code SimpleChannel} the discriminator was the registration index, and this
 * test could not have existed: reordering the list changed the protocol invisibly. A named id can be
 * asserted, and renaming one now breaks a test instead of a server.
 */
class PayloadRegistrationTest {

    /** Exactly the table in spec §9.1, in its order. */
    private static final Map<CustomPacketPayload.Type<?>, String> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put(RequestActionMenuC2SPacket.TYPE, "request_action_menu");
        EXPECTED.put(StartActionC2SPacket.TYPE, "start_action");
        EXPECTED.put(RequestSelfMenuC2SPacket.TYPE, "request_self_menu");
        EXPECTED.put(GuardChallengeResponseC2SPacket.TYPE, "guard_challenge_response");
        EXPECTED.put(RequestCaseLedgerC2SPacket.TYPE, "request_case_ledger");
        EXPECTED.put(SelfStatusS2CPacket.TYPE, "self_status");
        EXPECTED.put(BandSyncS2CPacket.TYPE, "band_sync");
        EXPECTED.put(BandBulkSyncS2CPacket.TYPE, "band_bulk_sync");
        EXPECTED.put(CaptiveStatusS2CPacket.TYPE, "captive_status");
        EXPECTED.put(ActionMenuS2CPacket.TYPE, "action_menu");
        EXPECTED.put(ActionProgressS2CPacket.TYPE, "action_progress");
        EXPECTED.put(GuardChallengeS2CPacket.TYPE, "guard_challenge");
        EXPECTED.put(CaseLedgerS2CPacket.TYPE, "case_ledger");
        EXPECTED.put(RestraintSyncS2CPacket.TYPE, "restraint_sync");
        EXPECTED.put(RestraintBulkSyncS2CPacket.TYPE, "restraint_bulk_sync");
        EXPECTED.put(WeaponPolicyS2CPacket.TYPE, "weapon_policy");
        EXPECTED.put(CriminalJobSyncS2CPacket.TYPE, "criminal_job_sync");
    }

    @Test
    void thereAreSeventeenPayloads() {
        assertEquals(17, EXPECTED.size());
    }

    @Test
    void everyIdMatchesTheSpecAndSitsInTheModNamespace() {
        EXPECTED.forEach((type, path) -> {
            ResourceLocation id = type.id();
            assertEquals("mcacrime", id.getNamespace(), "payload " + path + " left the mod namespace");
            assertEquals(path, id.getPath());
        });
    }

    @Test
    void everyIdIsUnique() {
        List<ResourceLocation> ids = EXPECTED.keySet().stream().map(CustomPacketPayload.Type::id).toList();
        assertEquals(ids.size(), ids.stream().distinct().count(), "duplicate payload id: " + ids);
    }

    @Test
    void everyPayloadReportsItsOwnType() {
        assertTrue(RequestCaseLedgerC2SPacket.TYPE == new RequestCaseLedgerC2SPacket().type(),
                "a payload whose type() is not its own TYPE would be registered under one id and sent "
                        + "under another");
    }
}
