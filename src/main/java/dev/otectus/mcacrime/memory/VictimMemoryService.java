package dev.otectus.mcacrime.memory;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.CrimeRecordResolvedEvent;
import dev.otectus.mcacrime.api.event.VictimCrimeMemoryChangedEvent;
import dev.otectus.mcacrime.api.model.VictimMemoryView;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeAwareness;
import dev.otectus.mcacrime.economy.account.VillagerPurse;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;

/** Persistent social consequences. Family learns through perception or a later local conversation. */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class VictimMemoryService {
    private VictimMemoryService() {}

    public static List<VictimMemoryView> memories(MinecraftServer server, UUID villager, UUID player) {
        if (server == null || villager == null || player == null || !McaCrimeConfig.COMMON.enableVictimMemory.get()) return List.of();
        long now = server.overworld().getGameTime();
        double decay = McaCrimeConfig.COMMON.memoryDecayMultiplier.get();
        return CrimeWorldData.get(server).villagerProfile(villager).stream()
                .flatMap(p -> p.crimeMemories().stream()).filter(m -> m.perpetrator().equals(player))
                .map(m -> m.view(now, decay)).toList();
    }

    public static Set<UUID> familyOf(LivingEntity villager) {
        Set<UUID> family = new LinkedHashSet<>();
        McaCompat.getSpouseUuid(villager).ifPresent(family::add);
        family.addAll(McaCompat.getParentUuids(villager)); family.addAll(McaCompat.getChildUuids(villager));
        family.addAll(McaCompat.getSiblingUuids(villager)); family.remove(villager.getUUID());
        return family;
    }

    public static void record(ServerLevel level, LivingEntity actor, LivingEntity victim, ResourceLocation crime,
                              UUID incident, CrimeAwareness awareness, boolean robbery, List<CrimeObservation> observations) {
        if (victim == null || !McaCompat.isMcaVillager(victim) || !ServerMutationGate.allows(level.getServer())
                || !McaCrimeConfig.COMMON.enableVictimMemory.get()) return;
        long now = level.getGameTime();
        // A blindfolded or sleeping victim cannot remember an identity the perception service withheld.
        boolean identified = !actor.isInvisible() && victim.hasLineOfSight(actor)
                && !victim.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS) && !McaCompat.isVillagerSleeping(victim);
        if (victim.isAlive() && identified) {
            remember(level.getServer(), victim.getUUID(), create(actor.getUUID(), victim.getUUID(), incident,
                    CrimeMemoryCategory.of(crime, robbery), now, awareness, 1, false), "created");
        }
        Set<UUID> family = McaCrimeConfig.COMMON.enableFamilyMemory.get() ? familyOf(victim) : Set.of();
        // Only actual identified observers receive secondary memories, including a dead victim's family.
        for (CrimeObservation observation : observations) {
            if (!observation.identifiesActor() || observation.observerId().equals(victim.getUUID())
                    || !(level.getEntity(observation.observerId()) instanceof LivingEntity observer)) continue;
            boolean relative = family.contains(observer.getUUID());
            if (!relative && awareness.severity() < 0.5) continue;
            double weight = relative ? McaCompat.getSpouseUuid(victim).filter(observer.getUUID()::equals).isPresent() ? 0.7 : 0.5 : 0.3;
            remember(level.getServer(), observer.getUUID(), create(actor.getUUID(), victim.getUUID(), incident,
                    relative ? CrimeMemoryCategory.FAMILY_HARM : CrimeMemoryCategory.WITNESSED,
                    now, awareness, weight, true), relative ? "family" : "witnessed");
        }
    }

    public static VictimCrimeMemory create(UUID actor, UUID victim, UUID incident, CrimeMemoryCategory category,
                                            long now, CrimeAwareness awareness, double weight, boolean indirect) {
        double fear = awareness.severity() * (awareness.violent() ? 1 : 0.3) * weight;
        double anger = Math.min(1, 0.25 + awareness.severity() * 0.75) * weight;
        return new VictimCrimeMemory(actor, incident, victim, category, now, now, awareness.memoryDays() * 24000L,
                awareness.severity() * weight, fear, anger, 1, indirect, false, false, false, 0);
    }

    public static void remember(MinecraftServer server, UUID villager, VictimCrimeMemory memory, String reason) {
        if (!ServerMutationGate.allows(server) || !McaCrimeConfig.COMMON.enableVictimMemory.get()) return;
        var data = CrimeWorldData.get(server);
        var profile = data.villagerProfile(villager, () -> new VillagerCrimeProfile(villager,
                new VillagerPurse(0, McaCrimeConfig.COMMON.muggingPurseCapacity.get(),
                        McaCrimeConfig.COMMON.muggingPurseDailyIncome.get(), memory.timestamp() / 24000)));
        if (profile == null) return;
        profile.remember(memory, McaCrimeConfig.COMMON.maximumMemoriesPerVillager.get(), memory.updatedAt(),
                McaCrimeConfig.COMMON.memoryDecayMultiplier.get());
        data.setDirty();
        profile.crimeMemories().stream().filter(m -> m.key().equals(memory.key())).findFirst()
                .ifPresent(m -> publish(villager, m, memory.updatedAt(), reason));
    }

    public static boolean canApologize(MinecraftServer server, UUID villager, UUID offender, long now) {
        return apologyStatus(server, villager, offender, now) == ApologyStatus.READY;
    }

    public static ApologyStatus apologyStatus(MinecraftServer server, UUID villager, UUID offender, long now) {
        if (server == null) return ApologyStatus.NOT_NEEDED;
        if (!McaCrimeConfig.COMMON.enableVictimMemory.get() || !McaCrimeConfig.COMMON.enableApologies.get())
            return ApologyStatus.DISABLED;
        return CrimeWorldData.get(server).villagerProfile(villager)
                .map(p -> ApologyStatus.evaluate(p.crimeMemories(), offender, now,
                        McaCrimeConfig.COMMON.apologyCooldownTicks.get()))
                .orElse(ApologyStatus.NOT_NEEDED);
    }

    public static boolean apologize(MinecraftServer server, UUID villager, UUID offender, long now) {
        if (!ServerMutationGate.allows(server) || !canApologize(server, villager, offender, now)) return false;
        var profile = CrimeWorldData.get(server).villagerProfile(villager).orElseThrow();
        for (var memory : profile.crimeMemories()) {
            if (!memory.perpetrator().equals(offender) || ApologyStatus.forMemory(memory, now,
                    McaCrimeConfig.COMMON.apologyCooldownTicks.get()) != ApologyStatus.READY) continue;
            var changed = memory.reconcile(now, McaCrimeConfig.COMMON.memoryDecayMultiplier.get(), true, false, false);
            profile.replaceMemory(changed); publish(villager, changed, now, "apology");
        }
        CrimeWorldData.get(server).setDirty();
        return true;
    }

    @SubscribeEvent
    public static void onResolved(CrimeRecordResolvedEvent event) {
        var record = event.getAfter(); var server = event.getPlayer().getServer();
        if (!ServerMutationGate.allows(server) || !McaCrimeConfig.COMMON.enableVictimMemory.get() || record.victimId().isEmpty()) return;
        boolean paid = record.resolution() == dev.otectus.mcacrime.ledger.Resolution.FINED
                && McaCrimeConfig.COMMON.enableMemoryRestitution.get();
        boolean served = record.resolution() == dev.otectus.mcacrime.ledger.Resolution.SERVED;
        if (!paid && !served) return;
        var data = CrimeWorldData.get(server);
        data.villagerProfile(record.victimId().get()).ifPresent(profile -> {
            for (var memory : profile.crimeMemories()) {
                // Settling an older offense must never forgive a newer offense merged into its category.
                if (!memory.incident().equals(record.id()) || !memory.perpetrator().equals(record.offenderId())) continue;
                boolean restitution = paid && (memory.category() == CrimeMemoryCategory.THEFT || memory.category() == CrimeMemoryCategory.ROBBERY);
                var changed = memory.reconcile(server.overworld().getGameTime(), McaCrimeConfig.COMMON.memoryDecayMultiplier.get(), false, restitution, served);
                profile.replaceMemory(changed); publish(profile.villager(), changed, server.overworld().getGameTime(), "reconciled");
            }
            data.setDirty();
        });
    }

    private static void publish(UUID villager, VictimCrimeMemory memory, long now, String reason) {
        dev.otectus.mcacrime.incident.IncidentNotifications.post(new VictimCrimeMemoryChangedEvent(villager,
                memory.view(now, McaCrimeConfig.COMMON.memoryDecayMultiplier.get()), reason));
        McaCrime.LOGGER.debug("Crime memory {} for {} ({})", reason, villager, memory.category());
    }
}
