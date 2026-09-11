package dev.otectus.mcacrime.mug.npc;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.state.CrimeAttachments;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * The thin adapter between {@link MugProtectionRules} and a real player (0.7.0).
 *
 * <p>Everything with a decision in it is next door and pure; this reads the config, reads the
 * capability, and writes the answer back. The split is the same one {@code TheftPolicy} and
 * {@code TheftPlanner} use, and for the same reason: the rules are worth testing and the capability
 * lookup is not.
 *
 * <p>Ticks are world game ticks throughout, taken from the overworld, because the thief's own mug
 * cooldown is stamped against that clock and two cooldowns compared against different clocks would
 * drift apart every time a player logged out.
 */
public final class MugProtection {

    private MugProtection() {
    }

    /** Protection, pair cooldown and — on a completed mugging only — the daily counter. */
    public static void afterMugging(@Nullable MinecraftServer server, UUID victimId, @Nullable UUID thiefId,
                                    boolean committed) {
        ServerPlayer victim = server == null || victimId == null
                ? null : server.getPlayerList().getPlayer(victimId);
        if (victim == null) {
            return;
        }
        long now = now(server);
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        double multiplier = c.muggingFrequencyMultiplier.get();
        PlayerCrimeData data = CrimeAttachments.get(victim);
        data.setMugProtectionUntilTick(MugProtectionRules.grant(data.getMugProtectionUntilTick(), now,
                c.playerMugProtectionTicks.get(), multiplier));
        long pair = MugProtectionRules.scale(c.thiefVictimRepeatCooldownTicks.get(), multiplier);
        if (thiefId != null && pair > 0L) {
            data.recordMugger(thiefId, now + pair, now);
        }
        if (committed) {
            data.recordMuggingOn(MugProtectionRules.day(now));
        }
    }

    /** A grant from one of the fixed windows: respawn, login, or release from custody. */
    public static void grant(@Nullable ServerPlayer player, int ticks) {
        if (player == null || player.getServer() == null || ticks <= 0) {
            return;
        }
        long now = now(player.getServer());
        double multiplier = McaCrimeConfig.COMMON.muggingFrequencyMultiplier.get();
        PlayerCrimeData data = CrimeAttachments.get(player);
        data.setMugProtectionUntilTick(
                MugProtectionRules.grant(data.getMugProtectionUntilTick(), now, ticks, multiplier));
    }

    /**
     * The three victim-scoped facts a selection needs, read once (0.7.0).
     *
     * <p>A record rather than four lookups, because {@code ThiefBehaviorService.scan} reads them per
     * candidate per scan and the capability lookup is the only expensive part of the answer.
     */
    public record Window(long protectedUntil, long pairCooldownUntil, int muggingsToday, int dailyCap) {

        /** Nothing protecting anybody, for a player whose capability could not be read. */
        public static final Window NONE = new Window(0L, 0L, 0, 0);
    }

    /** This player's protection window as of {@code now}, pruning pair cooldowns that have run out. */
    public static Window window(@Nullable ServerPlayer victim, @Nullable UUID thief, long now) {
        if (victim == null) {
            return Window.NONE;
        }
        PlayerCrimeData data = CrimeAttachments.get(victim);
        data.pruneRecentMuggers(now);
        return new Window(data.getMugProtectionUntilTick(), data.pairCooldownUntil(thief),
                data.muggingsOn(MugProtectionRules.day(now)),
                McaCrimeConfig.COMMON.maxMuggingsPerPlayerPerDay.get());
    }

    /** Whether this thief may rob this player right now, by all three victim-scoped rules. */
    public static boolean eligible(@Nullable ServerPlayer victim, @Nullable UUID thief, long now) {
        if (victim == null) {
            return true; // nothing to protect; the caller's own checks still apply
        }
        Window window = window(victim, thief, now);
        return MugProtectionRules.eligible(window.protectedUntil(), window.pairCooldownUntil(),
                window.muggingsToday(), window.dailyCap(), now);
    }

    /** Whether any thief at all is barred from this player right now, for the scan's early skip. */
    public static boolean protectedNow(@Nullable ServerPlayer victim, long now) {
        return !eligible(victim, null, now);
    }

    /** The overworld game tick, the clock every mug cooldown in this mod is stamped against. */
    public static long now(@Nullable MinecraftServer server) {
        return server == null ? 0L : server.overworld().getGameTime();
    }
}
