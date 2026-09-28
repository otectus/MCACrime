package dev.otectus.mcacrime.api.model;

import java.util.Optional;
import java.util.UUID;

/**
 * A search in progress, without its contents (0.7.5 M6.4).
 *
 * <p>The contents are deliberately absent. Only the authorised frisker ever receives what is in
 * somebody's pockets, and that goes to their own client through the frisking packets — never into a
 * public view a third mod can read, queue or log. What a companion may know is that a search is
 * happening, who is searching whom, and whether it is lawful.
 *
 * @param sessionId the session's own id
 * @param searcher  who is searching
 * @param subject   who is being searched
 * @param lawful    whether this is a guard's search rather than a robbery
 * @param revision  the session revision, so a stale view is recognisable
 * @param seizures  how many stacks have been taken so far
 */
public record FriskSessionView(UUID sessionId, UUID searcher, UUID subject, boolean lawful,
                               long revision, int seizures) {

    public FriskSessionView {
        revision = Math.max(0L, revision);
        seizures = Math.max(0, seizures);
    }

    /** Whether anything has actually changed hands yet. */
    public Optional<Integer> taken() {
        return seizures <= 0 ? Optional.empty() : Optional.of(seizures);
    }
}
