package dev.otectus.mcacrime.compat.revive;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * PlayerRevive's downed state, read and never written (0.7.5 M6.2, specification §15.1).
 *
 * <p>Isolated and reflective, like every adapter in {@code compat/}. It answers exactly one question
 * — "is this player down rather than dead?" — and two parts of MCA: Crime need it:
 *
 * <ul>
 *   <li><b>Application.</b> A downed player is vulnerable, which is what the low-health application
 *       gate is for; without this they would read as a healthy player with full hearts.</li>
 *   <li><b>Execution.</b> A downed player is not dead, and a guillotine must not treat them as
 *       either. The device already requires a confirmed death from its own damage action, so what
 *       this adds is the refusal <em>before</em> the blade: a condemned captive who is downed is not
 *       executed, and their downed lifecycle is left entirely to PlayerRevive.</li>
 * </ul>
 *
 * <p>Nothing here revives, bleeds out, cancels or extends anything. Writing to another mod's
 * lifecycle is how "no indefinite stuck downed restraint" turns into two mods fighting over one
 * player.
 */
public final class PlayerReviveAdapter {

    private static final String MOD_ID = "playerrevive";

    private static volatile Method capabilityAccessor;
    private static volatile Method isDowned;
    private static volatile boolean probed;
    private static volatile String status = "not probed";

    private PlayerReviveAdapter() {
    }

    /** Binds the members this adapter will call, and reports what it found. */
    public static synchronized String probe() {
        probed = true;
        if (!loaded()) {
            status = "not installed";
            return status;
        }
        try {
            Class<?> helper = Class.forName("de.maxhenkel.playerrevive.api.PlayerReviveHelper");
            capabilityAccessor = helper.getMethod("isBleeding",
                    Class.forName("net.minecraft.world.entity.player.Player"));
            isDowned = null;
            status = MOD_ID + " " + version() + ": supported";
        } catch (Throwable first) {
            try {
                Class<?> capability = Class.forName("de.maxhenkel.playerrevive.api.IRevival");
                isDowned = capability.getMethod("isBleeding");
                capabilityAccessor = null;
                status = MOD_ID + " " + version() + ": supported through the revival capability";
            } catch (Throwable second) {
                capabilityAccessor = null;
                isDowned = null;
                status = MOD_ID + " " + version() + ": unsupported build ("
                        + second.getClass().getSimpleName() + "); a downed player reads as ordinary";
            }
        }
        return status;
    }

    /** Whether this subject is down rather than dead. False for everything this adapter cannot read. */
    public static boolean downed(LivingEntity subject) {
        if (subject == null || !loaded()) {
            return false;
        }
        if (!probed) {
            probe();
        }
        try {
            Method helper = capabilityAccessor;
            if (helper != null) {
                Object answer = helper.invoke(null, subject);
                return Boolean.TRUE.equals(answer);
            }
            Method member = isDowned;
            if (member == null) {
                return false;
            }
            Object capability = subject.getClass()
                    .getMethod("getCapability", Class.forName(
                            "net.minecraftforge.common.capabilities.Capability"))
                    .invoke(subject, revivalCapability());
            Object resolved = capability == null ? null
                    : capability.getClass().getMethod("resolve").invoke(capability);
            if (resolved instanceof java.util.Optional<?> optional && optional.isPresent()) {
                return Boolean.TRUE.equals(member.invoke(optional.get()));
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object revivalCapability() throws Exception {
        return Class.forName("de.maxhenkel.playerrevive.capabilities.RevivalCapability")
                .getField("REVIVAL").get(null);
    }

    /** What the last probe found. */
    public static String status() {
        return probed ? status : "not probed";
    }

    private static boolean loaded() {
        ModList list = ModList.get();
        return list != null && list.isLoaded(MOD_ID);
    }

    private static String version() {
        try {
            ModList list = ModList.get();
            return list == null ? "unknown" : list.getModContainerById(MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
