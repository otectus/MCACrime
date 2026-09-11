package dev.otectus.mcacrime.item.contraband;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The contraband list, compiled from config and applied to a {@link ContrabandProbe} (0.7.0).
 *
 * <p>The same shape as {@code WeaponRules}, and pure for the same reason: no registry, no tag manager,
 * no {@code ItemStack}. An entry is {@code namespace:path} for an item or {@code #namespace:path} for a
 * tag, and an item is listed when its id is in the id set or it carries one of the tags.
 *
 * <p>Malformed entries are skipped and collected in {@link #problems()} rather than thrown: an operator
 * typo in one line must never take the rest of the list — or the config load — with it.
 *
 * <p>Tags cannot be validated while the config loads, because item tags are not bound yet. The active
 * rule set is therefore rebuilt through {@link #withTagsFiltered(Predicate)} once tags are known, which
 * drops the ones that do not exist instead of letting them silently match nothing forever.
 */
public final class ContrabandRules {

    private static final ContrabandRules EMPTY =
            new ContrabandRules(Set.of(), Set.of(), List.of());

    private final Set<ResourceLocation> ids;
    private final Set<ResourceLocation> tags;
    private final List<String> problems;

    private ContrabandRules(Set<ResourceLocation> ids, Set<ResourceLocation> tags, List<String> problems) {
        this.ids = ids;
        this.tags = tags;
        this.problems = problems;
    }

    /** An empty list, which lists nothing — the shipped default. */
    public static ContrabandRules empty() {
        return EMPTY;
    }

    /** Compiles the raw config values into a rule set. Malformed entries are dropped and reported. */
    public static ContrabandRules compile(List<? extends String> entries) {
        Set<ResourceLocation> parsedIds = new LinkedHashSet<>();
        Set<ResourceLocation> parsedTags = new LinkedHashSet<>();
        List<String> found = new ArrayList<>();
        parseInto(entries, parsedIds, parsedTags, found);
        return new ContrabandRules(parsedIds, parsedTags, List.copyOf(found));
    }

    /** Whether this stack is on the list: its id first, then the tags. */
    public boolean matches(ContrabandProbe probe) {
        if (probe == null) {
            return false;
        }
        if (probe.itemId() != null && ids.contains(probe.itemId())) {
            return true;
        }
        for (ResourceLocation tag : tags) {
            if (probe.hasTag(tag)) {
                return true;
            }
        }
        return false;
    }

    /** Nothing is listed, so no search is worth running. */
    public boolean isEmpty() {
        return ids.isEmpty() && tags.isEmpty();
    }

    public Set<ResourceLocation> ids() {
        return ids;
    }

    public Set<ResourceLocation> tags() {
        return tags;
    }

    /** Entries that could not be parsed, in config order, for {@code ConfigValidator} to report. */
    public List<String> problems() {
        return problems;
    }

    /**
     * A copy with every tag the given predicate does not know dropped, and one problem line recorded for
     * each. Called from the {@code TagsUpdatedEvent} listener, which is the first moment item tags exist.
     */
    public ContrabandRules withTagsFiltered(Predicate<ResourceLocation> known) {
        if (known == null || tags.isEmpty()) {
            return this;
        }
        Set<ResourceLocation> kept = new LinkedHashSet<>();
        List<String> stillProblems = new ArrayList<>(problems);
        for (ResourceLocation tag : tags) {
            if (known.test(tag)) {
                kept.add(tag);
            } else {
                stillProblems.add("unknown item tag: #" + tag);
            }
        }
        if (kept.size() == tags.size()) {
            return this;
        }
        return new ContrabandRules(ids, kept, List.copyOf(stillProblems));
    }

    private static void parseInto(List<? extends String> raw, Set<ResourceLocation> ids,
                                  Set<ResourceLocation> tags, List<String> problems) {
        if (raw == null) {
            return;
        }
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String trimmed = entry.trim();
            boolean isTag = trimmed.startsWith("#");
            ResourceLocation id = ResourceLocation.tryParse(isTag ? trimmed.substring(1) : trimmed);
            if (id == null) {
                problems.add("unparseable entry: " + trimmed);
                continue; // dropping it keeps the rest of the list working
            }
            if (isTag) {
                tags.add(id);
            } else {
                ids.add(id);
            }
        }
    }
}
