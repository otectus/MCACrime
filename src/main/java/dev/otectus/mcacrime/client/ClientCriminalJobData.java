package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.job.CriminalJob;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of the criminal jobs of the villagers this client is tracking (0.5.1).
 *
 * <p>Fed by the server when tracking starts and whenever a job changes; the client never decides one.
 * The Crime button is the only reader today: a fence is the one villager an unarmed player may open
 * the menu on, so the button has to know before the click which villager that is.
 */
public final class ClientCriminalJobData {

    private static final Map<UUID, CriminalJob> JOBS = new ConcurrentHashMap<>();

    private ClientCriminalJobData() {
    }

    /** This villager's job, or {@link CriminalJob#NONE} for anybody we have not been told about. */
    public static CriminalJob job(UUID villager) {
        CriminalJob job = villager == null ? null : JOBS.get(villager);
        return job == null ? CriminalJob.NONE : job;
    }

    public static void put(UUID villager, CriminalJob job) {
        if (villager == null || job == null || job == CriminalJob.NONE) {
            remove(villager);
            return;
        }
        JOBS.put(villager, job);
    }

    public static void remove(UUID villager) {
        if (villager != null) {
            JOBS.remove(villager);
        }
    }

    public static void clear() {
        JOBS.clear();
    }
}
