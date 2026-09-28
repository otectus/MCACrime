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
                    }
                    if (name.endsWith("/PlayerSaveData.class")) {
                        MethodNode load = node.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.endsWith("Lnet/minecraft/nbt/CompoundTag;)V")).findFirst().orElseThrow();
                        String original = load.desc;
                        load.desc = "(Lnet/minecraft/nbt/CompoundTag;)V";
                        assertFalse(McaJusticeMixinPlugin.supported(node), "missing exact same-object load path must degrade");
                        load.desc = original;
                    }
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
                    ClassWriter writer = new ClassWriter(0); node.accept(writer);
                    assertTrue(writer.toByteArray().length > 0); targets++;
                }
                assertEquals(3, targets, path); jars++;
            }
        }
        assertTrue(jars >= 3, "All three required supported MCA builds must be verified");
    }
}
