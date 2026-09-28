package dev.otectus.mcacrime.ledger;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detention.ExecutionAuthorization;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Whether a sentence is capital, and everything that may change that answer (0.7.5 §3.19, M6.6).
 *
 * <p>The whole feature's gate lives here, and it is deliberately small enough to read in one sitting:
 *
 * <ul>
 *   <li><b>One offence.</b> {@link #qualifies} is true only for an unresolved
 *       {@code mcacrime:kill_guard} case. No Heat, no charge count, no band and no combination of
 *       other crimes reaches it — a mugging that killed a guard files {@code mugging_murder} and is
 *       not one of these.</li>
 *   <li><b>Two switches.</b> {@code enabled} turns the feature off entirely and
 *       {@code guardKillingIsCapital} removes the only qualifying offence. Either one off means every
 *       sentence is custodial.</li>
 *   <li><b>Marked once.</b> {@link #mark} is idempotent and never upgrades: a sentence already bound
 *       keeps the kind it was bound with, so a replayed arrival, a second arrest or a re-priced
 *       holding term cannot turn a custodial sentence into a death sentence.</li>
 *   <li><b>Cleared only by clemency.</b> {@link #commute} and {@link #pardon} are the only ways back,
 *       and both are explicit privileged transactions — which {@link CaseTransitions} already
 *       requires of any pardon.</li>
 * </ul>
 *
 * <p>It is also the {@link ExecutionAuthorization.CondemnedSource}: M4 shipped the device half with a
 * source that condemned nobody, and {@link #install} replaces it with this. That indirection is why
 * a guillotine in a build with no capital sentence detains and releases and never executes.
 */
public final class CapitalSentenceService {

    private CapitalSentenceService() {
    }

    // --- configuration -------------------------------------------------------------------------------

    /** {@code sentencing.capitalPunishment.enabled}. */
    public static boolean featureEnabled() {
        try {
            return McaCrimeConfig.COMMON.capitalPunishmentEnabled.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return true;
        }
    }

    /** {@code sentencing.capitalPunishment.guardKillingIsCapital}: the only offence gate. */
    public static boolean guardKillingIsCapital() {
        try {
            return McaCrimeConfig.COMMON.guardKillingIsCapital.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return true;
        }
    }

    /** {@code sentencing.capitalPunishment.npcOffendersEligible}. Off by default. */
    public static boolean npcOffendersEligible() {
        try {
            return McaCrimeConfig.COMMON.npcOffendersEligible.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return false;
        }
    }

    /** {@code sentencing.capitalPunishment.refuseRansom}. */
    public static boolean refusesRansom() {
        try {
            return McaCrimeConfig.COMMON.capitalRefusesRansom.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return true;
        }
    }

    /** {@code sentencing.capitalPunishment.refuseBail}. */
    public static boolean refusesBail() {
        try {
            return McaCrimeConfig.COMMON.capitalRefusesBail.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return true;
        }
    }

    /** {@code sentencing.capitalPunishment.dropPossessionsOnExecution}. */
    public static boolean dropsPossessions() {
        try {
            return McaCrimeConfig.COMMON.dropPossessionsOnExecution.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return true;
        }
    }

    // --- eligibility ---------------------------------------------------------------------------------

    /**
     * Whether one crime id is the qualifying offence.
     *
     * <p>Pure, and the single point the whole feature turns on. Everything else about eligibility is
     * configuration, which is why this takes none.
     */
    public static boolean qualifyingOffence(@Nullable ResourceLocation crimeType) {
        return CrimeIds.KILL_GUARD.equals(crimeType);
    }

    /**
     * Whether an assessed case list would produce a capital sentence.
     *
     * <p>Pure in its arguments so the whole eligibility table can be asserted: the caller resolves
     * the cases and the configuration, and this decides. An {@code ESCAPED} case counts, like it does
     * everywhere else in the ledger — escaping is not forgiveness.
     */
    public static boolean qualifies(Collection<CrimeRecord> assessed, boolean enabled,
                                    boolean guardKillingIsCapital, boolean offenderIsNpc,
                                    boolean npcOffendersEligible) {
        if (!enabled || !guardKillingIsCapital || assessed == null || assessed.isEmpty()) {
            return false;
        }
        if (offenderIsNpc && !npcOffendersEligible) {
            return false;
        }
        for (CrimeRecord record : assessed) {
            if (record != null && qualifyingOffence(record.type())
                    && CaseTransitions.isActionable(record.resolution())) {
                return true;
            }
        }
        return false;
    }

    /** The same question against the live configuration. */
    public static boolean qualifies(Collection<CrimeRecord> assessed, boolean offenderIsNpc) {
        return qualifies(assessed, featureEnabled(), guardKillingIsCapital(), offenderIsNpc,
                npcOffendersEligible());
    }

    /** The assessed cases behind one sentence, resolved from the ledger. */
    public static List<CrimeRecord> casesFor(@Nullable CrimeWorldData data, @Nullable UUID offender,
                                             @Nullable UUID sentenceId) {
        if (data == null || offender == null || sentenceId == null) {
            return List.of();
        }
        return data.casesForSentence(offender, sentenceId);
    }

    // --- marking -------------------------------------------------------------------------------------

    /**
     * Marks a freshly bound sentence capital, if it qualifies.
     *
     * <p>Called by {@code enforcement/ArrestService} and {@code enforcement/NpcArrestService} straight
     * after {@code SentenceAssignmentService.assign}, with the same assessed case ids. Idempotent: a
     * sentence that is already capital stays capital and a sentence already bound as custodial is
     * never upgraded, which is the "replayed arrival cannot expand it" rule extended to the kind.
     *
     * @return the kind the sentence now carries
     */
    public static SentenceKind mark(@Nullable CrimeWorldData data, @Nullable UUID offender,
                                    @Nullable UUID sentenceId, boolean offenderIsNpc) {
        if (data == null || offender == null || sentenceId == null) {
            return SentenceKind.CUSTODIAL;
        }
        SentenceKind existing = data.sentenceKind(sentenceId);
        if (existing.capital()) {
            mirror(data, offender, existing);
            return existing;
        }
        CustodyRecord held = data.getCustody(offender);
        if (held != null && held.getSentenceId() != null && !sentenceId.equals(held.getSentenceId())) {
            // This is not the sentence the custody is serving. Marking it would condemn somebody for a
            // binding nobody is holding them under.
            return SentenceKind.CUSTODIAL;
        }
        if (!qualifies(casesFor(data, offender, sentenceId), offenderIsNpc)) {
            return SentenceKind.CUSTODIAL;
        }
        if (!data.setSentenceKind(sentenceId, SentenceKind.CAPITAL)) {
            return SentenceKind.CUSTODIAL; // the store refused; nobody is condemned by a failed write
        }
        mirror(data, offender, SentenceKind.CAPITAL);
        CustodyRecord bound = data.getCustody(offender);
        dev.otectus.mcacrime.incident.IncidentNotifications.post(
                new dev.otectus.mcacrime.api.event.CapitalSentenceAssignedEvent(offender,
                        bound != null && bound.isCaptivePlayer(), sentenceId,
                        bound == null || bound.getOwner() == null ? null
                                : bound.getOwner().ownerUuid().orElse(null),
                        bound == null ? 0L : bound.getRemainingJailTicks()));
        McaCrime.LOGGER.info("MCA: Crime - sentence {} for {} is capital: an unresolved {} case stands",
                sentenceId, offender, CrimeIds.KILL_GUARD);
        return SentenceKind.CAPITAL;
    }

    /** Copies the kind onto the custody record that is serving it, when there is one. */
    private static void mirror(CrimeWorldData data, UUID offender, SentenceKind kind) {
        CustodyRecord held = data.getCustody(offender);
        if (held != null && held.getSentenceKind() != kind) {
            held.setSentenceKind(kind);
            data.setDirty();
        }
    }

    // --- reading -------------------------------------------------------------------------------------

    /** What one sentence is. */
    public static SentenceKind kindOf(@Nullable CrimeWorldData data, @Nullable UUID sentenceId) {
        return data == null ? SentenceKind.CUSTODIAL : data.sentenceKind(sentenceId);
    }

    /**
     * Whether a live capital sentence names this subject.
     *
     * <p>The custody record first, because that is the sentence they are actually being held under;
     * the jail state second, for a player whose custody row has been settled but whose sentence has
     * not. Nobody who is not in custody is condemned: a capital sentence is served in a cell.
     */
    public static boolean condemned(@Nullable MinecraftServer server, @Nullable UUID subject) {
        if (server == null || subject == null) {
            return false;
        }
        try {
            CrimeWorldData data = CrimeWorldData.get(server);
            if (data == null) {
                return false;
            }
            CustodyRecord held = data.getCustody(subject);
            if (held == null) {
                return false;
            }
            if (held.isCondemned()) {
                return true;
            }
            return data.sentenceKind(held.getSentenceId()).capital();
        } catch (RuntimeException failed) {
            return false; // a broken read must never authorise a death
        }
    }

    /** Every live capital sentence, as sentence id to the subject serving it. Diagnostics. */
    public static Map<UUID, UUID> live(@Nullable CrimeWorldData data) {
        if (data == null) {
            return Map.of();
        }
        Map<UUID, UUID> out = new java.util.LinkedHashMap<>();
        for (CustodyRecord record : data.custodyRecords()) {
            if (record.getSentenceId() != null && data.sentenceKind(record.getSentenceId()).capital()) {
                out.put(record.getSentenceId(), record.getCaptive());
            }
        }
        return Map.copyOf(out);
    }

    // --- clemency ------------------------------------------------------------------------------------

    /** What a clemency attempt did. Each is a distinct message rather than a silent no-op. */
    public enum Clemency {
        /** Done. */
        GRANTED,
        /** Nobody by that name is under a capital sentence. */
        NOT_CONDEMNED,
        /** The store is read-only, or the write was refused. */
        REFUSED
    }

    /**
     * Commutes a capital sentence to a custodial one, keeping the holding term.
     *
     * <p>It never resurrects anybody and it never shortens anything: the prisoner stays in the cell
     * with the same number of ticks left. Any pending execution is cleared, which returns them to
     * <i>condemned in custody</i> and then, with the kind rewritten, to an ordinary prisoner.
     */
    public static Clemency commute(@Nullable MinecraftServer server, @Nullable UUID subject) {
        if (server == null || subject == null || !ServerMutationGate.allows(server)) {
            return Clemency.REFUSED;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        CustodyRecord held = data == null ? null : data.getCustody(subject);
        if (data == null || held == null || held.getSentenceId() == null
                || !data.sentenceKind(held.getSentenceId()).capital()) {
            return Clemency.NOT_CONDEMNED;
        }
        if (!data.setSentenceKind(held.getSentenceId(), SentenceKind.CUSTODIAL)) {
            return Clemency.REFUSED;
        }
        held.setSentenceKind(SentenceKind.CUSTODIAL);
        data.setDirty();
        ExecutionAuthorization.clear(subject, ExecutionAuthorization.ClearReason.COMMUTED);
        dev.otectus.mcacrime.enforcement.CondemnedEscortService.cancel(server, subject,
                "the sentence was commuted");
        dev.otectus.mcacrime.incident.IncidentNotifications.post(
                new dev.otectus.mcacrime.api.event.SentenceCommutedEvent(subject,
                        held.isCaptivePlayer(), held.getSentenceId(), null, false,
                        held.getRemainingJailTicks()));
        McaCrime.LOGGER.info("MCA: Crime - the capital sentence {} against {} was commuted; the holding "
                + "term stands", held.getSentenceId(), subject);
        return Clemency.GRANTED;
    }

    /**
     * Pardons the cases a capital sentence was for, and clears the sentence with them.
     *
     * <p>A privileged transaction, because {@link CaseTransitions} requires one of any pardon: no
     * quest reward, dialogue click or companion mod can reach this path, and an operator's command
     * looks like what it is.
     */
    public static Clemency pardon(@Nullable MinecraftServer server, @Nullable UUID subject,
                                  @Nullable UUID pardonedBy) {
        if (server == null || subject == null || !ServerMutationGate.allows(server)) {
            return Clemency.REFUSED;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        CustodyRecord held = data == null ? null : data.getCustody(subject);
        if (data == null || held == null || held.getSentenceId() == null
                || !data.sentenceKind(held.getSentenceId()).capital()) {
            return Clemency.NOT_CONDEMNED;
        }
        UUID sentenceId = held.getSentenceId();
        long now = server.overworld().getGameTime();
        List<UUID> pardoned = new ArrayList<>();
        for (CrimeRecord record : data.casesForSentence(subject, sentenceId)) {
            CrimeCaseService.Result result = CrimeCaseService.resolve(data, now, record.id(),
                    Resolution.PARDONED, McaCrime.id("capital_pardon"), "capital_pardon:" + sentenceId,
                    pardonedBy == null ? subject : pardonedBy,
                    Map.of(CrimeContext.SENTENCE_ID, sentenceId.toString()), true,
                    CrimeCaseService.ResolutionGate.ALLOW_ALL,
                    CrimeCaseService.ResolutionSink.forServer(server));
            if (result.successful()) {
                pardoned.add(record.id());
            }
        }
        data.setSentenceKind(sentenceId, SentenceKind.CUSTODIAL);
        held.setSentenceKind(SentenceKind.CUSTODIAL);
        data.setDirty();
        ExecutionAuthorization.clear(subject, ExecutionAuthorization.ClearReason.PARDONED);
        dev.otectus.mcacrime.enforcement.CondemnedEscortService.cancel(server, subject,
                "the sentence was pardoned");
        dev.otectus.mcacrime.incident.IncidentNotifications.post(
                new dev.otectus.mcacrime.api.event.SentenceCommutedEvent(subject,
                        held.isCaptivePlayer(), sentenceId, pardonedBy, true,
                        held.getRemainingJailTicks()));
        McaCrime.LOGGER.info("MCA: Crime - the capital sentence {} against {} was pardoned; {} case(s) "
                + "closed", sentenceId, subject, pardoned.size());
        return Clemency.GRANTED;
    }

    // --- the device seam -----------------------------------------------------------------------------

    /**
     * Installs this service as the answer to "is this subject condemned?".
     *
     * <p>Called once, from common setup. Until it runs, {@link ExecutionAuthorization}'s default
     * source says nobody is, and a guillotine can detain and release and nothing else.
     */
    public static void install() {
        ExecutionAuthorization.bind(CapitalSentenceService::condemned);
    }

    /** Restores "nobody is condemned". Server stop, and every test's setup. */
    public static void uninstall() {
        ExecutionAuthorization.bind(null);
    }
}
