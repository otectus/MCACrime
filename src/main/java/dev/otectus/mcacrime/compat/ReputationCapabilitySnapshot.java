package dev.otectus.mcacrime.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the installed MCA: Reputation can actually do, read once per server and then asked about.
 *
 * <p>Until 0.7.3 this mod negotiated by comparing one integer: MCA: Reputation's API version had to be
 * exactly 1 or the integration switched itself off. That check is still here and still necessary — it
 * is what catches a genuinely incompatible generation — but it is not sufficient, because the
 * companion deliberately keeps its API version at 1 while adding capabilities. Everything added since
 * 0.4.1 (receipts, keyed delivery, read-only lookup, bound resolution, supersession) and everything
 * added in 0.6.0 (public profiles) arrived without the version moving, exactly so that bridges written
 * against version 1 would keep working. Asking the version therefore tells us nothing about whether
 * {@code deliver} exists; the advertised feature strings do.
 *
 * <p>The strings are plain {@link String} constants rather than references to the companion's own
 * {@code ReputationCapabilities.FEATURE_*} fields, because this class loads on an installation with no
 * MCA: Reputation at all. The adapter checks the two sets agree the moment it loads, so a rename on
 * the companion's side shows up as one clear log line here rather than as a feature this mod silently
 * decides is unsupported.
 *
 * @param apiVersion      the companion's API generation, or a negative number when it could not be read
 * @param enabled         whether it currently accepts writes attributed to us
 * @param features        the advertised capability strings, verbatim
 * @param nativeKinds     the core kinds it is still detecting itself, by enum name
 * @param readinessReason its own explanation when something is not ready, for the debug command
 */
public record ReputationCapabilitySnapshot(int apiVersion, boolean enabled, Set<String> features,
                                           Set<String> nativeKinds, String readinessReason) {

    // --- 0.4.1 reliability surface ---
    public static final String FEATURE_SUPERSEDE = "supersede";
    public static final String FEATURE_RECEIPTS = "receipts";
    public static final String FEATURE_DELIVERY = "delivery";
    public static final String FEATURE_READ_ONLY_LOOKUP = "read_only_lookup";
    public static final String FEATURE_BOUND_RESOLUTION = "bound_resolution";

    // --- 0.6.0 public profiles. Advertised only while profiles are live, which is the point: an
    //     unavailable profile answer must never read as a negative one. ---
    public static final String FEATURE_PROFILE_SNAPSHOT = "profile_snapshot_v1";
    public static final String FEATURE_SPEAKER_PROFILE = "speaker_profile_v1";
    public static final String FEATURE_REPEAT_CREDIT = "repeat_credit_v1";
    public static final String FEATURE_PROFILED_DELIVERY = "profiled_delivery_v1";
    public static final String FEATURE_PROFILE_CHANGE = "profile_change_v1";

    /**
     * Every 0.6.0 profile string this build knows about, in a stable order.
     *
     * <p>Only two of the five gate anything here: {@code profile_snapshot_v1} says this mod's shipped
     * profile data is actually in use, and {@code profiled_delivery_v1} is what lets a killing absorb
     * the assault it finished under an operation key. The other three are mirrored so that
     * {@link #describe()} can report the whole picture to an operator diagnosing "why does the village
     * not know me for anything", and so the adapter's name check covers them — this mod reads no
     * speaker profile, needs no credit explanation, and has no consumer for a profile-changed event,
     * and inventing one to look busy would be worse than admitting that.
     */
    public static final List<String> PROFILE_FEATURES = List.of(FEATURE_PROFILE_SNAPSHOT,
            FEATURE_SPEAKER_PROFILE, FEATURE_REPEAT_CREDIT, FEATURE_PROFILED_DELIVERY,
            FEATURE_PROFILE_CHANGE);

    public ReputationCapabilitySnapshot {
        features = features == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(features));
        nativeKinds = nativeKinds == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(nativeKinds));
        readinessReason = readinessReason == null ? "" : readinessReason;
    }

    /**
     * What a build too old to answer the question looks like: nothing advertised.
     *
     * <p>Not "everything supported". A companion that cannot describe itself gets the oldest contract
     * this mod knows how to speak, which is plain {@code record}/{@code resolve} — the 0.3.0 surface
     * the integration was originally written against.
     */
    public static ReputationCapabilitySnapshot unsupported(int apiVersion, boolean enabled, String reason) {
        return new ReputationCapabilitySnapshot(apiVersion, enabled, Set.of(), Set.of(), reason);
    }

    /** No bridge at all. */
    public static ReputationCapabilitySnapshot absent() {
        return new ReputationCapabilitySnapshot(-1, false, Set.of(), Set.of(), "no bridge");
    }

    public boolean has(String feature) {
        return feature != null && features.contains(feature);
    }

    /** Whether keyed, exactly-once delivery with a receipt is available. */
    public boolean supportsDelivery() {
        return has(FEATURE_DELIVERY);
    }

    /**
     * Whether an operation's receipt can be read back without writing anything.
     *
     * <p>Both strings are required, and that is not belt-and-braces: {@code receipts} says a receipt
     * exists to find, {@code read_only_lookup} says finding it creates nothing. Without the second one
     * this mod has no way to discover a lost link that does not involve a write, and the honest
     * response is to not look.
     */
    public boolean supportsReceiptLookup() {
        return has(FEATURE_RECEIPTS) && has(FEATURE_READ_ONLY_LOOKUP);
    }

    /** Whether one deed may absorb an earlier one instead of stacking with it. */
    public boolean supportsSupersede() {
        return has(FEATURE_SUPERSEDE);
    }

    /** Whether a supersession can be delivered under an operation key, profiles and all. */
    public boolean supportsProfiledDelivery() {
        return has(FEATURE_PROFILED_DELIVERY);
    }

    /** Whether a resolution can be bound to one incident id under an operation key. */
    public boolean supportsBoundResolution() {
        return has(FEATURE_BOUND_RESOLUTION);
    }

    /** Whether the public-profile layer is live, and therefore whether our profile data is in use. */
    public boolean supportsProfiles() {
        return has(FEATURE_PROFILE_SNAPSHOT);
    }

    /** A compact line for {@code /crime debug integrations}. */
    public String describe() {
        StringBuilder out = new StringBuilder("api v").append(apiVersion)
                .append(" enabled=").append(enabled)
                .append(" delivery=").append(supportsDelivery())
                .append(" receipts=").append(supportsReceiptLookup())
                .append(" supersede=").append(supportsSupersede())
                .append(" bound_resolution=").append(supportsBoundResolution())
                .append(" profiles=").append(advertisedProfileFeatures());
        if (!readinessReason.isEmpty()) {
            out.append(" (").append(readinessReason).append(')');
        }
        return out.toString();
    }

    /**
     * Which of the profile features are live, or {@code none}.
     *
     * <p>Named individually rather than reduced to a boolean because they go dark together for
     * several different reasons — profiles switched off, no pack published, an older companion — and
     * an operator wondering why a crime produced no recognition needs to see which.
     */
    public String advertisedProfileFeatures() {
        List<String> advertised = new ArrayList<>();
        for (String feature : PROFILE_FEATURES) {
            if (has(feature)) {
                advertised.add(feature);
            }
        }
        return advertised.isEmpty() ? "none" : String.join(",", advertised);
    }
}
