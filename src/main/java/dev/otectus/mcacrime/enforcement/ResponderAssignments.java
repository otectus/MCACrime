package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.captivity.CustodyOwnerType;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;

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
}
