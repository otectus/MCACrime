package dev.otectus.mcacrime.compat;

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
import java.util.List;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * — so each target is a dotted string and each method is matched by name. There is no Mixin
 * annotation processor on this branch either: NeoForge 1.21.1 runs on Mojang names, so nothing
 * validates these annotations at build time at all. Every guarantee the compiler would normally give
 * is therefore gone, and this is what replaces it: the real Townstead jar is opened and the real
 * class, the real method and the real instruction are looked at.
 *
 * <h2>The three questions</h2>
 *
 * <ol>
 *   <li>Is the target class named in the dotted form, under Townstead's root, and is every
 *       {@code @At} owner vanilla? Checked always, from source, because both are rules about this mod
 *       rather than about Townstead.</li>
 *   <li>Does that class exist in the jar, with a method of that name and shape? A Townstead point
 *       release that renamed it would otherwise show up as a capability that quietly stopped.</li>
 *   <li>Is the vanilla call each {@code @Redirect} names still <em>in</em> that method? This is the
 *       one that matters most and the one nothing else can see: an injector with {@code require = 0}
 *       that matches nothing applies cleanly, logs nothing, and does nothing.</li>
 * </ol>
 *
 * <p>Question 3 is also what stands in for the refmap check the Forge 1.20.1 baseline runs. There is
 * no refmap here and no SRG: the supplied jar is a production NeoForge build whose Minecraft members
 * are spelled exactly as the annotations spell them, so finding {@code eraseMemory} by that name in
 * Townstead's own bytecode <em>is</em> the evidence that {@code remap = false} everywhere is correct
 * on this platform.
 *
 * <h2>Running it</h2>
 *
 * <pre>townsteadProbeTest -PtownsteadJar=&lt;path&gt;</pre>
 *
 * <p>The jar-backed tests skip on an assumption when no jar is supplied, exactly as
 * {@code TownsteadBindingProbeTest} does, so the ordinary {@code test} run stays green on a checkout
 * that has no Townstead jar anywhere.
 */
class TownsteadMixinTargetTest {

    private static final String TOWNSTEAD_ROOT = "com.aetherianartificer.townstead.";

    /** The one property {@code TownsteadBindingProbeTest} reads too, set by the Gradle task. */
    private static final String JARS_PROPERTY = "mcacrime.townstead.probe.jars";

    private static final Pattern MIXIN_TARGET =
            Pattern.compile("@Mixin\\(\\s*targets\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern METHOD =
            Pattern.compile("\\bmethod\\s*=\\s*(\\{[^}]*\\}|" + literal() + ")");
    private static final Pattern AT_TARGET = Pattern.compile("\\btarget\\s*=\\s*" + literal());
    private static final Pattern INJECTOR = Pattern.compile("@(Inject|Redirect)\\(");

    /**
     * One {@code @Inject}/{@code @Redirect}: the methods it names and the calls it points at.
     *
     * <p>{@code methods} is a list rather than a string because Mixin allows a selector array and a
     * match on any element is a match. Nothing in this package needs one on NeoForge — production runs
     * on Mojang names, so every selector here, vanilla or Townstead's own, is the same string in a
     * development run and in a shipped jar — but the parser accepts the array form so that this test
     * keeps checking a mixin somebody later writes with one instead of silently skipping it.
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
     * Every intercepted call names a vanilla owner.
     *
     * <p>This is what makes {@code remap = false} the correct answer everywhere on this branch, and
     * what stands in for the baseline's refmap assertion. Minecraft members keep their Mojang names in
     * a NeoForge 1.21.1 production jar, so a vanilla owner needs no mapping at all; a <em>Townstead</em>
     * owner in an {@code @At}, by contrast, would drag one of Townstead's relocated-MCA descriptors
     * into this mod's constant pool, which is forbidden outright.
     */
    @Test
    void everyInterceptedCallNamesAVanillaOwner() {
        List<String> offenders = new ArrayList<>();
        for (Hook hook : hooks()) {
            for (Injector injector : hook.injectors()) {
                for (String invocation : injector.invocations()) {
                    if (!invocation.startsWith("Lnet/minecraft/")) {
                        offenders.add(hook.source() + " -> " + invocation);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "an @At owner outside net/minecraft would be either unmappable or a Townstead type in "
                        + "this mod's constant pool: " + offenders);
    }

    // ---------------------------------------------------------------------------------------------
    // Jar-backed: rules about Townstead, checkable only against a real build of it.
    // ---------------------------------------------------------------------------------------------

    /** Each target class is really in each supplied Townstead jar. */
    @Test
    void everyTargetClassExistsInEverySuppliedJar() throws IOException {
        List<Path> jars = suppliedJars();
        Assumptions.assumeFalse(jars.isEmpty(),
                "No Townstead jar supplied (" + JARS_PROPERTY + "); run "
                        + "`townsteadProbeTest -PtownsteadJar=<path>` to exercise this.");

        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (Hook hook : hooks()) {
                    assertNotNull(entry(zip, hook.targetClass()),
                            hook.targetClass() + " is not in " + jar.getFileName() + ". "
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
     * signature carries an MCA type this mod may not name, presence under that name is what is
     * pinned: a {@code tick} that had gone would be a capability that quietly stopped.
     */
    @Test
    void everyNamedMethodExistsWithTheExpectedShape() throws IOException {
        List<Path> jars = suppliedJars();
        Assumptions.assumeFalse(jars.isEmpty(), "No Townstead jar supplied.");

        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (Hook hook : hooks()) {
                    ClassNode target = classNode(zip, hook.targetClass());
                    for (Injector injector : hook.injectors()) {
                        MethodNode found = findAny(target, injector.methods());
                        assertNotNull(found,
                                injector.method() + " named by " + hook.source() + " is absent from "
                                        + hook.targetClass() + " in " + jar.getFileName() + ", or has "
                                        + "a different descriptor.");
                        int expected = switch (found.name) {
                            case "tick", "restore", "forget", "freeze" -> 1;
                            case "restoreWalkTarget", "isProtectedStorage" -> 2;
                            case "<init>" -> 3;
                            case "lock" -> 4;
                            default -> 0; // Screen init/removed
                        };
                        assertEquals(expected, org.objectweb.asm.Type.getArgumentTypes(found.desc).length,
                                hook.source() + " captures arguments from " + found.name + " in " + jar.getFileName());
                        System.out.println("[mixin] " + jar.getFileName() + " " + hook.targetClass()
                                + "#" + injector.method() + " present");
                    }
                }
            }
        }
    }

    /**
     * Each redirected call is really in that method, under the Mojang name the annotation uses.
     *
     * <p>The assertion the whole file exists for. Everything above can pass while the injector matches
     * nothing at all — {@code require = 0} makes that silent by design — so the instruction itself is
     * looked for. One spelling only: a NeoForge 1.21.1 jar carries Mojang member names in production,
     * so matching the annotation's own string is both the check and the proof that no refmap is
     * needed.
     */
    @Test
    void everyRedirectedCallIsInThatMethod() throws IOException {
        List<Path> jars = suppliedJars();
        Assumptions.assumeFalse(jars.isEmpty(), "No Townstead jar supplied.");

        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (Hook hook : hooks()) {
                    ClassNode target = classNode(zip, hook.targetClass());
                    for (Injector injector : hook.injectors()) {
                        MethodNode method = findAny(target, injector.methods());
                        assertNotNull(method, injector.method() + " missing from " + hook.targetClass());
                        for (String invocation : injector.invocations()) {
                            int found = countMatches(method, invocation);
                            assertTrue(found > 0,
                                    hook.source() + "'s @At names " + invocation + ", but no such call is "
                                            + "in " + hook.targetClass() + "." + injector.method()
                                            + " in " + jar.getFileName() + ". The redirect would apply "
                                            + "cleanly and do nothing.");
                            System.out.println("[mixin] " + jar.getFileName() + " " + hook.source() + " "
                                    + injector.method() + " -> " + invocation + " x" + found);
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
        List<Path> jars = suppliedJars();
        Assumptions.assumeFalse(jars.isEmpty(), "No Townstead jar supplied.");

        String ticker = "com.aetherianartificer.townstead.tick.WorkToolTicker";
        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ClassNode target = classNode(zip, ticker);
                MethodNode tick = find(target, "tick");
                assertNotNull(tick, "WorkToolTicker.tick is absent from " + jar.getFileName());

                List<MethodInsnNode> copies = new ArrayList<>();
                for (AbstractInsnNode insn : tick.instructions) {
                    // One spelling only, as everywhere else on this branch: a NeoForge 1.21.1
                    // production jar carries Mojang member names, so `copy` is the name in the jar and
                    // the name in the annotation both.
                    if (insn instanceof MethodInsnNode call
                            && "net/minecraft/world/item/ItemStack".equals(call.owner)
                            && "()Lnet/minecraft/world/item/ItemStack;".equals(call.desc)
                            && "copy".equals(call.name)) {
                        copies.add(call);
                    }
                }
                assertEquals(2, copies.size(),
                        "WorkToolProvenanceMixin redirects ItemStack.copy() by ordinal 0 and 1 in "
                                + ticker + ".tick. " + jar.getFileName() + " has " + copies.size()
                                + " such calls, so the ordinals no longer mean what the mixin says they "
                                + "mean. Re-read the method before changing the ordinals.");

                assertEquals("java/util/Map", nextCallOwner(tick, copies.get(0)),
                        "ordinal 0 is supposed to be the copy that goes into Townstead's stash map; in "
                                + jar.getFileName() + " it is handed somewhere else.");
                assertEquals("(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;)V",
                        nextCallDescriptor(tick, copies.get(1)),
                        "ordinal 1 is supposed to be the copy that goes into the villager's hand; in "
                                + jar.getFileName() + " it is handed somewhere else. (The owner is "
                                + "deliberately not checked: it is an MCA type this mod may not name.)");

                // The two clearing hooks. Their absence would leave a display copy recorded against a
                // villager whose shift had ended, which reads as a prop that is really their own gear.
                assertNotNull(find(target, "restore"),
                        "WorkToolTicker.restore is gone from " + jar.getFileName());
                assertNotNull(find(target, "forget"),
                        "WorkToolTicker.forget is gone from " + jar.getFileName());

                System.out.println("[mixin] " + jar.getFileName() + " WorkToolTicker.tick copies: "
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
        List<Path> jars = suppliedJars();
        Assumptions.assumeFalse(jars.isEmpty(), "No Townstead jar supplied.");

        String context = "com.aetherianartificer.townstead.storage.StorageSearchContext";
        String descriptor = "isProtectedStorage(Lnet/minecraft/core/BlockPos;"
                + "Lnet/minecraft/world/level/block/state/BlockState;)Z";
        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                assertNotNull(entry(zip, context),
                        context + " is not in " + jar.getFileName() + "; TownsteadMixinPlugin would "
                                + "refuse StoragePolicyMixin and storage_policy would report unavailable");
                ClassNode target = classNode(zip, context);
                MethodNode method = find(target, descriptor);
                assertNotNull(method, descriptor + " is absent from " + context + " in "
                        + jar.getFileName() + ", or its parameters changed.");
                assertEquals(0, method.access & Opcodes.ACC_STATIC,
                        "StoragePolicyMixin injects an instance handler; a static target would be "
                                + "refused outright in " + jar.getFileName() + ".");

                boolean delegates = false;
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && insn.getOpcode() == Opcodes.INVOKESTATIC
                            && call.owner.endsWith("TownsteadConfig")
                            && "isProtectedStorage".equals(call.name)) {
                        delegates = true;
                    }
                }
                assertTrue(delegates, context + ".isProtectedStorage no longer delegates to the static "
                        + "block policy in " + jar.getFileName() + "; forcing its return value may now "
                        + "be forcing a different question.");
                System.out.println("[mixin] " + jar.getFileName() + " StorageSearchContext."
                        + "isProtectedStorage: instance method delegating to TownsteadConfig");
            }
        }
    }

    /**
     * The dialogue screen's two hooks, under the only names this platform has for them.
     *
     * <p>The one place in this package where the target members are <em>vanilla</em> names rather than
     * Townstead's own, and on NeoForge 1.21.1 that turns out to cost nothing: production runs on Mojang
     * names, so {@code init()V} and {@code removed()V} are the names in a development run and in the
     * shipped jar alike, and the single selectors on those injectors are the whole story. (The Forge
     * 1.20.1 baseline has to carry an SRG spelling beside each of them and assert both here; there is
     * no second spelling on this branch to assert.)
     *
     * <p>What is worth asserting instead is everything the two hooks depend on and nothing else
     * verifies: that the screen still extends {@code Screen} — Mixin refuses to apply a mixin whose
     * declared superclass the target does not have — and that the four reflective members the bridge
     * reaches by name are still there. A Townstead refactor that renamed any of them would leave the
     * button drawn, the mixin applied and the cleanup silently doing nothing.
     */
    @Test
    void theDialogueScreenStillCarriesItsHooks() throws IOException {
        List<Path> jars = suppliedJars();
        Assumptions.assumeFalse(jars.isEmpty(), "No Townstead jar supplied.");

        String screen = "com.aetherianartificer.townstead.client.gui.dialogue.RpgDialogueScreen";
        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                assertNotNull(entry(zip, screen), screen + " is not in " + jar.getFileName());
                ClassNode target = classNode(zip, screen);

                assertEquals("net/minecraft/client/gui/screens/Screen", target.superName,
                        "the dialogue mixin extends Screen, so the target must too, or Mixin refuses to "
                                + "apply it in " + jar.getFileName());

                assertNotNull(find(target, "init()V"),
                        "Screen.init() is init in a NeoForge 1.21.1 production jar, which is why the "
                                + "dialogue mixin names exactly that. It is not in " + jar.getFileName()
                                + ", so the law button would never be added.");
                assertNotNull(find(target, "removed()V"),
                        "Screen.removed() is removed in a NeoForge 1.21.1 production jar. It is not in "
                                + jar.getFileName() + ", so the external-interruption close would never "
                                + "run and Townstead would queue the conversation back over Crime's menu.");

                // The flag the close reason sets, and the three members the cleanup and the button reach
                // for. All four are Townstead's own; their absence is what a refactor of this screen
                // looks like from here.
                assertTrue(target.fields.stream().anyMatch(field -> "userInitiatedClose".equals(field.name)),
                        "RpgDialogueScreen.userInitiatedClose is gone from " + jar.getFileName() + "; "
                                + "the queued reopen can no longer be cancelled.");
                assertTrue(target.fields.stream().anyMatch(field -> "villagerUUID".equals(field.name)),
                        "RpgDialogueScreen.villagerUUID is gone from " + jar.getFileName() + "; the "
                                + "law button has no target to open against.");
                assertTrue(target.fields.stream().anyMatch(field -> "cameraController".equals(field.name)),
                        "RpgDialogueScreen.cameraController is gone from " + jar.getFileName() + "; an "
                                + "external close would leave the view framed on the villager.");
                assertNotNull(find(target, "sendDialogueState"),
                        "RpgDialogueScreen.sendDialogueState is gone from " + jar.getFileName() + "; "
                                + "an external close would leave the server-side dialogue token open.");

                System.out.println("[mixin] " + jar.getFileName() + " RpgDialogueScreen: init/removed "
                        + "present, userInitiatedClose/villagerUUID/cameraController/sendDialogueState "
                        + "present");
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

    /**
     * The mixin source tree.
     *
     * <p>Resolved lazily from {@code mcacrime.projectRoot} rather than in a static field: the NeoForge
     * unit-test runner works out of {@code build/minecraft-junit}, and a failed lookup in a static
     * initialiser reads as a class that would not load rather than as a missing property.
     */
    private static Path mixinRoot() {
        return Paths.get(projectRoot(), "src", "main", "java", "dev", "otectus", "mcacrime",
                "mixin", "townstead");
    }

    private static List<Hook> hooks() {
        List<Hook> hooks = new ArrayList<>();
        Path root = mixinRoot();
        if (!Files.isDirectory(root)) {
            return hooks;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                Matcher target = MIXIN_TARGET.matcher(text);
                if (!target.find()) {
                    continue; // the config plugin, which is not a mixin
                }
                hooks.add(new Hook(path.getFileName().toString(), target.group(1), injectors(text)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + root.toAbsolutePath(), e);
        }
        assertFalse(hooks.isEmpty(), "no Townstead mixin found under " + root);
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

    /** The method an injector's {@code method =} names: exact descriptor if given, else by name. */
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
     * How many times {@code invocation} appears in {@code method}.
     *
     * <p>Owner, name and descriptor all have to match: {@code stop()V} on some other class is not the
     * call being redirected, and neither is an overload. The count is reported rather than just a
     * boolean because the guard-rest hook deliberately covers two {@code eraseMemory} calls with one
     * redirect, and seeing that number drop to one is worth noticing in the log.
     */
    private static int countMatches(MethodNode method, String invocation) {
        String owner = invocation.substring(1, invocation.indexOf(';'));
        String rest = invocation.substring(invocation.indexOf(';') + 1);
        String name = rest.substring(0, rest.indexOf('('));
        String descriptor = rest.substring(rest.indexOf('('));
        int found = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call) || insn.getOpcode() == Opcodes.INVOKEDYNAMIC) {
                continue;
            }
            if (owner.equals(call.owner) && name.equals(call.name) && descriptor.equals(call.desc)) {
                found++;
            }
        }
        return found;
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

    /** Whatever {@code -PtownsteadJar} supplied. Empty when the ordinary {@code test} run reaches this. */
    private static List<Path> suppliedJars() {
        List<Path> jars = new ArrayList<>();
        for (String raw : System.getProperty(JARS_PROPERTY, "").split(File.pathSeparator)) {
            if (raw.isBlank()) {
                continue;
            }
            Path path = Paths.get(raw.trim());
            if (Files.isRegularFile(path)) {
                jars.add(path);
            }
        }
        return jars;
    }

    /** The project directory, as supplied by the test task. */
    private static String projectRoot() {
        String root = System.getProperty("mcacrime.projectRoot");
        assertTrue(root != null && !root.isBlank(),
                "mcacrime.projectRoot is not set; the test task in build.gradle supplies it");
        return root;
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
        assertTrue(sources.contains("WorkToolProvenanceMixin.java"),
                "equipment provenance has no other source of truth: without this mixin a Townstead "
                        + "display tool is indistinguishable from a villager's own gear and drops twice: "
                        + sources);
        assertTrue(invocations >= 2,
                "expected the two guard-rest redirects to be found; parsed " + invocations);
    }
}
