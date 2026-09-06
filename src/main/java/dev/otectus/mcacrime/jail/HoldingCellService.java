package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The only writer of {@link HoldingCell}s: provisions one when an arrest has nowhere to go, and takes
 * it down again when the prisoner leaves.
 *
 * <p>Everything about a built cell is a liability until it is removed, so removal has more paths into
 * it than creation does. A cell comes down when the sentence ends, when the prisoner is released for
 * any other reason, when the record outlives its cap, and when a restart finds one whose prisoner is
 * no longer serving anything. The last two are the ones that matter: a player who is arrested and never
 * logs in again must not leave a cage standing in somebody's village for the rest of the save.
 */
public final class HoldingCellService {

    private HoldingCellService() {
    }

    /** The cell currently standing for this prisoner, if any. */
    @Nullable
    public static HoldingCell existingFor(MinecraftServer server, UUID prisoner) {
        return server == null ? null : CrimeWorldData.get(server).holdingCellFor(prisoner);
    }

    /**
     * Builds a cell for this prisoner near {@code near}, or null when the terrain, the config, or the
     * dimension says no. An existing cell is reused rather than duplicated.
     */
    @Nullable
    public static HoldingCell provision(ServerLevel level, BlockPos near, UUID prisoner, UUID sentenceId) {
        MinecraftServer server = level == null ? null : level.getServer();
        if (server == null || !McaCrimeConfig.COMMON.buildHoldingCell.get()) {
            return null;
        }
        HoldingCell existing = existingFor(server, prisoner);
        if (existing != null) {
            return existing;
        }
        if (!ServerMutationGate.allows(server)) {
            return null; // a read-only store cannot record a cell, so nothing may be built into one
        }
        HoldingCell built = CellBuilder.build(level, near, prisoner, sentenceId);
        if (built == null) {
            return null;
        }
        if (!CrimeWorldData.get(server).putHoldingCell(built).stored()) {
            // The roster is full. A cage nothing points at can never be taken down, so it comes back
            // out immediately rather than becoming somebody's permanent garden feature.
            CellBuilder.demolish(level, built);
            return null;
        }
        return built;
    }

    /**
     * Removes this prisoner's cell, restoring the terrain, and forgets the record.
     *
     * <p>Idempotent and safe to call for a prisoner who never had a built cell — an operator-assigned
     * jail has no record here and is never touched. Deliberately: the mod may only demolish what it
     * built.
     */
    public static void dismantle(MinecraftServer server, UUID prisoner) {
        if (server == null) {
            return;
        }
        dismantle(CrimeWorldData.get(server), prisoner, demolisher(server));
    }

    /** The live demolisher: resolve the cell's dimension, put the blocks back, report what is left. */
    private static CellDemolisher demolisher(MinecraftServer server) {
        return cell -> {
            ServerLevel level = JailService.resolveLevel(server, cell.dim());
            if (level == null) {
                // The dimension is gone, so the blocks are unreachable and always will be. That counts
                // as resolved rather than pending: retrying it every sweep for the rest of the save
                // would put an entry in the journal that nothing could ever clear.
                McaCrime.LOGGER.debug("MCA: Crime dropped a holding-cell record in a missing dimension {}",
                        cell.dim());
                return Set.of();
            }
            return CellBuilder.demolish(level, cell);
        };
    }

    /**
     * What actually puts the blocks back. The server overload resolves the cell's dimension and calls
     * {@link CellBuilder#demolish}; a test records which cell it was handed, which is the only way to
     * assert that a record is never dropped without its blocks being dealt with first.
     */
    @FunctionalInterface
    public interface CellDemolisher {
        /** @return the positions that still hold this cell's blocks; empty when it all came back */
        Set<BlockPos> demolish(HoldingCell cell);
    }

    /**
     * Demolishes first, and forgets the cell only when there is nothing of it left standing.
     *
     * <p>This used to run the other way round: remove the roster entry, then demolish, on the reasoning
     * that a failed demolition must not leave a record pointing at a cell nobody would return to. It
     * traded one leak for a worse one. Demolition genuinely fails whenever part of the cell sits in an
     * unloaded chunk -- the common case, because a prisoner released on login is nowhere near the cage
     * -- and dropping the record then left iron bars standing in a village with nothing anywhere that
     * knew they were this mod's to remove.
     *
     * <p>So the leftovers are written down instead. The cell moves to
     * {@code pendingCellRestorations} narrowed to the positions still standing, and the sweep finishes
     * the job when those chunks come back. The roster entry still goes, because the sentence it belonged
     * to is over either way.
     */
    public static void dismantle(CrimeWorldData data, UUID prisoner, CellDemolisher demolisher) {
        if (data == null || prisoner == null || demolisher == null) {
            return;
        }
        HoldingCell cell = data.removeHoldingCell(prisoner);
        if (cell == null) {
            // Nothing on the roster, but there may still be an unfinished restoration to try again.
            retryPending(data, prisoner, demolisher);
            return;
        }
        Set<BlockPos> unresolved = demolisher.demolish(cell);
        if (unresolved == null || unresolved.isEmpty()) {
            data.removePendingCellRestoration(prisoner);
            return;
        }
        data.putPendingCellRestoration(cell.retaining(unresolved));
    }

    /**
     * One more attempt at a restoration that did not finish.
     *
     * <p>The entry only clears when the retry reaches every remaining position, so a cell half of which
     * is still unloaded shrinks rather than being declared done.
     *
     * @return true when the last of it came back
     */
    public static boolean retryPending(CrimeWorldData data, UUID prisoner, CellDemolisher demolisher) {
        if (data == null || prisoner == null || demolisher == null) {
            return false;
        }
        HoldingCell remains = data.pendingCellRestoration(prisoner);
        if (remains == null) {
            return false;
        }
        Set<BlockPos> unresolved = demolisher.demolish(remains);
        if (unresolved == null || unresolved.isEmpty()) {
            data.removePendingCellRestoration(prisoner);
            return true;
        }
        data.putPendingCellRestoration(remains.retaining(unresolved));
        return false;
    }

    /**
     * Moves a player clear of their cell before it is dismantled, then dismantles it.
     *
     * <p>The order is the entire point. {@code JailService.release} does not move anybody, so removing
     * a cell around a prisoner who is still standing in it restores the floor and roof courses into the
     * space they occupy — which is to say, it suffocates them with their own release. The player is put
     * on solid ground outside the structure first; if no safe stand can be found, the cell is left
     * standing rather than dropped on them, and the expiry sweep will clear it later.
     */
    public static void releaseAndDismantle(MinecraftServer server, ServerPlayer player) {
        if (player != null) {
            releaseAndDismantle(server, player.getUUID());
        }
    }

    /**
     * The same, for any prisoner — a player or an arrested villager (0.5.1).
     *
     * <p>An NPC prisoner is moved with {@code teleportTo} in its own level rather than the player
     * overload's cross-dimension form, because a villager in a cell is by construction in the cell's
     * dimension. A prisoner that is not loaded at all is not moved and the cell still comes down:
     * there is nobody standing in it to suffocate.
     */
    public static void releaseAndDismantle(MinecraftServer server, UUID prisoner) {
        if (server == null || prisoner == null) {
            return;
        }
        HoldingCell cell = CrimeWorldData.get(server).holdingCellFor(prisoner);
        if (cell == null) {
            return;
        }
        ServerLevel level = JailService.resolveLevel(server, cell.dim());
        if (level != null) {
            BlockPos outside = SafeCustodyDestination.validate(level,
                    cell.anchor().offset(CellBlueprint.RADIUS + 2, 0, 0), 8).orElse(null);
            if (outside == null) {
                McaCrime.LOGGER.debug("MCA: Crime found nowhere safe outside a cell; leaving it standing");
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(prisoner);
            if (player != null) {
                player.teleportTo(level, outside.getX() + 0.5, outside.getY(), outside.getZ() + 0.5,
                        player.getYRot(), player.getXRot());
            } else {
                Entity npc = level.getEntity(prisoner);
                if (npc != null) {
                    npc.teleportTo(outside.getX() + 0.5, outside.getY(), outside.getZ() + 0.5);
                }
            }
        }
        dismantle(server, prisoner);
    }

    /**
     * Takes down cells nobody is serving in any more.
     *
     * <p>Two reasons a cell qualifies: it has outlived {@code holdingCellLifetimeTicks}, or its
     * prisoner is online, not jailed, and not in custody on the way there — which means the sentence
     * ended by a path that did not reach the release hook. The lifetime cap is the one that makes a
     * permanent logout safe: it fires whether or not the prisoner ever returns.
     *
     * <p>Called on the enforcement scan, which is already throttled; bounded to a few records per pass
     * so a server with a large roster never spends a tick on this.
     */
    public static void sweep(MinecraftServer server) {
        if (server == null) {
            return;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        List<HoldingCell> cells = data.holdingCells();
        if (cells.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        long lifetime = McaCrimeConfig.COMMON.holdingCellLifetimeTicks.get();
        int budget = 4;
        for (HoldingCell cell : cells) {
            if (budget <= 0) {
                return;
            }
            if (cell.expired(now, lifetime)) {
                // The cap fires regardless of who is where. It is the guard against the one leak this
                // subsystem can have: a player arrested and never seen again.
                //
                // Occupancy, not presence, decides how it comes down. Dismantling an occupied cell
                // restores the floor and roof courses into the space the prisoner is standing in, which
                // is how a sweep came to suffocate the person it was tidying up after. The release path
                // moves whoever is in there -- player or villager -- before a single block goes back.
                if (occupied(server, cell)) {
                    releaseAndDismantle(server, cell.prisoner());
                } else {
                    dismantle(server, cell.prisoner());
                }
                budget--;
                continue;
            }
            if (stillNeeded(server, data, cell)) {
                continue;
            }
            ServerPlayer prisoner = server.getPlayerList().getPlayer(cell.prisoner());
            if (prisoner != null) {
                releaseAndDismantle(server, prisoner);
                budget--;
            }
        }
        retrySweep(server, data);
    }

    /**
     * Finishes restorations that could not complete when the cell came down.
     *
     * <p>Only attempted where the anchor is already loaded, so the sweep never forces a chunk in to put
     * a block back. A cage nobody is standing near is nobody's problem this tick, and it will still be
     * there the next time somebody walks past it.
     */
    private static void retrySweep(MinecraftServer server, CrimeWorldData data) {
        List<HoldingCell> pending = data.pendingCellRestorations();
        if (pending.isEmpty()) {
            return;
        }
        int budget = 4;
        for (HoldingCell remains : pending) {
            if (budget <= 0) {
                return;
            }
            ServerLevel level = JailService.resolveLevel(server, remains.dim());
            if (level == null || !level.isLoaded(remains.anchor())) {
                continue;
            }
            retryPending(data, remains.prisoner(), demolisher(server));
            budget--;
        }
    }

    /** Whether somebody is actually serving this cell's sentence inside it. */
    private static boolean occupied(MinecraftServer server, HoldingCell cell) {
        ServerPlayer prisoner = server.getPlayerList().getPlayer(cell.prisoner());
        UUID serving = prisoner == null || !JailService.isJailed(prisoner) ? null
                : dev.otectus.mcacrime.state.CrimeCapabilities.get(prisoner)
                        .map(dev.otectus.mcacrime.state.PlayerCrimeData::getJail)
                        .map(JailState::getSentenceId)
                        .orElse(null);
        return occupied(CrimeWorldData.get(server), cell, serving);
    }

    /**
     * The occupancy rule itself, over nothing but the store and an id.
     *
     * <p>Split out from the sweep because this is the decision that matters and the sweep is only the
     * thing that acts on it: an occupied cell has to be released before it is taken apart, or the
     * restoration puts the floor and roof courses back through whoever is standing between them.
     *
     * @param activeSentenceId the sentence the prisoner is currently serving, or null for a prisoner
     *                         who is offline or not jailed at all
     */
    public static boolean occupied(CrimeWorldData data, HoldingCell cell,
                                   @Nullable UUID activeSentenceId) {
        if (cell == null) {
            return false;
        }
        if (activeSentenceId != null) {
            // A null id on the cell is a pre-0.4.0 record, which is given the benefit of the doubt:
            // being wrong here means an unnecessary release, and being wrong the other way suffocates
            // somebody.
            return cell.sentenceId() == null || cell.sentenceId().equals(activeSentenceId);
        }
        // An offline or NPC prisoner has no sentence to read. A live custody record against a cell that
        // names a sentence is what says somebody is still in there.
        return cell.sentenceId() != null && data != null && data.getCustody(cell.prisoner()) != null;
    }

    /**
     * Whether a cell still has a job.
     *
     * <p>Two states count, and missing the second one would be a visible bug: a prisoner actually
     * serving a sentence, and a prisoner in lawful custody who has not arrived yet. The cell is built
     * at the start of an arrest and the sentence only begins when the escort reaches it, so between
     * those two moments the prisoner is online and not jailed — which, without this, is exactly the
     * shape of a cell nobody needs, and the sweep would demolish the destination while the guard was
     * still walking towards it.
     *
     * <p>An offline prisoner is left alone entirely. Their sentence is paused with them, and the
     * lifetime cap above is what stops that from becoming permanent.
     */
    private static boolean stillNeeded(MinecraftServer server, CrimeWorldData data, HoldingCell cell) {
        ServerPlayer prisoner = server.getPlayerList().getPlayer(cell.prisoner());
        if (prisoner == null) {
            return true;
        }
        if (JailService.isJailed(prisoner)) {
            // The id check is what the field was documented for and could never do: the cell used to be
            // stamped with a fresh random id at the start of an arrest while the sentence minted its
            // own on arrival, so the two never matched and no caller compared them. With one id threaded
            // through the arrest, a cell left over from a previous sentence is finally distinguishable
            // from the one this sentence is being served in. A null id is a pre-0.4.0 cell and is given
            // the benefit of the doubt.
            return matchesSentence(cell, prisoner);
        }
        CustodyRecord custody = data.getCustody(cell.prisoner());
        return custody != null && custody.isLawful();
    }

    /** Whether this cell belongs to the sentence the prisoner is actually serving. */
    private static boolean matchesSentence(HoldingCell cell, ServerPlayer prisoner) {
        if (cell.sentenceId() == null) {
            return true;
        }
        JailState jail = dev.otectus.mcacrime.state.CrimeCapabilities.get(prisoner)
                .map(dev.otectus.mcacrime.state.PlayerCrimeData::getJail)
                .orElse(null);
        return jail == null || jail.getSentenceId() == null
                || cell.sentenceId().equals(jail.getSentenceId());
    }
}
