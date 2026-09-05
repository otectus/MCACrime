package dev.otectus.mcacrime.action;

import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.economy.account.VillagerPurse;
import dev.otectus.mcacrime.memory.CrimeMemoryService;
import dev.otectus.mcacrime.memory.VillagerCrimeProfile;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * An MCA villager acting in the crime engine (0.5.1) — the second {@link CrimeActor} implementation,
 * and the one spec §10.5 makes a precondition for NPC crime.
 *
 * <p>Its money is the villager's own {@code VillagerPurse}: finite, capacity-bounded and refilled at a
 * configured daily rate. That is the point of routing NPC theft through here rather than through a
 * bespoke simulator — a thief who mugs a player is limited by what a villager can actually carry, and
 * every emerald it gains is one a later victim can take back off its corpse.
 *
 * <p>Feedback methods are no-ops rather than exceptions. A villager has nowhere to read chat, and a
 * handler that narrates its own progress should not have to ask who is listening.
 */
public final class VillagerCrimeActor implements EconomicCrimeActor {

    private final LivingEntity villager;
    private final ServerLevel level;

    private VillagerCrimeActor(LivingEntity villager, ServerLevel level) {
        this.villager = villager;
        this.level = level;
    }

    /**
     * Wraps an MCA villager, or empty for anything else.
     *
     * <p>The MCA check is not a formality: the purse, the memory profile and every consequence below
     * are keyed on a villager profile, so wrapping a zombie or a player here would create economic
     * state for an entity nothing else in the mod will ever look up again.
     */
    public static Optional<VillagerCrimeActor> of(@Nullable LivingEntity entity) {
        if (entity == null || !(entity.level() instanceof ServerLevel serverLevel)
                || !McaCompat.isMcaVillager(entity)) {
            return Optional.empty();
        }
        return Optional.of(new VillagerCrimeActor(entity, serverLevel));
    }

    @Override
    public UUID id() {
        return villager.getUUID();
    }

    @Override
    public LivingEntity entity() {
        return villager;
    }

    @Override
    public ServerLevel level() {
        return level;
    }

    /** Always {@code null}: this is the actor that exists so that answer stops being a surprise. */
    @Override
    @Nullable
    public ServerPlayer asPlayer() {
        return null;
    }

    @Override
    public void sendMessage(Component message) {
        // Nobody to tell. Villager-facing narration is dialogue, not chat.
    }

    @Override
    public void sendActionBar(Component message) {
        // As above: there is no hotbar above a villager.
    }

    @Override
    public long currencyBalance() {
        VillagerPurse purse = purse();
        return purse == null ? 0L : purse.balance();
    }

    @Override
    public long debitCurrency(long amount, TransactionReason reason) {
        VillagerPurse purse = purse();
        if (purse == null || amount <= 0L) {
            return 0L;
        }
        int taken = purse.withdraw((int) Math.min(Integer.MAX_VALUE, amount));
        if (taken > 0) {
            CrimeWorldData.get(server()).setDirty();
        }
        return taken;
    }

    @Override
    public long creditCurrency(long amount, TransactionReason reason) {
        VillagerPurse purse = purse();
        if (purse == null || amount <= 0L) {
            return 0L;
        }
        int added = purse.deposit((int) Math.min(Integer.MAX_VALUE, amount));
        if (added > 0) {
            CrimeWorldData.get(server()).setDirty();
        }
        return added;
    }

    /** The villager's persisted purse, created on first use exactly as the mugging path creates it. */
    @Nullable
    private VillagerPurse purse() {
        MinecraftServer server = server();
        if (server == null) {
            return null;
        }
        VillagerCrimeProfile profile = CrimeMemoryService.profile(server, villager, day());
        return profile == null ? null : profile.purse();
    }

    private MinecraftServer server() {
        return level.getServer();
    }

    private long day() {
        return level.getGameTime() / 24000L;
    }
}
