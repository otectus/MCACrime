package dev.otectus.mcacrime.economy;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.OptionalLong;

/**
 * {@code /crime payfine} (spec §6.1): clears Wanted status by paying an emerald fine. Server-authoritative
 * and atomic — the charge either fully succeeds (then Heat is cleared via the {@link CrimeState} chokepoint)
 * or nothing is taken. Severe (jailable) Heat and barred Red players are rejected with a clear message.
 */
public final class FineService {

    private FineService() {
    }

    public static int payFine(ServerPlayer player) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        if (!c.enableFines.get()) {
            player.sendSystemMessage(Component.translatable("mcacrime.fine.disabled"));
            return 0;
        }
        long heat = CrimeState.getHeat(player);
        Band band = CrimeState.getBand(player);
        OptionalLong fineOpt = FineCalculator.fineFor(heat, band, c.fineBase.get(), c.finePerHeat.get(),
                c.jailableHeatThreshold.get(), c.blueFineMultiplier.get(), c.redCanPayFine.get());

        if (fineOpt.isEmpty()) {
            String key = heat >= c.jailableHeatThreshold.get() ? "mcacrime.fine.notfinable" : "mcacrime.fine.barred";
            player.sendSystemMessage(Component.translatable(key));
            return 0;
        }
        long fine = fineOpt.getAsLong();
        if (fine <= 0L) {
            player.sendSystemMessage(Component.translatable("mcacrime.fine.nothing"));
            return 0;
        }
        if (!EmeraldCurrency.INSTANCE.tryCharge(player, fine)) {
            player.sendSystemMessage(Component.translatable("mcacrime.fine.need", fine));
            return 0;
        }
        CrimeState.clearHeat(player); // → no longer Wanted (fires WantedStatusChanged + sync)
        player.sendSystemMessage(Component.translatable("mcacrime.fine.paid", fine));
        return 1;
    }
}
