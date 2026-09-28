package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.restraint.PhysicalRestraintView;
import dev.otectus.mcacrime.restraint.RestraintSlot;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of who is physically restrained, with what, per slot.
 *
 * <p>The multi-slot successor to the retired {@code ClientRestraintData}, fed entirely by the server and never
 * classloaded on a dedicated server. Nothing here decides anything: writing to this map cannot make
 * anybody restrained, and the renderers that will read it in M2 can only draw what the server said.
 *
 * <p>Revision-ordered. A message that is older than what is already stored is dropped rather than
 * applied, because packets about one subject can overtake each other and the visible result of
 * obeying the older one is gear that reappears after it was removed.
 */
public final class ClientPhysicalRestraintData {

    private static final Map<UUID, PhysicalRestraintView> SUBJECTS = new ConcurrentHashMap<>();

    private ClientPhysicalRestraintData() {
    }

    /** Replaces the whole picture. The login/respawn message; a delta can never do this. */
    public static void replaceAll(Collection<PhysicalRestraintView> views) {
        SUBJECTS.clear();
        if (views == null) {
            return;
        }
        for (PhysicalRestraintView view : views) {
            if (view != null && view.subject() != null && !view.vacant()) {
                SUBJECTS.put(view.subject(), view);
            }
        }
    }

    /** Applies one subject's update, unless something newer is already stored. */
    public static void accept(PhysicalRestraintView view) {
        if (view == null || view.subject() == null) {
            return;
        }
        PhysicalRestraintView existing = SUBJECTS.get(view.subject());
        if (existing != null && newerThan(existing, view)) {
            return;
        }
        if (view.vacant()) {
            SUBJECTS.remove(view.subject());
        } else {
            SUBJECTS.put(view.subject(), view);
        }
    }

    /**
     * Forgets one subject, unless something newer is already stored.
     *
     * <p>A removal issued at revision 0 is unconditional: it is what the server sends when it has no
     * state left to quote a revision from, and refusing it would leave gear drawn forever.
     */
    public static void remove(UUID subject, long revision) {
        if (subject == null) {
            return;
        }
        PhysicalRestraintView existing = SUBJECTS.get(subject);
        if (existing != null && revision > 0L && existing.revision() > revision) {
            return;
        }
        SUBJECTS.remove(subject);
    }

    public static Optional<PhysicalRestraintView> get(UUID subject) {
        return subject == null ? Optional.empty() : Optional.ofNullable(SUBJECTS.get(subject));
    }

    /** What is on one slot of one subject, for the layer that draws it. */
    public static Optional<PhysicalRestraintView.SlotView> slot(UUID subject, RestraintSlot slot) {
        return get(subject).flatMap(view -> view.slot(slot));
    }

    public static boolean restrained(UUID subject) {
        return get(subject).map(view -> !view.slots().isEmpty()).orElse(false);
    }

    /** Every known subject. The renderers walk this. */
    public static Map<UUID, PhysicalRestraintView> all() {
        return SUBJECTS;
    }

    public static void clear() {
        SUBJECTS.clear();
    }

    /**
     * Whether {@code existing} describes a later state than {@code incoming}.
     *
     * <p>Generation first: a new hold always wins, however low its revision starts. Only within one
     * generation does the revision decide.
     */
    private static boolean newerThan(PhysicalRestraintView existing, PhysicalRestraintView incoming) {
        if (existing.generation() != incoming.generation()) {
            return existing.generation() > incoming.generation();
        }
        return existing.revision() > incoming.revision();
    }
}
