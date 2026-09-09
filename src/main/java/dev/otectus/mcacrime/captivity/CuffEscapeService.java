package dev.otectus.mcacrime.captivity;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.LocksReforgedBridge;
import dev.otectus.mcacrime.enforcement.RestraintPolicy;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Crime owns custody and authorization; the optional adapter owns the native lockpicking menu. */
public final class CuffEscapeService {
    private CuffEscapeService() {}

    public static boolean isCuff(RestraintType type) {
        return type == RestraintType.CUFFS || type == RestraintType.LOCKED_CUFFS;
    }

    public static boolean usesMinigame(RestraintType type) {
        return isCuff(type) && LocksReforgedBridge.installed();
    }

    public static boolean usesMinigame(ServerPlayer player) {
        return player != null && usesMinigame(RestraintPolicy.effective(player).orElse(RestraintType.NONE));
    }

    public static boolean mayAttempt(boolean alive, boolean sameCustody, boolean sameDimension,
            boolean cuffed, boolean requirePick, boolean hasPick) {
        return alive && sameCustody && sameDimension && cuffed && (!requirePick || hasPick);
    }

    public static boolean valid(ServerPlayer player, CustodyRecord expected, ResourceLocation dimension,
            RestraintType type, boolean hasPick) {
        return player != null && expected != null && ServerMutationGate.allows(player.getServer())
                && mayAttempt(player.isAlive() && !player.isSpectator(),
                    CrimeWorldData.get(player.getServer()).getCustody(player.getUUID()) == expected,
                    dimension.equals(player.level().dimension().location()),
                    RestraintPolicy.effective(player).orElse(RestraintType.NONE) == type && isCuff(type),
                    McaCrimeConfig.COMMON.cuffEscapeRequiresLockpick.get(), hasPick);
    }

    public static boolean start(ServerPlayer player, CustodyRecord record) {
        if (!ServerMutationGate.allows(player.getServer()) || !player.isAlive() || player.isSpectator()) return false;
        // Cancels a pre-install timed attempt before it can bypass the native minigame.
        record.setEscapeActive(false);
        record.setEscapeProgress(0);
        CrimeWorldData.get(player.getServer()).setDirty();
        long now = player.level().getGameTime();
        if (now < record.getEscapeCooldownUntil()) {
            player.sendSystemMessage(Component.translatable("mcacrime.captive.escape.cooldown", record.getEscapeCooldownUntil() - now));
            return false;
        }
        return LocksReforgedBridge.openCuffs(player, record);
    }

    public static void completed(ServerPlayer player, CustodyRecord expected) {
        var server = player.getServer();
        if (!ServerMutationGate.allows(server) || CrimeWorldData.get(server).getCustody(player.getUUID()) != expected) return;
        if (expected.isLawful()) dev.otectus.mcacrime.jail.JailService.escapeCuffs(player);
        // Filing jailbreak invokes addon callbacks; they must not let this result release replacement custody.
        if (CrimeWorldData.get(server).getCustody(player.getUUID()) != expected) return;
        CustodyService.release(server, player.getUUID(), CustodyReleaseReason.ESCAPED);
        dev.otectus.mcacrime.audio.CrimeSounds.restraintBroken(player);
    }
}
