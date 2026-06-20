package dev.otectus.mcacrime.captivity;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.EntityKidnappedEvent;
import dev.otectus.mcacrime.api.event.EntityReleasedFromCaptivityEvent;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.CrimeDetector;
import dev.otectus.mcacrime.detect.WitnessChecker;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.CrimeCapabilities;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.MinecraftForge;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * The server-authoritative, idempotent custody state machine for <b>unlawful</b> captivity — kidnapping
 * (spec §8). The structural twin of {@link JailService}: the sole writer of kidnapping {@link
 * CustodyRecord}s and of the {@code heldCaptiveRef}/{@code heldByRef} player pointers, with a guarded
 * {@link #release} (double-release is a no-op) and the pure {@link #advanceTick} real-time cap that makes a
 * softlock unreachable — an online captive is always freed within {@code maxCaptivityRealMinutes}, and
 * admin {@link #release} always works.
 *
 * <p>Lawful jail stays owned by {@code JailService}; the two never conflate (spec §1.4). Only the {@code
 * lawful} flag distinguishes them, and escaping kidnapping here is never a crime (contrast {@code
 * JailConfine}, which commits {@code jailbreak} on a physical jail escape — spec §8.1).
 */
public final class CustodyService {

    private CustodyService() {
    }

    // ------------------------------------------------------------------ queries

    public static boolean isCaptive(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server != null && CustodyRegistry.isCaptive(server, player.getUUID());
    }

    // ------------------------------------------------------------------ capture

    /**
     * Takes {@code captiveEntity} into unlawful captivity by {@code captor}. Idempotent: a no-op (false) if
     * the target is already held or is the captor. Commits the {@code kidnap} crime against the captor (the
     * victim is never penalised), sets the held refs, secures an NPC via leash, fires {@link
     * EntityKidnappedEvent}, and syncs. <b>Server side only.</b>
     */
    public static boolean capture(ServerPlayer captor, LivingEntity captiveEntity, RestraintType restraint) {
        MinecraftServer server = captor.getServer();
        if (server == null || !(captiveEntity.level() instanceof ServerLevel level)) {
            return false;
        }
        UUID captiveUuid = captiveEntity.getUUID();
        if (captiveUuid.equals(captor.getUUID()) || CustodyRegistry.isCaptive(server, captiveUuid)) {
            return false; // no self-capture, no double-capture
        }
        boolean captiveIsPlayer = captiveEntity instanceof ServerPlayer;
        long start = CrimeCapabilities.get(captor).map(PlayerCrimeData::getOnlineTicksLived).orElse(0L);
        CustodyRecord record = new CustodyRecord(captiveUuid, captiveIsPlayer, false,
                CustodyOwner.kidnapper(captor.getUUID()), restraint, start,
                captiveEntity.blockPosition(), level.dimension().location());
        CrimeWorldData.get(server).putCustody(record);

        CrimeCapabilities.get(captor).ifPresent(d -> d.setHeldCaptiveRef(captiveUuid));
        ServerPlayer captivePlayer = captiveIsPlayer ? (ServerPlayer) captiveEntity : null;
        if (captivePlayer != null) {
            CrimeCapabilities.get(captivePlayer).ifPresent(d -> d.setHeldByRef(captor.getUUID()));
        } else {
            McaCompat.leashTo(captiveEntity, captor); // best-effort physical hold for an NPC
        }

        // The captor is the criminal: commit the kidnap crime (Karma/Heat + ledger + witnessed events).
        int witnesses = WitnessChecker.countWitnesses(level, captiveEntity);
        CrimeDetector.commitDirect(captor, CrimeIds.KIDNAP, captiveEntity, level, witnesses > 0, witnesses);

        MinecraftForge.EVENT_BUS.post(new EntityKidnappedEvent(captiveUuid, captiveIsPlayer, captor.getUUID(),
                true, restraint, captivePlayer, captor));

        CrimeNetwork.sendSelfStatus(captor); // captor is now an active kidnapper -> Legal Target
        captor.sendSystemMessage(Component.translatable("mcacrime.kidnap.holding",
                McaCompat.getVillagerDisplayName(captiveEntity)));
        if (captivePlayer != null) {
            CrimeNetwork.sendSelfStatus(captivePlayer);
            CrimeNetwork.sendCaptiveStatus(captivePlayer);
            captivePlayer.sendSystemMessage(Component.translatable("mcacrime.kidnap.taken", captor.getDisplayName()));
        }
        return true;
    }

    // ------------------------------------------------------------------ release (idempotent)

    public static void release(MinecraftServer server, UUID captiveUuid, CustodyReleaseReason reason) {
        CrimeWorldData data = CrimeWorldData.get(server);
        CustodyRecord record = data.getCustody(captiveUuid);
        if (record == null) {
            return; // the guard that makes double-release a no-op
        }
        data.removeCustody(captiveUuid);
        UUID formerCaptor = record.getOwner().ownerUuid().orElse(null);

        if (formerCaptor != null) {
            ServerPlayer captorPlayer = server.getPlayerList().getPlayer(formerCaptor);
            if (captorPlayer != null) {
                CrimeCapabilities.get(captorPlayer).ifPresent(d -> {
                    if (captiveUuid.equals(d.getHeldCaptiveRef())) {
                        d.setHeldCaptiveRef(null);
                    }
                });
                CrimeNetwork.sendSelfStatus(captorPlayer);
            }
        }

        ServerPlayer captivePlayer = record.isCaptivePlayer() ? server.getPlayerList().getPlayer(captiveUuid) : null;
        if (captivePlayer != null) {
            CrimeCapabilities.get(captivePlayer).ifPresent(d -> d.setHeldByRef(null));
            CrimeNetwork.sendSelfStatus(captivePlayer);
            CrimeNetwork.sendCaptiveStatus(captivePlayer); // record already removed -> clears the client
            captivePlayer.sendSystemMessage(Component.translatable(releaseKey(reason)));
        } else if (!record.isCaptivePlayer()) {
            Entity npc = resolveEntity(server, record.getHoldDim(), captiveUuid);
            if (npc != null) {
                McaCompat.clearLeash(npc); // never delete the captive (§8.4) — only free it
            }
        }

        MinecraftForge.EVENT_BUS.post(new EntityReleasedFromCaptivityEvent(captiveUuid, record.isCaptivePlayer(),
                captivePlayer, formerCaptor, reason));
    }

    private static String releaseKey(CustodyReleaseReason reason) {
        return switch (reason) {
            case RESCUED -> "mcacrime.kidnap.released.rescued";
            case ESCAPED -> "mcacrime.kidnap.released.escaped";
            case CAPTOR_GONE -> "mcacrime.kidnap.released.captorgone";
            case CAPTIVITY_CAP -> "mcacrime.kidnap.released.cap";
            case ADMIN -> "mcacrime.kidnap.released.admin";
            case RANSOM_PAID -> "mcacrime.kidnap.released.ransom";
            case SENTENCE_SERVED -> "mcacrime.kidnap.released.served";
            case CAPTIVE_DIED -> "mcacrime.kidnap.released.died";
        };
    }

    // ------------------------------------------------------------------ escape (kidnapping only; never a crime)

    /**
     * A captive's server-validated escape attempt. Escaping kidnapping is never a crime (spec §8.1): on
     * success the captive is released with no Heat/bounty/karma loss. Lawful jail is not escapable here
     * (that path is {@code JailConfine}). Returns true if the captive broke free.
     */
    public static boolean attemptEscape(ServerPlayer captive) {
        MinecraftServer server = captive.getServer();
        if (server == null) {
            return false;
        }
        CustodyRecord record = CrimeWorldData.get(server).getCustody(captive.getUUID());
        if (record == null || record.isLawful()) {
            return false;
        }
        double chance = escapeChance(record.getRestraint());
        if (chance > 0.0 && captive.getRandom().nextDouble() < chance) {
            release(server, captive.getUUID(), CustodyReleaseReason.ESCAPED);
            return true;
        }
        captive.sendSystemMessage(Component.translatable("mcacrime.captive.escape.failed"));
        return false;
    }

    private static double escapeChance(RestraintType restraint) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        return switch (restraint) {
            case NONE -> 1.0;
            case ROPE -> c.restraintEscapeChanceRope.get();
            case CUFFS -> c.restraintEscapeChanceCuffs.get();
            case LOCKED_CUFFS -> c.restraintEscapeChanceLockedCuffs.get();
        };
    }

    // ------------------------------------------------------------------ per-tick cap (from CrimeDecayHandler)

    /** Per-tick cap accounting for an online PLAYER kidnapping captive; releases at the real-time cap (§7.2). */
    public static void tick(ServerPlayer captive) {
        MinecraftServer server = captive.getServer();
        if (server == null) {
            return;
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        CustodyRecord record = world.getCustody(captive.getUUID());
        if (record == null || record.isLawful()) {
            return; // lawful jail cap is JailService's job
        }
        long capTicks = (long) McaCrimeConfig.COMMON.maxCaptivityRealMinutes.get() * 1200L;
        CustodyReleaseReason due = advanceTick(record, capTicks);
        world.setDirty();
        if (due != null) {
            release(server, captive.getUUID(), due);
        }
    }

    /**
     * Pure one-tick advance: accumulates real held time and returns {@link CustodyReleaseReason#CAPTIVITY_CAP}
     * when the cap is reached, else null. No game/config deps, so the cap backstop is unit-testable.
     */
    public static CustodyReleaseReason advanceTick(CustodyRecord record, long capTicks) {
        record.setRealTicksHeld(record.getRealTicksHeld() + 1L);
        if (capTicks > 0L && record.getRealTicksHeld() >= capTicks) {
            return CustodyReleaseReason.CAPTIVITY_CAP;
        }
        return null;
    }

    // ------------------------------------------------------------------ login reconcile (no softlock)

    /**
     * On login, reconcile a player's captive/captor pointers against the authoritative table and guarantee
     * no softlock: a captive whose captor reference is gone is freed (§8.4); the real-time cap and admin
     * release remain the backstops for an offline-but-valid captor. A captor's dangling held-ref is cleared.
     */
    public static void reconcileOnLogin(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        Optional<PlayerCrimeData> dataOpt = CrimeCapabilities.get(player);
        CustodyRecord asCaptive = world.getCustody(player.getUUID());
        if (asCaptive != null && !asCaptive.isLawful()) {
            UUID captor = asCaptive.getOwner().ownerUuid().orElse(null);
            if (captor == null) {
                release(server, player.getUUID(), CustodyReleaseReason.CAPTOR_GONE);
            } else {
                dataOpt.ifPresent(d -> d.setHeldByRef(captor)); // table is authoritative
            }
        } else {
            dataOpt.ifPresent(d -> {
                if (d.getHeldByRef() != null) {
                    d.setHeldByRef(null); // no longer a kidnapping captive
                }
            });
        }
        dataOpt.ifPresent(d -> {
            UUID held = d.getHeldCaptiveRef();
            if (held != null && !world.isCaptive(held)) {
                d.setHeldCaptiveRef(null); // captor's pointer to a captive that no longer exists
            }
        });
    }

    @Nullable
    private static Entity resolveEntity(MinecraftServer server, @Nullable ResourceLocation dim, UUID uuid) {
        ServerLevel level = JailService.resolveLevel(server, dim);
        return level == null ? null : level.getEntity(uuid);
    }
}
