package dev.otectus.mcacrime.frisk;

import dev.otectus.mcacrime.restraint.Session;
import dev.otectus.mcacrime.restraint.SessionKind;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One search in progress (M5.2).
 *
 * <p>Everything a transfer packet could otherwise claim is pinned here when the screen opens: which
 * subject, which custody and which generation of it, and which slots exist in what order. The packet
 * carries a position in that view and the revision it was shown, and nothing else.
 *
 * <p>Transient, like every other session. A restart cancels it, which is correct — the subject and
 * the searcher both had their own durable state and none of it depended on the screen being open.
 */
public final class FriskSession extends Session {

    /** How many committed transfer ids are remembered, so a replay cannot be re-applied. */
    private static final int REMEMBERED_TRANSFERS = 64;

    private final int subjectEntityId;
    @Nullable
    private final UUID custodyId;
    private final long custodyGeneration;
    private final List<FriskSlotRef> view;
    private final SeizureKind kind;

    private int containerId = -1;
    private long lastTransferTick = Long.MIN_VALUE;
    private final Set<UUID> applied = new HashSet<>();
    private final Deque<UUID> appliedOrder = new ArrayDeque<>();

    public FriskSession(long id, UUID searcher, UUID subject, int subjectEntityId, long targetRevision,
                        @Nullable ResourceLocation dimension,
                        @Nullable UUID custodyId, long custodyGeneration, List<FriskSlotRef> view,
                        SeizureKind kind, long expiryTick) {
        super(id, SessionKind.FRISK, searcher, subject, targetRevision, dimension, null, expiryTick);
        this.subjectEntityId = subjectEntityId;
        this.custodyId = custodyId;
        this.custodyGeneration = custodyGeneration;
        this.view = view == null ? List.of() : List.copyOf(view);
        // Fail honest: a session that does not know what it is reads as a theft, never as evidence.
        this.kind = kind == null ? SeizureKind.CRIMINAL_SEIZURE : kind;
    }

    public int subjectEntityId() {
        return subjectEntityId;
    }

    @Nullable
    public UUID custodyId() {
        return custodyId;
    }

    public long custodyGeneration() {
        return custodyGeneration;
    }

    public SeizureKind seizureKind() {
        return kind;
    }

    /** The ordered slots this session is showing. */
    public List<FriskSlotRef> view() {
        return view;
    }

    /** The slot at a view position, or null when the position is not in the view. */
    @Nullable
    public FriskSlotRef slotAt(int viewIndex) {
        return viewIndex >= 0 && viewIndex < view.size() ? view.get(viewIndex) : null;
    }

    public int containerId() {
        return containerId;
    }

    public void bindContainer(int containerId) {
        this.containerId = containerId;
    }

    public long lastTransferTick() {
        return lastTransferTick;
    }

    /** Whether the server-owned search delay has elapsed. */
    public boolean delayElapsed(long now, int intervalTicks) {
        return lastTransferTick == Long.MIN_VALUE || now - lastTransferTick >= Math.max(0, intervalTicks);
    }

    public void stampTransfer(long now) {
        this.lastTransferTick = now;
    }

    /** Whether this transfer has already been committed in this session. */
    public boolean alreadyApplied(UUID transferId) {
        return transferId != null && applied.contains(transferId);
    }

    /** Records a committed transfer, forgetting the oldest once the bound is reached. */
    public void markApplied(UUID transferId) {
        if (transferId == null || !applied.add(transferId)) {
            return;
        }
        appliedOrder.addLast(transferId);
        while (appliedOrder.size() > REMEMBERED_TRANSFERS) {
            applied.remove(appliedOrder.removeFirst());
        }
    }
}
