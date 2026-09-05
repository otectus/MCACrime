package dev.otectus.mcacrime.ledger;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.api.event.CrimeCommittedEvent;
import dev.otectus.mcacrime.api.event.WantedStatusChangedEvent;
import dev.otectus.mcacrime.api.event.WarrantChangedEvent;
import dev.otectus.mcacrime.state.CrimeAttachments;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;

import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps one {@link Warrant} per offender in step with their Wanted state (0.5.1).
 *
 * <p>A bounty needs an identity for "the outstanding wanted state somebody is being paid to end", and
 * until this release nothing in the mod had one. Heat is a number that moves every tick; a {@link
 * CrimeRecord} is one offence out of many. Neither can answer "have I already been paid for this",
 * which is the only question that stops a bounty from becoming a respawn farm.
 *
 * <p>So the warrant is that identity, and its life is deliberately boring:
 *
 * <ul>
 *   <li>becoming Wanted opens one at revision 1;</li>
 *   <li>a crime that generates Heat while it is open bumps the revision, which is what makes a later
 *       claim legitimately new rather than a repeat;</li>
 *   <li>ceasing to be Wanted closes it — kept, not deleted, so an old claim key still resolves and
 *       cannot be recycled.</li>
 * </ul>
 *
 * <p>Those three decisions are pure functions ({@link #applyWanted}, {@link #applyCrime}) so the state
 * machine can be tested without a server; the subscribers below are the wiring that reads the world,
 * persists the answer, and tells everybody.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class WarrantService {

    private WarrantService() {
    }

    // ------------------------------------------------------------------ pure state machine

    /**
     * The warrant an offender should have after their Wanted state flipped to {@code wanted}, or empty
     * when nothing changes.
     *
     * <p>Idempotent in both directions: becoming Wanted while already carrying an open warrant is not a
     * second warrant (revision bumps come from crimes, not from Heat crossing the line twice), and
     * ceasing to be Wanted with nothing open is not a close.
     *
     * @param warrantId the id to mint an opening warrant with; ignored when nothing opens
     */
    public static Optional<Warrant> applyWanted(@Nullable Warrant current, UUID offender, boolean wanted,
                                                UUID warrantId, @Nullable ResourceLocation topOffense,
                                                @Nullable UUID recordId, long now) {
        if (wanted) {
            if (current != null && current.open()) {
                return Optional.empty();
            }
            return Optional.of(Warrant.open(warrantId, offender, topOffense, recordId, now));
        }
        if (current == null || !current.open()) {
            return Optional.empty();
        }
        return Optional.of(current.closed(now));
    }

    /**
     * The warrant after one more offence, or empty when this crime does not touch it.
     *
     * <p>Heat is the qualifier. A crime the law never noticed generated no Heat and did not make the
     * offender any more wanted than they already were, so charging a fresh bounty for it would be
     * charging for bookkeeping. A crime committed with no open warrant is likewise not a revision: it
     * either opened one through {@link #applyWanted}, or it never reached the threshold at all.
     */
    public static Optional<Warrant> applyCrime(@Nullable Warrant current, long heatApplied,
                                               @Nullable UUID recordId, @Nullable ResourceLocation offense,
                                               long now) {
        if (current == null || !current.open() || heatApplied <= 0L) {
            return Optional.empty();
        }
        return Optional.of(current.revised(recordId, offense, now));
    }

    /**
     * The offence a warrant should be named after: the heaviest thing the offender still has open.
     *
     * <p>Heat generated rather than Karma cost, because a warrant is the law's document and Heat is
     * what the law reacted to. Ties keep the first record seen, which is the newest — {@code
     * recordsForOffender} is newest-first — so a fresh murder outranks last week's identical one.
     */
    @Nullable
    public static ResourceLocation topOffense(List<CrimeRecord> unresolved, @Nullable ResourceLocation fallback) {
        ResourceLocation best = fallback;
        long bestHeat = Long.MIN_VALUE;
        for (CrimeRecord record : unresolved) {
            if (record.heatGenerated() > bestHeat) {
                bestHeat = record.heatGenerated();
                best = record.type();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ queries

    /** This offender's warrant if it is currently open; empty for a closed or absent one. */
    public static Optional<Warrant> open(MinecraftServer server, UUID offender) {
        if (server == null || offender == null) {
            return Optional.empty();
        }
        Warrant warrant = CrimeWorldData.get(server).warrant(offender);
        return warrant != null && warrant.open() ? Optional.of(warrant) : Optional.empty();
    }

    // ------------------------------------------------------------------ subscribers

    @SubscribeEvent
    public static void onWantedChanged(WantedStatusChangedEvent event) {
        ServerPlayer offender = event.getPlayer();
        MinecraftServer server = offender == null ? null : offender.getServer();
        if (server == null) {
            return;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        UUID id = offender.getUUID();
        Warrant current = data.warrant(id);
        long now = server.overworld().getGameTime();
        Optional<Warrant> next = applyWanted(current, id, event.isWanted(), UUID.randomUUID(),
                topOffense(data.actionableFor(id), current == null ? null : current.topOffense()), null, now);
        if (next.isEmpty()) {
            return;
        }
        Warrant warrant = next.get();
        data.putWarrant(warrant);
        if (!warrant.open()) {
            // The repeat-offender bonus is priced off how many times the law has had to do this before,
            // so the count moves exactly once per warrant, on the way out.
            CrimeAttachments.get(offender).incrementPriorWarrants();
        }
        CrimeDebug.crime("warrant {} for {} rev {}", warrant.open() ? "opened" : "closed", id, warrant.revision());
        NeoForge.EVENT_BUS.post(new WarrantChangedEvent(id, warrant,
                warrant.open() ? WarrantChangedEvent.Change.OPENED : WarrantChangedEvent.Change.CLOSED));
    }

    @SubscribeEvent
    public static void onCrimeCommitted(CrimeCommittedEvent event) {
        ServerPlayer offender = event.getPlayer();
        MinecraftServer server = offender == null ? null : offender.getServer();
        if (server == null) {
            return;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        UUID id = offender.getUUID();
        Warrant current = data.warrant(id);
        long now = server.overworld().getGameTime();
        Optional<Warrant> next = applyCrime(current, event.getHeatApplied(), event.getRecordId(),
                topOffense(data.actionableFor(id), event.getCrimeType()), now);
        if (next.isEmpty()) {
            return;
        }
        Warrant warrant = next.get();
        data.putWarrant(warrant);
        CrimeDebug.crime("warrant revised for {} to rev {} ({})", id, warrant.revision(), warrant.topOffense());
        NeoForge.EVENT_BUS.post(new WarrantChangedEvent(id, warrant, WarrantChangedEvent.Change.REVISED));
    }
}
