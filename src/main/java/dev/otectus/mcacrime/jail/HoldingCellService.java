package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.List;
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
        HoldingCell built = CellBuilder.build(level, near, prisoner, sentenceId);
        if (built != null) {
            CrimeWorldData.get(server).putHoldingCell(built);
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
        HoldingCell cell = CrimeWorldData.get(server).removeHoldingCell(prisoner);
        if (cell == null) {
            return;
        }
        ServerLevel level = JailService.resolveLevel(server, cell.dim());
        if (level == null) {
            // The dimension is gone, so the blocks are unreachable. Dropping the record is the only
            // thing left to do, and keeping it would leak forever.
            McaCrime.LOGGER.debug("MCA: Crime dropped a holding-cell record in a missing dimension {}", cell.dim());
            return;
        }
        CellBuilder.demolish(level, cell);
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
        if (server == null || player == null) {
            return;
        }
        HoldingCell cell = CrimeWorldData.get(server).holdingCellFor(player.getUUID());
        if (cell == null) {
            return;
        }
        ServerLevel level = JailService.resolveLevel(server, cell.dim());
        if (level != null) {
            BlockPos outside = JailService.findSafeStand(level,
                    cell.anchor().offset(CellBlueprint.RADIUS + 2, 0, 0), 8);
            if (outside == null) {
                McaCrime.LOGGER.debug("MCA: Crime found nowhere safe outside a cell; leaving it standing");
                return;
            }
            player.teleportTo(level, outside.getX() + 0.5, outside.getY(), outside.getZ() + 0.5,
                    player.getYRot(), player.getXRot());
        }
        dismantle(server, player.getUUID());
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
                ServerPlayer prisoner = server.getPlayerList().getPlayer(cell.prisoner());
                if (prisoner != null) {
                    releaseAndDismantle(server, prisoner);
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
