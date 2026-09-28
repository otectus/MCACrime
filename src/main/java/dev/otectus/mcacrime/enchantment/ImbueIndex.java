package dev.otectus.mcacrime.enchantment;

import dev.otectus.mcacrime.restraint.AppliedRestraint;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import dev.otectus.mcacrime.restraint.RestraintApplier;
import dev.otectus.mcacrime.restraint.RestraintSlot;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who is wearing an Imbue-enchanted restraint, keyed by whoever put it on (0.7.5 M6.1).
 *
 * <p>The reverse index is the fix for the sixth Imbue defect: upstream walks the whole player list on
 * <em>every</em> {@code LivingDamageEvent}, asking each restraint for its captor with a
 * {@code getPlayerByUUID} per slot ({@code ModServerEvents.java:407-415}). Here the world's restraint
 * table is walked once a second by {@link RestraintEffects#tick} and the answer is a map lookup.
 *
 * <p>It is also where the first defect is structurally impossible: the subjects behind one captor are
 * a {@link LinkedHashSet}, so a prisoner cuffed on the arms <em>and</em> shackled on the legs by the
 * same captor appears exactly once and cannot be hurt twice or inflate the divisor. Insertion order
 * is kept so the distribution is deterministic and a test can assert it.
 *
 * <p>Memory-only and rebuilt from world data, so a restart or a reload cannot leave a stale captor
 * holding a share of anybody's damage.
 */
public final class ImbueIndex {

    private static volatile Map<UUID, Set<UUID>> byCaptor = Map.of();

    private ImbueIndex() {
    }

    /**
     * Replaces the index from the current restraint table.
     *
     * <p>Whole-map replacement rather than in-place mutation: the damage handler reads this from the
     * server thread while the rebuild runs on the same thread, but a swapped reference is still the
     * cheaper contract to reason about, and an empty map is the common case.
     */
    public static void rebuild(@Nullable Collection<PhysicalRestraintState> states) {
        if (states == null || states.isEmpty()) {
            byCaptor = Map.of();
            return;
        }
        Map<UUID, Set<UUID>> built = new LinkedHashMap<>();
        for (PhysicalRestraintState state : states) {
            UUID subject = state.subject();
            if (subject == null) {
                continue;
            }
            for (RestraintSlot slot : RestraintSlot.values()) {
                AppliedRestraint worn = state.slot(slot).orElse(null);
                if (worn == null || RestraintEnchantments.levelOn(worn, CrimeEnchantKind.IMBUE) <= 0) {
                    continue;
                }
                UUID captor = captorOf(worn);
                if (captor == null || captor.equals(subject)) {
                    continue; // a self-applied Imbue restraint transfers a captor's damage to nobody
                }
                built.computeIfAbsent(captor, id -> new LinkedHashSet<>()).add(subject);
            }
        }
        byCaptor = built.isEmpty() ? Map.of() : Map.copyOf(built);
    }

    /** Whether anybody at all is wearing an Imbue restraint. One boolean on the damage path. */
    public static boolean empty() {
        return byCaptor.isEmpty();
    }

    /**
     * The distinct subjects {@code captor} holds in Imbue restraints, in a stable order.
     *
     * <p>Never null, and never contains the captor themselves.
     */
    public static List<UUID> recipients(@Nullable UUID captor) {
        Set<UUID> held = captor == null ? null : byCaptor.get(captor);
        return held == null ? List.of() : new ArrayList<>(held);
    }

    /** The highest Imbue level {@code worn} carries, for the share this instance is worth. */
    public static int level(@Nullable AppliedRestraint worn) {
        return RestraintEnchantments.levelOn(worn, CrimeEnchantKind.IMBUE);
    }

    /** Drops the index. Server stop, and every test's setup. */
    public static void clear() {
        byCaptor = Map.of();
    }

    @Nullable
    private static UUID captorOf(AppliedRestraint worn) {
        RestraintApplier applier = worn.applier();
        if (applier == null) {
            return null;
        }
        return switch (applier.kind()) {
            case PLAYER, NPC -> applier.entityId().orElse(null);
            // A device or the server holds nobody: Imbue moves a captor's damage, and neither a
            // pillory nor an arrest has any to move.
            case DEVICE, SYSTEM, NONE -> null;
        };
    }
}
