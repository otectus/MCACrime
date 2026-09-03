package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.ledger.Resolution;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;

/**
 * Which civic incident each crime type produces, and which resolved status each legal disposition
 * maps to.
 *
 * <p>Deliberately lives in {@code compat/} rather than {@code compat/reputation/}: it names no
 * companion type, only resource ids and lowercase status strings, so it is always loadable and can be
 * unit-tested with MCA: Reputation absent from the classpath entirely.
 *
 * <h2>One crime type, one incident</h2>
 *
 * <p>The table is a plain map with exactly one entry per crime type. That makes "one case produces at
 * most one incident" structural rather than a rule somebody has to remember — it is why a
 * {@code mugging_murder} can never also emit a generic killing.
 *
 * <h2>Why two of them reuse Reputation's own ids</h2>
 *
 * <p>Assault and killing map to {@code mcareputation:villager_assaulted} and
 * {@code mcareputation:villager_killed} rather than to new {@code mcacrime:} ids. Existing dialogue,
 * gossip phrases, and incident-tag queries in the wider suite already reference those two. Minting
 * parallel ids would fork that content and leave a server with two vocabularies for one deed.
 */
public final class CrimeIncidentMapping {

    private static final String REPUTATION = "mcareputation";
    private static final String CRIME = "mcacrime";

    /** Reputation's own assault incident, reused because existing content already speaks it. */
    public static final ResourceLocation VILLAGER_ASSAULTED =
            new ResourceLocation(REPUTATION, "villager_assaulted");
    /** Reputation's own killing incident, reused for the same reason. */
    public static final ResourceLocation VILLAGER_KILLED =
            new ResourceLocation(REPUTATION, "villager_killed");

    /** Crime-authored incidents, shipped as datapack JSON in this mod's namespace. */
    public static final ResourceLocation GUARD_ASSAULTED = new ResourceLocation(CRIME, "guard_assaulted");
    public static final ResourceLocation JAILBREAK = new ResourceLocation(CRIME, "jailbreak");
    public static final ResourceLocation KIDNAPPING = new ResourceLocation(CRIME, "kidnapping");
    public static final ResourceLocation THEFT = new ResourceLocation(CRIME, "theft");
    public static final ResourceLocation MUGGING_MURDER = new ResourceLocation(CRIME, "mugging_murder");

    /** Positive civic deeds. These add context; they never erase the incident they follow. */
    public static final ResourceLocation FINE_PAID = new ResourceLocation(CRIME, "fine_paid");
    public static final ResourceLocation SENTENCE_SERVED = new ResourceLocation(CRIME, "sentence_served");
    public static final ResourceLocation CAPTIVE_RESCUED = new ResourceLocation(CRIME, "captive_rescued");

    /** The deeds MCA: Reputation would otherwise detect itself, and so must be claimed as authority. */
    private static final Map<ResourceLocation, ResourceLocation> OVERLAPPING = Map.of(
            CrimeIds.HARM_VILLAGER, VILLAGER_ASSAULTED,
            CrimeIds.KILL_VILLAGER, VILLAGER_KILLED);

    private static final Map<ResourceLocation, ResourceLocation> INCIDENTS = Map.of(
            CrimeIds.HARM_VILLAGER, VILLAGER_ASSAULTED,
            CrimeIds.KILL_VILLAGER, VILLAGER_KILLED,
            CrimeIds.ASSAULT_GUARD, GUARD_ASSAULTED,
            CrimeIds.JAILBREAK, JAILBREAK,
            CrimeIds.KIDNAP, KIDNAPPING,
            CrimeIds.THEFT, THEFT,
            CrimeIds.MUGGING_MURDER, MUGGING_MURDER);

    private CrimeIncidentMapping() {
    }

    /** The single incident this crime type produces, or empty when it has no civic meaning. */
    public static Optional<ResourceLocation> incidentFor(ResourceLocation crimeType) {
        return Optional.ofNullable(INCIDENTS.get(crimeType));
    }

    /**
     * Whether this crime is one MCA: Reputation detects natively, and therefore one we must hold
     * authority over before recording it ourselves. Recording an overlapping deed without the claim
     * is exactly the double-count the handshake exists to prevent.
     */
    public static boolean overlapsNativeDetection(ResourceLocation crimeType) {
        return OVERLAPPING.containsKey(crimeType);
    }

    /**
     * The civic status a legal disposition maps to, as a bounded lowercase string so no companion
     * enum crosses the always-loadable boundary. Empty means the disposition changes nothing civically.
     *
     * <p>{@code ESCAPED} maps to nothing on purpose — breaking out of jail is not atonement, and the
     * original incident must stay active. {@code EXPIRED} likewise: a case ageing out of the ledger is
     * not the village deciding to forgive a murder.
     */
    public static Optional<String> statusFor(Resolution resolution, String fineStatus, String servedStatus) {
        if (resolution == null) {
            return Optional.empty();
        }
        return switch (resolution) {
            case FINED -> Optional.of(normalise(fineStatus, "atoned"));
            case SERVED -> Optional.of(normalise(servedStatus, "atoned"));
            case PARDONED -> Optional.of("forgiven");
            case UNRESOLVED, ESCAPED, EXPIRED -> Optional.empty();
        };
    }

    /** The statuses a config may name for a fine or a served sentence. */
    public static boolean isValidConfiguredStatus(String raw) {
        return "atoned".equals(raw) || "apologized".equals(raw);
    }

    private static String normalise(String configured, String fallback) {
        return isValidConfiguredStatus(configured) ? configured : fallback;
    }
}
