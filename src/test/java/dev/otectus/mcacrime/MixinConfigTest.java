package dev.otectus.mcacrime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verify common loot capture and client-only rendering stay correctly registered and isolated, and
 * that the optional Townstead layer stays in its own config.
 *
 * <h2>Two configs, and why the split is load-bearing</h2>
 *
 * <p>{@code mcacrime.mixins.json} is {@code required: true} and targets nothing but vanilla: every one
 * of its classes must apply, and a failure there is a bug worth stopping the game for.
 * {@code mcacrime.townstead.mixins.json} is the opposite — {@code required: false}, plugin-gated, and
 * its targets are named as strings because Townstead is never on the compile classpath. Merging the
 * two would mean either making the vanilla hooks optional (so a real break becomes silence) or making
 * the Townstead hooks mandatory (so a settlement mod's point release stops the game from starting).
 * The assertions below are what keeps a class from drifting into the wrong one.
 */
class MixinConfigTest {

    private static final Path CONFIG = TestPaths.resources("mcacrime.mixins.json");
    private static final Path TOWNSTEAD_CONFIG = TestPaths.resources("mcacrime.townstead.mixins.json");
    private static final Path MIXIN_SOURCE_ROOT =
            TestPaths.sources("dev", "otectus", "mcacrime", "mixin");

    /** The sub-package whose classes belong to the Townstead config rather than the main one. */
    private static final String TOWNSTEAD_PREFIX = "townstead.";

    /** Townstead's package root, dotted — the only form a Crime class is allowed to carry. */
    private static final String TOWNSTEAD_PACKAGE = "com.aetherianartificer.townstead.";

    /** {@code @Mixin(Foo.class)} — the only shape the vanilla config's mixins may use. */
    private static final Pattern CLASS_TARGET = Pattern.compile("@Mixin\\(\\s*([A-Za-z0-9_.]+)\\.class");

    /** {@code targets = "a.b.C"} — the only shape the Townstead config's mixins may use. */
    private static final Pattern STRING_TARGET = Pattern.compile("targets\\s*=\\s*\"([^\"]+)\"");

    /**
     * Everything the 0.7.2 occupation work needs is common, and the one rendering mixin stays client.
     *
     * <p>The exact list matters more than its length: a mixin that drifted from {@code mixins} to
     * {@code client} would apply on a single-player world and be absent from the dedicated server that
     * actually owns the decision, which is the failure mode this file was written for.
     */
    @Test
    void everyOccupationMixinIsCommonAndOnlyRenderingIsClient() {
        assertEquals(List.of(
                "MaskStationAcquisitionMixin",
                "MerchantOffersAccessor",
                "MobDeathEquipmentMixin",
                "NativeJobAssignmentMixin",
                "SandSensingMixin",
                "ThiefBrainMixin",
                "ThiefPoiValidationMixin"), mixins(CONFIG, "mixins"));
        assertEquals(List.of("client.RestraintPoseMixin"), mixins(CONFIG, "client"));
    }

    /**
     * The acquisition boundary is only a boundary if both halves are installed.
     *
     * <p>Filtering the acquirable predicate without wrapping native assignment would leave the
     * rebinding path unguarded; wrapping assignment without the filter would leave generic acquisition
     * unguarded. Naming both here means removing either one fails rather than half-working.
     */
    @Test
    void bothHalvesOfTheAcquisitionBoundaryArePresent() {
        List<String> listed = allMixins(CONFIG);
        assertTrue(listed.contains("MaskStationAcquisitionMixin"),
                "without this, any unemployed villager can claim a Mask Station natively");
        assertTrue(listed.contains("NativeJobAssignmentMixin"),
                "without this, native assignment can produce a visible Thief with no Crime occupation");
    }

    /**
     * Sand has to blind a mob on the machine that owns the decision.
     *
     * <p>The sensing hook is the only reason a blinded vanilla mob stops tracking, and a dedicated
     * server is where mob AI runs. Listed under {@code client} it would work in single player and
     * silently do nothing on every multiplayer server, which is the exact failure this file exists to
     * catch.
     */
    @Test
    void theSandSensingHookIsCommon() {
        assertTrue(mixins(CONFIG, "mixins").contains("SandSensingMixin"),
                "sand must block line of sight on the dedicated server, not only in single player");
        assertFalse(mixins(CONFIG, "client").contains("SandSensingMixin"));
    }

    @Test
    void everyListedMixinExistsOnDisk() {
        for (Path config : List.of(CONFIG, TOWNSTEAD_CONFIG)) {
            Path root = packageRoot(config);
            for (String name : allMixins(config)) {
                Path source = root.resolve(name.replace('.', '/') + ".java");
                assertTrue(Files.exists(source),
                        config.getFileName() + " lists " + name + " but " + source + " does not exist");
            }
        }
    }

    /**
     * The direction that actually bites: a mixin nobody listed applies to nothing and says nothing.
     *
     * <p>Now with a second config to get wrong. A Townstead mixin listed in the vanilla config would
     * be {@code required: true} against a class most installs do not have — a startup crash for
     * everyone without Townstead — and a vanilla mixin listed in the Townstead config would be
     * silently skipped on every install without it. So the file's own directory decides which config
     * has to name it, and both directions are checked.
     */
    @Test
    void everyMixinOnDiskIsListedInTheConfigThatOwnsIt() {
        List<String> main = allMixins(CONFIG);
        List<String> townstead = allMixins(TOWNSTEAD_CONFIG);
        for (String name : sourceNames()) {
            if (name.endsWith("MixinPlugin")) {
                // The config plugin is not a mixin: it decides whether the others apply. Listing it
                // would have Mixin try to merge it into something.
                assertFalse(main.contains(name) || townstead.contains(name),
                        name + " is a config plugin, not a mixin, and must not be listed");
                continue;
            }
            if (name.startsWith(TOWNSTEAD_PREFIX)) {
                String relative = name.substring(TOWNSTEAD_PREFIX.length());
                assertTrue(townstead.contains(relative),
                        name + " lives under mixin/townstead but is not named in "
                                + TOWNSTEAD_CONFIG.getFileName() + ", so it would never be applied");
                assertFalse(main.contains(relative),
                        name + " is a Townstead mixin and must not be in the required vanilla config");
            } else {
                assertTrue(main.contains(name),
                        name + " exists under mixin/ but is not named in " + CONFIG.getFileName()
                                + ", so it would never be applied");
            }
        }
    }

    /** Nothing may be claimed by both configs; Mixin would apply it twice. */
    @Test
    void theTwoConfigsShareNoClass() {
        List<String> shared = new ArrayList<>(allMixins(CONFIG));
        shared.retainAll(allMixins(TOWNSTEAD_CONFIG));
        assertTrue(shared.isEmpty(), "listed in both mixin configs: " + shared);
    }

    @Test
    void thePackageMatchesTheDirectoryAndTheToolchain() {
        JsonObject config = config(CONFIG);
        assertEquals("dev.otectus.mcacrime.mixin", config.get("package").getAsString());
        assertEquals("JAVA_21", config.get("compatibilityLevel").getAsString());
        // No refmap: NeoForge 1.21.1 production runs on Mojang names, so the annotation processor is
        // gone and a leftover refmap declaration would point Mixin at a file that is never generated.
        assertFalse(config.has("refmap"),
                "a refmap is neither generated nor needed on NeoForge 1.21.1; declaring one names a "
                        + "file that will not be in the jar");
    }

    /**
     * The Townstead config's own shape: optional, plugin-gated, same toolchain, and no refmap either.
     *
     * <p>{@code required: false} and the plugin are the two halves of one promise — that a Townstead
     * that moved a class degrades one capability instead of stopping the game. The absent refmap is
     * the NeoForge half: production runs on Mojang names, so the vanilla members these mixins redirect
     * are already spelled the way the shipped Townstead jar spells them, and a refmap key here would
     * name a file nothing generates. {@code TownsteadMixinTargetTest} checks that spelling against a
     * real jar rather than trusting it.
     */
    @Test
    void theTownsteadConfigIsOptionalAndPluginGated() {
        JsonObject config = config(TOWNSTEAD_CONFIG);
        assertEquals("dev.otectus.mcacrime.mixin.townstead", config.get("package").getAsString());
        assertEquals("JAVA_21", config.get("compatibilityLevel").getAsString());
        assertFalse(config.has("refmap"),
                "a refmap is neither generated nor needed on NeoForge 1.21.1");
        assertFalse(config.get("required").getAsBoolean(),
                "a Townstead point release that moved a class must not stop the game from starting");
        assertEquals("dev.otectus.mcacrime.mixin.townstead.TownsteadMixinPlugin",
                config.get("plugin").getAsString(),
                "without the plugin, every mixin here would be attempted on installs with no Townstead");
        assertEquals(0, config.getAsJsonObject("injectors").get("defaultRequire").getAsInt(),
                "an injection point that moved must log, not crash");
        assertTrue(Files.exists(MIXIN_SOURCE_ROOT.resolve("townstead").resolve("TownsteadMixinPlugin.java")),
                "the config names a plugin that does not exist");
    }

    /**
     * Both configs are declared in {@code neoforge.mods.toml}.
     *
     * <p>On NeoForge there is no {@code MixinConfigs} manifest attribute to fall back on: a config the
     * mod metadata does not name is simply never read, and the mixins in it never apply. That failure
     * is completely silent, which is why it is asserted here rather than left to the packaging check.
     */
    @Test
    void bothConfigsAreDeclaredInTheModMetadata() {
        String toml = read(TestPaths.resources("META-INF", "neoforge.mods.toml"));
        for (Path config : List.of(CONFIG, TOWNSTEAD_CONFIG)) {
            String name = config.getFileName().toString();
            assertTrue(toml.contains("config=\"" + name + "\""),
                    name + " has no [[mixins]] block in neoforge.mods.toml, so it would never be read");
        }
    }

    /** Both configs have to be readable by the loader that reads them, which starts with parsing. */
    @Test
    void bothConfigsParse() {
        assertTrue(config(CONFIG).has("package"));
        assertTrue(config(TOWNSTEAD_CONFIG).has("package"));
    }

    /**
     * The vanilla config stays vanilla.
     *
     * <p>This is the convention the project documents and the one the Townstead work amends: mixins
     * into another mod are allowed now, but only in the second config, and only through a string
     * target. A {@code targets =} appearing here — or a {@code @Mixin(Foo.class)} whose {@code Foo} is
     * not a Minecraft type — would mean the required config had grown a dependency on something that
     * might not be installed.
     */
    @Test
    void theVanillaConfigTargetsOnlyMinecraft() {
        List<String> offenders = new ArrayList<>();
        for (Path source : sourceFiles()) {
            String relative = MIXIN_SOURCE_ROOT.relativize(source).toString().replace('\\', '/');
            if (relative.startsWith("townstead/")) {
                continue;
            }
            String text = read(source);
            if (STRING_TARGET.matcher(text).find()) {
                offenders.add(relative + " -> string target in the required config");
                continue;
            }
            Matcher matcher = CLASS_TARGET.matcher(text);
            assertTrue(matcher.find(), relative + " declares no @Mixin target");
            String simple = matcher.group(1);
            if (!text.contains("import net.minecraft.") || !importsVanilla(text, simple)) {
                offenders.add(relative + " -> " + simple + " is not imported from net.minecraft");
            }
        }
        assertTrue(offenders.isEmpty(),
                "mcacrime.mixins.json is required:true and must target vanilla only: " + offenders);
    }

    /**
     * Every Townstead mixin names its target as a dotted string under Townstead's package root.
     *
     * <p>Dotted because the internal form is a real class reference and would re-link MCA: Crime to
     * one MCA package layout through Townstead's own descriptors; under the root because a target
     * anywhere else in this config would be applied on the strength of Townstead being installed,
     * which says nothing about the other mod.
     */
    @Test
    void everyTownsteadMixinNamesADottedTownsteadTarget() {
        List<String> checked = new ArrayList<>();
        for (Path source : sourceFiles()) {
            String relative = MIXIN_SOURCE_ROOT.relativize(source).toString().replace('\\', '/');
            if (!relative.startsWith("townstead/") || relative.endsWith("TownsteadMixinPlugin.java")) {
                continue;
            }
            String text = read(source);
            Matcher matcher = STRING_TARGET.matcher(text);
            assertTrue(matcher.find(), relative + " must name its target with targets = \"...\"");
            do {
                String target = matcher.group(1);
                assertFalse(target.contains("/"),
                        relative + " names " + target + " in internal form; only the dotted form may "
                                + "appear in this mod's bytecode");
                assertTrue(target.startsWith(TOWNSTEAD_PACKAGE),
                        relative + " targets " + target + ", which is not under " + TOWNSTEAD_PACKAGE);
                checked.add(relative + " -> " + target);
            } while (matcher.find());
            assertFalse(CLASS_TARGET.matcher(text).find(),
                    relative + " uses a class literal target; Townstead is never on the compile path");
        }
        // No assertion on the count: the layer lands one mixin at a time, and an empty package is a
        // legitimate state of this config (it ships that way before the first hook).
        System.out.println("[mixin] Townstead targets: " + checked);
    }

    private static boolean importsVanilla(String text, String simpleName) {
        return text.contains("import net.minecraft.") && text.matches("(?s).*import net\\.minecraft\\.[A-Za-z0-9_.]*"
                + Pattern.quote(simpleName) + ";.*");
    }

    /** Where the mixins of {@code config} live on disk. */
    private static Path packageRoot(Path config) {
        return config.equals(TOWNSTEAD_CONFIG)
                ? MIXIN_SOURCE_ROOT.resolve("townstead")
                : MIXIN_SOURCE_ROOT;
    }

    private static JsonObject config(Path path) {
        return JsonParser.parseString(read(path)).getAsJsonObject();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path.toAbsolutePath(), e);
        }
    }

    private static List<String> mixins(Path config, String side) {
        JsonArray array = config(config).getAsJsonArray(side);
        List<String> names = new ArrayList<>();
        if (array != null) {
            array.forEach(element -> names.add(element.getAsString()));
        }
        return names;
    }

    private static List<String> allMixins(Path config) {
        List<String> all = new ArrayList<>(mixins(config, "mixins"));
        all.addAll(mixins(config, "client"));
        return all;
    }

    private static List<Path> sourceFiles() {
        if (!Files.isDirectory(MIXIN_SOURCE_ROOT)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(MIXIN_SOURCE_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + MIXIN_SOURCE_ROOT.toAbsolutePath(), e);
        }
    }

    private static List<String> sourceNames() {
        return sourceFiles().stream()
                .map(path -> MIXIN_SOURCE_ROOT.relativize(path).toString().replace('\\', '.').replace('/', '.'))
                .map(name -> name.substring(0, name.length() - ".java".length()))
                .toList();
    }
}
