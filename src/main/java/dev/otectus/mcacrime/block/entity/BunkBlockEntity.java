package dev.otectus.mcacrime.block.entity;

import dev.otectus.mcacrime.detention.BunkRespawnPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * The head half of a bunk, and who is in it (0.7.5 M4.7).
 *
 * <p>The source's equivalent is a seventeen-line stub with no state of its own, which is why its
 * bunks cannot tell a prisoner's respawn override from a player's own choice. This one remembers the
 * sleeper, so releasing them can ask {@link BunkRespawnPolicy} the ownership question with a name
 * attached rather than guessing from the block.
 */
public class BunkBlockEntity extends BlockEntity {

    private static final String TAG_SLEEPER = "Sleeper";

    @Nullable
    private UUID sleeper;

    public BunkBlockEntity(BlockPos pos, BlockState state) {
        super(CrimeBlockEntities.BUNK.get(), pos, state);
    }

    /** Records who lay down here. */
    public void setSleeper(@Nullable UUID id) {
        sleeper = id;
        setChanged();
    }

    public Optional<UUID> sleeper() {
        return Optional.ofNullable(sleeper);
    }

    /**
     * The bunk is done with this occupant: give their respawn point back if we still own it.
     *
     * <p>Called on release rather than on waking, because waking up in a cell is not being let out of
     * it — the prisoner sleeps here again tomorrow and their spawn should stay here until the
     * sentence ends.
     */
    public boolean release(@Nullable MinecraftServer server) {
        if (sleeper == null || server == null) {
            return false;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(sleeper);
        boolean restored = BunkRespawnPolicy.release(player);
        // Offline players keep the persisted snapshot. Login reconciliation verifies that the same
        // custody/sentence episode ended and that the bunk is still their exact respawn point.
        setSleeper(null);
        return restored;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (sleeper != null) {
            tag.putUUID(TAG_SLEEPER, sleeper);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        sleeper = tag != null && tag.hasUUID(TAG_SLEEPER) ? tag.getUUID(TAG_SLEEPER) : null;
    }

    @Override
    @Nullable
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }
}
