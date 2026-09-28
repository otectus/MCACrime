package dev.otectus.mcacrime.menu;

import dev.otectus.mcacrime.frisk.FriskSession;
import dev.otectus.mcacrime.frisk.FriskingService;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

/**
 * The frisking screen's server half: a projection with no writable slot anywhere (M5.2, plan §3.8).
 *
 * <p>Three properties together make this safe, and all three are enforced here rather than by the
 * screen:
 *
 * <ul>
 *   <li>every slot refuses both {@code mayPlace} and {@code mayPickup};</li>
 *   <li>{@link #quickMoveStack} returns empty, so shift-click moves nothing;</li>
 *   <li>{@link #clicked} is overridden to do nothing at all, so <em>every</em> click type — pickup,
 *       quick-move, swap, clone, throw, drag and pickup-all — fails with no mutation. The
 *       specification's requirement is that each either has defined safe behaviour or fails without
 *       mutating; "does nothing" is the defined safe behaviour for all of them.</li>
 * </ul>
 *
 * <p>The searcher's own inventory is deliberately not part of this menu. There is nothing to move it
 * to and nothing to move into it, so adding it would only create slots whose clicks have to be
 * rejected.
 *
 * <p>{@link #stillValid} re-asks the whole session question every tick, which is where liveness,
 * dimension, reach, the restraint condition and the custody generation are re-checked.
 */
public class FriskingMenu extends AbstractContainerMenu {

    private final Container projection;
    @Nullable
    private final FriskSession session;
    private final long sessionId;

    /** Server side: slots come from the session's pinned view. */
    public FriskingMenu(int containerId, Inventory searcherInventory, FriskSession session) {
        super(CrimeMenus.FRISKING.get(), containerId);
        this.session = session;
        this.sessionId = session.id();
        this.projection = new FriskProjection(session,
                () -> searcherInventory.player instanceof net.minecraft.server.level.ServerPlayer searcher
                        ? FriskingService.subjectOf(searcher, session) : null);
        addProjectionSlots(projection.getContainerSize());
        session.bindContainer(containerId);
    }

    /** Client side: the same shape, backed by a container the server keeps filling. */
    public FriskingMenu(int containerId, Inventory searcherInventory, long sessionId, int slotCount) {
        super(CrimeMenus.FRISKING.get(), containerId);
        this.session = null;
        this.sessionId = sessionId;
        this.projection = new SimpleContainer(FriskingLayout.visible(slotCount));
        addProjectionSlots(this.projection.getContainerSize());
    }

    private void addProjectionSlots(int slots) {
        int visible = FriskingLayout.visible(slots);
        for (int index = 0; index < visible; index++) {
            addSlot(new Slot(projection, index, FriskingLayout.x(index), FriskingLayout.y(index)) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }

                @Override
                public boolean mayPickup(Player player) {
                    return false;
                }
            });
        }
    }

    /** The session this screen belongs to; the client only ever knows its id. */
    public long sessionId() {
        return sessionId;
    }

    public int slotCount() {
        return projection.getContainerSize();
    }

    /**
     * Every click, rejected.
     *
     * <p>Not "most clicks" and not "clicks on the projection": the override is total, because the
     * only supported way to move anything here is the transfer packet, and a menu that accepts some
     * clicks is a menu whose click handling has to be right. This one has nothing to get right.
     */
    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
    }

    /** Shift-click moves nothing. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        if (player.level().isClientSide) {
            return true;
        }
        return player instanceof net.minecraft.server.level.ServerPlayer searcher
                && FriskingService.stillValid(searcher, session);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player instanceof net.minecraft.server.level.ServerPlayer searcher && session != null) {
            FriskingService.close(null, session,
                    dev.otectus.mcacrime.restraint.SessionCancelCause.MENU_CLOSED);
        }
    }
}
