package dev.otectus.mcacrime.compat.mca;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.mixin.mca.McaJusticeMixinPlugin;
import java.util.Set;

/** Explicit supported/degraded contract; a missing native penalty site is never silently called safe. */
public final class NativeJusticeCapabilities {
    private static boolean warned;
    private NativeJusticeCapabilities() {}
    public static boolean combatComplete() {
        return McaJusticeMixinPlugin.APPLIED.containsAll(Set.of("VillagerEntityMCA", "Relationship"));
    }
    /** Whether MCA's guard alert is gated for exempt blows (0.7.7); optional beside the heart hooks. */
    public static boolean guardAlertGated() {
        return McaJusticeMixinPlugin.APPLIED.contains(McaJusticeMixinPlugin.GUARD_ALERT);
    }
    public static String status() {
        return (combatComplete() ? "native combat hooks verified" : "DEGRADED: native combat hooks incomplete")
                + (guardAlertGated() ? "; guard alert gated" : "; guard alert ungated");
    }
    public static void initialize() {
        String root = McaHandles.resolution().root();
        if (root != null) for (String type : new String[]{"entity.VillagerEntityMCA", "entity.ai.Relationship", "server.world.data.PlayerSaveData"}) {
            try { Class.forName(root + type); } catch (ClassNotFoundException | LinkageError failure) {
                McaJusticeMixinPlugin.DEGRADED.add(root + type);
            }
        }
        if (!combatComplete() && !warned) { warned = true; McaCrime.LOGGER.warn("{}; check /crime validate before relying on reputation-safe Thief combat", status()); }
    }
}
