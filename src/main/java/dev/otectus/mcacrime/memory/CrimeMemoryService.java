package dev.otectus.mcacrime.memory;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.economy.account.VillagerPurse;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/** Sole writer for villager offender memory and purse profiles. */
public final class CrimeMemoryService {
    private CrimeMemoryService() {}

    public static VillagerCrimeProfile profile(MinecraftServer server, LivingEntity villager, long day) {
        CrimeWorldData world = CrimeWorldData.get(server);
        VillagerCrimeProfile profile = world.villagerProfile(villager.getUUID(), () -> {
            McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
            int capacity = c.muggingPurseCapacity.get();
            int initialMax = Math.min(capacity, c.muggingPurseInitialMax.get());
            int seed = initialMax <= 0 ? 0 : 1 + Math.floorMod(villager.getUUID().hashCode(), initialMax);
            return new VillagerCrimeProfile(villager.getUUID(),
                    new VillagerPurse(seed, capacity, c.muggingPurseDailyIncome.get(), day));
        });
        if (profile != null && profile.purse().ensureNonZeroInitialSeed(Math.min(1, profile.purse().capacity()))) {
            world.setDirty();
        }
        return profile;
    }

    public static void recordMugAttempt(MinecraftServer server, LivingEntity victim, UUID offender, long now) {
        VillagerCrimeProfile profile = profile(server, victim, now / 24000L);
        profile.memory(offender).recordAttempt(now, McaCrimeConfig.COMMON.muggingFearMemoryTicks.get(),
                McaCrimeConfig.COMMON.muggingPanicTicks.get());
        CrimeWorldData.get(server).setDirty();
    }

    public static void recordMugSuccess(MinecraftServer server, LivingEntity victim, UUID offender,
                                        long now, long amount) {
        VillagerCrimeProfile profile = profile(server, victim, now / 24000L);
        profile.memory(offender).recordSuccess(now, amount);
        profile.setRecoveryUntil(now + McaCrimeConfig.COMMON.muggingVictimRecoveryTicks.get());
        CrimeWorldData.get(server).setDirty();
    }
}
