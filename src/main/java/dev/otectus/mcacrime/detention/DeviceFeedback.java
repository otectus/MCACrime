package dev.otectus.mcacrime.detention;

import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The pillory and guillotine's action-bar explanations (0.8.0): what just happened, and the next useful
 * step, in one line.
 *
 * <p>Only ever sent in answer to an interaction, never on a tick, and an identical line for the same
 * device is not repeated within {@link #REPEAT_TICKS} -- holding the use key on a device narrates its
 * state once rather than every few ticks. The text is translatable and says the reason in words, so
 * nothing depends on a colour.
 */
public final class DeviceFeedback {

    /** How long an identical line for the same device stays quiet. Two seconds. */
    public static final long REPEAT_TICKS = 40L;

    private record Last(BlockPos device, String text, long at) {
    }

    private static final Map<UUID, Last> LAST = new ConcurrentHashMap<>();

    private DeviceFeedback() {
    }

    /** Puts {@code message} on the player's action bar unless it is a repeat of the last line. */
    public static void say(@Nullable ServerPlayer player, @Nullable BlockPos device, Component message) {
        if (player == null || message == null) {
            return;
        }
        BlockPos at = device == null ? BlockPos.ZERO : device.immutable();
        long now = player.level().getGameTime();
        String text = message.getString();
        Last last = LAST.get(player.getUUID());
        if (repeat(last == null ? null : last.device, last == null ? null : last.text,
                last == null ? Long.MIN_VALUE : last.at, at, text, now)) {
            return;
        }
        LAST.put(player.getUUID(), new Last(at, text, now));
        player.displayClientMessage(message, true);
    }

    /** As {@link #say}, for a caller holding a {@link Player} that may not be a server player. */
    public static void say(@Nullable Player player, @Nullable BlockPos device, Component message) {
        if (player instanceof ServerPlayer server) {
            say(server, device, message);
        }
    }

    /** Whether a line is a repeat that should stay quiet. Pure, so the window is a unit test. */
    public static boolean repeat(@Nullable BlockPos lastDevice, @Nullable String lastText, long lastAt,
                                 BlockPos device, String text, long now) {
        return lastDevice != null && lastDevice.equals(device) && text.equals(lastText)
                && now - lastAt < REPEAT_TICKS && now >= lastAt;
    }

    /** Drops what a player was last told. Logout. */
    public static void forget(@Nullable UUID player) {
        if (player != null) {
            LAST.remove(player);
        }
    }

    /** Forgets everybody. Server stop. */
    public static void clearAll() {
        LAST.clear();
    }

    /**
     * Why an execution order was refused, in the words a player needs: which setting, or which fact
     * about the occupant, stopped it.
     */
    public static Component refusal(@Nullable ExecutionAuthorization.Refusal refusal, LivingEntity occupant) {
        Component name = occupant.getDisplayName();
        if (refusal == null) {
            return Component.translatable("mcacrime.execution.refused.no_order");
        }
        return switch (refusal) {
            case FEATURE_DISABLED -> Component.translatable("mcacrime.device.guillotine.capital_disabled", name);
            case NOT_CONDEMNED -> !(occupant instanceof Player) && !npcOffendersEligible()
                    ? Component.translatable("mcacrime.device.guillotine.not_condemned.npc", name)
                    : Component.translatable("mcacrime.device.guillotine.not_condemned", name);
            case ALREADY_ARMED -> Component.translatable("mcacrime.device.guillotine.already_armed", name);
            case ACTOR_NOT_PERMITTED, NO_DEVICE, NO_ACTOR, NONE ->
                    Component.translatable(ExecutionAuthorization.messageKey(refusal));
        };
    }

    private static boolean npcOffendersEligible() {
        try {
            return McaCrimeConfig.COMMON.npcOffendersEligible.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return false;
        }
    }
}
