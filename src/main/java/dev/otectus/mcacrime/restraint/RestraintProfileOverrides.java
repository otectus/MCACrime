package dev.otectus.mcacrime.restraint;

import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The datapack layer over the ten code definitions (plan §3.13).
 *
 * <p>A layer rather than a mutable registry, and that is the whole safety argument.
 * {@link RestraintDefinitions} keeps its {@code Map.copyOf} of ten records that nothing can replace
 * an entry in; this class holds the pack's numbers beside it and hands out a <em>derived</em>
 * definition. Removing the pack and reloading restores the code table exactly, because the code
 * table was never touched.
 *
 * <p>Read through {@link RestraintDefinitions#get} rather than here. Every caller that already asked
 * the registry for a definition therefore reads effective values with no change at the call site,
 * which is the only way to be sure no reader was missed: durability, restrictions, pick numbers, key
 * family and the rig predicate all arrive on the same record they always did.
 *
 * <p>Both sides hold the same map. The server fills it from
 * {@code RestraintProfileLoader}; a client is told about it by
 * {@code network/RestraintProfileSyncS2CPacket} on login and on every datapack reload, because the
 * client composes its own {@link RestrictionPolicy} for input prediction and would otherwise predict
 * against numbers the server no longer uses.
 */
public final class RestraintProfileOverrides {

    /** The most profiles one pack set may state: the closed table is ten, plus third-party room. */
    public static final int MAX_PROFILES = 64;

    private static volatile Map<ResourceLocation, RestraintProfile> profiles = Map.of();
    private static volatile Map<ResourceLocation, RestraintDefinition> effective = Map.of();

    private RestraintProfileOverrides() {
    }

    /**
     * Replaces every override with {@code loaded}, recomputing the derived definitions once.
     *
     * <p>Once, at load, rather than per read: {@code get} is on the input path and runs every tick a
     * restrained player holds a key down.
     */
    public static void replaceAll(@Nullable Collection<RestraintProfile> loaded) {
        if (loaded == null || loaded.isEmpty()) {
            profiles = Map.of();
            effective = Map.of();
            return;
        }
        Map<ResourceLocation, RestraintProfile> byId = new LinkedHashMap<>();
        Map<ResourceLocation, RestraintDefinition> derived = new LinkedHashMap<>();
        for (RestraintProfile profile : loaded) {
            if (profile == null || profile.definitionId() == null) {
                continue;
            }
            RestraintDefinition base = RestraintDefinitions.base(profile.definitionId()).orElse(null);
            if (base == null) {
                continue; // the loader refuses unknown ids; this is the belt to that braces
            }
            byId.put(profile.definitionId(), profile);
            RestraintDefinition applied = profile.applyTo(base);
            if (applied != null) {
                derived.put(profile.definitionId(), applied);
            }
        }
        profiles = Map.copyOf(byId);
        effective = Map.copyOf(derived);
    }

    /** Drops every override: no pack loaded, or a client that has left the server. */
    public static void clear() {
        profiles = Map.of();
        effective = Map.of();
    }

    /** True when nothing is overridden, which is the shipped state — no profile files are shipped. */
    public static boolean isEmpty() {
        return effective.isEmpty();
    }

    /** How many definitions are overridden. */
    public static int size() {
        return effective.size();
    }

    /** Every active override, in load order. */
    public static List<RestraintProfile> all() {
        return List.copyOf(profiles.values());
    }

    /** The override for {@code definitionId}, or empty when a pack said nothing about it. */
    public static Optional<RestraintProfile> profile(@Nullable ResourceLocation definitionId) {
        return definitionId == null ? Optional.empty() : Optional.ofNullable(profiles.get(definitionId));
    }

    /**
     * {@code base} with any override applied, or {@code base} itself when there is none.
     *
     * <p>Identity when nothing is overridden, deliberately: the common case must not allocate.
     */
    @Nullable
    public static RestraintDefinition effective(@Nullable RestraintDefinition base) {
        if (base == null) {
            return null;
        }
        if (effective.isEmpty()) {
            return base;
        }
        RestraintDefinition applied = effective.get(base.id());
        return applied == null ? base : applied;
    }

    /**
     * The overridden starting durability of {@code definitionId}, where a pack stated one.
     *
     * <p>{@link RestraintDurability} asks this before it reads the config, because a pack naming a
     * definition's durability is a more specific statement than a server-wide tuning key — and
     * because otherwise the seven configured families could never be data-driven at all.
     */
    public static OptionalInt durability(@Nullable ResourceLocation definitionId) {
        RestraintProfile profile = definitionId == null ? null : profiles.get(definitionId);
        return profile == null ? OptionalInt.empty() : profile.durability();
    }
}
