package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.compat.TownsteadBridge;
import dev.otectus.mcacrime.compat.TownsteadCapability;
import dev.otectus.mcacrime.compat.TownsteadLifeStageView;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a restrained villager is being drawn on a body that has wrists.
 *
 * <h2>Why the renderer has to ask</h2>
 *
 * <p>MCA: Crime's cuffs are two bands parented to a {@code HumanoidModel}'s arms. That holds for every
 * player and every MCA villager, which is why the layer was written that way. Townstead breaks the
 * assumption from underneath: a life stage may declare its own rig, so a villager can be rendered as
 * an egg, a grub or something with no arms at all — and the cuffs would then be drawn where the arms
 * would have been, floating in the air beside a body that has none.
 *
 * <p>So the layer asks here, and draws a band sized from the hitbox instead when the answer is no.
 *
 * <h2>Why it is cached, and cached this way</h2>
 *
 * <p>The answer comes from the Townstead bridge, which is reflection over another mod's data. That is
 * cheap for a command and much too expensive for a render pass that runs for every restrained entity
 * on every frame. A life stage changes on the order of in-game days, so the value is held for five
 * seconds and re-read after that — long enough that the cost disappears, short enough that a stage
 * change is picked up while the player is still looking at the villager.
 *
 * <h2>Where the answer comes from on a remote server</h2>
 *
 * <p>The bridge does not exist on a multiplayer client — it binds on {@code ServerStartedEvent}, which
 * never runs there — so asking it would answer "humanoid" for everybody, correct for almost all
 * villagers and wrong for exactly the ones the check was written for. The server therefore tells the
 * client instead, through {@code RestraintRigSyncS2CPacket}, when a restraint is applied and when a
 * client starts tracking a restrained subject.
 *
 * <p>A pushed answer wins over a local one and does not expire, because it came from the side that can
 * actually see the life stage; the local query remains as the single-player path and as the fallback
 * for a subject the server has not spoken about yet. Uncertainty still resolves to "humanoid" in every
 * direction: a wrong "humanoid" costs a cosmetic oddity on an unusual body, while a wrong "not
 * humanoid" would replace the cuffs with a band for every ordinary villager on the server.
 */
public final class ClientRestraintRig {

    /** How long one answer is trusted. Five seconds at twenty ticks. */
    private static final long REFRESH_TICKS = 100L;

    /** A ceiling, not a target: restrained entities are counted on one hand in practice. */
    private static final int MAX_CACHED = 512;

    private record Cached(boolean humanoid, long at) {
    }

    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();

    /** What the server said, per subject. No expiry: it is authoritative until it is corrected. */
    private static final Map<UUID, Pushed> PUSHED = new ConcurrentHashMap<>();

    /**
     * One server-supplied answer.
     *
     * @param humanoid whether the cuffs fit
     * @param rig      the rig id, kept for diagnostics and for a layer that later wants more than a flag
     */
    public record Pushed(boolean humanoid, String rig) {

        public Pushed {
            rig = rig == null ? "" : rig;
        }
    }

    private ClientRestraintRig() {
    }

    /**
     * Records what the server said about one subject's rig.
     *
     * <p>Also drops the locally-derived cache entry for them, so a stale guess made a moment earlier
     * cannot outlive the real answer.
     */
    public static void accept(UUID subject, boolean humanoid, String rig) {
        if (subject == null) {
            return;
        }
        if (PUSHED.size() >= MAX_CACHED && !PUSHED.containsKey(subject)) {
            PUSHED.keySet().stream().findFirst().ifPresent(PUSHED::remove);
        }
        PUSHED.put(subject, new Pushed(humanoid, rig));
        CACHE.remove(subject);
    }

    /** What the server said about this subject, if it has said anything. */
    public static java.util.Optional<Pushed> pushed(UUID subject) {
        return subject == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(PUSHED.get(subject));
    }

    /**
     * Whether this entity's Townstead rig has arms the cuffs can sit on.
     *
     * <p>Every uncertainty answers {@code true}: no Townstead, an unbound stage read, a villager
     * Townstead has no state for, a query that failed. See the class comment for why that is the safe
     * direction.
     */
    public static boolean humanoid(@Nullable LivingEntity entity) {
        if (entity == null) {
            return true;
        }
        Pushed told = PUSHED.get(entity.getUUID());
        if (told != null) {
            // The server has the life stage and this client does not. Its answer is the answer.
            return told.humanoid();
        }
        if (!TownsteadBridge.installed()
                || !TownsteadBridge.has(TownsteadCapability.STAGE_CAPABILITIES)) {
            // The rig lives on the same internal stage record as the behaviour flags, so without that
            // capability there is nothing to read and no reason to pay for the lookup.
            return true;
        }
        UUID id = entity.getUUID();
        long now = entity.level().getGameTime();
        Cached cached = CACHE.get(id);
        if (cached != null && now - cached.at() < REFRESH_TICKS && now >= cached.at()) {
            return cached.humanoid();
        }
        boolean humanoid = true;
        try {
            humanoid = TownsteadBridge.lifeStage(entity).asOptional()
                    .map(TownsteadLifeStageView::humanoidRig)
                    .orElse(true);
        } catch (Throwable ignored) {
            // A render pass is the last place an integration may throw from.
        }
        if (CACHE.size() >= MAX_CACHED && !CACHE.containsKey(id)) {
            CACHE.keySet().stream().findFirst().ifPresent(CACHE::remove);
        }
        CACHE.put(id, new Cached(humanoid, now));
        return humanoid;
    }

    /** Drops every answer. Called on disconnect, beside the other client caches. */
    public static void clear() {
        CACHE.clear();
        PUSHED.clear();
    }
}
