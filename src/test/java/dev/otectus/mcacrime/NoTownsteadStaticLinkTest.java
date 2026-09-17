package dev.otectus.mcacrime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Standing tripwire: <b>no compiled class may reference a Townstead type, and nothing outside
 * {@code dev/otectus/mcacrime/compat/townstead/} may reference that package.</b>
 *
 * <p>The first scan has <b>no exemption list at all</b> — not even for the guarded package itself.
 * The Townstead integration is reflection-only end to end: {@code TownsteadBinding} matches methods
 * by name and arity and adapts every handle to an all-{@code Object} shape, so not one Townstead
 * class is named anywhere in this mod's bytecode.
 *
 * <p>That strictness matters more here than "one more optional mod" suggests, and for the same reason
 * {@link NoMcaStaticLinkTest} exists. Townstead is compiled against MCA, so its classes carry MCA
 * descriptors in their own constant pools; a single import would drag a <em>relocated MCA</em> type
 * into ours and reintroduce exactly the {@code NoClassDefFoundError} that killed dedicated servers
 * when MCA renamed its package root. Naming a Townstead type would also make the class unloadable
 * without Townstead, which is the ordinary case for nearly every install.
 *
 * <p>The scans byte-search the raw constant pool of every {@code .class} under
 * {@code build/classes/java/main}. The distinction between the two forms is what makes this exact and
 * free: a real class, method or field reference is stored in <em>internal (slash)</em> form, while
 * {@code TownsteadBinding}'s manifest stores Townstead's package root as a <em>dotted</em> string
 * constant. So the slash scan is absolute, and the dotted scan is confined to the one package allowed
 * to hold that string — which is how "only inside a string constant, never as a reference" is proved
 * rather than asserted in a comment.
 *
 * <p><b>The last scan needs no whitelist either.</b> The single sanctioned entry point,
 * {@code TownsteadBridge}, names the implementation class as a dotted literal for
 * {@code Class.forName}, and a dotted literal can never collide with the slash form the needle looks
 * for. The always-loaded seam types ({@code compat/TownsteadBridge}, the {@code Townstead*View}
 * records) sit in {@code compat} with a capital {@code T}, which differs from the needle's lowercase
 * {@code t} at the first byte after the package separator, so they never match either.
 *
 * @see NoMcaStaticLinkTest the same technique, applied to MCA itself
 * @see OptionalClassloadTest the same technique, applied to the other optional integrations
 */
class NoTownsteadStaticLinkTest {

    private static final String GUARDED_PACKAGE_PREFIX = "dev/otectus/mcacrime/compat/townstead/";

    /**
     * The second place allowed to carry Townstead's package root as a <em>string</em>.
     *
     * <p>The mixin layer names each target with {@code @Mixin(targets = "com.aetherianartificer...")},
     * which the compiler stores as an annotation string constant and never as a class reference —
     * which is precisely why a mixin into another mod can be written at all without linking to it.
     * {@link #everyTownsteadMixinKeepsTheNameInAStringConstant()} is what proves that claim rather
     * than asserting it: it parses the constant pool and checks that no {@code CONSTANT_Class} entry
     * anywhere in the package names Townstead.
     */
    private static final String MIXIN_PACKAGE_PREFIX = "dev/otectus/mcacrime/mixin/townstead/";

    /** A real Townstead class, method or field reference. Allowed nowhere. */
    private static final byte[] TOWNSTEAD_REFERENCE_NEEDLE =
            "com/aetherianartificer/townstead".getBytes(StandardCharsets.UTF_8);

    /** The manifest's string form. Allowed only inside the guarded package. */
    private static final byte[] TOWNSTEAD_STRING_NEEDLE =
            "com.aetherianartificer.townstead".getBytes(StandardCharsets.UTF_8);

    /**
     * Trailing slash on purpose: it is what separates the guarded package
     * {@code compat/townstead/} from the always-loaded seam types {@code compat/Townstead*}.
     */
    private static final byte[] GUARDED_PACKAGE_NEEDLE =
            GUARDED_PACKAGE_PREFIX.getBytes(StandardCharsets.UTF_8);

    @Test
    void noCompiledClassReferencesATownsteadType() throws IOException {
        List<String> violations = scan(TOWNSTEAD_REFERENCE_NEEDLE, false);

        assertTrue(violations.isEmpty(),
                "Class(es) statically reference com.aetherianartificer.townstead. Every Townstead "
                        + "access must resolve by name through TownsteadBinding, so MCA: Crime keeps "
                        + "loading when Townstead is absent and Townstead's own relocated-MCA descriptors "
                        + "never reach our constant pool. Offenders: " + violations);
    }

    @Test
    void onlyTheGuardedPackageNamesTownsteadInAString() throws IOException {
        List<String> violations = scan(TOWNSTEAD_STRING_NEEDLE, true);

        assertTrue(violations.isEmpty(),
                "Class(es) outside " + GUARDED_PACKAGE_PREFIX + " and " + MIXIN_PACKAGE_PREFIX
                        + " carry Townstead's package root as a string. Only TownsteadBinding's manifest "
                        + "and the mixin layer's @Mixin(targets = ...) may name it, and only as a dotted "
                        + "constant each resolves by hand. Offenders: " + violations);
    }

    /**
     * The mixin layer's half of the same promise: a name in a string, never a name in a reference.
     *
     * <p>{@link #noCompiledClassReferencesATownsteadType()} already forbids the internal form
     * everywhere, and that is the byte-level statement of this rule. This test says the same thing
     * structurally, because the mixin package is the one place where somebody would <em>want</em> to
     * write {@code @Mixin(SomeTownsteadClass.class)} and the compiler would happily oblige: a class
     * literal becomes a {@code CONSTANT_Class} entry, the class stops loading without Townstead, and
     * one of Townstead's relocated MCA descriptors comes along with it.
     *
     * <p>So the constant pool is parsed rather than byte-searched, and every {@code CONSTANT_Class}
     * is checked by name. The dotted string constants the {@code targets =} annotations produce are
     * {@code CONSTANT_Utf8} entries no {@code CONSTANT_Class} points at, so they are invisible here by
     * construction — which is exactly the distinction being proved.
     */
    @Test
    void everyTownsteadMixinKeepsTheNameInAStringConstant() throws IOException {
        Path classesDir = classesDir();
        Path mixinDir = classesDir.resolve(MIXIN_PACKAGE_PREFIX);
        if (!Files.isDirectory(mixinDir)) {
            return; // the layer has not landed yet; nothing to prove
        }

        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(mixinDir)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                String relative = classesDir.relativize(path).toString().replace('\\', '/');
                for (String referenced : classReferences(Files.readAllBytes(path))) {
                    if (referenced.contains("aetherianartificer")) {
                        violations.add(relative + " -> " + referenced);
                    }
                    for (String mcaRoot : new String[] {"forge/net/mca", "net/conczin/mca", "net/mca/"}) {
                        if (referenced.startsWith(mcaRoot)) {
                            violations.add(relative + " -> " + referenced);
                        }
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Class(es) under " + MIXIN_PACKAGE_PREFIX + " reference a Townstead or MCA type. A mixin "
                        + "into another mod may name its target only as a dotted string; every handler "
                        + "parameter and every @At owner must be a vanilla type. Offenders: " + violations);
    }

    /**
     * Every class name the constant pool of {@code bytes} refers to, in internal form.
     *
     * <p>Hand-parsed rather than read with ASM because the parse needed here is the trivial prefix of
     * a class file — the pool, and the one tag inside it that means "this class links to that one" —
     * and keeping it dependency-free means this tripwire cannot be disabled by a classpath change.
     */
    private static List<String> classReferences(byte[] bytes) {
        List<String> names = new ArrayList<>();
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        buffer.getInt();   // magic
        buffer.getShort(); // minor
        buffer.getShort(); // major
        int count = Short.toUnsignedInt(buffer.getShort());
        String[] utf8 = new String[count];
        int[] classNameIndex = new int[count];
        for (int index = 1; index < count; index++) {
            int tag = Byte.toUnsignedInt(buffer.get());
            switch (tag) {
                case 1 -> { // Utf8
                    int length = Short.toUnsignedInt(buffer.getShort());
                    byte[] raw = new byte[length];
                    buffer.get(raw);
                    utf8[index] = new String(raw, StandardCharsets.UTF_8);
                }
                case 7 -> classNameIndex[index] = Short.toUnsignedInt(buffer.getShort()); // Class
                case 8, 16, 19, 20 -> buffer.getShort();             // String / MethodType / Module / Package
                case 15 -> { buffer.get(); buffer.getShort(); }      // MethodHandle
                case 3, 4, 9, 10, 11, 12, 17, 18 -> buffer.getInt(); // Integer / Float / *ref / NameAndType / *Dynamic
                case 5, 6 -> { buffer.getLong(); index++; }          // Long / Double take two slots
                default -> throw new IllegalStateException("unknown constant pool tag " + tag);
            }
        }
        for (int index = 1; index < count; index++) {
            if (classNameIndex[index] != 0) {
                String name = utf8[classNameIndex[index]];
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    @Test
    void noAlwaysLoadedClassReferencesTheGuardedPackage() throws IOException {
        List<String> violations = scan(GUARDED_PACKAGE_NEEDLE, true);

        assertTrue(violations.isEmpty(),
                "Class(es) outside " + GUARDED_PACKAGE_PREFIX + " reference it directly. The only "
                        + "sanctioned entry point is TownsteadBridge's Class.forName on a dotted class "
                        + "name, which is invisible to this scan by design. Offenders: " + violations);
    }

    /**
     * The compiled class output.
     *
     * <p>Resolved from {@code mcacrime.projectRoot}, not relatively: the NeoForge unit-test runner
     * works out of {@code build/minecraft-junit}, so a relative path lands nowhere near it.
     */
    private static Path classesDir() {
        String projectRoot = System.getProperty("mcacrime.projectRoot");
        assertTrue(projectRoot != null && !projectRoot.isBlank(),
                "mcacrime.projectRoot is not set; the test task in build.gradle supplies it");
        Path classesDir = Paths.get(projectRoot, "build", "classes", "java", "main");
        assertTrue(Files.isDirectory(classesDir),
                classesDir + " does not exist; run `compileJava` (or `test`, which depends on it) "
                        + "before running this test directly.");
        return classesDir;
    }

    private static List<String> scan(byte[] needle, boolean exemptGuardedPackage) throws IOException {
        Path classesDir = classesDir();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(classesDir)) {
            paths.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                String relative = classesDir.relativize(p).toString().replace('\\', '/');
                // The mixin package is exempt from both needles, for two different reasons that happen
                // to have the same shape. It may hold Townstead's name as a dotted string because that
                // is how a mixin declares a target it cannot import. And it may name the guarded
                // adapter package because those classes are plugin-gated: TownsteadMixinPlugin refuses
                // to apply any of them unless Townstead is installed and the target class resolves, so
                // a reference from one of them into the adapter cannot drag anything into a load that
                // has no Townstead -- the mixin class is not loaded there either. Every class outside
                // these two packages is still held to the blanket rule.
                if (exemptGuardedPackage
                        && (relative.startsWith(GUARDED_PACKAGE_PREFIX)
                            || relative.startsWith(MIXIN_PACKAGE_PREFIX))) {
                    return;
                }
                try {
                    if (containsNeedle(Files.readAllBytes(p), needle)) {
                        violations.add(relative);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return violations;
    }

    private static boolean containsNeedle(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
