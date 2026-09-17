package dev.otectus.mcacrime.api.model;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.ledger.Resolution;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * What one community may know about one person — and nothing else.
 *
 * <h2>Truth, knowledge and presentation are three different things</h2>
 *
 * <p>MCA: Crime's ledger is the truth: every case, witnessed or not, resolved or not, wherever it
 * happened. {@code McaCrimeApi.selectRecords} hands that out, correctly, to the player it is about. It
 * is the wrong thing to hand to a village (reference §11.1): a settlement that reacted to every
 * {@code CrimeCommittedEvent} would know about a burglary nobody saw, in a village on another
 * continent, in the same tick it happened. That is not a strict village — it is a mod reading the
 * player's mind, and no amount of tuning makes it feel like anything else.
 *
 * <p>So this is the projection with the knowledge rule applied. It is scoped to <em>one observer
 * community</em>, it contains only what accepted reports and case resolutions have made public there,
 * and it is what a settlement reaction, a dialogue line or a client-side status icon is allowed to be
 * built from.
 *
 * <h2>The one rule that matters</h2>
 *
 * <p>{@link #isPublic} is deliberately the same rule {@code CrimeIntegrationHooks.onCommitted} uses to
 * decide whether a deed may be filed as a civic incident. It has to be: if the projection were more
 * generous than the filing rule, a village would react to something MCA: Reputation never recorded,
 * and the two would tell the player different stories about the same evening. Both are pure functions
 * of the case and the reports, and neither reads the other's output.
 *
 * @param community        the community doing the knowing; never empty
 * @param subject          who the view is about
 * @param band             their public standing band, as this mod shows it (Lawful / Neutral / Outlaw)
 * @param wanted           whether they are wanted here
 * @param standing         this community's own standing number for them
 * @param publicIncidents  how many incidents this community knows about
 * @param openIncidents    how many of those are still unsettled
 * @param openBountyAmount the posted bounty on them, or zero
 * @param recent           the newest known incidents, capped; oldest facts drop off first
 */
public record CrimePublicView(CrimeCommunityKey community, UUID subject, Band band, boolean wanted,
                              int standing, int publicIncidents, int openIncidents,
                              long openBountyAmount, List<PublicIncident> recent) {

    /**
     * How many incidents one view carries.
     *
     * <p>A ceiling on a projection, not on the ledger. A public view is read to draw a line of text, to
     * pick a reaction, or to fill a packet, and none of those gets better with a hundred rows; the
     * count fields carry the magnitude, and the list carries the recognisable ones.
     */
    public static final int MAX_RECENT = 16;

    /**
     * One incident a community knows about.
     *
     * <p>Note what is absent: no witness ids, no victim, no heat, no fine, no context map. A public
     * incident is a thing the village can say out loud — what happened, roughly when, and whether it is
     * settled. Who saw it is the witness's own memory and belongs to the victim/eyewitness views
     * (§11.1), not to everyone in earshot.
     */
    public record PublicIncident(UUID caseId, ResourceLocation crimeType, long gameTime,
                                 Resolution resolution) {

        /** Whether the community still considers this outstanding. */
        public boolean open() {
            return resolution == Resolution.UNRESOLVED || resolution == Resolution.ESCAPED;
        }
    }

    public CrimePublicView {
        recent = recent == null ? List.of() : List.copyOf(recent);
        publicIncidents = Math.max(0, publicIncidents);
        openIncidents = Math.max(0, openIncidents);
        openBountyAmount = Math.max(0L, openBountyAmount);
        band = band == null ? Band.GREY : band;
    }

    /** An empty view: this community knows nothing about this person, which is the usual answer. */
    public static CrimePublicView empty(CrimeCommunityKey community, UUID subject) {
        return new CrimePublicView(community, subject, Band.GREY, false, 0, 0, 0, 0L, List.of());
    }

    /** Whether this community knows anything at all. */
    public boolean known() {
        return publicIncidents > 0 || wanted || openBountyAmount > 0L;
    }

    /**
     * Whether one case is public knowledge in {@code observer}'s community.
     *
     * <p>Four questions, in order, and each one removes a different kind of private information:
     *
     * <ol>
     *   <li><b>Is it this community's business?</b> A case with no community happened in the
     *       wilderness; a case in another community is that community's to know. Neither travels.</li>
     *   <li><b>Did anybody see it?</b> An unwitnessed deed is the whole reason this method exists. It is
     *       a real case, it carries real Heat, and no villager anywhere may act on it.</li>
     *   <li><b>Did an authority already know?</b> A jailbreak and an operator command are public by
     *       their nature — nobody has to report a prisoner missing from a cell — and they carry no
     *       witness list, which is why the witness test alone would wrongly hide them.</li>
     *   <li><b>Did it reach an authority?</b> With observations enabled, being seen is not being
     *       reported: knowledge becomes public when a witness's account actually supports action. With
     *       observations off there is no reporting layer at all and witnessed is the whole rule.</li>
     * </ol>
     *
     * @param view                 the case
     * @param observer             the community being asked about
     * @param observationsEnabled  whether the report layer is running at all
     * @param reportedToAuthority  whether an accepted report against this case exists; consulted only
     *                             when observations are enabled
     */
    public static boolean isPublic(CrimeRecordView view, CrimeCommunityKey observer,
                                   boolean observationsEnabled, Predicate<UUID> reportedToAuthority) {
        if (view == null || observer == null) {
            return false;
        }
        Optional<CrimeCommunityKey> community = view.community();
        if (community.isEmpty() || !community.get().equals(observer)) {
            return false;
        }
        String detection = view.context("detection").orElse("");
        boolean authorityKnown = "jailbreak".equals(detection) || "command".equals(detection);
        if (!view.witnessed() && !authorityKnown) {
            return false;
        }
        if (!observationsEnabled || authorityKnown) {
            return true;
        }
        return reportedToAuthority != null && reportedToAuthority.test(view.id());
    }

    /**
     * Builds the view, as a pure function of the cases and the knowledge rule.
     *
     * <p>No server, no world data and no config: everything that needs one is a parameter, which is
     * what lets the privacy rule be asserted directly rather than inferred from a packet somebody
     * captured.
     */
    public static CrimePublicView of(CrimeCommunityKey community, UUID subject, Band band, boolean wanted,
                                     int standing, long openBountyAmount, List<CrimeRecordView> cases,
                                     boolean observationsEnabled, Predicate<UUID> reportedToAuthority) {
        if (community == null || subject == null) {
            return empty(community, subject);
        }
        List<PublicIncident> known = new ArrayList<>();
        int open = 0;
        for (CrimeRecordView view : cases == null ? List.<CrimeRecordView>of() : cases) {
            if (!isPublic(view, community, observationsEnabled, reportedToAuthority)) {
                continue;
            }
            PublicIncident incident = new PublicIncident(view.id(), view.crimeType(),
                    view.committedGameTime(), view.resolution());
            known.add(incident);
            if (incident.open()) {
                open++;
            }
        }
        int total = known.size();
        known.sort(Comparator.comparingLong(PublicIncident::gameTime).reversed());
        if (known.size() > MAX_RECENT) {
            known = new ArrayList<>(known.subList(0, MAX_RECENT));
        }
        return new CrimePublicView(community, subject, band, wanted, standing, total, open,
                openBountyAmount, List.copyOf(known));
    }
}
