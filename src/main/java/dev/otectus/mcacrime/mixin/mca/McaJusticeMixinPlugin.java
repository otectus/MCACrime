package dev.otectus.mcacrime.mixin.mca;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.*;

/** Narrow native compatibility instrumentation; all MCA owners are taken from verified bytecode.
 * Vanilla descriptors remain mapped by the loader. No MCA class is resolved or linked here.
 */
public final class McaJusticeMixinPlugin implements IMixinConfigPlugin, Opcodes {
    private static final String CONTEXT = "dev/otectus/mcacrime/compat/mca/NativeCombatContext";
    private static final String MAIL = "dev/otectus/mcacrime/compat/McaMailBridge";
    private static final String SCOPE = "L" + CONTEXT + "$Scope;";
    private static final String DAMAGE = "Lnet/minecraft/world/damagesource/DamageSource;";
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("mcacrime-native-justice");
    public static final Set<String> DEGRADED = Collections.synchronizedSet(new HashSet<>());
    private static final String TAG = "Lnet/minecraft/nbt/CompoundTag;";
    public static final Set<String> APPLIED = Collections.synchronizedSet(new HashSet<>());
    @Override public void onLoad(String pkg) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public List<String> getMixins() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        try (var stream = getClass().getClassLoader().getResourceAsStream(target.replace('.', '/') + ".class")) {
            if (stream == null) return false;
            ClassNode node = new ClassNode(); new ClassReader(stream).accept(node, 0);
            boolean supported = supported(node);
            if (!supported && DEGRADED.add(target)) LOGGER.warn("MCA native justice capability unavailable for {}; reputation safety/mail are degraded", target);
            return supported;
        } catch (Exception failure) { if (DEGRADED.add(target)) LOGGER.warn("MCA native justice probe failed for {}", target, failure); return false; }
    }
    public static boolean supported(ClassNode node) {
        if (node.name.endsWith("/VillagerEntityMCA")) {
            return node.methods.stream().anyMatch(m -> hurtSite(m))
                    && node.methods.stream().anyMatch(m -> deathSite(m));
        }
        if (node.name.endsWith("/Relationship")) return node.methods.stream().anyMatch(m -> tragedy(node, m) && count(m, "modHearts", "(I)V") == 1);
        if (node.name.endsWith("/PlayerSaveData")) return node.fields.stream().anyMatch(f -> f.name.equals("inbox") && f.desc.equals("Ljava/util/List;"))
                && node.methods.stream().anyMatch(m -> mailConstructor(m))
                && node.methods.stream().anyMatch(m -> m.name.equals("sendMail") && m.desc.equals("(" + TAG + ")V"))
                && node.methods.stream().anyMatch(m -> m.name.equals("getMail") && m.desc.equals("()Lnet/minecraft/world/item/ItemStack;"))
                && node.methods.stream().anyMatch(m -> (m.access & ACC_STATIC) == 0 && m.desc.equals("(" + TAG + ")" + TAG) && (m.name.equals("save") || m.name.equals("m_7176_")));
        return false;
    }
    private static boolean hurtSite(MethodNode method) {
        return (method.access & ACC_STATIC) == 0 && (method.name.equals("hurt") || method.name.equals("m_6469_"))
                && method.desc.equals("(" + DAMAGE + "F)Z") && count(method, "modHearts", "(I)V") == 1;
    }
    private static boolean deathSite(MethodNode method) {
        return (method.access & ACC_STATIC) == 0 && (method.name.equals("die") || method.name.equals("m_6667_"))
                && method.desc.equals("(" + DAMAGE + ")V") && count(method, "onDeath", "(" + DAMAGE + ")V") == 1
                && count(method, "pushHearts", "(Ljava/util/UUID;I)V") <= 1;
    }
    private static boolean tragedy(ClassNode owner, MethodNode method) {
        String root = owner.name.substring(0, owner.name.length() - "entity/ai/Relationship".length());
        return (method.access & ACC_STATIC) == 0 && method.name.equals("onTragedy")
                && method.desc.equals("(" + DAMAGE + "Lnet/minecraft/core/BlockPos;L" + root
                + "entity/ai/relationship/RelationshipType;Lnet/minecraft/world/entity/Entity;)V");
    }
    private static boolean mailConstructor(MethodNode method) {
        return method.name.equals("<init>") && method.desc.equals(
                "(Lnet/minecraft/server/level/ServerLevel;Ljava/util/UUID;" + TAG + ")V");
    }
    private static long count(MethodNode m, String name, String desc) {
        return Arrays.stream(m.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call && call.name.equals(name) && call.desc.equals(desc)).count();
    }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (!supported(node)) {
            if (DEGRADED.add(target)) LOGGER.warn("MCA native justice target changed after probe: {}; capability disabled", target);
            return;
        }
        transform(node); APPLIED.add(target.substring(target.lastIndexOf('.') + 1));
    }
    public static void transform(ClassNode node) {
        for (MethodNode method : List.copyOf(node.methods)) {
            method.maxStack += 3;
            if (node.name.endsWith("/VillagerEntityMCA")) {
                if (hurtSite(method)) {
                    for (var insn : method.instructions.toArray()) if (insn instanceof MethodInsnNode call && call.name.equals("modHearts") && call.desc.equals("(I)V"))
                        method.instructions.insertBefore(insn, new MethodInsnNode(INVOKESTATIC, CONTEXT, "damageDelta", "(I)I", false));
                    wrap(node, method, false);
                } else if (deathSite(method)) {
                    for (var insn : method.instructions.toArray()) if (insn instanceof MethodInsnNode call && call.name.equals("pushHearts") && call.desc.equals("(Ljava/util/UUID;I)V")) {
                        int local = method.maxLocals++;
                        InsnList code = new InsnList(); code.add(new VarInsnNode(ISTORE, local)); code.add(new InsnNode(DUP));
                        code.add(new VarInsnNode(ILOAD, local));
                        code.add(new MethodInsnNode(INVOKESTATIC, CONTEXT, "standingDelta", "(Ljava/util/UUID;I)I", false));
                        method.instructions.insertBefore(insn, code);
                    }
                    wrap(node, method, true);
                }
            } else if (node.name.endsWith("/Relationship") && tragedy(node, method)) {
                for (var insn : method.instructions.toArray()) if (insn instanceof MethodInsnNode call && call.name.equals("modHearts") && call.desc.equals("(I)V")) {
                    InsnList code = new InsnList(); code.add(new VarInsnNode(ALOAD, 1)); code.add(new VarInsnNode(ALOAD, 4));
                    code.add(new MethodInsnNode(INVOKESTATIC, CONTEXT, "tragedyDelta", "(I" + DAMAGE + "Lnet/minecraft/world/entity/Entity;)I", false));
                    method.instructions.insertBefore(insn, code);
                }
            } else if (node.name.endsWith("/PlayerSaveData")) {
                if ((method.access & ACC_STATIC) == 0 && method.desc.equals("(" + TAG + ")" + TAG) && (method.name.equals("save") || method.name.equals("m_7176_"))) {
                    for (var insn : method.instructions.toArray()) if (insn.getOpcode() == ARETURN) {
                        InsnList code = new InsnList(); code.add(new VarInsnNode(ALOAD, 0)); code.add(new InsnNode(SWAP));
                        code.add(new MethodInsnNode(INVOKESTATIC, MAIL, "saveReceipts", "(Ljava/lang/Object;" + TAG + ")" + TAG, false));
                        method.instructions.insertBefore(insn, code);
                    }
                } else if (mailConstructor(method)) {
                    for (var insn : method.instructions.toArray()) if (insn.getOpcode() == RETURN) {
                        InsnList code = new InsnList(); code.add(new VarInsnNode(ALOAD, 0)); code.add(new VarInsnNode(ALOAD, 3));
                        code.add(new MethodInsnNode(INVOKESTATIC, MAIL, "loadReceipts", "(Ljava/lang/Object;" + TAG + ")V", false));
                        method.instructions.insertBefore(insn, code);
                    }
                } else if (method.name.equals("getMail") && method.desc.equals("()Lnet/minecraft/world/item/ItemStack;")) {
                    for (var insn : method.instructions.toArray()) if (insn.getOpcode() == ARETURN) {
                        InsnList code = new InsnList(); code.add(new InsnNode(DUP)); code.add(new VarInsnNode(ALOAD, 0));
                        code.add(new MethodInsnNode(INVOKESTATIC, MAIL, "collected", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                        method.instructions.insertBefore(insn, code);
                    }
                }
            }
        }
    }
    /** Keep the original body intact; a real catch-all finally handles nested and throwing callbacks. */
    private static void wrap(ClassNode node, MethodNode original, boolean death) {
        String name = original.name;
        MethodNode wrapper = new MethodNode(ASM9, original.access, name, original.desc, original.signature,
                original.exceptions.toArray(String[]::new));
        original.name = "mcacrime$native$" + name;
        original.access = (original.access & ~(ACC_PUBLIC | ACC_PROTECTED)) | ACC_PRIVATE;
        var c = wrapper.instructions;
        c.add(new VarInsnNode(ALOAD, 0)); c.add(new VarInsnNode(ALOAD, 1)); c.add(new InsnNode(death ? ICONST_1 : ICONST_0));
        c.add(new MethodInsnNode(INVOKESTATIC, CONTEXT, "enter", "(Lnet/minecraft/world/entity/LivingEntity;" + DAMAGE + "Z)" + SCOPE, false));
        int scope = death ? 2 : 3;
        c.add(new VarInsnNode(ASTORE, scope));
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
        c.add(start); c.add(new VarInsnNode(ALOAD, 0)); c.add(new VarInsnNode(ALOAD, 1));
        if (!death) c.add(new VarInsnNode(FLOAD, 2));
        c.add(new MethodInsnNode(INVOKESPECIAL, node.name, original.name, original.desc, false));
        c.add(end); c.add(new VarInsnNode(ALOAD, scope));
        c.add(new MethodInsnNode(INVOKESTATIC, CONTEXT, "exit", "(" + SCOPE + ")V", false));
        c.add(new InsnNode(death ? RETURN : IRETURN));
        c.add(handler);
        Object[] locals = death ? new Object[]{node.name, "net/minecraft/world/damagesource/DamageSource", CONTEXT + "$Scope"}
                : new Object[]{node.name, "net/minecraft/world/damagesource/DamageSource", FLOAT, CONTEXT + "$Scope"};
        c.add(new FrameNode(F_FULL, locals.length, locals, 1, new Object[]{"java/lang/Throwable"}));
        c.add(new VarInsnNode(ALOAD, scope)); c.add(new MethodInsnNode(INVOKESTATIC, CONTEXT, "exit", "(" + SCOPE + ")V", false));
        c.add(new InsnNode(ATHROW));
        wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        wrapper.maxLocals = scope + 1; wrapper.maxStack = 4;
        node.methods.add(wrapper);
    }
}
