package dev.otectus.mcacrime.captivity;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.ActionSessionManager;
import dev.otectus.mcacrime.action.CancelReason;
import dev.otectus.mcacrime.api.event.EntityKidnappedEvent;
import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.api.event.EntityReleasedFromCaptivityEvent;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.CrimeDetector;
import dev.otectus.mcacrime.detect.WitnessChecker;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.network.ActionProgressS2CPacket;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.CrimeAttachments;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.util.RandomSource;

import org.jetbrains.annotations.Nullable;
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

    /** How often the escape channel refreshes its bar, matching the action engine's cadence. */
    private static final int ESCAPE_PROGRESS_INTERVAL_TICKS = 5;

    /**
     * A stable bar id for one captive's escape work, derived from the custody record rather than
     * minted per tick, so a second attempt never inherits the first attempt's bar.
     */
    private static UUID escapeBarId(CustodyRecord record) {
        UUID captive = record.getCaptive();
        return new UUID(captive.getMostSignificantBits() ^ 0x65_73_63_61_70_65_00_01L,
                captive.getLeastSignificantBits());
    }

    private static void escapeBarEnded(ServerPlayer captive, CustodyRecord record, boolean succeeded, String key) {
        CrimeNetwork.sendActionProgress(captive, ActionProgressS2CPacket.ended(escapeBarId(record),
                "gui.mcacrime.action.escape",
                succeeded ? ActionProgressS2CPacket.Phase.FINISHED : ActionProgressS2CPacket.Phase.CANCELLED,
                key));
    }

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
        long alreadyHeld = CustodyRegistry.byOwner(server, captor.getUUID()).stream()
                .filter(existing -> !existing.isLawful()).count();
        if (alreadyHeld >= McaCrimeConfig.COMMON.maxUnlawfulCaptivesPerCaptor.get()) {
            return false; // commit-time invariant: a race cannot bypass the start check
        }
        boolean captiveIsPlayer = captiveEntity instanceof ServerPlayer;
        long start = CrimeAttachments.get(captor).getOnlineTicksLived();
        CustodyRecord record = new CustodyRecord(captiveUuid, captiveIsPlayer, false,
                CustodyOwner.kidnapper(captor.getUUID()), restraint, start,
                captiveEntity.blockPosition(), level.dimension().location());
        CrimeWorldData.get(server).putCustody(record);

        CrimeAttachments.get(captor).setHeldCaptiveRef(captiveUuid);
        ServerPlayer captivePlayer = captiveIsPlayer ? (ServerPlayer) captiveEntity : null;
        if (captivePlayer != null) {
            CrimeAttachments.get(captivePlayer).setHeldByRef(captor.getUUID());
        } else {
            McaCompat.leashTo(captiveEntity, captor); // best-effort physical hold for an NPC
        }

        // The captor is the criminal: commit the kidnap crime (Karma/Heat + ledger + witnessed events).
        CrimeDetector.commitDirect(captor, CrimeIds.KIDNAP, captiveEntity, level,
                WitnessChecker.resolve(level, captiveEntity), "custody");

        CrimeSounds.restrainApplied(captiveEntity);
        NeoForge.EVENT_BUS.post(new EntityKidnappedEvent(captiveUuid, captiveIsPlayer, captor.getUUID(),
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

    /**
     * Takes a player into <b>lawful</b> custody on behalf of an authority (spec §2.3).
     *
     * <p>Separate from {@link #capture} rather than a flag on it, because almost everything that method
     * does is wrong for an arrest. {@code capture} commits the {@code kidnap} crime <em>against the
     * captor</em>, counts against {@code maxUnlawfulCaptivesPerCaptor}, and leashes the captive — a
     * guard performing a lawful arrest must do none of those things, and a boolean parameter guarding
     * four unrelated behaviours would be a worse contract than two methods.
     *
     * <p>What the two share is the record: {@link CustodyOwner#guard} and {@link CustodyOwner#jail}
     * have existed since the custody table was written and until now were constructed only by tests,
     * because nothing in the mod ever took somebody into custody lawfully. This is what makes them
     * real.
     *
     * <p>The per-tick cost of a lawful record is a map lookup: {@code CrimeDecayHandler} enters
     * {@code CustodyService.tick} and {@code CustodyConfine.tick} because {@code heldByRef} is set, and
     * both return immediately on {@code record.isLawful()}. A lawful captive is not a kidnapping victim
     * and must not be subject to the escape work, the tether, or the real-time captivity cap; their
     * clock is the sentence.
     *
     * @return false when the player is already held by anybody, lawfully or not
     */
    public static boolean captureLawful(MinecraftServer server, ServerPlayer captive, CustodyOwner owner,
                                        BlockPos holdPos, ResourceLocation holdDim) {
        if (server == null || captive == null || owner == null) {
            return false;
        }
        UUID captiveUuid = captive.getUUID();
        if (CustodyRegistry.isCaptive(server, captiveUuid)) {
            return false;
        }
        long start = CrimeAttachments.get(captive).getOnlineTicksLived();
        CustodyRecord record = new CustodyRecord(captiveUuid, true, true, owner, RestraintType.NONE,
                start, holdPos, holdDim);
        CrimeWorldData.get(server).putCustody(record);
        CrimeAttachments.get(captive).setHeldByRef(owner.ownerUuid().orElse(captiveUuid));
        CrimeNetwork.sendSelfStatus(captive);
        CrimeNetwork.sendCaptiveStatus(captive);
        return true;
    }

    /**
     * Rewrites who holds a lawful captive, without releasing and re-taking them.
     *
     * <p>Used at the end of an escort: custody passes from the arresting guard to the jail itself, and
     * a release-then-capture would fire the public events twice and momentarily leave the player free.
     */
    public static boolean transferLawfulCustody(MinecraftServer server, UUID captiveUuid, CustodyOwner owner) {
        if (server == null || owner == null) {
            return false;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        CustodyRecord record = data.getCustody(captiveUuid);
        if (record == null || !record.isLawful()) {
            return false;
        }
        record.setOwner(owner);
        data.putCustody(record);
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
        data.removeRansom(captiveUuid);
        ActionSessionManager.clearFor(captiveUuid, CancelReason.TARGET_GONE);
        UUID formerCaptor = record.getOwner().ownerUuid().orElse(null);

        if (formerCaptor != null) {
            ServerPlayer captorPlayer = server.getPlayerList().getPlayer(formerCaptor);
            if (captorPlayer != null) {
                PlayerCrimeData captorData = CrimeAttachments.get(captorPlayer);
                if (captiveUuid.equals(captorData.getHeldCaptiveRef())) {
                    captorData.setHeldCaptiveRef(null);
                }
                CrimeNetwork.sendSelfStatus(captorPlayer);
            }
        }

        ServerPlayer captivePlayer = record.isCaptivePlayer() ? server.getPlayerList().getPlayer(captiveUuid) : null;
        if (captivePlayer != null) {
            CrimeAttachments.get(captivePlayer).setHeldByRef(null);
            CrimeNetwork.sendSelfStatus(captivePlayer);
            CrimeNetwork.sendCaptiveStatus(captivePlayer); // record already removed -> clears the client
            captivePlayer.sendSystemMessage(Component.translatable(releaseKey(reason)));
        } else if (!record.isCaptivePlayer()) {
            Entity npc = resolveEntity(server, record.getHoldDim(), captiveUuid);
            if (npc != null) {
                McaCompat.clearLeash(npc); // never delete the captive (§8.4) — only free it
            }
        }

        if (captivePlayer != null) {
            CrimeSounds.restraintRemoved(captivePlayer);
        }
        NeoForge.EVENT_BUS.post(new EntityReleasedFromCaptivityEvent(captiveUuid, record.isCaptivePlayer(),
                captivePlayer, formerCaptor, reason));
    }

    private static String releaseKey(CustodyReleaseReason reason) {
        return switch (reason) {
            case RESCUED -> "mcacrime.kidnap.released.rescued";
            case ESCAPED -> "mcacrime.kidnap.released.escaped";
            case CAPTOR_GONE -> "mcacrime.kidnap.released.captorgone";
            case CAPTIVITY_CAP -> "mcacrime.kidnap.released.cap";
            case ADMIN -> "mcacrime.kidnap.released.admin";
            case RELEASED_BY_CAPTOR -> "mcacrime.kidnap.released.captor";
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
        long now = captive.level().getGameTime();
        if (record.isEscapeActive()) {
            captive.sendSystemMessage(Component.translatable("mcacrime.captive.escape.already",
                    record.getEscapeProgress(), escapeWorkTicks(record.getRestraint())));
            return true;
        }
        if (now < record.getEscapeCooldownUntil()) {
            captive.sendSystemMessage(Component.translatable("mcacrime.captive.escape.cooldown",
                    record.getEscapeCooldownUntil() - now));
            return false;
        }
        if (escapeChance(record.getRestraint()) <= 0.0D) {
            captive.sendSystemMessage(Component.translatable("mcacrime.captive.escape.locked"));
            return false;
        }
        int attempt = record.getEscapeAttempts() + 1;
        record.setEscapeAttempts(attempt);
        record.setEscapeProgress(0);
        record.setEscapeActive(true);
        record.setEscapeCooldownUntil(now + McaCrimeConfig.COMMON.escapeAttemptCooldownTicks.get());
        long seed = captive.getUUID().getMostSignificantBits() ^ captive.getUUID().getLeastSignificantBits()
                ^ record.getStartTickOnline() ^ ((long) attempt * 0x9E3779B97F4A7C15L);
        record.setEscapeRoll(RandomSource.create(seed).nextDouble());
        CrimeWorldData.get(server).setDirty();
        captive.sendSystemMessage(Component.translatable("mcacrime.captive.escape.started",
                escapeWorkTicks(record.getRestraint())));
        return true;
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
        UUID captorId = record.getOwner().ownerUuid().orElse(null);
        if (record.isCaptivePlayer() && captorId != null) {
            ServerPlayer captor = server.getPlayerList().getPlayer(captorId);
            long now = captive.level().getGameTime();
            if (captor != null) {
                if (record.getCaptorDisconnectedAt() != 0L) record.setCaptorDisconnectedAt(0L);
            } else {
                if (record.getCaptorDisconnectedAt() == 0L) record.setCaptorDisconnectedAt(now);
                long grace = McaCrimeConfig.COMMON.captorDisconnectGraceTicks.get();
                if (grace == 0L || now - record.getCaptorDisconnectedAt() >= grace) {
                    release(server, captive.getUUID(), CustodyReleaseReason.CAPTOR_GONE);
                    return;
                }
            }
        }
        if (record.isEscapeActive()) {
            int progress = record.getEscapeProgress() + 1;
            record.setEscapeProgress(progress);
            int required = escapeWorkTicks(record.getRestraint());
            if (progress >= required) {
                record.setEscapeActive(false);
                record.setEscapeProgress(0);
                if (record.getEscapeRoll() < escapeChance(record.getRestraint())) {
                    CrimeSounds.restraintBroken(captive);
                    escapeBarEnded(captive, record, true, "mcacrime.kidnap.released.escaped");
                    release(server, captive.getUUID(), CustodyReleaseReason.ESCAPED);
                    return;
                }
                escapeBarEnded(captive, record, false, "mcacrime.captive.escape.failed");
                captive.sendSystemMessage(Component.translatable("mcacrime.captive.escape.failed"));
            } else if (progress % ESCAPE_PROGRESS_INTERVAL_TICKS == 0) {
                // The HUD channel bar, not an action-bar line that overwrites itself every second.
                CrimeNetwork.sendActionProgress(captive, new ActionProgressS2CPacket(escapeBarId(record),
                        "gui.mcacrime.action.escape", progress, required,
                        ActionProgressS2CPacket.Phase.PROGRESS, ""));
            }
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
        PlayerCrimeData data = CrimeAttachments.get(player);
        CustodyRecord asCaptive = world.getCustody(player.getUUID());
        if (asCaptive != null && !asCaptive.isLawful()) {
            UUID captor = asCaptive.getOwner().ownerUuid().orElse(null);
            if (captor == null) {
                release(server, player.getUUID(), CustodyReleaseReason.CAPTOR_GONE);
            } else {
                data.setHeldByRef(captor); // table is authoritative
            }
        } else {
            if (data.getHeldByRef() != null) {
                data.setHeldByRef(null); // no longer a kidnapping captive
            }
        }
        java.util.List<CustodyRecord> owned = CustodyRegistry.byOwner(server, player.getUUID()).stream()
                .filter(record -> !record.isLawful()).toList();
        data.setHeldCaptiveRef(owned.isEmpty() ? null : owned.get(0).getCaptive());
        // Legacy builds could orphan several records behind one scalar cache. Keep the oldest/first
        // authoritative record and release the ambiguous extras instead of hiding them forever.
        for (int i = 1; i < owned.size(); i++) {
            release(server, owned.get(i).getCaptive(), CustodyReleaseReason.ADMIN);
        }
        // If this player is a returning captor, clear their disconnect stamps.
        for (CustodyRecord held : CustodyRegistry.byOwner(server, player.getUUID())) {
            if (held.getCaptorDisconnectedAt() != 0L) {
                held.setCaptorDisconnectedAt(0L);
                world.setDirty();
            }
        }
    }

    public static void onCaptorLogout(ServerPlayer captor) {
        MinecraftServer server = captor.getServer();
        if (server == null) return;
        CrimeWorldData world = CrimeWorldData.get(server);
        long now = captor.level().getGameTime();
        for (CustodyRecord held : CustodyRegistry.byOwner(server, captor.getUUID())) {
            if (!held.isLawful() && held.isCaptivePlayer() && held.getCaptorDisconnectedAt() == 0L) {
                held.setCaptorDisconnectedAt(now);
                world.setDirty();
            }
        }
    }

    public static void interruptEscape(UUID captive, MinecraftServer server) {
        if (server == null) return;
        CustodyRecord record = CrimeWorldData.get(server).getCustody(captive);
        if (record != null && record.isEscapeActive()) {
            record.setEscapeActive(false);
            record.setEscapeProgress(0);
            CrimeWorldData.get(server).setDirty();
        }
    }

    private static int escapeWorkTicks(RestraintType restraint) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        return switch (restraint) {
            case NONE -> 1;
            case ROPE -> c.escapeWorkTicksRope.get();
            case CUFFS -> c.escapeWorkTicksCuffs.get();
            case LOCKED_CUFFS -> Integer.MAX_VALUE;
        };
    }

    @Nullable
    // ------------------------------------------------------------------ NPC captives (§14.5 virtualization)

    /**
     * Advances every NPC captivity, and implements {@code npcCaptiveVirtualizeWhenUnloaded}.
     *
     * <p>This closes a real hole rather than adding a feature. Only <em>player</em> captives were ever
     * ticked, through {@code CrimeDecayHandler}, so a kidnapped villager's real-time cap never advanced
     * at all: the captivity that was supposed to end within {@code maxCaptivityRealMinutes} instead
     * lasted until somebody released it, and if the captor walked away and the chunk unloaded, it
     * lasted forever as an invisible row in world data.
     *
     * <p>The setting decides what happens when the captive's chunk is not loaded. On, the record is
     * marked virtual and keeps counting — captivity continues while nobody is looking, which is what
     * the key describes. Off, the captivity ends, because §13.3 is explicit that the alternative must
     * never be an invisible permanent custody record.
     *
     * @param elapsedTicks how many ticks to credit; the caller batches these rather than running every tick
     */
    public static void tickNpcCaptives(MinecraftServer server, long elapsedTicks) {
        if (server == null || elapsedTicks <= 0L) {
            return;
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        boolean virtualize = McaCrimeConfig.COMMON.npcCaptiveVirtualizeWhenUnloaded.get();
        long capTicks = (long) McaCrimeConfig.COMMON.maxCaptivityRealMinutes.get() * 1200L;
        boolean dirty = false;

        for (CustodyRecord record : world.custodyRecords()) {
            if (record.isCaptivePlayer() || record.isLawful()) {
                continue; // player captives tick with their own player; lawful custody is JailService's
            }
            Entity npc = resolveEntity(server, record.getHoldDim(), record.getCaptive());
            if (npc == null) {
                if (!virtualize) {
                    release(server, record.getCaptive(), CustodyReleaseReason.CAPTOR_GONE);
                    continue;
                }
                if (!record.isVirtual()) {
                    record.setVirtual(true);
                    dirty = true;
                }
            } else if (record.isVirtual()) {
                // Back in a loaded chunk. Re-secure it: a leash does not survive being reloaded from a
                // record the entity itself knows nothing about.
                record.setVirtual(false);
                dirty = true;
                UUID captor = record.getOwner().ownerUuid().orElse(null);
                ServerPlayer holder = captor == null ? null : server.getPlayerList().getPlayer(captor);
                if (holder != null) {
                    McaCompat.leashTo(npc, holder);
                }
            }

            record.setRealTicksHeld(record.getRealTicksHeld() + elapsedTicks);
            dirty = true;
            if (capTicks > 0L && record.getRealTicksHeld() >= capTicks) {
                release(server, record.getCaptive(), CustodyReleaseReason.CAPTIVITY_CAP);
            }
        }
        if (dirty) {
            world.setDirty();
        }
    }

    private static Entity resolveEntity(MinecraftServer server, @Nullable ResourceLocation dim, UUID uuid) {
        ServerLevel level = JailService.resolveLevel(server, dim);
        return level == null ? null : level.getEntity(uuid);
    }
}
