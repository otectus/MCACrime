package dev.otectus.mcacrime.stat;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.stat.CrimeStatIds.Kind;
import dev.otectus.mcacrime.stat.CrimeStatIds.Tracked;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import org.jetbrains.annotations.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This mod's fifteen custom statistics, as registrations (M5.11, Appendix A.3).
 *
 * <p>Four restraint families with three counts each, two lockpick counts and the
 * safe. Which fifteen, and what each one means, is {@link CrimeStatIds}; this is only where they
 * are put into the registry and awarded. The registration is a {@link DeferredHolder} because that
 * is what a NeoForge {@code DeferredRegister} hands back; it is otherwise the baseline class. Every one of them is awarded <b>once per committed server
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
     * <p>The only structural difference from the baseline, and it is forced. A {@code Stat} builds its
     * translation key from {@code StatType#getRegistry().getKey(value)}, so asking {@code Stats.CUSTOM}
     * for one before the id is in {@code BuiltInRegistries.CUSTOM_STAT} gives a null key -- and on this
     * platform the {@code DeferredRegister} supplier runs <em>before</em> the value is registered, where
     * Forge's registry object resolved afterwards. So the formatter pass moved to
     * {@link #registerFormatters()}, which common setup calls once the registry is populated.
     * The invariant the baseline's comment claims is kept: every registered id gets a formatter, from
     * one place, and nothing else may add one.
     */
    private static final Map<String, StatFormatter> FORMATTERS = new LinkedHashMap<>();

    /**
     * The registered holder for each path, so the formatter pass can hand {@code Stats.CUSTOM} the
     * <em>registered</em> id object rather than an equal one.
     *
     * <p>This is not a nicety. {@code MappedRegistry} keeps its value-to-key map as an
     * {@code IdentityHashMap}, so a freshly built {@code ResourceLocation} that merely equals a
     * registered one is not found: {@code Stat}'s key construction then dereferences a null and every
     * formatter is lost with it. Reading the holder is the only way to get the instance the registry
     * actually holds.
     */
    private static final Map<String, DeferredHolder<ResourceLocation, ResourceLocation>> ALL =
            new LinkedHashMap<>();

    private static final Map<String, DeferredHolder<ResourceLocation, ResourceLocation>> RESTRAINT_STATS =
            registerRestraintStats();

    private static Map<String, DeferredHolder<ResourceLocation, ResourceLocation>> registerRestraintStats() {
        Map<String, DeferredHolder<ResourceLocation, ResourceLocation>> stats = new LinkedHashMap<>();
        for (Tracked tracked : Tracked.values()) {
            for (Kind kind : Kind.values()) {
                String path = CrimeStatIds.path(tracked, kind);
                stats.put(path, register(path, kind.formatter()));
            }
        }
        return Map.copyOf(stats);
    }

    public static final DeferredHolder<ResourceLocation, ResourceLocation> SUCCESSFUL_LOCKPICKS =
            register(CrimeStatIds.SUCCESSFUL_LOCKPICKS, StatFormatter.DEFAULT);
    public static final DeferredHolder<ResourceLocation, ResourceLocation> LOCKPICKS_BROKEN =
            register(CrimeStatIds.LOCKPICKS_BROKEN, StatFormatter.DEFAULT);
    /** Awarded once per safe a player opens. */
    public static final DeferredHolder<ResourceLocation, ResourceLocation> OPEN_SAFE =
            register(CrimeStatIds.OPEN_SAFE, StatFormatter.DEFAULT);

    private static DeferredHolder<ResourceLocation, ResourceLocation> register(String path, StatFormatter formatter) {
        FORMATTERS.put(path, formatter);
        DeferredHolder<ResourceLocation, ResourceLocation> holder =
                CUSTOM_STATS.register(path, () -> McaCrime.id(path));
        ALL.put(path, holder);
        return holder;
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
                DeferredHolder<ResourceLocation, ResourceLocation> holder = ALL.get(path);
                Stats.CUSTOM.get(holder == null ? McaCrime.id(path) : holder.get(), formatter);
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
    public static DeferredHolder<ResourceLocation, ResourceLocation> restraintStat(@Nullable Tracked tracked,
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
    public static void award(@Nullable ServerPlayer player, @Nullable DeferredHolder<ResourceLocation, ResourceLocation> stat) {
        award(player, stat, 1);
    }

    public static void award(@Nullable ServerPlayer player,
                             @Nullable DeferredHolder<ResourceLocation, ResourceLocation> stat, int amount) {
        if (player == null || stat == null || amount <= 0) {
            return;
        }
        try {
            ResourceLocation id = stat.isBound() ? stat.get() : null;
            if (id != null) {
                player.awardStat(Stats.CUSTOM.get(id, StatFormatter.DEFAULT), amount);
            }
        } catch (RuntimeException notReady) {
            // no statistic is worth an exception on a gameplay path
        }
    }
}
