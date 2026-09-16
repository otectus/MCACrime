package dev.otectus.mcacrime.compat.reputation;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.compat.CrimeAuthorityPolicy;
import dev.otectus.mcacrime.compat.ReputationBridge;
import dev.otectus.mcacrime.compat.ReputationCapabilitySnapshot;
import dev.otectus.mcacrime.compat.ReputationDelivery;
import dev.otectus.mcacrime.compat.ReputationOps;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcareputation.api.CoreIncidentAuthority;
import dev.otectus.mcareputation.api.CoreIncidentAuthorityRegistration;
import dev.otectus.mcareputation.api.CoreIncidentKind;
import dev.otectus.mcareputation.api.DeliveryOutcome;
import dev.otectus.mcareputation.api.IncidentDelivery;
import dev.otectus.mcareputation.api.McaReputationApi;
import dev.otectus.mcareputation.api.ReceiptOutcome;
import dev.otectus.mcareputation.api.ReceiptView;
import dev.otectus.mcareputation.api.ReputationCapabilities;
import dev.otectus.mcareputation.api.ReputationMirror;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.ReputationResult;
import dev.otectus.mcareputation.api.ResolutionResult;
import dev.otectus.mcareputation.api.SupersedeSpec;
import dev.otectus.mcareputation.api.profile.ProfiledDelivery;
import dev.otectus.mcareputation.api.profile.ProfiledDeliveryResult;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentSubject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
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
 * <p>Only the documented public surface: {@code api.*} (including {@code api.profile}) plus the
 * consumer-facing types that live in {@code incident} ({@code IncidentStatus}, {@code IncidentSubject},
 * and their enums) and {@code community.CommunityKey}. Never {@code incident.IncidentRecord}, never
 * {@code state.*}, never {@code reputation.ReputationService} — those are internals, and reaching into
 * them would make this adapter break on a patch release that was free to move them.
 *
 * <h2>How much of the companion is used</h2>
 *
 * <p>Decided by the capability handshake, not by the version number. MCA: Reputation deliberately keeps
 * its API version at 1 while adding facilities, so a bridge that only compared versions could not tell
 * a 0.3.0 install from a 0.6.0 one. {@link ReputationBridge#capabilities()} holds what the installed
 * build advertised at server start, and every call below takes the strongest path that answer permits:
 * keyed delivery with a receipt where {@code delivery} is advertised and plain {@code record} where it
 * is not; a profile-aware supersession where {@code profiled_delivery_v1} is live and the plain fold
 * where only {@code supersede} is; bound resolution where {@code bound_resolution} is offered. Every
 * fallback is a fully working integration, just an older-shaped one.
 *
 * <h2>Authority</h2>
 *
 * <p>MCA: Reputation detects villager assault and killing itself. This adapter claims <b>those two
 * kinds and nothing else</b> — see {@link CrimeAuthorityPolicy} for why answering the same boolean for
 * every kind was a bug rather than a shortcut. The claim is honest: {@link CrimeCoreAuthority#owns}
 * answers false the moment the integration is disabled, degraded, or detection is off, and
 * {@link CrimeCoreAuthority#canDeliver} answers false when the outbox pump that would actually file the
 * deed is switched off. Reputation resumes on the very next event either way.
 */
public final class CrimeReputationCompat implements ReputationOps {

    private static final ResourceLocation SOURCE = McaCrime.id("crime_bridge");
    private static final ResourceLocation AUTHORITY_ID = McaCrime.id("crime_detector");

    /** The kinds this mod detects, as the companion's own enum. Kept in step with the policy class. */
    private static final Set<CoreIncidentKind> DECLARED_KINDS =
            EnumSet.of(CoreIncidentKind.MCA_VILLAGER_ASSAULT, CoreIncidentKind.MCA_VILLAGER_KILL);

    @Nullable
    private static CoreIncidentAuthorityRegistration authority;
    @Nullable
    private static ReputationMirror mirror;

    private CrimeReputationCompat() {
    }

    /** Invoked reflectively by {@code ReputationBridge}. Installs this implementation. */
    public static void register() {
        verifyMirroredNames();
        ReputationBridge.setOps(new CrimeReputationCompat());
    }

    /**
     * Checks the always-loadable side's copies of the companion's names still match the companion.
     *
     * <p>{@code ReputationCapabilitySnapshot} and {@code CrimeAuthorityPolicy} hold plain strings
     * because they load with MCA: Reputation absent, and a plain string cannot be checked by the
     * compiler. This is the one place where both sides are on the classpath at once, so it is the only
     * place the comparison can be made — and a mismatch has to be loud, because its symptom is a
     * feature silently treated as unsupported, or a claim that silently covers nothing.
     */
    private static void verifyMirroredNames() {
        List<String> drift = new ArrayList<>();
        compare(drift, "delivery", ReputationCapabilitySnapshot.FEATURE_DELIVERY,
                ReputationCapabilities.FEATURE_DELIVERY);
        compare(drift, "receipts", ReputationCapabilitySnapshot.FEATURE_RECEIPTS,
                ReputationCapabilities.FEATURE_RECEIPTS);
        compare(drift, "read_only_lookup", ReputationCapabilitySnapshot.FEATURE_READ_ONLY_LOOKUP,
                ReputationCapabilities.FEATURE_READ_ONLY_LOOKUP);
        compare(drift, "supersede", ReputationCapabilitySnapshot.FEATURE_SUPERSEDE,
                ReputationCapabilities.FEATURE_SUPERSEDE);
        compare(drift, "bound_resolution", ReputationCapabilitySnapshot.FEATURE_BOUND_RESOLUTION,
                ReputationCapabilities.FEATURE_BOUND_RESOLUTION);
        compare(drift, "profile_snapshot", ReputationCapabilitySnapshot.FEATURE_PROFILE_SNAPSHOT,
                ReputationCapabilities.FEATURE_PROFILE_SNAPSHOT);
        compare(drift, "speaker_profile", ReputationCapabilitySnapshot.FEATURE_SPEAKER_PROFILE,
                ReputationCapabilities.FEATURE_SPEAKER_PROFILE);
        compare(drift, "repeat_credit", ReputationCapabilitySnapshot.FEATURE_REPEAT_CREDIT,
                ReputationCapabilities.FEATURE_REPEAT_CREDIT);
        compare(drift, "profiled_delivery", ReputationCapabilitySnapshot.FEATURE_PROFILED_DELIVERY,
                ReputationCapabilities.FEATURE_PROFILED_DELIVERY);
        compare(drift, "profile_change", ReputationCapabilitySnapshot.FEATURE_PROFILE_CHANGE,
                ReputationCapabilities.FEATURE_PROFILE_CHANGE);

        Set<String> declared = new LinkedHashSet<>();
        DECLARED_KINDS.forEach(kind -> declared.add(kind.name()));
        if (!declared.equals(CrimeAuthorityPolicy.declaredKinds())) {
            drift.add("declared kinds " + declared + " != " + CrimeAuthorityPolicy.declaredKinds());
        }
        if (!drift.isEmpty()) {
            McaCrime.LOGGER.error("MCA: Crime — the MCA: Reputation names this build mirrors have drifted: "
                    + "{}. The integration still runs, but the affected features are treated as "
                    + "unsupported. This is a bug in MCA: Crime, not a misconfiguration.", drift);
        }
    }

    private static void compare(List<String> drift, String label, String ours, String theirs) {
        if (!ours.equals(theirs)) {
            drift.add(label + " '" + ours + "' != '" + theirs + "'");
        }
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

    @Override
    public ReputationCapabilitySnapshot capabilities(@Nullable MinecraftServer server) {
        try {
            ReputationCapabilities caps = McaReputationApi.capabilities(server);
            Set<String> nativeKinds = new LinkedHashSet<>();
            caps.nativeKinds().forEach(kind -> nativeKinds.add(kind.name()));
            return new ReputationCapabilitySnapshot(caps.apiVersion(), caps.enabled(), caps.features(),
                    nativeKinds, caps.readinessReason().orElse(""));
        } catch (Throwable t) {
            // A companion older than 0.4.1 has no capabilities() to call, which is a supported
            // installation rather than an error: it advertises nothing, and we use the oldest surface.
            return ReputationCapabilitySnapshot.unsupported(apiVersion(), acceptsWrites(),
                    "capabilities() unavailable (" + t.getClass().getSimpleName() + ")");
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
    public ReputationDelivery deliverIncident(MinecraftServer server, CrimeRecordView view,
                                              ResourceLocation incidentType, String operationKey,
                                              OptionalInt deltaOverride,
                                              @Nullable UUID precursorIncidentId,
                                              long supersedeWindowTicks) {
        Optional<CommunityKey> community = toCommunity(view.community().orElse(null));
        if (community.isEmpty()) {
            // No village to record it against, and there never will be. Terminal rather than a delay:
            // retrying it is only noise in the queue.
            return ReputationDelivery.of(ReputationDelivery.Outcome.REFUSED_INVALID,
                    "the case names no community");
        }
        ReputationCapabilitySnapshot caps = ReputationBridge.capabilities();
        try {
            ReputationRequest request = requestFor(server, view, community.get(), incidentType,
                    operationKey, deltaOverride);
            boolean supersedes = precursorIncidentId != null && supersedeWindowTicks > 0L;

            if (supersedes && caps.supportsProfiledDelivery()) {
                // The unified path: one fold, one record, one standing change and one set of profile
                // evidence, under our operation key. The same transaction MCA: Reputation's own
                // assault-to-killing upgrade uses, which is the point — a deed we took off it must not
                // be recorded differently merely because we are the producer.
                ProfiledDeliveryResult result = McaReputationApi.deliverProfiled(
                        ProfiledDelivery.superseding(delivery(request, operationKey),
                                SupersedeSpec.of(precursorIncidentId, supersedeWindowTicks, true)));
                return fromDelivery(result.outcome());
            }
            if (supersedes && caps.supportsSupersede()) {
                // No profiled delivery on this build: the fold still happens, just without a receipt.
                // A replay is then caught by the request's own dedupe key, exactly as before 0.4.1.
                return fromResult(McaReputationApi.recordSuperseding(request,
                        SupersedeSpec.of(precursorIncidentId, supersedeWindowTicks, true)));
            }
            if (caps.supportsDelivery()) {
                return fromDelivery(McaReputationApi.deliver(delivery(request, operationKey)));
            }
            return fromResult(McaReputationApi.record(request));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — delivering {} failed; will retry", incidentType, t);
            return ReputationDelivery.unknown(t.getClass().getSimpleName());
        }
    }

    /** The keyed delivery for one request: our namespace scopes the operation key. */
    private static IncidentDelivery delivery(ReputationRequest request, String operationKey) {
        return IncidentDelivery.of(request, McaCrime.MOD_ID, operationKey);
    }

    private static ReputationRequest requestFor(MinecraftServer server, CrimeRecordView view,
                                                CommunityKey community, ResourceLocation incidentType,
                                                String operationKey, OptionalInt deltaOverride) {
        ReputationRequest.Builder builder = ReputationRequest
                .builder(server, view.offenderId(), community, incidentType, SOURCE)
                .dedupeKey(operationKey)
                // The time the crime happened, not the time we got round to delivering it. A
                // replayed operation must not tell the village a year-old murder just occurred.
                .gameTime(view.committedGameTime())
                .witnesses(view.witnessIds());
        deltaOverride.ifPresent(builder::delta);
        contextFor(view).forEach(builder::context);
        subjectFor(view).ifPresent(builder::subject);
        return builder.build();
    }

    @Override
    public ReputationDelivery findDelivery(MinecraftServer server, UUID playerId,
                                           CrimeCommunityKey community, String operationKey) {
        Optional<CommunityKey> key = toCommunity(community);
        if (key.isEmpty() || operationKey == null || operationKey.isEmpty()) {
            return ReputationDelivery.of(ReputationDelivery.Outcome.REFUSED_INVALID,
                    "no community or operation key to look up");
        }
        if (!ReputationBridge.capabilities().supportsReceiptLookup()) {
            // This build cannot answer the question without writing, and a write is never an
            // acceptable way to ask one. The pump keeps whatever the delivery itself said.
            return ReputationDelivery.unknown("receipt lookup is not supported by this build");
        }
        try {
            Optional<ReceiptView> receipt = McaReputationApi.findReceipt(server, McaCrime.MOD_ID, playerId,
                    key.get(), operationKey);
            if (receipt.isPresent()) {
                ReceiptView stored = receipt.get();
                // The receipt names the incident its delivery produced; confirm the record is still
                // there before handing the id back as a link, because retention or a later deed
                // absorbing it would otherwise have us link a case to nothing.
                UUID incidentId = stored.incidentId()
                        .filter(id -> McaReputationApi.findIncident(server, playerId, key.get(), id).isPresent())
                        .orElse(null);
                return fromReceipt(stored.outcome(), incidentId, "receipt");
            }
            // No receipt. That is only "never delivered" while nothing has been forgotten, and the
            // floor is how the companion says how far back its memory reaches. Either way it is not a
            // terminal answer: the next keyed delivery replays the truth.
            OptionalLong floor = McaReputationApi.receiptFloor(server, playerId);
            return ReputationDelivery.unknown(floor.isPresent()
                    ? "no receipt; receipts before game time " + floor.getAsLong() + " are forgotten"
                    : "no receipt for this operation");
        } catch (Throwable t) {
            return ReputationDelivery.unknown(t.getClass().getSimpleName());
        }
    }

    @Override
    public ReputationDelivery resolveIncident(MinecraftServer server, UUID playerId,
                                              CrimeCommunityKey community, UUID incidentId, String status,
                                              String operationKey) {
        Optional<CommunityKey> key = toCommunity(community);
        Optional<IncidentStatus> target = IncidentStatus.byName(status);
        if (key.isEmpty() || target.isEmpty() || incidentId == null) {
            return ReputationDelivery.of(ReputationDelivery.Outcome.REFUSED_INVALID,
                    "unresolvable community, status or incident");
        }
        try {
            boolean bound = ReputationBridge.capabilities().supportsBoundResolution()
                    && operationKey != null && !operationKey.isEmpty();
            ResolutionResult result = bound
                    // Bound to the one incident already discovered, under this settlement's own key, so
                    // a retry after a crash cannot move a different record and a replay returns the
                    // settled answer without moving anything a second time.
                    ? McaReputationApi.resolveBound(server, playerId, key.get(), incidentId, target.get(),
                            SOURCE, operationKey)
                    : McaReputationApi.resolve(server, playerId, key.get(), incidentId, target.get(), SOURCE);
            return fromResolution(result, incidentId);
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — resolving incident {} failed; will retry", incidentId, t);
            return ReputationDelivery.unknown(t.getClass().getSimpleName());
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

    // ------------------------------------------------------------------ translation

    /**
     * The companion's receipted answer, in our vocabulary.
     *
     * <p>The id is taken from the result first and the receipt second, because a replayed delivery
     * reports the incident on the receipt rather than in a fresh result.
     */
    private static ReputationDelivery fromDelivery(DeliveryOutcome outcome) {
        if (outcome == null) {
            return ReputationDelivery.unknown("null delivery outcome");
        }
        UUID incidentId = outcome.result() == null
                ? outcome.receipt().flatMap(ReceiptView::incidentId).orElse(null)
                : outcome.result().incidentId()
                        .or(() -> outcome.receipt().flatMap(ReceiptView::incidentId))
                        .orElse(null);
        if (outcome.outcome() == ReceiptOutcome.REFUSED_INVALID && outcome.result() != null) {
            // One receipt row covers several reasons, and the one that matters to an operator is an
            // incident definition the companion has never heard of — almost always a datapack
            // overriding or missing a file this mod ships.
            return fromInvalid(outcome.result());
        }
        return fromReceipt(outcome.outcome(), incidentId,
                outcome.result() == null ? "" : outcome.result().reason().name());
    }

    private static ReputationDelivery fromReceipt(ReceiptOutcome outcome, @Nullable UUID incidentId,
                                                  String detail) {
        if (outcome == null) {
            return ReputationDelivery.unknown("null receipt outcome");
        }
        return switch (outcome) {
            case APPLIED -> ReputationDelivery.of(ReputationDelivery.Outcome.ACCEPTED, incidentId, detail);
            case ACCEPTED_NO_PUBLIC_INCIDENT -> ReputationDelivery.of(
                    ReputationDelivery.Outcome.ACCEPTED_NO_PUBLIC_INCIDENT, incidentId, detail);
            case DUPLICATE -> ReputationDelivery.of(ReputationDelivery.Outcome.DUPLICATE, incidentId, detail);
            case REFUSED_CAPACITY -> ReputationDelivery.of(ReputationDelivery.Outcome.REFUSED_CAPACITY,
                    detail);
            case REFUSED_DISABLED -> ReputationDelivery.of(ReputationDelivery.Outcome.REFUSED_DISABLED,
                    detail);
            case REFUSED_INVALID -> ReputationDelivery.of(ReputationDelivery.Outcome.REFUSED_INVALID,
                    detail);
        };
    }

    /** An unreceipted {@code record}/{@code recordSuperseding} answer, in our vocabulary. */
    private static ReputationDelivery fromResult(ReputationResult result) {
        if (result == null) {
            return ReputationDelivery.unknown("null result");
        }
        UUID incidentId = result.incidentId().orElse(null);
        if (result.applied()) {
            return ReputationDelivery.of(ReputationDelivery.Outcome.ACCEPTED, incidentId, "applied");
        }
        return switch (result.reason()) {
            // The id is present on a duplicate as well as on an applied write: the companion hands back
            // the id its first attempt produced, which is what repairs a link lost to a crash.
            case DUPLICATE -> ReputationDelivery.of(ReputationDelivery.Outcome.DUPLICATE, incidentId,
                    "duplicate");
            // Legitimately not made public: an unwitnessed deed whose definition does not retain one.
            // The crime is still in our ledger; the village simply never learned of it, and nothing
            // further is owed.
            case UNWITNESSED -> ReputationDelivery.of(
                    ReputationDelivery.Outcome.ACCEPTED_NO_PUBLIC_INCIDENT, incidentId, "unwitnessed");
            case CAPACITY -> ReputationDelivery.of(ReputationDelivery.Outcome.REFUSED_CAPACITY,
                    "the village ledger is full");
            case DISABLED -> ReputationDelivery.of(ReputationDelivery.Outcome.REFUSED_DISABLED,
                    "the companion's integration is off");
            case UNKNOWN_INCIDENT -> ReputationDelivery.of(
                    ReputationDelivery.Outcome.UNKNOWN_INCIDENT_TYPE, "unknown incident definition");
            case NO_COMMUNITY, INVALID -> ReputationDelivery.of(
                    ReputationDelivery.Outcome.REFUSED_INVALID, result.reason().name());
            case ERROR -> ReputationDelivery.unknown("the companion reported an internal error");
            case APPLIED -> ReputationDelivery.of(ReputationDelivery.Outcome.ACCEPTED, incidentId,
                    "applied");
        };
    }

    private static ReputationDelivery fromInvalid(ReputationResult result) {
        return result.reason() == ReputationResult.Reason.UNKNOWN_INCIDENT
                ? ReputationDelivery.of(ReputationDelivery.Outcome.UNKNOWN_INCIDENT_TYPE,
                        "unknown incident definition")
                : ReputationDelivery.of(ReputationDelivery.Outcome.REFUSED_INVALID,
                        result.reason().name());
    }

    private static ReputationDelivery fromResolution(ResolutionResult result, UUID incidentId) {
        if (result == null) {
            return ReputationDelivery.unknown("null resolution result");
        }
        if (result.applied()) {
            return ReputationDelivery.of(ReputationDelivery.Outcome.ACCEPTED, incidentId, "applied");
        }
        return switch (result.reason()) {
            // Already at this status or a stronger one. Monotonic strength is the idempotency
            // mechanism here, so this has to read as done or the outbox would retry it forever.
            case NOT_STRONGER -> ReputationDelivery.of(ReputationDelivery.Outcome.DUPLICATE, incidentId,
                    "already settled at least this strongly");
            case NOT_FOUND -> ReputationDelivery.of(ReputationDelivery.Outcome.MISSING_INCIDENT,
                    "the incident is no longer in the ledger");
            case DISABLED -> ReputationDelivery.of(ReputationDelivery.Outcome.REFUSED_DISABLED,
                    "the companion's integration is off");
            case INVALID -> ReputationDelivery.of(ReputationDelivery.Outcome.REFUSED_INVALID, "invalid");
            case ERROR -> ReputationDelivery.unknown("the companion reported an internal error");
            case APPLIED -> ReputationDelivery.of(ReputationDelivery.Outcome.ACCEPTED, incidentId,
                    "applied");
        };
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
     * Our claim to be the producer of villager assault and killing — and of nothing else.
     *
     * <p>Answers honestly and cheaply. Every {@code false} here hands detection straight back to MCA:
     * Reputation on the next event, which is exactly what should happen when our own detector is off,
     * the integration is disabled, the bridge has degraded, or the queue that would deliver the
     * incident is not being drained. The decisions themselves live in {@link CrimeAuthorityPolicy},
     * which is unit-testable with the companion absent from the classpath.
     */
    private static final class CrimeCoreAuthority implements CoreIncidentAuthority {

        @Override
        public ResourceLocation authorityId() {
            return AUTHORITY_ID;
        }

        @Override
        public String authorityName() {
            return "MCA: Crime";
        }

        /**
         * Exactly the two kinds this mod produces.
         *
         * <p>Declaring them is what stops an operator's rescues, cures, repelled raids and in-village
         * player kills going unrecorded: an authority that declares nothing is trusted with the kinds
         * that existed when it could have been written, and this mod has never detected any of the
         * four added since.
         */
        @Override
        public Optional<Set<CoreIncidentKind>> declaredKinds() {
            return Optional.of(EnumSet.copyOf(DECLARED_KINDS));
        }

        @Override
        public boolean owns(CoreIncidentKind kind) {
            return kind != null && CrimeAuthorityPolicy.owns(kind.name(),
                    McaCrimeConfig.COMMON.enableCrimeDetection.get(),
                    McaCrimeConfig.COMMON.enableReputation.get(),
                    ReputationBridge.isAvailable());
        }

        @Override
        public boolean canDeliver(CoreIncidentKind kind) {
            return kind != null && CrimeAuthorityPolicy.canDeliver(kind.name(),
                    McaCrimeConfig.COMMON.enableCrimeDetection.get(),
                    McaCrimeConfig.COMMON.enableReputation.get(),
                    ReputationBridge.isAvailable(),
                    McaCrimeConfig.COMMON.replayPendingOperations.get());
        }

        /**
         * Nothing to release here.
         *
         * <p>This mod's own {@code ServerStoppingEvent} handler closes the registration and clears the
         * cached capability handshake; that happens either side of this call depending on listener
         * order, and is idempotent either way. Deliberately <em>not</em> used to drop the registration:
         * the companion documents that a registration survives into the next world in the same JVM,
         * and releasing the handle here would leave this mod silently unregistered in a second world.
         */
        @Override
        public void onServerStopped() {
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
            MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
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

    /**
     * Which of the kinds we declare are effectively ours right now, for diagnostics.
     *
     * <p>Asks about the declared kinds only. Walking every value of the companion's enum, as this used
     * to, reported another mod's claim as ours.
     */
    public static List<String> claimedKinds() {
        List<String> kinds = new ArrayList<>();
        try {
            for (CoreIncidentKind kind : DECLARED_KINDS) {
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
