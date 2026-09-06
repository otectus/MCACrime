package dev.otectus.mcacrime.state.world;

import java.util.List;
import java.util.UUID;

/**
 * Hands {@link PropertyLot}s to their owners, and keeps whatever would not fit.
 *
 * <p>The whole method is the two-line rule that a delivery either happens or is still owed: a lot is
 * only forgotten once the handover says everything arrived, and a partial handover shrinks the lot
 * rather than closing it. That is the property worth having, and it is stated here — away from
 * inventories, players and the login event — so it can be asserted with a lambda that refuses to
 * accept anything.
 */
public final class PropertyEscrow {

    private PropertyEscrow() {
    }

    /**
     * One attempt at giving a lot to its owner.
     *
     * <p>Returns what is <em>still owed</em>: an empty result means everything arrived. The server
     * binding pushes an item through {@code Inventory.add}, which shrinks the stack it is given and
     * leaves the overflow in it, so the remainder is exactly what that call did not take.
     */
    @FunctionalInterface
    public interface Handover {
        PropertyLot deliver(PropertyLot lot);
    }

    /**
     * Delivers everything owed to {@code owner}.
     *
     * @return how many lots were closed completely
     */
    public static int deliverPending(CrimeWorldData data, UUID owner, Handover handover) {
        if (data == null || owner == null || handover == null || !ServerMutationGate.allows(data)) {
            return 0;
        }
        List<PropertyLot> owed = data.propertyEscrowFor(owner);
        int closed = 0;
        for (PropertyLot lot : owed) {
            PropertyLot left = handover.deliver(lot);
            if (left == null || left.empty()) {
                data.removePropertyLot(lot.lotId());
                closed++;
            } else if (!left.equals(lot)) {
                // Only rewrite when something actually moved: an owner with no room at all must not
                // dirty the store once per login for the rest of the save.
                data.putPropertyLot(left);
            }
        }
        return closed;
    }
}
