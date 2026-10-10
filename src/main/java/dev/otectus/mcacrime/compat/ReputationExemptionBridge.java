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

/**
 * MCA: Reputation's per-incident exemption query, answered from the Thief combat policy (0.7.7 on this
 * port, from the Forge tree).
 *
 * <p>Reputation records its own assault and killing incidents from MCA villagers' damage and death
 * events; without this, a mugging victim fighting the mugger off -- exempt here -- would still lose
 * standing there. The handshake is reflective and versioned exactly as on Forge: Reputation 0.6.1 or
 * newer offers {@code incident_exemptions_v1}; an older Reputation (0.5.x has none) or none at all leaves
 * this off with one log line naming the minimum version, and nothing else changes.
 */
public final class ReputationExemptionBridge {
    private static String status = "not initialized";
    private ReputationExemptionBridge() {}
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void init() {
        if (!net.neoforged.fml.ModList.get().isLoaded("mcareputation")) { status = "absent"; return; }
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
                    // A killing is asked of the narrower kill exemption: under NORMAL_LAW a mugging victim
                    // may fight the mugger off, but killing them is still Reputation's to record.
                    boolean kill = "MCA_VILLAGER_KILL".equals(args[0].toString());
                    if (sampled != null && (kill ? sampled.exemptsKill() : sampled.exempt())) return exempt;
                    // A hit MCA: Crime is going to forgive as minor friendly fire is not an assault for
                    // Reputation either. A peek, so asking changes nothing; the final damage arrives here.
                    return "MCA_VILLAGER_ASSAULT".equals(args[0].toString()) && args[4] instanceof Float amount
                            && dev.otectus.mcacrime.detect.DamageIncidentService.minorHarmCandidate(target, source, amount)
                            ? exempt : pass;
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
