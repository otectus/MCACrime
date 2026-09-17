package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.activity.CrimeActivityRegistry;
import dev.otectus.mcacrime.activity.CrimeActivityView;
import dev.otectus.mcacrime.captivity.CustodyOwnerType;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.UUID;

/** Reads durable escort ownership rather than borrowing a guard already walking another captive. */
public final class ResponderAssignments {
    private ResponderAssignments() { }

    public static boolean isEscorting(MinecraftServer server, UUID guard, UUID exceptCaptive) {
        return server != null && isEscorting(CrimeWorldData.get(server), guard, exceptCaptive);
    }

    public static boolean isEscorting(CrimeWorldData data, UUID guard, UUID exceptCaptive) {
        if (data == null || guard == null) return false;
        return data.custodyRecords().stream().anyMatch(record -> record.isLawful()
                && !record.getCaptive().equals(exceptCaptive)
                && record.getOwner().type() == CustodyOwnerType.GUARD
                && record.getOwner().ownerUuid().filter(guard::equals).isPresent());
    }

    /**
     * Whether something stronger than {@code intended} already owns this responder.
     *
     * <p>The durable check above reads custody records, which is the right source for "is this guard
     * already walking somebody to a cell". It cannot see the states that have no record at all — a
     * responder who is themselves a prisoner, for one — so this asks the activity registry for the
     * same question in the other direction.
     *
     * <p><b>Strictly</b> outranks, on purpose. Two assignments of equal weight are a scheduling
     * decision the caller already makes for itself, and treating them as a conflict here would mean a
     * guard pursuing one suspect could never be re-pointed at a closer one.
     */
    public static boolean isCommitted(@Nullable UUID guard, CrimeActivityView.Kind intended, long now) {
        if (guard == null || intended == null) {
            return false;
        }
        return CrimeActivityRegistry.activeFor(guard, now)
                .map(claim -> claim.authority().outranks(intended.authority()))
                .orElse(false);
    }
}
