package dev.otectus.mcacrime.detect;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiles the {@code entities.protectedEntities} and {@code entities.responderEntities} config lists
 * into predicates, so the two settings finally do something.
 *
 * <p>Both keys shipped in 0.1.0 and were validated by {@code /crime validate} but never consulted by
 * anything, which is the worst state a setting can be in: an operator adds a modded guard to
 * {@code responderEntities}, {@code /crime validate} tells them the id is fine, and the guard then
 * fails to witness a single crime. The plan (§22.3) is explicit that these must be compiled to
 * predicates in the gate and observation service or removed; this is that compilation.
 *
 * <p>Entries are either an entity id ({@code minecraft:iron_golem}) or a tag reference
 * ({@code #minecraft:raiders}). Unparseable entries are dropped with one warning per reparse rather
 * than per lookup — a broken config line must not log once per entity per crime.
 *
 * <p>MCA's own villagers and guards are matched through {@link McaCompat} and are never in these
 * lists: the config is <em>additive</em>, so emptying it cannot switch off the mod's core subject.
 */
public final class EntitySelectors {

    private record Compiled(Set<ResourceLocation> ids, List<TagKey<EntityType<?>>> tags) {

        boolean matches(Entity entity) {
            if (entity == null) {
                return false;
            }
            EntityType<?> type = entity.getType();
            if (!ids.isEmpty()) {
                ResourceLocation id = EntityType.getKey(type);
                if (ids.contains(id)) {
                    return true;
                }
            }
            for (TagKey<EntityType<?>> tag : tags) {
                if (type.is(tag)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final Compiled EMPTY = new Compiled(Set.of(), List.of());

    private static volatile List<? extends String> protectedSource = List.of();
    private static volatile Compiled protectedCompiled = EMPTY;
    private static volatile List<? extends String> responderSource = List.of();
    private static volatile Compiled responderCompiled = EMPTY;

    private EntitySelectors() {
    }

    /**
     * A protected victim: an MCA villager, or an entity the server owner added. Guards are villagers
     * too, so they are covered without being listed.
     */
    public static boolean isProtected(Entity entity) {
        if (McaCompat.isMcaVillager(entity)) {
            return true;
        }
        return protectedSelector().matches(entity);
    }

    /** True only for a configured extra protected entity — the additive half, on its own. */
    public static boolean isConfiguredProtected(Entity entity) {
        return protectedSelector().matches(entity);
    }

    /** A law responder: an MCA guard, or an entity the server owner added. */
    public static boolean isResponder(Entity entity) {
        if (McaCompat.isGuard(entity)) {
            return true;
        }
        return responderSelector().matches(entity);
    }

    /** Drops the compiled forms so the next lookup rebuilds them. Called on config reload. */
    public static void invalidate() {
        protectedSource = List.of();
        protectedCompiled = EMPTY;
        responderSource = List.of();
        responderCompiled = EMPTY;
    }

    private static Compiled protectedSelector() {
        List<? extends String> current = safeList(McaCrimeConfig.COMMON.protectedEntities);
        if (current != protectedSource) {
            protectedCompiled = compile("protectedEntities", current);
            protectedSource = current;
        }
        return protectedCompiled;
    }

    private static Compiled responderSelector() {
        List<? extends String> current = safeList(McaCrimeConfig.COMMON.responderEntities);
        if (current != responderSource) {
            responderCompiled = compile("responderEntities", current);
            responderSource = current;
        }
        return responderCompiled;
    }

    /**
     * Reads a config list without letting a not-yet-loaded spec throw. Selectors are consulted from
     * damage handling, which can run before config load in a malformed setup; an empty list there is
     * correct (nothing extra is protected yet) and a crash is not.
     */
    private static List<? extends String> safeList(net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<List<? extends String>> value) {
        try {
            List<? extends String> list = value.get();
            return list == null ? List.of() : list;
        } catch (IllegalStateException e) {
            return List.of();
        }
    }

    private static Compiled compile(String key, List<? extends String> raw) {
        if (raw.isEmpty()) {
            return EMPTY;
        }
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        List<TagKey<EntityType<?>>> tags = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String trimmed = entry.trim();
            boolean isTag = trimmed.startsWith("#");
            ResourceLocation id = ResourceLocation.tryParse(isTag ? trimmed.substring(1) : trimmed);
            if (id == null) {
                rejected.add(trimmed);
                continue;
            }
            if (isTag) {
                tags.add(TagKey.create(Registries.ENTITY_TYPE, id));
            } else {
                ids.add(id);
            }
        }
        if (!rejected.isEmpty()) {
            McaCrime.LOGGER.warn("MCA: Crime ignored {} unparseable entr(ies) in {}: {}",
                    rejected.size(), key, String.join(", ", rejected));
        }
        return new Compiled(Set.copyOf(ids), List.copyOf(tags));
    }
}
