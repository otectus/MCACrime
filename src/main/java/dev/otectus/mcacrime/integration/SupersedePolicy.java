package dev.otectus.mcacrime.integration;

import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.compat.CrimeIncidentMapping;
import dev.otectus.mcacrime.ledger.Resolution;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * When a killing should absorb the assault that preceded it, rather than be recorded beside it.
 *
 * <p>MCA: Reputation's own detector already does this: a villager beaten and then killed is one
 * encounter, and the killing's figure replaces the assault's instead of stacking on top of it. While
 * this mod holds the detection authority, that fold is ours to ask for — and if we do not ask, a
 * player who hits a villager twice and then kills them pays the assault <em>and</em> the killing,
 * which is a heavier penalty than MCA: Reputation would ever have applied on its own. Claiming a deed
 * and then charging more for it than the mod we claimed it from is not a defensible outcome.
 *
 * <p>Pure arithmetic over views, so every rule below is testable without a server, a ledger, or the
 * companion mod. The window comes from config; zero switches the fold off entirely.
 *
 * <h2>What is deliberately <em>not</em> a precursor</h2>
 *
 * <ul>
 *   <li>A case against a different victim. One swing at a farmer does not become part of killing the
 *       blacksmith, and MCA: Reputation is asked to require the shared subject as well.</li>
 *   <li>A case in a different village. The two incidents live in different ledgers; there is nothing
 *       to fold.</li>
 *   <li>A case with no linked civic incident. There is nothing on the companion's side to absorb yet —
 *       the create is still in the outbox, and the killing is recorded on its own terms.</li>
 *   <li>A case that has already been settled. A fine paid for the assault is an atonement the village
 *       accepted; folding that record into a later killing would quietly delete it.</li>
 * </ul>
 */
public final class SupersedePolicy {

    private SupersedePolicy() {
    }

    /**
     * The incident a fatal encounter should supersede, when there is one.
     *
     * @param successorIncident the civic incident the new case produces
     * @param successor         the new case
     * @param priorCases        this offender's other cases, in any order
     * @param windowTicks       how far back a precursor may lie; {@code 0} disables the fold
     * @return the linked civic incident id of the precursor case, or empty
     */
    public static Optional<UUID> precursorFor(ResourceLocation successorIncident, CrimeRecordView successor,
                                              List<CrimeRecordView> priorCases, long windowTicks) {
        if (successorIncident == null || successor == null || priorCases == null || windowTicks <= 0L) {
            return Optional.empty();
        }
        if (!isFatal(successorIncident) || successor.victimId().isEmpty()
                || successor.community().isEmpty()) {
            return Optional.empty();
        }
        UUID victim = successor.victimId().get();
        CrimeRecordView best = null;
        for (CrimeRecordView candidate : priorCases) {
            if (!isPrecursor(candidate, successor, victim, windowTicks)) {
                continue;
            }
            if (best == null || newer(candidate, best)) {
                best = candidate;
            }
        }
        return best == null ? Optional.empty() : best.linkedReputationIncidentId();
    }

    /**
     * Whether this civic incident is the fatal end of an encounter.
     *
     * <p>Both of ours count. A mugging murder is a killing with a motive attached, and MCA: Reputation
     * treats it as one for supersession purposes because the precursor it absorbs is the same assault
     * either way.
     */
    public static boolean isFatal(ResourceLocation incident) {
        return CrimeIncidentMapping.VILLAGER_KILLED.equals(incident)
                || CrimeIncidentMapping.MUGGING_MURDER.equals(incident);
    }

    private static boolean isPrecursor(CrimeRecordView candidate, CrimeRecordView successor, UUID victim,
                                       long windowTicks) {
        if (candidate == null || candidate.id().equals(successor.id())) {
            return false;
        }
        if (!CrimeIncidentMapping.VILLAGER_ASSAULTED
                .equals(CrimeIncidentMapping.incidentFor(candidate.crimeType()).orElse(null))) {
            return false;
        }
        if (candidate.victimId().isEmpty() || !candidate.victimId().get().equals(victim)) {
            return false;
        }
        if (candidate.community().isEmpty()
                || !candidate.community().get().equals(successor.community().get())) {
            return false;
        }
        if (candidate.linkedReputationIncidentId().isEmpty()
                || candidate.resolution() != Resolution.UNRESOLVED) {
            return false;
        }
        long gap = successor.committedGameTime() - candidate.committedGameTime();
        return gap >= 0L && gap <= windowTicks;
    }

    /** Deterministic newest-first ordering, so a replay picks the same precursor every time. */
    private static boolean newer(CrimeRecordView candidate, CrimeRecordView incumbent) {
        if (candidate.committedGameTime() != incumbent.committedGameTime()) {
            return candidate.committedGameTime() > incumbent.committedGameTime();
        }
        return candidate.id().compareTo(incumbent.id()) > 0;
    }
}
