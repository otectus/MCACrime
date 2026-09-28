package dev.otectus.mcacrime.restraint;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code restraint_profiles} datapack layer (plan §3.13).
 *
 * <p>Three properties, and the third is the one that matters. A pack may move the numbers around a
 * definition; it may not introduce a definition, because the slot semantics behind one are an
 * authorisation decision nothing would validate. A file naming an id nobody declared is an error and
 * is ignored, and the table is still ten entries afterwards.
 *
 * <p>The second property is all-or-nothing. A file with one bad field is refused whole rather than
 * applied up to the bad line: a restraint whose durability came from the pack and whose restrictions
 * came from the code is a state neither the author nor the player can reason about.
 */
class RestraintProfileLoaderTest {

    private static final int SHIPPED_DEFINITIONS = 9;

    @AfterEach
    void clearOverrides() {
        RestraintProfileOverrides.clear();
    }

    @Test
    void aProfileOverridesEveryValueItNames() {
        List<String> errors = new ArrayList<>();
        List<RestraintProfile> loaded = RestraintProfileLoader.read(files(Map.of(
                RestraintDefinitions.HANDCUFFS_ARMS, """
                        {
                          "durability": 120,
                          "restrictions": { "mine_blocks": true, "sprint": false },
                          "pick": { "progress_increase": 3, "speed_increase": 20 },
                          "key_family": "shackles",
                          "supported_rigs": ["humanoid", "tall"]
                        }
                        """)), errors);

        assertTrue(errors.isEmpty(), "unexpected errors: " + errors);
        assertEquals(1, loaded.size());
        RestraintProfileOverrides.replaceAll(loaded);

        RestraintDefinition effective =
                RestraintDefinitions.get(RestraintDefinitions.HANDCUFFS_ARMS).orElseThrow();
        assertEquals(120, effective.escape().durability());
        assertTrue(effective.restrictions().mineBlocks(), "mine_blocks was loosened");
        assertFalse(effective.restrictions().sprint(), "sprint was tightened");
        assertFalse(effective.restrictions().useItem(), "an unnamed component keeps the code's answer");
        assertEquals(3, effective.pick().progressIncrease());
        assertEquals(20, effective.pick().speedIncrease());
        assertEquals(RestraintFamily.SHACKLES, effective.family().orElseThrow());
        assertEquals(RestraintDefinitions.ITEM_SHACKLES_KEY, effective.keyItem().orElseThrow());
        assertTrue(effective.rigPredicate().test(RigProfile.scaledHumanoid("tall", 1.2F)));
        assertFalse(effective.rigPredicate().test(
                new RigProfile("grub", true, true, true, true, 1.0F)));

        // Durability is read through the one funnel, so the pack's number is what an application stamps.
        assertEquals(120, RestraintDurability.resolve(RestraintDefinitions.HANDCUFFS_ARMS,
                RestraintDurability.Settings.defaults()));

        // The code table is untouched, and reloading the pack away restores it exactly.
        RestraintDefinition base =
                RestraintDefinitions.base(RestraintDefinitions.HANDCUFFS_ARMS).orElseThrow();
        assertEquals(40, base.escape().durability());
        assertEquals(RestraintFamily.HANDCUFFS, base.family().orElseThrow());
        RestraintProfileOverrides.clear();
        assertSame(base, RestraintDefinitions.get(RestraintDefinitions.HANDCUFFS_ARMS).orElseThrow());
    }

    @Test
    void anUnknownDefinitionIdIsRefusedAndAddsNothing() {
        List<String> errors = new ArrayList<>();
        List<RestraintProfile> loaded = RestraintProfileLoader.read(files(Map.of(
                ResourceLocation.fromNamespaceAndPath("mcacrime", "adamantium_cuffs"),
                "{ \"durability\": 500 }")), errors);

        assertTrue(loaded.isEmpty(), "an unknown id must not load");
        assertEquals(1, errors.size(), "the refusal must be reported: " + errors);
        assertTrue(errors.get(0).contains("adamantium_cuffs"));

        RestraintProfileOverrides.replaceAll(loaded);
        assertEquals(SHIPPED_DEFINITIONS, RestraintDefinitions.all().size());
        assertFalse(RestraintDefinitions.exists(ResourceLocation.fromNamespaceAndPath("mcacrime", "adamantium_cuffs")));
    }

    @Test
    void anOutOfRangeValueRejectsTheWholeFile() {
        List<String> errors = new ArrayList<>();
        List<RestraintProfile> loaded = RestraintProfileLoader.read(files(Map.of(
                RestraintDefinitions.SHACKLES_ARMS, """
                        {
                          "durability": 100000,
                          "restrictions": { "jump": false }
                        }
                        """)), errors);

        assertTrue(loaded.isEmpty(), "a file with a bad value must not load at all");
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("durability"), errors.toString());

        RestraintProfileOverrides.replaceAll(loaded);
        // Not even the good half: jump is still permitted, exactly as the code table says.
        assertTrue(RestraintDefinitions.get(RestraintDefinitions.SHACKLES_ARMS)
                .orElseThrow().restrictions().jump());
    }

    @Test
    void everyOtherMalformedShapeIsAlsoRefused() {
        List<String> errors = new ArrayList<>();
        Map<ResourceLocation, String> bad = new LinkedHashMap<>();
        bad.put(RestraintDefinitions.HANDCUFFS_ARMS, "{ \"durabilty\": 40 }");
        bad.put(RestraintDefinitions.HANDCUFFS_LEGS, "{ \"restrictions\": { \"fly\": false } }");
        bad.put(RestraintDefinitions.SHACKLES_ARMS, "{ \"restrictions\": { \"jump\": \"no\" } }");
        bad.put(RestraintDefinitions.SHACKLES_LEGS, "{ \"pick\": { \"progress_increase\": 6 } }");
        bad.put(RestraintDefinitions.DUCK_TAPE_LEGS, "{ \"key_family\": \"skeleton\" }");
        bad.put(RestraintDefinitions.BUNDLE, "{ \"supported_rigs\": [] }");
        bad.put(RestraintDefinitions.DUCK_TAPE_ARMS, "[]");

        List<RestraintProfile> loaded = RestraintProfileLoader.read(files(bad), errors);
        assertTrue(loaded.isEmpty(), "none of these shapes may load");
        assertEquals(bad.size(), errors.size(), errors.toString());
    }

    @Test
    void anEmptyProfileIsLegalAndChangesNothing() {
        List<String> errors = new ArrayList<>();
        List<RestraintProfile> loaded = RestraintProfileLoader.read(files(Map.of(
                RestraintDefinitions.BUNDLE, "{}")), errors);
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(1, loaded.size());
        assertTrue(loaded.get(0).empty());

        RestraintDefinition before = RestraintDefinitions.base(RestraintDefinitions.BUNDLE).orElseThrow();
        RestraintProfileOverrides.replaceAll(loaded);
        assertSame(before, RestraintDefinitions.get(RestraintDefinitions.BUNDLE).orElseThrow());
    }

    @Test
    void aProfileCanMakeAKeylessRestraintPickableAndAKeyedOneKeyless() {
        List<String> errors = new ArrayList<>();
        Map<ResourceLocation, String> raw = new LinkedHashMap<>();
        raw.put(RestraintDefinitions.DUCK_TAPE_ARMS,
                "{ \"pick\": { \"progress_increase\": 10, \"speed_increase\": 5 } }");
        raw.put(RestraintDefinitions.SHACKLES_LEGS, "{ \"key_family\": \"none\" }");
        RestraintProfileOverrides.replaceAll(RestraintProfileLoader.read(files(raw), errors));
        assertTrue(errors.isEmpty(), errors.toString());

        RestraintDefinition tape =
                RestraintDefinitions.get(RestraintDefinitions.DUCK_TAPE_ARMS).orElseThrow();
        assertTrue(tape.pick().pickable());
        RestraintDefinition legs =
                RestraintDefinitions.get(RestraintDefinitions.SHACKLES_LEGS).orElseThrow();
        assertTrue(legs.keyItem().isEmpty(), "a keyless family carries no key item");
        assertTrue(legs.family().isEmpty());
        assertNotEquals(RestraintDefinitions.base(RestraintDefinitions.SHACKLES_LEGS).orElseThrow(), legs);
    }

    @Test
    void aSetLargerThanTheCapIsRefusedWholeAndKeepsThePreviousOverrides() {
        // A pack already in force, so "refused" can be told apart from "cleared".
        List<String> errors = new ArrayList<>();
        RestraintProfileOverrides.replaceAll(RestraintProfileLoader.read(files(Map.of(
                RestraintDefinitions.HANDCUFFS_ARMS, "{ \"durability\": 120 }")), errors));
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(120, RestraintDefinitions.get(RestraintDefinitions.HANDCUFFS_ARMS)
                .orElseThrow().escape().durability());

        List<RestraintProfile> oversized = new ArrayList<>();
        for (int i = 0; i <= RestraintProfileOverrides.MAX_PROFILES; i++) {
            oversized.add(RestraintProfile.none(
                    ResourceLocation.fromNamespaceAndPath("packmod", "restraint_" + i)));
        }
        assertEquals(65, oversized.size(), "sixty-five is one past the cap");

        List<String> capErrors = new ArrayList<>();
        assertFalse(RestraintProfileLoader.install(oversized, capErrors),
                "a set past the cap must not install");
        assertEquals(1, capErrors.size(), capErrors.toString());
        assertTrue(capErrors.get(0).contains("65"), capErrors.toString());
        assertTrue(capErrors.get(0).contains(String.valueOf(RestraintProfileOverrides.MAX_PROFILES)),
                capErrors.toString());

        // Refused whole: the working pack's numbers are still the effective ones.
        assertEquals(1, RestraintProfileOverrides.size());
        assertEquals(120, RestraintDefinitions.get(RestraintDefinitions.HANDCUFFS_ARMS)
                .orElseThrow().escape().durability());

        // And the cap itself is a legal size, so the refusal is off-by-one safe.
        List<String> atCapErrors = new ArrayList<>();
        assertTrue(RestraintProfileLoader.install(
                        oversized.subList(0, RestraintProfileOverrides.MAX_PROFILES), atCapErrors),
                "a set at exactly the cap installs");
        assertTrue(atCapErrors.isEmpty(), atCapErrors.toString());
    }

    @Test
    void theShippedModOverridesNothing() {
        // No profile file ships: the shipped behaviour is the code table, and this is what says so.
        assertTrue(java.nio.file.Files.notExists(java.nio.file.Path.of("src", "main", "resources",
                        "data", "mcacrime", "mcacrime", "restraint_profiles")),
                "MCA: Crime ships no restraint profile overrides");
        assertTrue(RestraintProfileOverrides.isEmpty());
    }

    private static Map<ResourceLocation, JsonElement> files(Map<ResourceLocation, String> raw) {
        Map<ResourceLocation, JsonElement> parsed = new LinkedHashMap<>();
        raw.forEach((id, json) -> parsed.put(id, JsonParser.parseString(json)));
        return parsed;
    }
}
