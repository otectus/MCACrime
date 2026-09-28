package dev.otectus.mcacrime.stat;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.stat.CrimeStatIds.Kind;
import dev.otectus.mcacrime.stat.CrimeStatIds.Tracked;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This mod's nineteen custom statistics, as registrations (M5.11, Appendix A.3).
 *
 * <p>Four restraint families with three counts each, two lockpick counts and the
 * safe. Which nineteen, and what each one means, is {@link CrimeStatIds}; this is only where they
 * are put into the registry and awarded. Every one of them is awarded <b>once per committed server
 * event</b> — not once per tick, not once per packet, and not on a client fire — because a statistic
 * that counts the same act twice is a statistic nobody can read.
 *
 * <p>The lockpick pair is two rather than one on purpose, and the specification says why: "a used
 * pick is not automatically a broken pick". {@link #SUCCESSFUL_LOCKPICKS} counts locks that opened;
 * {@link #LOCKPICKS_BROKEN} counts picks that snapped. A session can raise either, both or neither.
 */
public final class CrimeStats {

    public static final DeferredRegister<ResourceLocation> CUSTOM_STATS =
            DeferredRegister.create(Registries.CUSTOM_STAT, McaCrime.MOD_ID);

    /**
     * The formatter each statistic displays with, collected at class-init and applied at setup.
     *
     * <p>Not at registration, and that is forced. A {@code Stat} builds its translation key from
     * {@code StatType#getRegistry().getKey(value)}, so asking {@code Stats.CUSTOM} for one from
     * inside the {@code DeferredRegister} supplier -- which runs <em>before</em> the id reaches
     * {@code BuiltInRegistries.CUSTOM_STAT} -- dereferences a null key and throws out of
     * {@code RegisterEvent}, taking the whole mod's registration with it. So the formatter pass
     * moved to {@link #registerFormatters()}, which common setup calls once the registry is
     * populated. The invariant is unchanged: every registered id gets a formatter, from one place,
     * and nothing else may add one.
     */
    private static final Map<String, StatFormatter> FORMATTERS = new LinkedHashMap<>();

    /**
     * The registration for each path, so the formatter pass can hand {@code Stats.CUSTOM} the
     * <em>registered</em> id object rather than an equal one.
     *
     * <p>This is not a nicety. {@code MappedRegistry} keeps its value-to-key map as an
     * {@code IdentityHashMap}, so a freshly built {@code ResourceLocation} that merely equals a
     * registered one is not found, and {@code Stat}'s key construction dereferences a null again.
     * Reading the {@link RegistryObject} is the only way to get the instance the registry holds.
     */
    private static final Map<String, RegistryObject<ResourceLocation>> ALL = new LinkedHashMap<>();

    private static final Map<String, RegistryObject<ResourceLocation>> RESTRAINT_STATS =
            registerRestraintStats();

    private static Map<String, RegistryObject<ResourceLocation>> registerRestraintStats() {
        Map<String, RegistryObject<ResourceLocation>> stats = new LinkedHashMap<>();
        for (Tracked tracked : Tracked.values()) {
            for (Kind kind : Kind.values()) {
                String path = CrimeStatIds.path(tracked, kind);
                stats.put(path, register(path, kind.formatter()));
            }
        }
        return Map.copyOf(stats);
    }


    public static final RegistryObject<ResourceLocation> SUCCESSFUL_LOCKPICKS =
            register(CrimeStatIds.SUCCESSFUL_LOCKPICKS, StatFormatter.DEFAULT);
    public static final RegistryObject<ResourceLocation> LOCKPICKS_BROKEN =
            register(CrimeStatIds.LOCKPICKS_BROKEN, StatFormatter.DEFAULT);

    public static final RegistryObject<ResourceLocation> OPEN_SAFE =
            register(CrimeStatIds.OPEN_SAFE, StatFormatter.DEFAULT);

    private CrimeStats() {
    }

    private static RegistryObject<ResourceLocation> register(String path, StatFormatter formatter) {
        FORMATTERS.put(path, formatter);
        RegistryObject<ResourceLocation> registered = CUSTOM_STATS.register(path, () -> McaCrime.id(path));
        ALL.put(path, registered);
        return registered;
    }

    public static void register(IEventBus modBus) {
        CUSTOM_STATS.register(modBus);
    }

    /**
     * Gives every registered statistic its formatter. Called once from common setup.
     *
     * <p>{@code Stats.CUSTOM.get} is idempotent and caches, so this is the one place a formatter is
     * chosen; a later caller asking for the same id with {@code StatFormatter.DEFAULT} gets the
     * already-built {@code Stat} rather than replacing it.
     */
    public static void registerFormatters() {
        FORMATTERS.forEach((path, formatter) -> {
            try {
                RegistryObject<ResourceLocation> registered = ALL.get(path);
                ResourceLocation id = registered == null ? null : registered.orElse(null);
                Stats.CUSTOM.get(id == null ? McaCrime.id(path) : id, formatter);
            } catch (RuntimeException notReady) {
                // No statistic is worth failing setup over -- but a silent skip leaves a statistic
                // that renders as a raw tick count with nothing in the log to explain it, so the id
                // that failed is named (0.7.5 P6, closing a P5 note).
                McaCrime.LOGGER.warn("MCA: Crime could not give statistic '{}' its display formatter; "
                        + "it will render with the default format ({})", McaCrime.id(path), notReady.toString());
            }
        });
    }

    /** The statistic for one family and kind, or null when that combination has none. */
    @Nullable
    public static RegistryObject<ResourceLocation> restraintStat(@Nullable Tracked tracked,
                                                                 @Nullable Kind kind) {
        if (tracked == null || kind == null) {
            return null;
        }
        return RESTRAINT_STATS.get(CrimeStatIds.path(tracked, kind));
    }

    /**
     * Awards one restraint statistic for a committed event.
     *
     * <p>Called from the transaction that committed, never from a tick or a packet handler, so the
     * count is the number of things that actually happened.
     */
    public static void awardRestraint(@Nullable net.minecraft.world.entity.LivingEntity subject,
                                      @Nullable ResourceLocation definition, @Nullable Kind kind) {
        awardRestraint(subject, definition, kind, 1);
    }

    /** The same, by an amount — which is how the time-spent counters are fed. */
    public static void awardRestraint(@Nullable net.minecraft.world.entity.LivingEntity subject,
                                      @Nullable ResourceLocation definition, @Nullable Kind kind,
                                      int amount) {
        if (!(subject instanceof ServerPlayer player) || amount <= 0) {
            return; // a villager has no statistics screen to write to
        }
        award(player, restraintStat(CrimeStatIds.trackedFor(definition), kind), amount);
    }

    /**
     * Adds one to a custom statistic.
     *
     * <p>Silent on anything that cannot be counted: a statistic is a record of play, and failing to
     * write one must never interrupt what the player was doing.
     */
    public static void award(@Nullable ServerPlayer player, @Nullable RegistryObject<ResourceLocation> stat) {
        award(player, stat, 1);
    }

    public static void award(@Nullable ServerPlayer player,
                             @Nullable RegistryObject<ResourceLocation> stat, int amount) {
        if (player == null || stat == null || amount <= 0) {
            return;
        }
        try {
            ResourceLocation id = stat.orElse(null);
            if (id != null) {
                player.awardStat(Stats.CUSTOM.get(id, StatFormatter.DEFAULT), amount);
            }
        } catch (RuntimeException notReady) {
            // no statistic is worth an exception on a gameplay path
        }
    }
}
