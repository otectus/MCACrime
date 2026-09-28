package dev.otectus.mcacrime.enchantment;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcacrime.TestPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the five restraint enchantments can be got hold of, expressed as tags (0.7.5 P6.1, P7).
 *
 * <h2>What the baseline does in code</h2>
 * On 1.20.1 each of the five is a class: {@code canApplyAtEnchantingTable} answers yes for the items
 * it fits, {@code isDiscoverable} is true while the config allows that enchantment, and
 * {@code isTradeable} is <b>false</b> — these are prison equipment, not commerce. Nothing marks any
 * of them treasure.
 *
 * <h2>What that becomes here</h2>
 * On 1.21.1 an enchantment is a datapack entry, so those three answers are memberships of vanilla
 * tags rather than overrides:
 *
 * <ul>
 *   <li>{@code #minecraft:in_enchanting_table} — the table may roll them, exactly as
 *       {@code canApplyAtEnchantingTable} plus {@code isDiscoverable} allowed;</li>
 *   <li>{@code #minecraft:on_random_loot} — loot may carry them, which is what {@code isDiscoverable}
 *       governed for {@code enchant_randomly} and {@code enchant_with_levels};</li>
 *   <li>no {@code #minecraft:tradeable}, and no {@code #minecraft:non_treasure} either: the vanilla
 *       aggregate is <em>included into</em> the tradeable tag, so joining it would put the five on a
 *       librarian's table and contradict the baseline outright. Non-treasure status is expressed by
 *       staying out of {@code #minecraft:treasure} and {@code #minecraft:double_trade_price}, which
 *       is the only part of it that has behaviour.</li>
 * </ul>
 *
 * <p>The one divergence, recorded rather than hidden: a datapack entry cannot read
 * {@code enchantments.allowed}, so on this line the table and loot may offer an enchantment the
 * config has switched off. The <em>effect</em> stays gated — {@link EnchantmentApplicability} is
 * consulted by every service that acts on one — so a disallowed enchantment does nothing, exactly as
 * it does on 1.20.1 when it is already on an item.
 */
class EnchantmentAvailabilityTagsTest {

    private static final Path ENCHANTMENTS = TestPaths.resources("data", "mcacrime", "enchantment");
    private static final Path VANILLA_TAGS = TestPaths.resources("data", "minecraft", "tags",
            "enchantment");

    private static final List<String> FIVE = List.of("mcacrime:imbue", "mcacrime:famine",
            "mcacrime:shroud", "mcacrime:exhaust", "mcacrime:silence");

    /** The five entries exist, and the list this test asserts against is exactly them. */
    @Test
    void thereAreFiveEnchantmentEntries() {
        List<String> ids = new ArrayList<>();
        try (var files = Files.list(ENCHANTMENTS)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> ids.add("mcacrime:"
                            + path.getFileName().toString().replace(".json", "")));
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + ENCHANTMENTS.toAbsolutePath(), e);
        }
        assertEquals(FIVE.stream().sorted().toList(), ids.stream().sorted().toList(),
                "the data-driven enchantments and the availability list have drifted apart");
    }

    @Test
    void everyOneIsOfferedAtAnEnchantingTable() {
        assertEquals(List.of(), missingFrom("in_enchanting_table"),
                "an enchantment outside this tag can never be rolled, on a book or on anything else");
    }

    @Test
    void everyOneCanTurnUpInLoot() {
        assertEquals(List.of(), missingFrom("on_random_loot"));
    }

    /** Not for sale: the baseline's {@code isTradeable} is false and nothing here may undo that. */
    @Test
    void noneIsTradeableAndNoneJoinsTheTreasureAggregates() {
        for (String tag : List.of("tradeable", "treasure", "non_treasure", "double_trade_price",
                "curse")) {
            Path file = VANILLA_TAGS.resolve(tag + ".json");
            assertFalse(Files.exists(file), "mcacrime adds nothing to #minecraft:" + tag
                    + "; joining it would trade, double-price or curse prison equipment");
        }
    }

    /** A tag file this mod ships never replaces vanilla's own contents. */
    @Test
    void theTagsAreAdditive() {
        for (String tag : List.of("in_enchanting_table", "on_random_loot")) {
            JsonObject root = read(VANILLA_TAGS.resolve(tag + ".json"));
            assertTrue(root.has("replace") && !root.get("replace").getAsBoolean(),
                    tag + " must set replace:false, or vanilla's own enchantments vanish from it");
        }
    }

    private static List<String> missingFrom(String tag) {
        JsonArray values = read(VANILLA_TAGS.resolve(tag + ".json")).getAsJsonArray("values");
        List<String> present = new ArrayList<>();
        values.forEach(element -> present.add(element.getAsString()));
        List<String> missing = new ArrayList<>();
        for (String id : FIVE) {
            if (!present.contains(id)) {
                missing.add(id);
            }
        }
        return missing;
    }

    private static JsonObject read(Path file) {
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            assertTrue(parsed.isJsonObject(), file + " is not a JSON object");
            return parsed.getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file.toAbsolutePath(), e);
        }
    }
}
