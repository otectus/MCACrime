package dev.otectus.mcacrime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the load ordering this mod declares against each family companion.
 *
 * <p>Forge sorts every installed mod by the {@code ordering} its dependencies declare, optional ones
 * included, and refuses to start at all when two mods each declare the other {@code AFTER}
 * ("Mod Sorting failed. Detected Cycles"). 0.7.5 briefly declared MCA: Kingdoms (then Ultima Kingdoms)
 * {@code AFTER} while Kingdoms, which consumes this mod's institutional-service and jurisdiction APIs,
 * declares {@code mcacrime} {@code AFTER} itself: no server with both could launch. The family's order
 * runs from providers to consumers (MCA, then MCA: Reputation, then this mod and its other companions,
 * then MCA: Kingdoms), so a companion that consumes this mod is {@code BEFORE} here, never {@code AFTER}.
 * Kingdoms answers to {@code mcakingdoms} and, for one release, to its pre-rename {@code ultima_kingdoms}.
 */
class ModsTomlOrderingTest {

    private static final Pattern FIELD = Pattern.compile("(?m)^\\s*(modId|ordering)\\s*=\\s*\"([^\"]*)\"");

    @Test
    void companionsAreOrderedFromProviderToConsumer() throws IOException {
        String toml = Files.readString(Path.of("src/main/resources/META-INF/mods.toml"), StandardCharsets.UTF_8);
        Map<String, String> ordering = new LinkedHashMap<>();
        for (String chunk : toml.split("\\[\\[dependencies\\.")) {
            String modId = null;
            String order = null;
            Matcher field = FIELD.matcher(chunk);
            while (field.find()) {
                if (field.group(1).equals("modId") && modId == null) {
                    modId = field.group(2);
                } else if (field.group(1).equals("ordering")) {
                    order = field.group(2);
                }
            }
            if (modId != null && order != null && !modId.startsWith("${")) {
                ordering.put(modId, order);
            }
        }

        // Providers this mod reads at setup load first.
        assertEquals("AFTER", ordering.get("mca"));
        assertEquals("AFTER", ordering.get("mcareputation"));
        assertEquals("AFTER", ordering.get("mcaquests"));
        assertEquals("AFTER", ordering.get("townstead"));
        // A consumer of this mod loads after it; AFTER here would be a cycle with its own declaration.
        assertEquals("BEFORE", ordering.get("mcakingdoms"),
                "MCA: Kingdoms declares mcacrime AFTER; declaring it AFTER here stops Forge sorting mods");
        assertEquals("BEFORE", ordering.get("ultima_kingdoms"),
                "the pre-rename id of the same mod is ordered the same way while it is still recognised");
    }
}
