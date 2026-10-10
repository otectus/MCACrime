package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.mixin.mca.McaJusticeMixinPlugin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.io.File;
import java.util.*;
import java.util.jar.JarFile;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The native heart hooks against every MCA build in {@code mca_probe_versions} (0.7.7, ported from the
 * Forge test with the heart targets only).
 *
 * <p>Each jar must carry exactly the two targets with exactly the probed shape, a changed shape must
 * degrade before injection, and the transformed classes must verify. The jars travel as paths in
 * {@code mcacrime.probe.jars}; nothing is loaded, so no MCA class links into the test JVM.
 */
class McaJusticeTargetTest {
    @Test void everyFleetJarHasExactHeartSitesAndValidTransformedStacks() throws Exception {
        String paths = System.getProperty("mcacrime.probe.jars", "");
        assertFalse(paths.isBlank(), "Required MCA target fleet was not supplied");
        int jars = 0, supplied = 0;
        for (String path : paths.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (path.isBlank()) continue;
            supplied++;
            try (JarFile jar = new JarFile(path)) {
                int targets = 0;
                for (var entry : Collections.list(jar.entries())) {
                    String name = entry.getName();
                    if (name.startsWith("forge/") || name.startsWith("fabric/")
                            || !(name.endsWith("/entity/VillagerEntityMCA.class") || name.endsWith("/entity/ai/Relationship.class"))) continue;
                    ClassNode node = new ClassNode(); new ClassReader(jar.getInputStream(entry)).accept(node, 0);
                    assertTrue(McaJusticeMixinPlugin.supported(node), path + ": missing required hook in " + name);
                    if (name.endsWith("/Relationship.class")) {
                        MethodNode tragedy = node.methods.stream().filter(m -> m.name.equals("onTragedy") && Type.getArgumentTypes(m.desc).length == 4).findFirst().orElseThrow();
                        String original = tragedy.desc;
                        tragedy.desc = "(Ljava/lang/String;Lnet/minecraft/core/BlockPos;Ljava/lang/Object;Lnet/minecraft/world/entity/Entity;)V";
                        assertFalse(McaJusticeMixinPlugin.supported(node), "changed argument layout must degrade before injection");
                        tragedy.desc = original;
                        FieldNode mourner = node.fields.stream().filter(f -> f.name.equals("entity")
                                && f.desc.equals("Lnet/minecraft/world/entity/Mob;")).findFirst().orElseThrow(
                                () -> new AssertionError(path + ": Relationship has no Mob entity field"));
                        node.fields.remove(mourner);
                        assertFalse(McaJusticeMixinPlugin.supported(node), "a missing mourner field must degrade");
                        node.fields.add(mourner);
                    } else {
                        MethodNode hurt = node.methods.stream().filter(m -> m.name.equals("hurt")
                                && m.desc.equals("(Lnet/minecraft/world/damagesource/DamageSource;F)Z")).findFirst().orElseThrow();
                        String original = hurt.name;
                        hurt.name = "renamedHurt";
                        assertFalse(McaJusticeMixinPlugin.supported(node), "a moved hurt site must degrade before injection");
                        hurt.name = original;
                        assertTrue(McaJusticeMixinPlugin.guardAlertSite(node),
                                path + ": no single friend test in hurt for the guard-alert hook (0.7.7)");
                    }
                    McaJusticeMixinPlugin.transform(node);
                    for (MethodNode method : node.methods) {
                        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                        try { new Analyzer<>(new BasicVerifier()).analyze(node.name, method); }
                        catch (AnalyzerException failure) { fail(path + ": " + method.name + method.desc, failure); }
                    }
                    if (name.endsWith("/Relationship.class")) assertMournerPassed(path, node);
                    else {
                        assertRawAmountPassed(path, node);
                        assertGuardAlertHooked(path, node);
                    }
                    ClassWriter writer = new ClassWriter(0); node.accept(writer);
                    assertTrue(writer.toByteArray().length > 0); targets++;
                }
                assertEquals(2, targets, path); jars++;
            }
        }
        assertEquals(supplied, jars, "every supplied fleet jar must be verified");
        assertTrue(jars >= 5, "all five mca_probe_versions builds must be verified, found " + jars);
    }

    /** The killing hook receives delta, source, deceased and this relationship's own villager. */
    private static void assertMournerPassed(String path, ClassNode node) {
        int hooks = 0;
        for (MethodNode method : node.methods) {
            for (var insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.name.equals("tragedyDelta")) {
                    assertEquals("(ILnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/entity/Entity;Ljava/lang/Object;)I",
                            call.desc, path);
                    AbstractInsnNode previous = call.getPrevious();
                    assertTrue(previous instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                            && field.owner.equals(node.name) && field.name.equals("entity")
                            && field.desc.equals("Lnet/minecraft/world/entity/Mob;"), path + ": mourner not loaded from entity");
                    assertTrue(call.getNext() instanceof MethodInsnNode next && next.name.equals("modHearts"),
                            path + ": killing hook must sit directly before modHearts");
                    hooks++;
                }
            }
        }
        assertEquals(1, hooks, path + ": exactly one killing heart hook");
    }

    /** The friend test inside hurt is passed through friendlyToMca, exactly once (0.7.7). */
    private static void assertGuardAlertHooked(String path, ClassNode node) {
        int hooks = 0;
        for (MethodNode method : node.methods) {
            for (var insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.name.equals("friendlyToMca")) {
                    assertEquals("(Z)Z", call.desc, path);
                    assertTrue(call.getPrevious() instanceof MethodInsnNode friend && friend.name.equals("isFriend")
                            && friend.desc.equals("(Lnet/minecraft/world/entity/EntityType;)Z"),
                            path + ": the guard-alert hook must sit directly after MCA's friend test");
                    hooks++;
                }
            }
        }
        assertEquals(1, hooks, path + ": exactly one guard-alert hook");
    }

    /** The native wrappers hand the raw damage to the scope, and a death hands none. */
    private static void assertRawAmountPassed(String path, ClassNode node) {
        int wrappers = 0, hurtHooks = 0;
        for (MethodNode method : node.methods) {
            for (var insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.name.equals("damageDelta")) hurtHooks++;
                if (insn instanceof MethodInsnNode call && call.name.equals("enter")) {
                    assertTrue(call.desc.startsWith("(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;FZ)"),
                            path + ": " + call.desc);
                    AbstractInsnNode amount = call.getPrevious().getPrevious();
                    boolean death = method.desc.endsWith(")V");
                    assertTrue(death ? amount.getOpcode() == Opcodes.FCONST_0
                                    : amount instanceof VarInsnNode load && load.getOpcode() == Opcodes.FLOAD && load.var == 2,
                            path + ": " + method.name + " passes the wrong amount");
                    wrappers++;
                }
            }
        }
        assertEquals(2, wrappers, path + ": one hurt and one death wrapper");
        assertEquals(1, hurtHooks, path + ": exactly one hurt heart hook");
    }
}
