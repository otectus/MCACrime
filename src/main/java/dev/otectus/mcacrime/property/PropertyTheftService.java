package dev.otectus.mcacrime.property;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.WitnessResult;
import dev.otectus.mcacrime.incident.IncidentContext;
import dev.otectus.mcacrime.incident.IncidentService;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns an observed container transfer into a charge, a permission, or an explicit nothing.
 *
 * <h2>The order, and why it is this order</h2>
 *
 * <ol>
 *   <li>Look the policy up. No policy is the ordinary state of the world and ends here.</li>
 *   <li>Ask whether an authorised settlement task covers this exact withdrawal (§10.4).</li>
 *   <li>Ask {@link PropertyAccess} whether this actor may take from this container.</li>
 *   <li>Only a denial produces anything: one incident per group, one receipt per transfer.</li>
 * </ol>
 *
 * <h2>One incident, several transfers</h2>
 *
 * <p>Two rules in the specification pull opposite ways. §10.2 wants one transfer id per committed
 * transfer and a receipt for the actual quantity; §10.5 wants a continuous action grouped into a
 * bounded incident rather than escalated into a charge per stack. Both are honoured by separating the
 * two identities: the <em>receipt</em> is per transfer, so restitution can resolve exactly the lot that
 * was taken, while the <em>incident id</em> is derived from the grouping key, so emptying a chest is
 * one theft rather than fourteen. A transfer id that already has a receipt is never charged again,
 * which is what makes replay after a reconnect safe.
 */
public final class PropertyTheftService {

    /** The detection string this path stamps on every record it commits. */
    public static final String DETECTION = "property_theft";

    private PropertyTheftService() {
    }

    /**
     * Judges one observed transfer without committing anything.
     *
     * <p>Split out from {@link #commit} so the decision can be read by {@code /crime property inspect}
     * and exercised without a world.
     */
    public static TransferAttribution judge(@Nullable ServerLevel level,
                                            TransferAttribution.CommittedTransfer transfer,
                                            PropertyActor actor, boolean supportedMenu) {
        PropertyPolicy policy = level == null ? null
                : PropertyRegistry.policyAt(level, transfer.container()).orElse(null);
        WorkTransferContext work = WorkTransferContext
                .current(transfer.actor(), transfer.dimension(), transfer.container(), transfer.gameTime())
                .orElse(null);
        PropertyAccess.Operation operation = transfer.direction() == TransferAttribution.Direction.IN
                ? PropertyAccess.Operation.PUT : PropertyAccess.Operation.TAKE;
        PropertyAccess.Decision decision = PropertyAccess.decide(policy, actor, operation);
        return TransferAttribution.of(transfer, policy, decision, work, supportedMenu);
    }

    /**
     * Commits whatever the attribution says should be committed.
     *
     * @return the crime record id when this transfer was charged, or empty
     */
    public static Optional<UUID> commit(@Nullable ServerLevel level, @Nullable ServerPlayer offender,
                                        TransferAttribution attribution) {
        if (level == null || offender == null || level.getServer() == null || !attribution.charges()) {
            return Optional.empty();
        }
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        if (data.hasPropertyReceipt(attribution.transferId())) {
            // Already accounted for. §10.2's "prevents duplicate debit" is one map lookup, and it is the
            // reason the transfer id is derived rather than random.
            return Optional.empty();
        }
        PropertyPolicy policy = attribution.policyId() == null ? null
                : data.propertyPolicy(attribution.policyId());
        if (policy == null) {
            return Optional.empty();
        }

        UUID incidentId = incidentIdFor(attribution.groupingKey());
        IncidentContext context = contextFor(level, attribution, policy);
        Map<String, String> provenance = new LinkedHashMap<>();
        provenance.put(CrimeContext.TRANSFER_ID, attribution.transferId().toString());
        provenance.put(CrimeContext.TRANSFER_GROUP, attribution.groupingKey());

        // The record may already exist: every transfer in the same five-second window derives the same
        // incident id, and the ledger refuses the duplicate. That is the grouping, not a failure, so the
        // receipt is written either way and points at the record the group produced.
        IncidentService.commitPlayer(incidentId, offender, CrimeIds.THEFT, null, level,
                WitnessResult.none(), DETECTION, provenance, null, null, null, context);

        data.putPropertyReceipt(PropertyReceipt.lost(attribution, policy, incidentId));
        return Optional.of(incidentId);
    }

    /**
     * The typed context a property theft is recorded under.
     *
     * <p>The one place the new jurisdiction rule actually bites: the community comes from the policy's
     * building, so a theft inside a settlement's granary is that settlement's business even when the
     * offender lives next door and the container's registered owner is somebody who moved away.
     */
    public static IncidentContext contextFor(ServerLevel level, TransferAttribution attribution,
                                             PropertyPolicy policy) {
        ResourceLocation dimension = level.dimension().location();
        BlockPos where = attribution.transfer().container();
        CrimeCommunityKey jurisdiction = policy.jurisdiction().orElse(null);
        return IncidentContext.at(dimension, where)
                .withProperty(jurisdiction, policy.id(), policy.revision());
    }

    /**
     * The crime record id one grouping key always produces.
     *
     * <p>Derived so that the second, third and fourteenth transfer of one continuous theft land on the
     * record the first one opened instead of opening records of their own.
     */
    public static UUID incidentIdFor(String groupingKey) {
        return UUID.nameUUIDFromBytes(("mcacrime:property-incident:" + groupingKey)
                .getBytes(StandardCharsets.UTF_8));
    }

    // --- restitution (spec 10.6) ------------------------------------------------------------------

    /**
     * Marks every outstanding receipt for goods this actor has just put back into the same container.
     *
     * <p>Deliberately narrow. It resolves the corresponding property loss and nothing else: the case
     * itself, and anything else bound to it, is settled by the ordinary routes. §10.6 is explicit that
     * returning stolen bread must never erase an assault, and the way to guarantee that is for this
     * method to touch only receipts.
     *
     * @return how many receipts were marked returned
     */
    public static int restore(@Nullable ServerLevel level, @Nullable UUID actor,
                              TransferAttribution.CommittedTransfer deposit) {
        if (level == null || level.getServer() == null || actor == null) {
            return 0;
        }
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        int restored = 0;
        int remaining = deposit.count();
        for (PropertyReceipt receipt : data.outstandingPropertyReceipts(actor, deposit.dimension(),
                deposit.container())) {
            if (remaining <= 0) {
                break;
            }
            // The lot has to match. Returning a stack of dirt must not settle a loss of emeralds, and
            // "preserve item data and prevent repeated turn-ins against the same lot" is exactly that.
            if (receipt.fingerprint().isEmpty()
                    || !receipt.fingerprint().equals(deposit.fingerprint())) {
                continue;
            }
            if (remaining < receipt.count()) {
                // Short. The lot is not back, and saying it is would grant the restitution treatment for
                // a fraction of the goods -- so the loss stays outstanding and is marked uncertain,
                // which is the state §10.2 asks for rather than a debit repeated or a reward granted.
                PropertyReceipt partial = receipt.unresolved(deposit.gameTime());
                if (partial != receipt) {
                    data.putPropertyReceipt(partial);
                }
                break;
            }
            PropertyReceipt marked = receipt.restored(deposit.gameTime());
            if (marked != receipt && data.putPropertyReceipt(marked)) {
                remaining -= receipt.count();
                restored++;
                // A returned lot is also the accepted output a restitution-delivery contract is
                // measured in (reference §12.1). Credited by transfer id, so the same lot cannot be
                // counted twice, and a no-op on every server with community service switched off.
                dev.otectus.mcacrime.civic.CivicWorkService.credit(level.getServer(), actor,
                        dev.otectus.mcacrime.civic.CivicTask.RESTITUTION_DELIVERY,
                        "property:" + receipt.transferId(), 1);
            }
        }
        return restored;
    }

    /**
     * Marks the receipts belonging to cases a paid fine has just settled.
     *
     * <p>The second half of §10.6: a fine is the route for goods that were consumed or transformed and
     * cannot be handed back. It resolves the property loss and leaves everything else about the case to
     * the existing settlement path, which has already run by the time this is called.
     *
     * @return how many receipts were marked returned
     */
    public static int restoreForCases(@Nullable MinecraftServer server, List<UUID> settledCaseIds,
                                      long now) {
        if (server == null || settledCaseIds == null || settledCaseIds.isEmpty()) {
            return 0;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        List<UUID> cases = new ArrayList<>(settledCaseIds);
        int restored = 0;
        for (PropertyReceipt receipt : data.propertyReceipts()) {
            if (!receipt.outstanding() || receipt.incidentId() == null
                    || !cases.contains(receipt.incidentId())) {
                continue;
            }
            PropertyReceipt marked = receipt.restored(now);
            if (marked != receipt && data.putPropertyReceipt(marked)) {
                restored++;
            }
        }
        return restored;
    }

}
