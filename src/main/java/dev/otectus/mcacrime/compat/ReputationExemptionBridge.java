package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.compat.mca.NativeCombatContext;
import dev.otectus.mcacrime.justice.ThiefCombatService;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import java.lang.reflect.Proxy;
import java.util.Set;

/** Optional per-action handshake remains active when ordinary incident delivery hands back. */
public final class ReputationExemptionBridge {
    private static String status = "not initialized";
    private ReputationExemptionBridge() {}
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void init() {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("mcareputation")) { status = "absent"; return; }
        try {
            Class<?> api = Class.forName("dev.otectus.mcareputation.api.CoreIncidentExemptions");
            if (!Integer.valueOf(1).equals(api.getMethod("capabilityVersion").invoke(null))) { status = "unsupported capability"; return; }
            Class<? extends Enum> kind = (Class<? extends Enum>) Class.forName("dev.otectus.mcareputation.api.CoreIncidentKind");
            Class<?> query = Class.forName(api.getName() + "$Query");
            Class<? extends Enum> decision = (Class<? extends Enum>) Class.forName(api.getName() + "$Decision");
            Object pass = Enum.valueOf(decision, "PASS"), exempt = Enum.valueOf(decision, "EXEMPT");
            Object adapter = Proxy.newProxyInstance(query.getClassLoader(), new Class[]{query}, (proxy, method, args) -> {
                if (method.getName().equals("evaluate")) {
                    LivingEntity target = (LivingEntity) args[2]; DamageSource source = (DamageSource) args[3];
                    var sampled = NativeCombatContext.decision(target, source);
                    if (sampled == null) sampled = ThiefCombatService.sample(target, (ServerPlayer) args[1], args[0].toString());
                    return sampled != null && sampled.exempt() ? exempt : pass;
                }
                return switch (method.getName()) {
                    case "toString" -> "MCA Crime exact-action legal exemptions";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0]; default -> null;
                };
            });
            api.getMethod("register", ResourceLocation.class, Set.class, query).invoke(null, McaCrime.id("thief_combat"),
                    Set.of(Enum.valueOf(kind, "MCA_VILLAGER_ASSAULT"), Enum.valueOf(kind, "MCA_VILLAGER_KILL")), adapter);
            status = "capability 1 registered";
        } catch (ReflectiveOperationException | LinkageError failure) {
            status = "unavailable: MCA: Reputation 0.6.1 or newer adds the per-incident exemption capability";
            McaCrime.LOGGER.warn("{}; thief reputation safety is degraded while normal authority is handed back", status);
        }
    }
    public static String status() { return status; }
}
