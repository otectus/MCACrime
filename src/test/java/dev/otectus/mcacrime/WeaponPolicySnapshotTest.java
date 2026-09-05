package dev.otectus.mcacrime;

import dev.otectus.mcacrime.item.weapon.WeaponMatch;
import dev.otectus.mcacrime.item.weapon.WeaponPolicySnapshot;
import dev.otectus.mcacrime.item.weapon.WeaponProbe;
import dev.otectus.mcacrime.item.weapon.WeaponRules;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The snapshot that keeps the client's idea of "armed" identical to the server's.
 *
 * <p>Two properties, and the second is the reason the class exists. The round-trip is ordinary
 * packet hygiene. The second — that {@code toRules()} classifies exactly as a directly compiled rule
 * set does — is the invariant: the client greys out the Crime button off these rules and the server
 * rejects the packet off its own, so any divergence at all shows up as a button that lies.
 */
class WeaponPolicySnapshotTest {

    private static final List<String> WHITELIST = List.of("minecraft:stick", "#forge:tools/knives");
    private static final List<String> BLACKLIST = List.of("minecraft:golden_sword");
    private static final List<String> KEYWORDS = List.of("gun", "rifle");
    private static final List<String> MODS = List.of("tacz");

    private static WeaponPolicySnapshot snapshot() {
        return new WeaponPolicySnapshot(false, WHITELIST, BLACKLIST, true, 4.5D, KEYWORDS, MODS, true);
    }

    /** The buffer every payload codec on this platform is written against. */
    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    /** A probe for an item that does not exist, which is all the pure rules ever see. */
    private static WeaponProbe probe(String id, Set<String> tags, boolean sword, double damage) {
        return new WeaponProbe(ResourceLocation.parse(id), tag -> tags.contains(tag.toString()),
                false, sword, false, false, false, false, false, false, damage, "NONE");
    }

    @Test
    void encodeDecodeRoundTrips() {
        WeaponPolicySnapshot original = snapshot();
        RegistryFriendlyByteBuf buf = buffer();
        WeaponPolicySnapshot.STREAM_CODEC.encode(buf, original);
        assertEquals(original, WeaponPolicySnapshot.STREAM_CODEC.decode(buf));
        assertEquals(0, buf.readableBytes(), "decode must consume exactly what encode wrote");
    }

    @Test
    void theOffHandFlagSurvivesTheHop() {
        RegistryFriendlyByteBuf buf = buffer();
        WeaponPolicySnapshot.STREAM_CODEC.encode(buf,
                new WeaponPolicySnapshot(true, List.of(), List.of(), false, 0.0D, List.of(), List.of(), true));
        assertTrue(WeaponPolicySnapshot.STREAM_CODEC.decode(buf).allowOffHand());
    }

    /**
     * The menu gate travels too, and it is the one flag whose loss would be silent: a client that
     * assumed a weapon was required would grey out the button on a server that had turned the
     * requirement off, and the player would never learn why.
     */
    @Test
    void theCrimeMenuGateSurvivesTheHop() {
        RegistryFriendlyByteBuf buf = buffer();
        WeaponPolicySnapshot.STREAM_CODEC.encode(buf,
                new WeaponPolicySnapshot(true, List.of(), List.of(), false, 0.0D, List.of(), List.of(), false));
        WeaponPolicySnapshot decoded = WeaponPolicySnapshot.STREAM_CODEC.decode(buf);
        assertFalse(decoded.requireWeaponForCrimeMenu());
        assertEquals(0, buf.readableBytes(), "decode must consume exactly what encode wrote");
    }

    @Test
    void compiledRulesAgreeWithDirectlyCompiledRules() {
        WeaponPolicySnapshot snapshot = snapshot();
        WeaponRules fromSnapshot = snapshot.toRules();
        WeaponRules direct = WeaponRules.compile(WHITELIST, BLACKLIST, true, 4.5D, KEYWORDS, MODS);

        WeaponProbe[] probes = {
                probe("minecraft:stick", Set.of(), false, 0.0D),            // whitelisted
                probe("minecraft:golden_sword", Set.of(), true, 4.0D),      // blacklisted
                probe("minecraft:diamond_sword", Set.of(), true, 7.0D),     // auto-detected
                probe("minecraft:feather", Set.of(), false, 0.0D),          // nothing at all
                probe("tacz:rifle", Set.of(), false, 0.0D),                 // gun by namespace
                probe("othermod:thing", Set.of("mcacrime:weapons"), false, 0.0D), // tag-driven
        };
        for (WeaponProbe p : probes) {
            WeaponMatch expected = direct.classify(p);
            WeaponMatch actual = fromSnapshot.classify(p);
            assertEquals(expected, actual, "client and server disagree about " + p.id());
        }
    }

    @Test
    void aRoundTrippedSnapshotStillCompilesToTheSameRules() {
        RegistryFriendlyByteBuf buf = buffer();
        WeaponPolicySnapshot.STREAM_CODEC.encode(buf, snapshot());
        WeaponRules decoded = WeaponPolicySnapshot.STREAM_CODEC.decode(buf).toRules();
        WeaponProbe sword = probe("minecraft:diamond_sword", Set.of(), true, 7.0D);
        assertEquals(snapshot().toRules().classify(sword), decoded.classify(sword));
    }
}
