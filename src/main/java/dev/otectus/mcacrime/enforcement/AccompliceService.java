package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.detect.WitnessModifiers;
import dev.otectus.mcacrime.relationship.FamilyGraph;
import dev.otectus.mcacrime.relationship.FamilyTier;
import dev.otectus.mcacrime.state.world.AccompliceRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live half of the accomplice system: who is helping right now, what their help is doing, and who
 * gets told when it goes wrong.
 *
 * <p>Everything with a clock on it lives here in memory — the posted effects, the warning throttle,
 * the window in which guards lose their grip on a fleeing player. The part that has to survive a
 * restart is the {@link AccompliceRecord} in world data, and only that part, because an agreement is
 * accountable and a shift is not.
 *
 * <p>The ticker is throttled and returns immediately when nobody has been recruited, which is the
 * state of nearly every world nearly all of the time.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class AccompliceService {

    /** Ticks between passes. A lookout's warning is worth a second of latency; nothing else here is urgent. */
    private static final int INTERVAL_TICKS = 20;

    /** The roles a record can carry, matching the action that created it. */
    public static final String ROLE_LOOKOUT = "lookout";
    public static final String ROLE_DISTRACTION = "distraction";
    public static final String ROLE_ESCAPE_HELP = "escape_help";

    /** accomplice id -> the last tick they warned somebody, so one guard is not announced every pass. */
    private static final Map<UUID, Long> LAST_WARNED = new ConcurrentHashMap<>();
    /** player id -> the tick their relative's interference stops working. */
    private static final Map<UUID, Long> ESCAPE_HELP = new ConcurrentHashMap<>();

    private static int counter;

    private AccompliceService() {
    }

    // ------------------------------------------------------------------ recruitment

    /**
     * Records that {@code relative} agreed to help {@code player} in {@code role} until {@code
     * expiresAt}, and returns the stored agreement.
     *
     * <p>Renewal rather than replacement: the same relative asked twice keeps their assist count, their
     * arrest count and any warrant already out for them. Forgetting those on a second job would make
     * asking again the cheapest way to launder a wanted accomplice.
     */
    @Nullable
    public static AccompliceRecord recruit(ServerPlayer player, LivingEntity relative, ServerLevel level,
                                           String role, long durationTicks, UUID incidentId) {
        MinecraftServer server = level.getServer();
        if (server == null || !ServerMutationGate.allows(server)) {
            return null;
        }
        long now = level.getGameTime();
        CrimeWorldData data = CrimeWorldData.get(server);
        AccompliceRecord existing = data.accomplice(relative.getUUID());
        AccompliceRecord record = existing == null
                ? new AccompliceRecord(relative.getUUID(), player.getUUID(), role,
                        incidentId == null ? "" : incidentId.toString(), now, now + durationTicks, false,
                        1, now, 0)
                : existing.renewed(role, incidentId == null ? "" : incidentId.toString(), now,
                        now + durationTicks);
        if (!data.putAccomplice(record).stored()) {
            return null;
        }
        CrimeDebug.crime("villager {} agreed to act as {} for {}", relative.getUUID(), role,
                player.getGameProfile().getName());
        return record;
    }

    // ------------------------------------------------------------------ effects

    /** Posts a lookout: the offender's witness radius shrinks for as long as the relative watches. */
    public static void postLookout(ServerPlayer player, LivingEntity relative, long now) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        WitnessModifiers.put(new WitnessModifiers.Modifier(player.getUUID(), relative.getUUID(),
                WitnessModifiers.Kind.LOOKOUT, 0.0D, c.lookoutWitnessRadiusMultiplier.get(),
                now + c.lookoutDurationTicks.get()));
    }

    /** Starts a distraction: civilians near the relative watch them instead of the player. */
    public static void startDistraction(ServerPlayer player, LivingEntity relative, long now) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        WitnessModifiers.put(new WitnessModifiers.Modifier(player.getUUID(), relative.getUUID(),
                WitnessModifiers.Kind.DISTRACTION, c.distractionRadius.get(), 1.0D,
                now + c.distractionDurationTicks.get()));
    }

    /**
     * Opens the escape-help window for this player, during which guards do not re-acquire them and a
     * restraint comes off faster.
     */
    public static void startEscapeHelp(ServerPlayer player, long now) {
        long until = now + McaCrimeConfig.COMMON.escapeHelpDurationTicks.get();
        ESCAPE_HELP.merge(player.getUUID(), until, Math::max);
    }

    /** Whether a relative is currently keeping the guards off this player. */
    public static boolean escapeHelpActive(UUID player, long now) {
        Long until = player == null ? null : ESCAPE_HELP.get(player);
        return until != null && until > now;
    }

    /**
     * What the work required to get out of a restraint is divided by for this player right now.
     *
     * <p>Expressed as a divisor on the requirement rather than as extra progress per tick, because
     * progress is counted in whole ticks: adding {@code escapeHelpEscapeBonus} to a per-tick integer
     * would round 0.5 up to a flat doubling, and the same setting would mean something different at
     * every value. Dividing the target is the same intent, continuously.
     */
    public static double escapeWorkDivisor(UUID player, long now) {
        return escapeHelpActive(player, now)
                ? 1.0D + Math.max(0.0D, McaCrimeConfig.COMMON.escapeHelpEscapeBonus.get())
                : 1.0D;
    }

    /** One-shot: every responder currently onto this player loses their target. */
    public static int shakeOffResponders(ServerPlayer player, ServerLevel level) {
        double radius = McaCrimeConfig.COMMON.guardAggroRadius.get();
        AABB box = player.getBoundingBox().inflate(radius);
        int cleared = 0;
        for (LivingEntity responder : level.getEntitiesOfClass(LivingEntity.class, box,
                EntitySelectors::isAvailableResponder)) {
            McaCompat.clearGuardTarget(responder, player);
            cleared++;
        }
        return cleared;
    }

    // ------------------------------------------------------------------ ticker

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++counter < INTERVAL_TICKS) {
            return;
        }
        counter = 0;
        tick(event.getServer());
    }

    /** One pass: expire the windows, prune the effects, and let the lookouts call out. */
    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        long now = server.overworld().getGameTime();
        ESCAPE_HELP.entrySet().removeIf(entry -> entry.getValue() <= now);
        for (WitnessModifiers.Modifier expired : WitnessModifiers.prune(now)) {
            // Told, rather than left to be noticed: the offender's cover changed and nothing on screen
            // would otherwise say so, which is the difference between a mechanic and a mystery.
            LivingEntity relative = null;
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(expired.accomplice()) instanceof LivingEntity found) {
                    relative = found;
                    break;
                }
            }
            if (relative != null && expired.kind() == WitnessModifiers.Kind.DISTRACTION) {
                // The scene is over: MCA gets its villager back rather than being left with one
                // standing where the distraction pointed it.
                McaCompat.releaseVillagerControl(relative);
            }
            ServerPlayer offender = server.getPlayerList().getPlayer(expired.offender());
            if (offender != null && relative != null) {
                offender.sendSystemMessage(Component.translatable("mcacrime.msg.accomplice.expired",
                        McaCompat.getVillagerDisplayName(relative)));
            }
        }
        LAST_WARNED.entrySet().removeIf(entry -> now - entry.getValue() > 24000L);
        if (!McaCrimeConfig.COMMON.enableAccomplices.get()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (WitnessModifiers.Modifier modifier : WitnessModifiers.active(player.getUUID(), now)) {
                if (modifier.kind() == WitnessModifiers.Kind.LOOKOUT) {
                    warnIfGuardApproaching(player, modifier.accomplice(), now);
                }
            }
        }
    }

    /**
     * "Somebody is coming." Sent only when a responder is genuinely inside the warning radius of the
     * lookout, and at most once per {@code lookoutWarnCooldownTicks}: a warning that fires every pass
     * is chat noise, and one that fires with nobody there is a lie the player will learn to ignore.
     */
    private static void warnIfGuardApproaching(ServerPlayer player, UUID accompliceId, long now) {
        int cooldown = McaCrimeConfig.COMMON.lookoutWarnCooldownTicks.get();
        Long last = LAST_WARNED.get(accompliceId);
        if (last != null && now - last < cooldown) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)
                || !(level.getEntity(accompliceId) instanceof LivingEntity lookout)) {
            return;
        }
        double radius = McaCrimeConfig.COMMON.lookoutWarnRadius.get();
        AABB box = lookout.getBoundingBox().inflate(radius);
        LivingEntity responder = null;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box,
                EntitySelectors::isAvailableResponder)) {
            responder = candidate;
            break;
        }
        if (responder == null) {
            return;
        }
        LAST_WARNED.put(accompliceId, now);
        player.sendSystemMessage(Component.translatable("mcacrime.msg.accomplice.lookout.warn",
                McaCompat.getVillagerDisplayName(lookout)));
    }

    // ------------------------------------------------------------------ notifications

    /**
     * Tells the accomplice's online relatives that they have been taken, or let go.
     *
     * <p>Scoped to family rather than broadcast: an arrest in a village nobody is related to is not
     * everybody's business, and the whole point of the message is that somebody who cares can go and
     * post bail.
     */
    public static void notifyFamily(MinecraftServer server, UUID villagerId, String key) {
        if (server == null || !McaCrimeConfig.COMMON.notifyFamilyOnArrest.get()) {
            return;
        }
        Entity villager = null;
        for (ServerLevel level : server.getAllLevels()) {
            villager = level.getEntity(villagerId);
            if (villager != null) {
                break;
            }
        }
        if (villager == null) {
            return;
        }
        Map<UUID, FamilyTier> relatives = FamilyGraph.relativesOf(villager,
                EnumSet.allOf(FamilyTier.class), McaCrimeConfig.COMMON.familyLoyaltyGenerations.get());
        Component name = McaCompat.getVillagerDisplayName(villager);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (relatives.containsKey(player.getUUID())) {
                player.sendSystemMessage(Component.translatable(key, name));
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        clearAll();
    }

    /** Drops every window, throttle and effect. Called on server stop and between worlds in a session. */
    public static void clearAll() {
        WitnessModifiers.clearAll();
        LAST_WARNED.clear();
        ESCAPE_HELP.clear();
        counter = 0;
    }
}
