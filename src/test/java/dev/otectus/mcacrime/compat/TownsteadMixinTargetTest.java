package dev.otectus.mcacrime.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Townstead mixins against the classes they claim to modify.
 *
 * <h2>Why this cannot be left to the compiler</h2>
 *
 * <p>Nothing about these mixins is compile-checked, by design. Townstead is never on this mod's
 * compile classpath — one import would put a relocated MCA descriptor into MCA: Crime's constant pool
 * — so each target is a dotted string, each method is matched by name, and the annotation processor
 * is told to treat an unresolvable target as a note rather than an error. Every guarantee the
 * compiler would normally give is therefore gone, and this is what replaces it: the real Townstead
 * jar is opened and the real class, the real method and the real instruction are looked at.
 *
 * <h2>The three questions</h2>
 *
 * <ol>
 *   <li>Is the target class named in the dotted form, under Townstead's root? Checked always, from
 *       source, because it is a rule about this mod rather than about Townstead.</li>
 *   <li>Does that class exist in the jar, with a method of that name and shape? A Townstead point
 *       release that renamed it would otherwise show up as a capability that quietly stopped.</li>
 *   <li>Is the vanilla call each {@code @Redirect} names still <em>in</em> that method? This is the
 *       one that matters most and the one nothing else can see: an injector with {@code require = 0}
 *       that matches nothing applies cleanly, logs nothing, and does nothing.</li>
 * </ol>
 *
 * <p>Question 3 also verifies the refmap, which is the part of this arrangement most likely to be got
 * wrong. The supplied jars are production builds, so their Minecraft members carry SRG names: finding
 * {@code m_21936_} where the annotation says {@code eraseMemory} is direct evidence that
 * {@code mcacrime.refmap.json} maps this mixin's {@code @At} targets correctly, which is exactly what
 * makes a class-level {@code remap = false} with a member-level {@code remap = true} the right
 * combination on Forge 1.20.1.
 *
 * <h2>Running it</h2>
 *
 * <pre>townsteadProbeTest -PtownsteadLegacyJar=&lt;path&gt; -PtownsteadModernJar=&lt;path&gt;</pre>
 *
 * <p>The jar-backed tests skip on an assumption when no jar is supplied, exactly as
 * {@code TownsteadBindingProbeTest} does, so the ordinary {@code test} run stays green on a checkout
 * that has no Townstead jar anywhere.
 */
class TownsteadMixinTargetTest {

    private static final Path MIXIN_ROOT = Paths.get("src", "main", "java", "dev", "otectus",
            "mcacrime", "mixin", "townstead");

    private static final String MIXIN_PACKAGE = "dev/otectus/mcacrime/mixin/townstead/";

    private static final String TOWNSTEAD_ROOT = "com.aetherianartificer.townstead.";

    /** The same properties {@code TownsteadBindingProbeTest} reads, set by the Gradle task. */
    private static final List<String> JAR_PROPERTIES = List.of(
            "mcacrime.townstead.probe.legacyJar", "mcacrime.townstead.probe.modernJar");

    private static final Pattern MIXIN_TARGET =
            Pattern.compile("@Mixin\\(\\s*targets\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern METHOD =
            Pattern.compile("\\bmethod\\s*=\\s*(\\{[^}]*\\}|" + literal() + ")");
    private static final Pattern AT_TARGET = Pattern.compile("\\btarget\\s*=\\s*" + literal());
    private static final Pattern INJECTOR = Pattern.compile("@(Inject|Redirect)\\(");

    /**
     * One {@code @Inject}/{@code @Redirect}: the methods it names and the calls it points at.
     *
     * <p>{@code methods} is a list rather than a string because of the dialogue hooks. Every other
     * target in this package is a Townstead method, whose name is never obfuscated and is therefore the
     * same string everywhere. {@code init} and {@code removed} are vanilla {@code Screen} members that
     * Townstead overrides, so a development runtime sees {@code init} and a production jar sees
     * {@code m_7856_} — and the refmap cannot bridge the two, because the annotation processor never
     * resolves a {@code targets = "..."} class and so writes no entry for a method on it. Both spellings
     * are therefore listed on the injector, and a match on any of them is a match.
     */
    private record Injector(String source, List<String> methods, List<String> invocations) {

        /** The name to print, which is the first spelling the author wrote. */
        String method() {
            return methods.isEmpty() ? "" : methods.get(0);
        }
    }

    /** One mixin file: its target class and its injectors. */
    private record Hook(String source, String targetClass, List<Injector> injectors) {
    }

    // ---------------------------------------------------------------------------------------------
    // Always run: rules about this mod, checkable from source alone.
    // ---------------------------------------------------------------------------------------------

    /**
     * Every target is a dotted name under Townstead's root.
     *
     * <p>Dotted because the internal form is what a real class reference looks like, and a real class
     * reference here would re-link MCA: Crime to one MCA package layout through Townstead's own
     * descriptors. Under the root because these mixins apply on the strength of Townstead being
     * installed, which says nothing about any other mod.
     */
    @Test
    void everyTargetIsADottedTownsteadClass() {
        List<Hook> hooks = hooks();
        for (Hook hook : hooks) {
            assertFalse(hook.targetClass().contains("/"),
                    hook.source() + " names its target in internal form: " + hook.targetClass());
            assertTrue(hook.targetClass().startsWith(TOWNSTEAD_ROOT),
                    hook.source() + " targets " + hook.targetClass() + ", outside " + TOWNSTEAD_ROOT);
            assertFalse(hook.injectors().isEmpty(),
                    hook.source() + " declares a target but injects nothing");
        }
        System.out.println("[mixin] Townstead hooks: " + hooks.stream().map(Hook::source).toList());
    }

    /**
     * Every vanilla call these mixins redirect has a refmap entry.
     *
     * <p>The single most breakable thing about this layer. A development runtime uses Mojang names and
     * a production jar uses SRG, so an {@code @At} target resolves in both only if the annotation
     * processor wrote it into the refmap — and the combination that makes it do so (class-level
     * {@code remap = false}, member-level {@code remap = true}) is easy to get wrong in a way that
     * works perfectly in dev and silently does nothing in the wild. A missing entry here is that
     * mistake, caught in a unit test instead of in a bug report.
     */
    @Test
    void everyRedirectedVanillaMemberIsInTheRefmap() {
        Map<String, Map<String, String>> refmap = refmap();
        Assumptions.assumeFalse(refmap.isEmpty(),
                "no generated refmap found; run compileJava first (the `test` task does).");

        List<String> missing = new ArrayList<>();
        for (Hook hook : hooks()) {
            String mixin = MIXIN_PACKAGE + hook.source().replace(".java", "");
            Map<String, String> entries = refmap.getOrDefault(mixin, Map.of());
            for (Injector injector : hook.injectors()) {
                for (String invocation : injector.invocations()) {
                    if (!entries.containsKey(invocation)) {
                        missing.add(mixin + " -> " + invocation);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "these @At targets are not in mcacrime.refmap.json, so they would resolve in a "
                        + "development runtime and silently match nothing in a production jar. Check that "
                        + "the @At carries remap = true, that the owner is a vanilla class, and that "
                        + "build.gradle registers mcacrime.townstead.mixins.json with the mixin plugin. "
                        + "Missing: " + missing);
    }

    // ---------------------------------------------------------------------------------------------
    // Jar-backed: rules about Townstead, checkable only against a real build of it.
    // ---------------------------------------------------------------------------------------------

    /** Each target class is really in each supplied Townstead jar. */
    @Test
    void everyTargetClassExistsInEverySuppliedJar() throws IOException {
        Map<String, Path> jars = suppliedJars();
        Assumptions.assumeFalse(jars.isEmpty(),
                "No Townstead jar supplied; run `townsteadProbeTest -PtownsteadLegacyJar=<path>` "
                        + "(or -PtownsteadModernJar) to exercise this.");

        for (Map.Entry<String, Path> jar : jars.entrySet()) {
            try (ZipFile zip = new ZipFile(jar.getValue().toFile())) {
                for (Hook hook : hooks()) {
                    assertNotNull(entry(zip, hook.targetClass()),
                            hook.targetClass() + " is not in the " + jar.getKey() + " Townstead jar. "
                                    + "TownsteadMixinPlugin would refuse to apply " + hook.source()
                                    + " and the capability behind it would report unavailable.");
                }
            }
        }
    }

    /**
     * Each named method is really on that class, with the shape the injector assumes.
     *
     * <p>Where a descriptor is written out — which is only possible when the whole signature is
     * vanilla — it is checked exactly. Where the method is matched by name alone, because its
     * signature carries an MCA type this mod may not name, the parameter <em>count</em> is still
     * pinned: a {@code tick(villager, long)} where the hook expects {@code tick(villager)} is a
     * different method that happens to share a name.
     */
    @Test
    void everyNamedMethodExistsWithTheExpectedShape() throws IOException {
        Map<String, Path> jars = suppliedJars();
        Assumptions.assumeFalse(jars.isEmpty(), "No Townstead jar supplied.");

        for (Map.Entry<String, Path> jar : jars.entrySet()) {
            try (ZipFile zip = new ZipFile(jar.getValue().toFile())) {
                for (Hook hook : hooks()) {
                    ClassNode target = classNode(zip, hook.targetClass());
                    for (Injector injector : hook.injectors()) {
                        MethodNode found = findAny(target, injector.methods());
                        assertNotNull(found,
                                injector.methods() + " named by " + hook.source() + " is absent from "
                                        + hook.targetClass() + " in the " + jar.getKey() + " jar, or has "
                                        + "a different parameter count.");
                        int expected = switch (found.name) {
                            case "tick", "restore", "forget", "freeze" -> 1;
                            case "restoreWalkTarget", "isProtectedStorage" -> 2;
                            case "<init>" -> 3;
                            case "lock" -> 4;
                            default -> 0; // Screen init/removed, including their SRG names
                        };
                        assertEquals(expected, org.objectweb.asm.Type.getArgumentTypes(found.desc).length,
                                hook.source() + " captures arguments from " + found.name + " in " + jar.getKey());
                    }
                }
            }
        }
    }

    /**
     * Each redirected call is really in that method, under the name the refmap produces.
     *
     * <p>The assertion the whole file exists for. Everything above can pass while the injector matches
     * nothing at all — {@code require = 0} makes that silent by design — so the instruction itself is
     * looked for. Both spellings are accepted because the jar may be either a production build (SRG
     * members, which is what the supplied jars are) or a development-mapped one; which one matched is
     * printed, because "matched the SRG name" is the evidence that the refmap is doing its job.
     */
    @Test
    void everyRedirectedCallIsInThatMethod() throws IOException {
        Map<String, Path> jars = suppliedJars();
        Assumptions.assumeFalse(jars.isEmpty(), "No Townstead jar supplied.");
        Map<String, Map<String, String>> refmap = refmap();

        for (Map.Entry<String, Path> jar : jars.entrySet()) {
            try (ZipFile zip = new ZipFile(jar.getValue().toFile())) {
                for (Hook hook : hooks()) {
                    ClassNode target = classNode(zip, hook.targetClass());
                    Map<String, String> entries =
                            refmap.getOrDefault(MIXIN_PACKAGE + hook.source().replace(".java", ""), Map.of());
                    for (Injector injector : hook.injectors()) {
                        MethodNode method = findAny(target, injector.methods());
                        assertNotNull(method, injector.methods() + " missing from " + hook.targetClass());
                        for (String invocation : injector.invocations()) {
                            String mapped = entries.get(invocation);
                            String matched = matchedName(method, invocation, mapped);
                            assertNotNull(matched,
                                    hook.source() + "'s @At names " + invocation + ", but no such call is "
                                            + "in " + hook.targetClass() + "." + injector.method()
                                            + " in the " + jar.getKey() + " jar"
                                            + (mapped == null ? "" : " (nor its mapped form " + mapped + ")")
                                            + ". The redirect would apply cleanly and do nothing.");
                            System.out.println("[mixin] " + jar.getKey() + " " + hook.source() + " "
                                    + injector.method() + " -> " + matched);
                        }
                    }
                }
            }
        }
    }

    /**
     * The two {@code ItemStack.copy()} calls the provenance redirects tell apart by ordinal.
     *
     * <p>{@code @At(ordinal = n)} is positional, and positions are the one thing a source reading
     * cannot guarantee: the stash copy is inside a branch and the display copy is not, so nothing about
     * the source makes it obvious which one the compiler emits first, and getting it backwards would
     * record a display tool as a stash and a stash as a display tool — silently, with both hooks firing
     * and both diagnostics green. So the real bytecode is counted and the two call sites are identified
     * by what each copy is handed to.
     *
     * <p>A third copy appearing in {@code tick} is just as bad as a missing one: it would shift the
     * ordinals under the redirects. Hence an exact count rather than a lower bound.
     */
    @Test
    void theWorkToolTickerStillMakesExactlyTwoCopies() throws IOException {
        Map<String, Path> jars = suppliedJars();
        Assumptions.assumeFalse(jars.isEmpty(), "No Townstead jar supplied.");

        String ticker = "com.aetherianartificer.townstead.tick.WorkToolTicker";
        for (Map.Entry<String, Path> jar : jars.entrySet()) {
            try (ZipFile zip = new ZipFile(jar.getValue().toFile())) {
                ClassNode target = classNode(zip, ticker);
                MethodNode tick = find(target, "tick");
                assertNotNull(tick, "WorkToolTicker.tick is absent from the " + jar.getKey() + " jar");

                List<MethodInsnNode> copies = new ArrayList<>();
                for (AbstractInsnNode insn : tick.instructions) {
                    if (insn instanceof MethodInsnNode call
                            && "net/minecraft/world/item/ItemStack".equals(call.owner)
                            && "()Lnet/minecraft/world/item/ItemStack;".equals(call.desc)
                            && ("copy".equals(call.name) || "m_41777_".equals(call.name))) {
                        copies.add(call);
                    }
                }
                assertEquals(2, copies.size(),
                        "WorkToolProvenanceMixin redirects ItemStack.copy() by ordinal 0 and 1 in "
                                + ticker + ".tick. The " + jar.getKey() + " jar has " + copies.size()
                                + " such calls, so the ordinals no longer mean what the mixin says they "
                                + "mean. Re-read the method before changing the ordinals.");

                assertEquals("java/util/Map", nextCallOwner(tick, copies.get(0)),
                        "ordinal 0 is supposed to be the copy that goes into Townstead's stash map; in "
                                + "the " + jar.getKey() + " jar it is handed somewhere else.");
                assertEquals("(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;)V",
                        nextCallDescriptor(tick, copies.get(1)),
                        "ordinal 1 is supposed to be the copy that goes into the villager's hand; in the "
                                + jar.getKey() + " jar it is handed somewhere else. (The owner is "
                                + "deliberately not checked: it is an MCA type this mod may not name.)");

                // The two clearing hooks. Their absence would leave a display copy recorded against a
                // villager whose shift had ended, which reads as a prop that is really their own gear.
                assertNotNull(find(target, "restore"),
                        "WorkToolTicker.restore is gone from the " + jar.getKey() + " jar");
                assertNotNull(find(target, "forget"),
                        "WorkToolTicker.forget is gone from the " + jar.getKey() + " jar");

                System.out.println("[mixin] " + jar.getKey() + " WorkToolTicker.tick copies: "
                        + copies.stream().map(call -> call.name).toList());
            }
        }
    }

    /**
     * The storage search context still asks the question the property hook answers.
     *
     * <p>{@code isProtectedStorage} is checked by exact descriptor above, along with every other named
     * method, so what is worth asserting separately is the part a descriptor does not say. It has to be
     * an <em>instance</em> method, because {@code StoragePolicyMixin}'s handler is an instance handler
     * and Mixin refuses the merge outright if the target is static. And the policy it consults still has
     * to be a static call into Townstead's config rather than something derived from the villager, or
     * the method has stopped meaning "is this block a protected storage block" and forcing its result
     * would be forcing something else.
     */
    @Test
    void theStorageContextStillAsksAStaticBlockPolicy() throws IOException {
        Map<String, Path> jars = suppliedJars();
        Assumptions.assumeFalse(jars.isEmpty(), "No Townstead jar supplied.");

        String context = "com.aetherianartificer.townstead.storage.StorageSearchContext";
        String descriptor = "isProtectedStorage(Lnet/minecraft/core/BlockPos;"
                + "Lnet/minecraft/world/level/block/state/BlockState;)Z";
        for (Map.Entry<String, Path> jar : jars.entrySet()) {
            try (ZipFile zip = new ZipFile(jar.getValue().toFile())) {
                assertNotNull(entry(zip, context),
                        context + " is not in the " + jar.getKey() + " jar; TownsteadMixinPlugin would "
                                + "refuse StoragePolicyMixin and storage_policy would report unavailable");
                ClassNode target = classNode(zip, context);
                MethodNode method = find(target, descriptor);
                assertNotNull(method, descriptor + " is absent from " + context + " in the "
                        + jar.getKey() + " jar, or its parameters changed.");
                assertEquals(0, method.access & Opcodes.ACC_STATIC,
                        "StoragePolicyMixin injects an instance handler; a static target would be "
                                + "refused outright in the " + jar.getKey() + " jar.");

                boolean delegates = false;
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && insn.getOpcode() == Opcodes.INVOKESTATIC
                            && call.owner.endsWith("TownsteadConfig")
                            && "isProtectedStorage".equals(call.name)) {
                        delegates = true;
                    }
                }
                assertTrue(delegates, context + ".isProtectedStorage no longer delegates to the static "
                        + "block policy in the " + jar.getKey() + " jar; forcing its return value may "
                        + "now be forcing a different question.");
                System.out.println("[mixin] " + jar.getKey() + " StorageSearchContext.isProtectedStorage: "
                        + "instance method delegating to TownsteadConfig");
            }
        }
    }

    /**
     * The dialogue screen's two hooks, under the names a production jar actually uses.
     *
     * <p>The one place in this package where the target member is a <em>vanilla</em> name, and therefore
     * the one place the usual arrangement does not work. Every other Townstead method these mixins name
     * is Townstead's own and is spelled identically in a development runtime and in a shipped jar;
     * {@code init} and {@code removed} are {@code Screen} members, so the shipped jar carries
     * {@code m_7856_} and {@code m_7861_} and a mixin naming only {@code init} would apply cleanly,
     * match nothing, and silently produce a dialogue with no Crime button — in production only, which
     * is the worst possible place for it to show up.
     *
     * <p>Hence the dual-name selector on those injectors, and hence this: the SRG spellings are
     * asserted against the real jar, so a Minecraft version that renumbered them fails here rather than
     * in a bug report.
     */
    @Test
    void theDialogueScreenHasBothSpellingsOfItsHooks() throws IOException {
        Map<String, Path> jars = suppliedJars();
        Assumptions.assumeFalse(jars.isEmpty(), "No Townstead jar supplied.");

        String screen = "com.aetherianartificer.townstead.client.gui.dialogue.RpgDialogueScreen";
        for (Map.Entry<String, Path> jar : jars.entrySet()) {
            try (ZipFile zip = new ZipFile(jar.getValue().toFile())) {
                assertNotNull(entry(zip, screen),
                        screen + " is not in the " + jar.getKey() + " jar");
                ClassNode target = classNode(zip, screen);

                assertEquals("net/minecraft/client/gui/screens/Screen", target.superName,
                        "the dialogue mixin extends Screen, so the target must too, or Mixin refuses to "
                                + "apply it in the " + jar.getKey() + " jar");

                assertNotNull(find(target, "m_7856_()V"),
                        "Screen.init() is m_7856_ in a production jar, and the dialogue mixin names both "
                                + "spellings for exactly that reason. It is not in the " + jar.getKey()
                                + " jar.");
                assertNotNull(find(target, "m_7861_()V"),
                        "Screen.removed() is m_7861_ in a production jar. It is not in the "
                                + jar.getKey() + " jar, so the external-interruption close would never "
                                + "run and Townstead would queue the conversation back over Crime's menu.");

                // The flag the close reason sets, and the two members the cleanup reaches for. All three
                // are Townstead's own and are therefore unobfuscated in both jars; their absence is what
                // a Townstead refactor of this screen looks like.
                assertTrue(target.fields.stream().anyMatch(field -> "userInitiatedClose".equals(field.name)),
                        "RpgDialogueScreen.userInitiatedClose is gone from the " + jar.getKey() + " jar; "
                                + "the queued reopen can no longer be cancelled.");
                assertTrue(target.fields.stream().anyMatch(field -> "villagerUUID".equals(field.name)),
                        "RpgDialogueScreen.villagerUUID is gone from the " + jar.getKey() + " jar; the "
                                + "law button has no target to open against.");
                assertNotNull(find(target, "sendDialogueState"),
                        "RpgDialogueScreen.sendDialogueState is gone from the " + jar.getKey() + " jar; "
                                + "an external close would leave the server-side dialogue token open.");

                System.out.println("[mixin] " + jar.getKey() + " RpgDialogueScreen: m_7856_/m_7861_ present, "
                        + "userInitiatedClose present");
            }
        }
    }

    /** The owner of the first method call after {@code from}, or null. */
    private static String nextCallOwner(MethodNode method, MethodInsnNode from) {
        MethodInsnNode next = nextCall(method, from);
        return next == null ? null : next.owner;
    }

    /** The descriptor of the first method call after {@code from}, or null. */
    private static String nextCallDescriptor(MethodNode method, MethodInsnNode from) {
        MethodInsnNode next = nextCall(method, from);
        return next == null ? null : next.desc;
    }

    private static MethodInsnNode nextCall(MethodNode method, MethodInsnNode from) {
        boolean seen = false;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn == from) {
                seen = true;
                continue;
            }
            if (seen && insn instanceof MethodInsnNode call && insn.getOpcode() != Opcodes.INVOKEDYNAMIC) {
                return call;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------------------------

    private static List<Hook> hooks() {
        List<Hook> hooks = new ArrayList<>();
        if (!Files.isDirectory(MIXIN_ROOT)) {
            return hooks;
        }
        try (Stream<Path> paths = Files.walk(MIXIN_ROOT)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                Matcher target = MIXIN_TARGET.matcher(text);
                if (!target.find()) {
                    continue; // the config plugin, which is not a mixin
                }
                hooks.add(new Hook(path.getFileName().toString(), target.group(1), injectors(text)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + MIXIN_ROOT.toAbsolutePath(), e);
        }
        assertFalse(hooks.isEmpty(), "no Townstead mixin found under " + MIXIN_ROOT);
        return hooks;
    }

    /**
     * Every {@code @Inject}/{@code @Redirect} block, bounded by the member declaration that follows it.
     *
     * <p>Bounded rather than brace-matched because an annotation's arguments cannot contain a member
     * declaration: the first {@code private}/{@code public}/{@code protected} after the annotation
     * starts is reliably the handler it sits on, which is enough to attribute each {@code @At} to the
     * method its injector names.
     */
    private static List<Injector> injectors(String text) {
        List<Injector> injectors = new ArrayList<>();
        Matcher starts = INJECTOR.matcher(text);
        while (starts.find()) {
            int from = starts.start();
            int to = text.length();
            for (String keyword : List.of("private ", "public ", "protected ")) {
                int candidate = text.indexOf(keyword, from);
                if (candidate >= 0 && candidate < to) {
                    to = candidate;
                }
            }
            String block = text.substring(from, to);
            Matcher method = METHOD.matcher(block);
            if (!method.find()) {
                continue;
            }
            List<String> methods = selectors(method.group(1));
            List<String> invocations = new ArrayList<>();
            Matcher at = AT_TARGET.matcher(block);
            while (at.find()) {
                invocations.add(join(at.group(1)));
            }
            injectors.add(new Injector(block, methods, invocations));
        }
        return injectors;
    }

    /** A Java string literal, possibly written as several concatenated pieces across lines. */
    private static String literal() {
        return "((?:\"[^\"]*\"\\s*\\+\\s*)*\"[^\"]*\")";
    }

    /**
     * The selectors in a {@code method =} value: one literal, or every literal inside a {@code { ... }}.
     *
     * <p>Each element of an array form is a separate whole string, so they are not concatenated the way
     * a single split literal is.
     */
    private static List<String> selectors(String raw) {
        List<String> names = new ArrayList<>();
        if (!raw.trim().startsWith("{")) {
            names.add(join(raw));
            return names;
        }
        Matcher piece = Pattern.compile("\"([^\"]*)\"").matcher(raw);
        while (piece.find()) {
            names.add(piece.group(1));
        }
        return names;
    }

    /** Joins the pieces of such a literal back into the one string the compiler produces. */
    private static String join(String raw) {
        StringBuilder out = new StringBuilder();
        Matcher piece = Pattern.compile("\"([^\"]*)\"").matcher(raw);
        while (piece.find()) {
            out.append(piece.group(1));
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Bytecode
    // ---------------------------------------------------------------------------------------------

    /** The method an injector's {@code method =} names: exact descriptor if given, else name+arity. */
    private static MethodNode find(ClassNode target, String selector) {
        int paren = selector.indexOf('(');
        String name = paren < 0 ? selector : selector.substring(0, paren);
        String descriptor = paren < 0 ? null : selector.substring(paren);
        for (MethodNode method : target.methods) {
            if (!method.name.equals(name)) {
                continue;
            }
            if (descriptor == null || descriptor.equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    /** The first of several selectors that resolves, or null when none of them does. */
    private static MethodNode findAny(ClassNode target, List<String> selectors) {
        for (String selector : selectors) {
            MethodNode found = find(target, selector);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The name under which {@code invocation} was found in {@code method}, or null.
     *
     * <p>Owner and descriptor have to match as well as the name: {@code stop()V} on some other class
     * is not the call being redirected, and neither is an overload. Class names are never obfuscated
     * on 1.20.1 Forge, so the owner is the same string in both mapping sets and only the member name
     * differs — which is precisely what the refmap supplies.
     */
    private static String matchedName(MethodNode method, String invocation, String mapped) {
        String owner = invocation.substring(1, invocation.indexOf(';'));
        String rest = invocation.substring(invocation.indexOf(';') + 1);
        String name = rest.substring(0, rest.indexOf('('));
        String descriptor = rest.substring(rest.indexOf('('));
        String mappedName = null;
        if (mapped != null) {
            String tail = mapped.substring(mapped.indexOf(';') + 1);
            mappedName = tail.substring(0, tail.indexOf('('));
        }
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call) || insn.getOpcode() == Opcodes.INVOKEDYNAMIC) {
                continue;
            }
            if (!owner.equals(call.owner) || !descriptor.equals(call.desc)) {
                continue;
            }
            if (name.equals(call.name)) {
                return name;
            }
            if (mappedName != null && mappedName.equals(call.name)) {
                return mappedName;
            }
        }
        return null;
    }

    private static ZipEntry entry(ZipFile zip, String dottedClassName) {
        return zip.getEntry(dottedClassName.replace('.', '/') + ".class");
    }

    private static ClassNode classNode(ZipFile zip, String dottedClassName) throws IOException {
        ZipEntry entry = entry(zip, dottedClassName);
        assertNotNull(entry, dottedClassName + " is not in " + zip.getName());
        try (InputStream in = zip.getInputStream(entry)) {
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Inputs
    // ---------------------------------------------------------------------------------------------

    /** {@code <variant> -> jar}, for whichever of the two probe properties was supplied. */
    private static Map<String, Path> suppliedJars() {
        Map<String, Path> jars = new LinkedHashMap<>();
        for (String property : JAR_PROPERTIES) {
            for (String raw : System.getProperty(property, "").split(File.pathSeparator)) {
                if (raw.isBlank()) {
                    continue;
                }
                Path path = Paths.get(raw.trim());
                if (Files.isRegularFile(path)) {
                    jars.put(property.contains("legacy") ? "legacy" : "modern", path);
                }
            }
        }
        return jars;
    }

    /**
     * The generated refmap, as {@code <mixin internal name> -> <official ref> -> <srg ref>}.
     *
     * <p>Read from the annotation processor's own output rather than from the jar, so this works
     * after a plain {@code compileJava}. Empty when it has not been generated yet, which the one test
     * that needs it treats as an assumption rather than a failure.
     */
    private static Map<String, Map<String, String>> refmap() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Path candidate : List.of(
                Paths.get("build", "tmp", "compileJava", "mcacrime.refmap.json"),
                Paths.get("build", "resources", "main", "mcacrime.refmap.json"))) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                JsonObject root = JsonParser.parseString(
                        Files.readString(candidate, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject mappings = root.getAsJsonObject("mappings");
                if (mappings == null) {
                    continue;
                }
                for (String mixin : mappings.keySet()) {
                    JsonObject members = mappings.getAsJsonObject(mixin);
                    Map<String, String> entries = new LinkedHashMap<>();
                    for (String member : members.keySet()) {
                        entries.put(member, members.get(member).getAsString());
                    }
                    out.put(mixin, entries);
                }
                return out;
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + candidate.toAbsolutePath(), e);
            }
        }
        return out;
    }

    /** Sanity: the parser has to actually find something, or every assertion above passes vacuously. */
    @Test
    void theParserFindsTheInjectorsItIsAsserting() {
        List<Hook> hooks = hooks();
        int invocations = 0;
        for (Hook hook : hooks) {
            for (Injector injector : hook.injectors()) {
                assertFalse(injector.method().isBlank(), hook.source() + " has an injector with no method");
                invocations += injector.invocations().size();
            }
        }
        List<String> sources = hooks.stream().map(Hook::source).toList();
        assertTrue(sources.contains("GuardRestYieldMixin.java") && sources.contains("ReactionLockGateMixin.java"),
                "the activity-coordination layer is both of these, and half of it is a different bug "
                        + "rather than a degraded version of it: " + sources);
        assertTrue(sources.contains("client/RpgDialogueEntryMixin.java")
                        || sources.contains("RpgDialogueEntryMixin.java"),
                "the dialogue entry point is the only way into Crime's law menu from a Townstead "
                        + "conversation, and the only thing that cancels its queued reopen: " + sources);
        assertTrue(sources.contains("WorkToolProvenanceMixin.java"),
                "equipment provenance has no other source of truth: without this mixin a Townstead "
                        + "display tool is indistinguishable from a villager's own gear and drops twice: "
                        + sources);
        assertTrue(invocations >= 2,
                "expected the two guard-rest redirects to be found; parsed " + invocations);
    }
}
