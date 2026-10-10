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

class McaJusticeTargetTest {
    @Test void everyRequiredJarHasExactPenaltyAndMailboxSitesAndValidTransformedStacks() throws Exception {
        String paths = System.getProperty("mcacrime.probe.jars", "");
        assertFalse(paths.isBlank(), "Required MCA target fleet was not supplied");
        int jars = 0;
        for (String path : paths.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!path.contains("minecraft-comes-alive")) continue;
            try (JarFile jar = new JarFile(path)) {
                int targets = 0;
                for (var entry : Collections.list(jar.entries())) {
                    String name = entry.getName();
                    if (!name.startsWith("forge/") || !(name.endsWith("/entity/VillagerEntityMCA.class")
                            || name.endsWith("/entity/ai/Relationship.class") || name.endsWith("/server/world/data/PlayerSaveData.class"))) continue;
                    ClassNode node = new ClassNode(); new ClassReader(jar.getInputStream(entry)).accept(node, 0);
                    assertTrue(McaJusticeMixinPlugin.supported(node), path + ": missing required hook in " + name);
                    if (name.endsWith("/Relationship.class")) {
                        MethodNode tragedy = node.methods.stream().filter(m -> m.name.equals("onTragedy") && Type.getArgumentTypes(m.desc).length == 4).findFirst().orElseThrow();
                        String original = tragedy.desc;
                        tragedy.desc = "(Ljava/lang/String;Lnet/minecraft/core/BlockPos;Ljava/lang/Object;Lnet/minecraft/world/entity/Entity;)V";
                        assertFalse(McaJusticeMixinPlugin.supported(node), "changed argument layout must degrade before injection");
                        tragedy.desc = original;
                        // 0.7.7: the mourner is read from the relationship's own Mob field, so a build
                        // without it must degrade rather than load a field that is not there.
                        FieldNode mourner = node.fields.stream().filter(f -> f.name.equals("entity")
                                && f.desc.equals("Lnet/minecraft/world/entity/Mob;")).findFirst().orElseThrow(
                                () -> new AssertionError(path + ": Relationship has no Mob entity field"));
                        node.fields.remove(mourner);
                        assertFalse(McaJusticeMixinPlugin.supported(node), "a missing mourner field must degrade");
                        node.fields.add(mourner);
                    }
                    if (name.endsWith("/PlayerSaveData.class")) {
                        MethodNode load = node.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.endsWith("Lnet/minecraft/nbt/CompoundTag;)V")).findFirst().orElseThrow();
                        String original = load.desc;
                        load.desc = "(Lnet/minecraft/nbt/CompoundTag;)V";
                        assertFalse(McaJusticeMixinPlugin.supported(node), "missing exact same-object load path must degrade");
                        load.desc = original;
                    }
                    boolean villager = name.endsWith("/VillagerEntityMCA.class");
                    if (villager) assertTrue(McaJusticeMixinPlugin.guardAlertSite(node),
                            path + ": no single friend test in hurt for the guard-alert hook (0.7.7)");
                    McaJusticeMixinPlugin.transform(node);
                    int mailSaves = 0;
                    for (MethodNode method : node.methods) {
                        for (var insn : method.instructions.toArray()) if (insn instanceof MethodInsnNode call && call.name.equals("saveReceipts")) {
                            assertEquals(0, method.access & Opcodes.ACC_STATIC, "mail receipt owner must be saved object");
                            assertTrue(method.name.equals("save") || method.name.equals("m_7176_")); mailSaves++;
                        }
                        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                        try { new Analyzer<>(new BasicVerifier()).analyze(node.name, method); }
                        catch (AnalyzerException failure) { fail(path + ": " + method.name + method.desc, failure); }
                    }
                    if (name.endsWith("/PlayerSaveData.class")) assertEquals(1, mailSaves, "exactly one native save hook");
                    if (name.endsWith("/Relationship.class")) assertMournerPassed(path, node);
                    if (villager) assertRawAmountPassed(path, node);
                    if (villager) assertGuardAlertHooked(path, node);
                    ClassWriter writer = new ClassWriter(0); node.accept(writer);
                    assertTrue(writer.toByteArray().length > 0); targets++;
                }
                assertEquals(3, targets, path); jars++;
            }
        }
        assertTrue(jars >= 3, "All three required supported MCA builds must be verified");
    }

    /** The killing hook receives delta, source, deceased and this relationship's own villager (0.7.7). */
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

    /** The native wrappers hand the raw damage to the scope, and a death hands none (0.7.7). */
    private static void assertRawAmountPassed(String path, ClassNode node) {
        int wrappers = 0;
        for (MethodNode method : node.methods) {
            for (var insn : method.instructions.toArray()) {
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
    }
}
