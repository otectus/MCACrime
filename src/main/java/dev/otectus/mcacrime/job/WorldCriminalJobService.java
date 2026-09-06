package dev.otectus.mcacrime.job;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.CriminalJobChangedEvent;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.world.CriminalVillagerRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@link CriminalJobService} over {@code CrimeWorldData} (0.5.1) — the only writer of criminal jobs.
 *
 * <p>Two facts are kept, and only one of them is authoritative. The record in the world data is what
 * makes a villager a thief; the MCA-visible profession is a label this mod may additionally hang on
 * them, per job, per the {@code presentFenceAsMcaProfession} / {@code presentThiefAsMcaProfession}
 * keys. A fence is discoverable by default because a fence nobody can find is not a shop; a thief is
 * not, because a thief wearing a "Thief" sign has no cover.
 *
 * <p>The label is reversible. The profession the villager had before is persisted with the record, so
 * clearing the job — or an operator turning the key off and reloading — puts the cleric back to being
 * a cleric instead of leaving the village permanently short one because a setting changed once.
 */
public final class WorldCriminalJobService implements CriminalJobService {

    private final MinecraftServer server;

    private WorldCriminalJobService(MinecraftServer server) {
        this.server = server;
    }

    public static WorldCriminalJobService of(MinecraftServer server) {
        return new WorldCriminalJobService(server);
    }

    @Override
    public CriminalJob get(UUID villager) {
        CriminalVillagerRecord record = raw(villager);
        return record == null ? CriminalJob.NONE : record.job();
    }

    @Override
    public boolean isCriminal(UUID villager) {
        return get(villager) != CriminalJob.NONE;
    }

    @Override
    public Optional<CriminalVillagerRecord> record(UUID villager) {
        return Optional.ofNullable(raw(villager));
    }

    @Override
    public Collection<CriminalVillagerRecord> all() {
        return server == null ? List.of() : CrimeWorldData.get(server).criminalVillagers();
    }

    @Override
    public void set(UUID villager, CriminalJob job) {
        assign(villager, job, false);
    }

    /**
     * Assigns a job, remembering whether the villager was found outside any village.
     *
     * <p>{@code wildOrigin} is preserved rather than overwritten when a criminal changes job: a thief
     * who was born in the wilderness and later becomes a fence did not acquire a home village by
     * changing trade.
     */
    public void assign(UUID villager, CriminalJob job, boolean wildOrigin) {
        if (server == null || villager == null || job == null || !ServerMutationGate.allows(server)) {
            return;
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        CriminalVillagerRecord existing = world.criminalVillager(villager);
        CriminalJob previous = existing == null ? CriminalJob.NONE : existing.job();
        if (previous == job) {
            return;
        }
        long day = day();
        if (job == CriminalJob.NONE) {
            // Reverted first: once the record is gone there is nowhere left to read what this villager
            // used to do for a living.
            revertPresentation(villager, existing);
            world.removeCriminalVillager(villager);
            CrimeDebug.crime("Criminal job cleared for {} (was {})", villager, previous);
        } else {
            CriminalVillagerRecord record = new CriminalVillagerRecord(villager, job,
                    existing == null ? day : existing.assignedDay(),
                    existing == null ? 0L : existing.lastMugAt(),
                    day,
                    existing == null ? wildOrigin : existing.wildOrigin(),
                    existing == null ? seedFor(villager) : existing.personalitySeed(),
                    existing == null ? null : existing.previousProfessionId());
            world.putCriminalVillager(record);
            applyPresentation(villager);
            CrimeDebug.crime("Criminal job {} assigned to {} (wild={})", job, villager, record.wildOrigin());
        }
        NeoForge.EVENT_BUS.post(new CriminalJobChangedEvent(villager, previous, job));
        broadcast(villager, job);
    }

    @Override
    public void touchMug(UUID villager, long now) {
        if (server == null || villager == null) {
            return;
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        CriminalVillagerRecord existing = world.criminalVillager(villager);
        if (existing == null) {
            return;
        }
        world.putCriminalVillager(new CriminalVillagerRecord(existing.villager(), existing.job(),
                existing.assignedDay(), now, existing.lastSeenDay(), existing.wildOrigin(),
                existing.personalitySeed(), existing.previousProfessionId()));
    }

    /** Stamps that this criminal was seen loaded today, so a later sweep can retire stale records. */
    public void touchSeen(UUID villager, long day) {
        if (server == null || villager == null) {
            return;
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        CriminalVillagerRecord existing = world.criminalVillager(villager);
        if (existing == null || existing.lastSeenDay() == day) {
            return;
        }
        world.putCriminalVillager(new CriminalVillagerRecord(existing.villager(), existing.job(),
                existing.assignedDay(), existing.lastMugAt(), day, existing.wildOrigin(),
                existing.personalitySeed(), existing.previousProfessionId()));
    }

    /**
     * Re-decides the visible profession of every loaded criminal against the current config.
     *
     * <p>Called on config reload, because both presentation keys are answers to a question that is
     * otherwise asked once per villager: a pack that turns the thief label on wants the thieves it
     * already has to wear it, and a pack that turns the fence label off wants its shopkeepers back.
     */
    public void refreshPresentation() {
        if (server == null) {
            return;
        }
        for (CriminalVillagerRecord record : all()) {
            if (present(record.job())) {
                applyPresentation(record.villager());
            } else {
                revertPresentation(record.villager(), record);
            }
        }
    }

    /**
     * Puts this mod's profession on a criminal, saving whatever they were doing before.
     *
     * <p>No-op when the villager is unloaded: presentation is a property of an entity that exists, and
     * the record is re-examined by {@link #refreshPresentation()} when it comes back.
     */
    private void applyPresentation(UUID villager) {
        CriminalVillagerRecord record = raw(villager);
        if (record == null || !present(record.job())) {
            return;
        }
        ResourceLocation target = CriminalProfessions.professionIdFor(record.job());
        Entity entity = findLoaded(villager);
        if (target == null || entity == null) {
            return;
        }
        ResourceLocation current = McaCompat.getProfessionId(entity).orElse(null);
        if (target.equals(current)) {
            return;
        }
        if (!McaCompat.setVillagerProfession(entity, target)) {
            CrimeDebug.compat("MCA has no profession setter bound; {} keeps its visible profession", villager);
            return;
        }
        // An empty string means "had no readable profession", which is still a fact worth persisting:
        // it is the difference between reverting to unemployed and never reverting at all.
        CrimeWorldData.get(server).putCriminalVillager(new CriminalVillagerRecord(record.villager(),
                record.job(), record.assignedDay(), record.lastMugAt(), record.lastSeenDay(),
                record.wildOrigin(), record.personalitySeed(),
                current == null ? "" : current.toString()));
    }

    /** Puts back what the villager was doing before this mod relabelled them, if anything. */
    private void revertPresentation(UUID villager, @Nullable CriminalVillagerRecord record) {
        if (record == null || record.previousProfessionId() == null) {
            return;
        }
        Entity entity = findLoaded(villager);
        if (entity != null) {
            ResourceLocation previous = record.previousProfessionId().isBlank()
                    ? ResourceLocation.fromNamespaceAndPath("minecraft", "none")
                    : ResourceLocation.tryParse(record.previousProfessionId());
            if (previous != null) {
                McaCompat.setVillagerProfession(entity, previous);
            }
        }
        if (raw(villager) != null) {
            CrimeWorldData.get(server).putCriminalVillager(new CriminalVillagerRecord(record.villager(),
                    record.job(), record.assignedDay(), record.lastMugAt(), record.lastSeenDay(),
                    record.wildOrigin(), record.personalitySeed(), null));
        }
    }

    /** Whether this job is shown to players as an MCA profession at all. Per job, per spec. */
    private static boolean present(CriminalJob job) {
        return switch (job) {
            case FENCE -> McaCrimeConfig.COMMON.presentFenceAsMcaProfession.get();
            case THIEF -> McaCrimeConfig.COMMON.presentThiefAsMcaProfession.get();
            case NONE -> false;
        };
    }

    private void broadcast(UUID villager, CriminalJob job) {
        Entity entity = findLoaded(villager);
        if (entity != null) {
            CrimeNetwork.broadcastCriminalJob(entity, job);
        }
    }

    @Nullable
    private CriminalVillagerRecord raw(UUID villager) {
        return server == null || villager == null ? null : CrimeWorldData.get(server).criminalVillager(villager);
    }

    @Nullable
    private Entity findLoaded(UUID villager) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(villager);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    /**
     * A stable per-villager seed for behaviour that should differ between two thieves but never
     * between two sessions of the same one. Derived from the UUID, so it survives a record rewrite.
     */
    private static long seedFor(UUID villager) {
        return villager.getMostSignificantBits() ^ villager.getLeastSignificantBits();
    }

    private long day() {
        return server.overworld().getGameTime() / 24000L;
    }
}
