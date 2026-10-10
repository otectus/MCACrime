package dev.otectus.mcacrime.compat.mca;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.mixin.mca.McaJusticeMixinPlugin;
import java.util.Set;

/**
 * Explicit supported/degraded contract for MCA's native heart hooks (0.7.7, ported from Forge with the
 * heart hooks only). A missing native penalty site is never silently called safe.
 */
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
        return (combatComplete() ? "native heart hooks verified" : "DEGRADED: native heart hooks incomplete")
                + (guardAlertGated() ? "; guard alert gated" : "; guard alert ungated");
    }
    public static void initialize() {
        String root = McaHandles.resolution().root();
        if (root != null) for (String type : new String[]{"entity.VillagerEntityMCA", "entity.ai.Relationship"}) {
            try { Class.forName(root + type); } catch (ClassNotFoundException | LinkageError failure) {
                McaJusticeMixinPlugin.DEGRADED.add(root + type);
            }
        }
        if (!combatComplete() && !warned) {
            warned = true;
            McaCrime.LOGGER.warn("{}; MCA's own killing and hurt heart penalties are not gated by "
                    + "relationship.murderHeartsRequireWitness or minor-harm forgiveness on this MCA build", status());
        }
    }
}
