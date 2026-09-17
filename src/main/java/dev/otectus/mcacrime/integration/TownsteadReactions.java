package dev.otectus.mcacrime.integration;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.PlayerJailedEvent;
import dev.otectus.mcacrime.api.event.PlayerReleasedFromJailEvent;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.compat.TownsteadReactionBindings;
import dev.otectus.mcacrime.compat.TownsteadReactionEvent;
import dev.otectus.mcacrime.jail.ReleaseReason;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import org.jetbrains.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns MCA: Crime's public transitions into bounded Townstead reactions.
 *
 * <h2>Where the triggers come from</h2>
 *
 * <p>Four, and each one is a moment a settlement genuinely has something to react to (reference §11.2):
 * a crime that became public, a jailbreak, somebody taken into custody, and a sentence finished. Three
 * of them arrive on MCA: Crime's own published events, which is deliberate — a reaction is a
 * consequence of the law, not a step in it, and hanging it off the events means no arrest path, release
 * path or jailbreak path has to remember to call anything.
 *
 * <h2>Exactly one durable effect per thing that happened</h2>
 *
 * <p>The failure this is built against is a village that reacts to the same arrest four times: once
 * when it happens, again when the queue is replayed after a crash, again when the player logs back in,
 * again when an operator retries the outbox. So every reaction carries an idempotency key made of what
 * happened and which case or sentence it happened to, the operation's own id is derived from that key,
 * and the key is written into the world's bounded one-shot receipt ledger when the reaction is actually
 * delivered. A replay finds the receipt and enqueues nothing.
 *
 * <h2>And a cap on top of that</h2>
 *
 * <p>Idempotency stops one event producing many reactions. It does nothing about many events: a raid,
 * a spree, or a player testing the arrest command twenty times would each produce one legitimate
 * reaction and the village would spend a minute doing nothing else. Hence a per-community rate cap
 * (§11.3) — a bounded number of reactions per community per window, memory-only, because a cap that
 * survived a restart would be a village that stayed quiet for the wrong reason.
 *
 * <p>Nothing here names a Townstead type. The reaction ids come from a datapack and the delivery goes
 * through {@code TownsteadBridge}.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class TownsteadReactions {

    /** How long one rate-cap window lasts. Thirty seconds of game time. */
    public static final int RATE_WINDOW_TICKS = 600;

    /** How many reactions one community may be asked for in a window. */
    public static final int MAX_PER_WINDOW = 4;

    /** How many communities the cap remembers. A ceiling on memory, not on villages. */
    private static final int MAX_TRACKED_COMMUNITIES = 256;

    /** The namespace the idempotency keys live in, so a derived id cannot collide with anything else. */
    private static final String KEY_NAMESPACE = "mcacrime:townstead-reaction:";

    /** community -> when its window opened, and how many reactions it has had since. */
    private static final Map<String, Window> WINDOWS = new LinkedHashMap<>();

    private static final class Window {
        private long openedAt;
        private int count;
    }

    private TownsteadReactions() {
    }

    // ------------------------------------------------------------------ triggers

    /**
     * A case that has just become public knowledge.
     *
     * <p>Called from {@code CrimeIntegrationHooks.onCommitted}, after that method's own knowledge gate
     * has passed — which is the point. The gate is what decides whether the village may know; reacting
     * before it would have residents respond to a burglary nobody saw.
     */
    public static void onPublicIncident(MinecraftServer server, CrimeRecordView view) {
        if (server == null || view == null || view.community().isEmpty()) {
            return;
        }
        TownsteadReactionEvent event = "jailbreak".equals(view.context("detection").orElse(""))
                ? TownsteadReactionEvent.JAILBREAK
                : TownsteadReactionEvent.CRIME_WITNESSED;
        ServerPlayer offender = server.getPlayerList().getPlayer(view.offenderId());
        if (offender == null) {
            // Nobody to locate the scene from. A reaction needs a place for the audience to be near,
            // and the world origin is not it.
            return;
        }
        enqueue(server, event, view.community().get(), levelOf(server, offender),
                offender.blockPosition(), view.id());
    }

    /** Somebody was taken into lawful custody here. */
    @SubscribeEvent
    public static void onJailed(PlayerJailedEvent event) {
        ServerPlayer player = event.getPlayer();
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null) {
            return;
        }
        CrimeCommunityKey community = communityFor(server, player.getUUID()).orElse(null);
        if (community == null) {
            // Nothing they are being held for names a settlement. A reaction would be a village
            // responding to an arrest that, as far as it knows, has nothing to do with it.
            return;
        }
        BlockPos where = event.getAnchor() == null ? player.blockPosition() : event.getAnchor();
        enqueue(server, TownsteadReactionEvent.CUSTODY_STARTED, community,
                levelOf(server, player), where, sentenceKey(player));
    }

    /**
     * A sentence finished.
     *
     * <p>Only {@link ReleaseReason#SENTENCE_SERVED}. The other reasons are not the same event and must
     * not borrow its reaction: a captivity cap is a safety valve, an admin release is an operator, a
     * pardon is a decision somebody made, and bail is a transaction. Reading all of them as "served"
     * would have a village congratulate a player for a sentence they bought out.
     */
    @SubscribeEvent
    public static void onReleased(PlayerReleasedFromJailEvent event) {
        if (event.getReason() != ReleaseReason.SENTENCE_SERVED) {
            return;
        }
        ServerPlayer player = event.getPlayer();
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null) {
            return;
        }
        CrimeCommunityKey community = communityFor(server, player.getUUID()).orElse(null);
        if (community == null) {
            return;
        }
        enqueue(server, TownsteadReactionEvent.CUSTODY_ENDED, community, levelOf(server, player),
                player.blockPosition(), sentenceKey(player));
    }

    // ------------------------------------------------------------------ enqueue

    /**
     * Queues one reaction, or decides not to.
     *
     * <p>Six reasons not to, checked cheapest first so the ordinary case on a server with no Townstead
     * — the switch is off, or nothing is bound — costs a config read and returns.
     *
     * @param subjectKey what this reaction is about: a case id, a sentence id, anything stable. It is
     *                   half of the idempotency key, and the half that decides what "the same thing
     *                   happening again" means.
     */
    public static void enqueue(MinecraftServer server, TownsteadReactionEvent event,
                               @Nullable CrimeCommunityKey community, @Nullable ServerLevel level,
                               @Nullable BlockPos pos, @Nullable Object subjectKey) {
        if (server == null || event == null || community == null || level == null || pos == null
                || subjectKey == null || !enabled()) {
            return;
        }
        TownsteadReactionBindings.Binding binding = TownsteadReactionBindings.binding(event).orElse(null);
        if (binding == null) {
            // No pack bound this event to anything. Silence is the answer, not a default reaction.
            return;
        }
        long now = server.overworld().getGameTime();
        String key = idempotencyKey(event, subjectKey);
        UUID operationId = operationIdFor(key);

        CrimeWorldData data = CrimeWorldData.get(server);
        if (data.hasOneShotReceipt(operationId)) {
            return; // already delivered once, in this session or a previous one
        }
        if (!allow(community, now)) {
            McaCrime.LOGGER.debug("MCA: Crime — not asking Townstead to react to {} in {}: the settlement "
                    + "has already had {} reaction(s) this window.", event.id(), community.asString(),
                    MAX_PER_WINDOW);
            return;
        }

        CompoundTag payload = new CompoundTag();
        payload.putString(IntegrationTargets.PAYLOAD_EVENT, event.id());
        payload.putString(IntegrationTargets.PAYLOAD_REACTION_ID, binding.reaction().toString());
        payload.putString(IntegrationTargets.PAYLOAD_DIMENSION, level.dimension().location().toString());
        payload.putInt(IntegrationTargets.PAYLOAD_X, pos.getX());
        payload.putInt(IntegrationTargets.PAYLOAD_Y, pos.getY());
        payload.putInt(IntegrationTargets.PAYLOAD_Z, pos.getZ());
        payload.putInt(IntegrationTargets.PAYLOAD_RADIUS, binding.radius());
        payload.putString(IntegrationTargets.PAYLOAD_DEDUPE_KEY, key);
        payload.putLong(IntegrationTargets.PAYLOAD_GAME_TIME, now);
        payload.put(IntegrationTargets.PAYLOAD_COMMUNITY, community.save());

        // The operation id is derived from the key rather than random, so a second enqueue of the same
        // event while the first is still queued is refused by the outbox itself.
        data.enqueueOperation(CrimeIntegrationOperation.create(operationId,
                IntegrationTargets.TOWNSTEAD_REACTION, subjectUuid(subjectKey), caseIdOf(subjectKey),
                IntegrationTargets.ACTION_REACT, payload, now));
    }

    /**
     * The stable identity of one reaction: what happened, and what it happened to.
     *
     * <p>Pure, and the only thing standing between a crash-replayed queue and a village reacting to the
     * same arrest twice. Case id rather than a timestamp on purpose — a timestamp would make every
     * replay a new event, which is exactly the property an idempotency key must not have.
     */
    public static String idempotencyKey(TownsteadReactionEvent event, Object subjectKey) {
        return event.id() + ":" + subjectKey;
    }

    /** The operation id for a key. Deterministic, so the same key always names the same operation. */
    public static UUID operationIdFor(String key) {
        return UUID.nameUUIDFromBytes((KEY_NAMESPACE + key).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Whether this community may have another reaction right now, counting it if so.
     *
     * <p>Memory-only and deliberately so: the cap exists to stop a burst, and a burst does not survive a
     * restart. Persisting it would mean a village that had a bad minute before a crash came back
     * silent for no reason a player could see.
     */
    public static synchronized boolean allow(CrimeCommunityKey community, long now) {
        String key = community.asString();
        Window window = WINDOWS.get(key);
        if (window == null) {
            if (WINDOWS.size() >= MAX_TRACKED_COMMUNITIES) {
                WINDOWS.remove(WINDOWS.keySet().iterator().next());
            }
            window = new Window();
            window.openedAt = now;
            WINDOWS.put(key, window);
        }
        if (now < window.openedAt || now - window.openedAt >= RATE_WINDOW_TICKS) {
            window.openedAt = now;
            window.count = 0;
        }
        if (window.count >= MAX_PER_WINDOW) {
            return false;
        }
        window.count++;
        return true;
    }

    /** Drops the rate-cap windows. Called on server stop, beside the other memory-only registries. */
    public static synchronized void clearAll() {
        WINDOWS.clear();
    }

    /**
     * Whether public reactions are switched on and something can actually play one.
     *
     * <p>The capability check is here as well as at delivery, and it is not redundant: without it every
     * arrest on every server without Townstead — which is nearly all of them — would queue an operation
     * that exists only to be dropped a tick later. The outbox is bounded and shared with the civic
     * records, and filling it with work nobody can do would push real work out of it.
     */
    public static boolean enabled() {
        try {
            if (!McaCrimeConfig.COMMON.townsteadEnabled.get()
                    || !McaCrimeConfig.COMMON.townsteadPublicReactions.get()) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        return dev.otectus.mcacrime.compat.TownsteadBridge
                .has(dev.otectus.mcacrime.compat.TownsteadCapability.DISPATCH_REACTION);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The settlement a player's current legal situation belongs to.
     *
     * <p>The newest of their still-actionable cases that names one. Newest because it is the one that
     * put them where they are, and actionable because a settled case is not what anybody is reacting to.
     */
    public static Optional<CrimeCommunityKey> communityFor(MinecraftServer server, UUID playerId) {
        for (CrimeRecord record : CrimeWorldData.get(server).recordsForOffender(playerId)) {
            Optional<CrimeCommunityKey> community = record.communityKey();
            if (community.isPresent() && record.actionable()) {
                return community;
            }
        }
        return Optional.empty();
    }

    /**
     * The sentence a player is serving, or their own id when there is none to name.
     *
     * <p>Read through the data attachment, which always answers with a record rather than an optional,
     * so the "no sentence" case is a null sentence id instead of an empty result.
     */
    private static Object sentenceKey(ServerPlayer player) {
        try {
            UUID sentence = dev.otectus.mcacrime.state.CrimeAttachments.get(player).getJail()
                    .getSentenceId();
            return sentence == null ? player.getUUID() : sentence;
        } catch (Throwable t) {
            return player.getUUID();
        }
    }

    @Nullable
    private static ServerLevel levelOf(MinecraftServer server, @Nullable ServerPlayer player) {
        if (player != null && player.level() instanceof ServerLevel level) {
            return level;
        }
        return server.overworld();
    }

    /** The operation's player field. Reactions are not about a player's standing, so it is nominal. */
    private static UUID subjectUuid(Object subjectKey) {
        return subjectKey instanceof UUID id ? id : operationIdFor(String.valueOf(subjectKey));
    }

    @Nullable
    private static UUID caseIdOf(Object subjectKey) {
        return subjectKey instanceof UUID id ? id : null;
    }
}
