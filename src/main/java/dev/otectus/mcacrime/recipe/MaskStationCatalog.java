package dev.otectus.mcacrime.recipe;

import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The styles the server is currently willing to make from the inputs in one station menu, in one
 * fixed order, tagged with the recipe generation they were gathered at (0.7.2 §7.5, §8.4).
 *
 * <p>Order is part of the contract rather than an implementation detail. The client picks by recipe
 * id, not by index, precisely so a resort cannot hand somebody a different mask than the one they
 * clicked — but a catalogue that reordered itself between two recomputes would still make the grid
 * jump under the player's cursor, so the comparator is fixed at group, then sort order, then id.
 *
 * <p>The generation is what makes a selection expire. A selection is only honoured when it was made
 * against the generation the server is still on; after a reload the client's id is re-checked against
 * the new catalogue and dropped if it is no longer there (a deleted recipe) — never quietly swapped
 * for the recipe that now sorts into the same position.
 */
public final class MaskStationCatalog {

    private static final Comparator<Entry> ORDER = Comparator
            .comparing(Entry::group)
            .thenComparingInt(Entry::sortOrder)
            .thenComparing(entry -> entry.id().toString());

    /** One selectable style: what to ask for, and where it sorts. */
    public record Entry(ResourceLocation id, String group, int sortOrder) {

        public Entry {
            group = group == null ? "" : group;
        }
    }

    /** No inputs, no styles — and generation 0, which no live server catalogue ever carries. */
    public static final MaskStationCatalog EMPTY = new MaskStationCatalog(0, List.of());

    private final int generation;
    private final List<Entry> entries;

    private MaskStationCatalog(int generation, List<Entry> entries) {
        this.generation = generation;
        this.entries = entries;
    }

    /** Gathers and sorts one catalogue. */
    public static MaskStationCatalog of(int generation, Collection<Entry> entries) {
        return new MaskStationCatalog(generation, sorted(entries));
    }

    /** The fixed order, exposed so a test can assert it without building a catalogue. */
    public static List<Entry> sorted(Collection<Entry> entries) {
        List<Entry> copy = new ArrayList<>(entries == null ? List.of() : entries);
        copy.sort(ORDER);
        return List.copyOf(copy);
    }

    public int generation() {
        return generation;
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean contains(@Nullable ResourceLocation id) {
        return id != null && entries.stream().anyMatch(entry -> entry.id().equals(id));
    }

    public Optional<Entry> entry(@Nullable ResourceLocation id) {
        return entries.stream().filter(candidate -> candidate.id().equals(id)).findFirst();
    }

    /**
     * The selection this catalogue still honours.
     *
     * <p>Empty when nothing was selected, when the selection was made against an older generation, or
     * when the recipe it named is no longer offered. All three clear the preview rather than sliding
     * the selection sideways onto whatever now occupies that slot in the grid.
     */
    public Optional<ResourceLocation> revalidate(@Nullable ResourceLocation selected, int selectionGeneration) {
        if (selected == null || selectionGeneration != generation || !contains(selected)) {
            return Optional.empty();
        }
        return Optional.of(selected);
    }
}
