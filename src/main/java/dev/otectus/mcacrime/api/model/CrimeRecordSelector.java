package dev.otectus.mcacrime.api.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applies a {@link CrimeRecordQuery} to a list of views.
 *
 * <p>Split out from the query itself, and taking plain lists rather than a server, so the whole
 * selection rule — filtering, ordering, truncation — is unit-testable with no Minecraft server
 * involved. That matters more than usual here: a quest reward or a dialogue action resolves a
 * selector to an <em>exact</em> case before mutating anything, so an ordering bug does not produce a
 * wrong list, it pardons the wrong crime.
 *
 * <p>Ordering is total: game time first, then record UUID. Two cases committed on the same tick — a
 * mugging and the murder that ends it, say — would otherwise sort by whatever order the ledger
 * happened to be in, and "oldest unresolved case" would mean different things on different runs.
 */
public final class CrimeRecordSelector {

    private static final Comparator<CrimeRecordView> OLDEST_FIRST =
            Comparator.comparingLong(CrimeRecordView::committedGameTime)
                    .thenComparing(view -> view.id().toString());

    private CrimeRecordSelector() {
    }

    /** Filtered, ordered, and truncated to {@code query.limit()}. */
    public static List<CrimeRecordView> select(List<CrimeRecordView> candidates,
                                               CrimeRecordQuery query, long now) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<CrimeRecordView> matched = new ArrayList<>();
        for (CrimeRecordView view : candidates) {
            if (view != null && query.matches(view, now)) {
                matched.add(view);
            }
        }
        matched.sort(query.order() == CrimeRecordQuery.SortOrder.OLDEST_FIRST
                ? OLDEST_FIRST
                : OLDEST_FIRST.reversed());
        return matched.size() <= query.limit()
                ? List.copyOf(matched)
                : List.copyOf(matched.subList(0, query.limit()));
    }

    /** How many would match before the limit is applied — for a "showing 25 of 140" line. */
    public static int countMatching(List<CrimeRecordView> candidates, CrimeRecordQuery query, long now) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (CrimeRecordView view : candidates) {
            if (view != null && query.matches(view, now)) {
                count++;
            }
        }
        return count;
    }
}
