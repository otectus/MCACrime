package dev.otectus.mcacrime.compat.reputation;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.compat.CrimeIncidentMapping;
import dev.otectus.mcacrime.compat.ReputationBridge;
import dev.otectus.mcacrime.compat.ReputationOps;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcareputation.api.CoreIncidentAuthority;
import dev.otectus.mcareputation.api.CoreIncidentAuthorityRegistration;
import dev.otectus.mcareputation.api.CoreIncidentKind;
import dev.otectus.mcareputation.api.McaReputationApi;
import dev.otectus.mcareputation.api.ReputationMirror;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.ReputationResult;
import dev.otectus.mcareputation.api.ResolutionResult;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentSubject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The MCA: Reputation adapter — <b>the only file in this mod that imports {@code mcareputation}</b>.
 *
 * <p>It is reached by name from {@code ReputationBridge}, after {@code ModList} has confirmed the mod
 * is installed. Nothing else may reference it, or the JVM would try to resolve these imports on an
 * installation without the companion and throw {@code NoClassDefFoundError} at whatever unlucky point
 * first touched the chain.
 *
 * <h2>Which companion types are allowed across</h2>
 *
 * <p>Only the documented public surface: {@code api.*} plus the four consumer-facing types that live
 * in {@code incident} ({@code IncidentStatus}, {@code IncidentSubject}, and their enums) and
 * {@code community.CommunityKey}. Never {@code incident.IncidentRecord}, never {@code state.*}, never
 * {@code reputation.ReputationService} — those are internals, and reaching into them would make this
 * adapter break on a patch release that was free to move them.
 *
 * <h2>Authority</h2>
 *
 * <p>MCA: Reputation detects villager assault and killing itself. This adapter claims those deeds so
 * only one mod records them. The claim is honest: {@link CrimeCoreAuthority#owns} answers false the
 * moment the integration is disabled, degraded, or detection is off, and Reputation resumes on the
 * very next event.
 */
public final class CrimeReputationCompat implements ReputationOps {

    private static final ResourceLocation SOURCE = McaCrime.id("crime_bridge");
    private static final ResourceLocation AUTHORITY_ID = McaCrime.id("crime_detector");

    @Nullable
    private static CoreIncidentAuthorityRegistration authority;
    @Nullable
    private static ReputationMirror mirror;

    private CrimeReputationCompat() {
    }

    /** Invoked reflectively by {@code ReputationBridge}. Installs this implementation. */
    public static void register() {
        ReputationBridge.setOps(new CrimeReputationCompat());
    }

    // ------------------------------------------------------------------ handshake

    @Override
    public int apiVersion() {
        try {
            return McaReputationApi.getApiVersion();
        } catch (Throwable t) {
            // A future build that removed the method would land here. Answering with a version we
            // cannot match is what makes the bridge refuse rather than guess.
            return -1;
        }
    }

    @Override
    public boolean acceptsWrites() {
        try {
            return McaReputationApi.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ authority

    @Override
    public synchronized boolean claimAuthority() {
        if (authority != null && authority.isActive()) {
            return true;
        }
        try {
            authority = McaReputationApi.registerCoreIncidentAuthority(new CrimeCoreAuthority());
            boolean claimed = authority.isActive()
                    && McaReputationApi.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_ASSAULT)
                    && McaReputationApi.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_KILL);
            if (claimed && mirror == null && McaCrimeConfig.COMMON.mirrorReputationFallback.get()) {
                // Registered only once authority is genuinely held: a mirror on a bridge that is not
                // recording anything would keep a fallback copy of a store we do not write to.
                mirror = new CrimeStandingMirror();
                McaReputationApi.registerMirror(mirror);
            }
            return claimed;
        } catch (Throwable t) {
            McaCrime.LOGGER.error("MCA: Crime — registering detection authority with MCA: Reputation failed.", t);
            return false;
        }
    }

    @Override
    public synchronized void releaseAuthority() {
        try {
            if (authority != null) {
                authority.close();
                authority = null;
            }
            if (mirror != null) {
                McaReputationApi.unregisterMirror(mirror);
                mirror = null;
            }
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — releasing detection authority threw; ignoring", t);
        }
    }

    @Override
    public boolean holdsAuthority() {
        CoreIncidentAuthorityRegistration current = authority;
        return current != null && current.isActive();
    }

    // ------------------------------------------------------------------ incidents

    @Override
    public Optional<UUID> recordIncident(MinecraftServer server, CrimeRecordView view,
                                         ResourceLocation incidentType, String dedupeKey,
                                         OptionalInt deltaOverride) {
        Optional<CommunityKey> community = toCommunity(view.community().orElse(null));
        if (community.isEmpty()) {
            return Optional.empty();
        }
        try {
            ReputationRequest.Builder builder = ReputationRequest
                    .builder(server, view.offenderId(), community.get(), incidentType, SOURCE)
                    .dedupeKey(dedupeKey)
                    // The time the crime happened, not the time we got round to delivering it. A
                    // replayed operation must not tell the village a year-old murder just occurred.
                    .gameTime(view.committedGameTime())
                    .witnesses(view.witnessIds());
            deltaOverride.ifPresent(builder::delta);
            contextFor(view).forEach(builder::context);
            subjectFor(view).ifPresent(builder::subject);

            ReputationResult result = McaReputationApi.record(builder.build());
            if (result.incidentId().isPresent()) {
                // Present on APPLIED, and also on DUPLICATE — the companion hands back the id its
                // first attempt produced, which is what repairs a link lost to a crash.
                return result.incidentId();
            }
            if (result.reason() == ReputationResult.Reason.UNWITNESSED) {
                // Legitimately dropped: an unwitnessed deed whose definition does not retain it. The
                // crime is still in our ledger; the village simply never learned of it.
                McaCrime.LOGGER.debug("MCA: Crime — {} was not made public (unwitnessed); the case stands.",
                        view.crimeType());
            }
            return Optional.empty();
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — recording {} failed; will retry", incidentType, t);
            return Optional.empty();
        }
    }

    @Override
    public Optional<UUID> findIncident(MinecraftServer server, UUID playerId,
                                       CrimeCommunityKey community, String dedupeKey) {
        Optional<CommunityKey> key = toCommunity(community);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        try {
            // Re-sending the same key is the supported way to recover the id: a duplicate is refused
            // without mutating anything and answers with the incident the first attempt created.
            ReputationRequest probe = ReputationRequest
                    .builder(server, playerId, key.get(), CrimeIncidentMapping.VILLAGER_ASSAULTED, SOURCE)
                    .dedupeKey(dedupeKey)
                    .build();
            ReputationResult result = McaReputationApi.record(probe);
            return result.reason() == ReputationResult.Reason.DUPLICATE
                    ? result.incidentId()
                    : Optional.empty();
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    @Override
    public boolean resolveIncident(MinecraftServer server, UUID playerId, CrimeCommunityKey community,
                                   UUID incidentId, String status) {
        Optional<CommunityKey> key = toCommunity(community);
        Optional<IncidentStatus> target = IncidentStatus.byName(status);
        if (key.isEmpty() || target.isEmpty()) {
            return false;
        }
        try {
            ResolutionResult result = McaReputationApi.resolve(server, playerId, key.get(),
                    incidentId, target.get(), SOURCE);
            // NOT_STRONGER means the incident already sits at this status or a stronger one. There is
            // no dedupe key on this call — monotonic strength is the idempotency mechanism, so a
            // replayed resolution has to read as success or the outbox would retry it forever.
            return result.applied() || result.reason() == ResolutionResult.Reason.NOT_STRONGER;
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — resolving incident {} failed; will retry", incidentId, t);
            return false;
        }
    }

    @Override
    public OptionalInt score(MinecraftServer server, UUID playerId, CrimeCommunityKey community) {
        Optional<CommunityKey> key = toCommunity(community);
        if (key.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            return McaReputationApi.getScore(server, playerId, key.get());
        } catch (Throwable t) {
            return OptionalInt.empty();
        }
    }

    // ------------------------------------------------------------------ conversion

    /**
     * Our community key to theirs. Total by construction: both types reject a null dimension and a
     * negative id, so a key that exists on our side always converts.
     */
    private static Optional<CommunityKey> toCommunity(@Nullable CrimeCommunityKey community) {
        return community == null
                ? Optional.empty()
                : CommunityKey.of(community.dimension(), community.villageId());
    }

    /** The allowlisted context we hand across, plus our own record id for link recovery. */
    private static Map<String, String> contextFor(CrimeRecordView view) {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("record_id", view.id().toString());
        context.put("crime_type", view.crimeType().toString());
        view.context(CrimeContext.VICTIM_ROLE).ifPresent(role -> context.put("victim_role", role));
        view.context(CrimeContext.WITNESS_COUNT_TOTAL)
                .ifPresent(total -> context.put("witness_count_total", total));
        view.context(CrimeContext.SENTENCE_ID).ifPresent(id -> context.put("sentence_id", id));
        view.context(CrimeContext.CUSTODY_ID).ifPresent(id -> context.put("custody_id", id));
        view.context(CrimeContext.RANSOM_ID).ifPresent(id -> context.put("ransom_id", id));
        return context;
    }

    /** The victim, as the companion's subject type, when we know who they were. */
    private static Optional<IncidentSubject> subjectFor(CrimeRecordView view) {
        if (view.victimId().isEmpty()) {
            return Optional.empty();
        }
        String name = view.context(CrimeContext.VICTIM_NAME).orElse("");
        String role = view.context(CrimeContext.VICTIM_ROLE).orElse("victim");
        return Optional.of(IncidentSubject.villager(view.victimId().get(), name, role));
    }

    // ------------------------------------------------------------------ nested

    /**
     * Our claim to be the producer of villager assault and killing.
     *
     * <p>Answers honestly and cheaply. Every {@code false} here hands detection straight back to MCA:
     * Reputation on the next event, which is exactly what should happen when our own detector is off,
     * the integration is disabled, or the bridge has degraded.
     */
    private static final class CrimeCoreAuthority implements CoreIncidentAuthority {

        @Override
        public ResourceLocation authorityId() {
            return AUTHORITY_ID;
        }

        @Override
        public boolean owns(CoreIncidentKind kind) {
            return McaCrimeConfig.COMMON.enableCrimeDetection.get()
                    && McaCrimeConfig.COMMON.enableReputation.get()
                    && ReputationBridge.isAvailable();
        }
    }

    /**
     * Keeps our own village standing store in step with the companion's canonical one.
     *
     * <p>The contract is strict and worth restating: called after the canonical commit, on the server
     * thread, and it <b>must not call back into Reputation</b> — a mirror that recorded a score by
     * asking Reputation to record a score would recurse. It also sends no messages and fires no
     * events; the canonical commit already did. This is bookkeeping so that uninstalling Reputation
     * later leaves players with sensible standing instead of resetting everyone to a stranger.
     */
    private static final class CrimeStandingMirror implements ReputationMirror {

        @Override
        public void mirrorScore(UUID player, CommunityKey community, int score,
                                ResourceLocation ladder, String highWaterTierId) {
            MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return;
            }
            CrimeCommunityKey.of(community.dimension(), community.villageId()).ifPresent(key ->
                    dev.otectus.mcacrime.state.world.CrimeWorldData.get(server)
                            .setReputation(key, player, score));
        }

        @Override
        public void mirrorVillageTitle(UUID player, CommunityKey community, ResourceLocation title) {
            // Titles are Reputation's own concept; MCA: Crime has nowhere meaningful to keep them and
            // inventing a store would be a second source of truth for something we do not own.
        }

        @Override
        public void mirrorGlobalTitle(UUID player, ResourceLocation title) {
            // As above.
        }

        @Override
        public String mirrorName() {
            return "MCA: Crime village standing";
        }
    }

    /** Every authority kind this adapter claims, for diagnostics. */
    public static List<String> claimedKinds() {
        List<String> kinds = new ArrayList<>();
        try {
            for (CoreIncidentKind kind : CoreIncidentKind.values()) {
                if (McaReputationApi.hasExternalAuthority(kind)) {
                    kinds.add(kind.name());
                }
            }
        } catch (Throwable t) {
            // Diagnostics must never be the thing that throws.
        }
        return kinds;
    }
}
