package dev.otectus.mcacrime.tether;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who is tied to whom, looked up without walking the world (0.7.5 M4.1).
 *
 * <p>The source answers "what is anchored to this?" by iterating {@code server.getAllEntities()} —
 * a full world entity scan on <em>every right-click of any block</em>
 * ({@code event/ModServerEvents.java:165-171}). On a populated server that is the single most
 * expensive thing a player can do by pressing a button. This is the replacement: three reverse
 * indices maintained on write, so a lookup is a map read and a tick only visits tethers whose ends
 * exist.
 *
 * <p>Transient by construction. The tether table in {@code CrimeWorldData} is the authority and the
 * only thing persisted; this is rebuilt from it at load and kept in step by {@link TetherService}.
 * A stale index can therefore never outlive a restart, which is the failure mode a persisted index
 * would have and a rebuilt one cannot.
 *
 * <p>Not static: one instance per store, so a test can hold its own and the server's is not a global
 * anybody can corrupt from another thread.
 */
public final class TetherIndex {

    /** A fixed point: a dimension and a block. The key type of the anchor index. */
    public record AnchorKey(@Nullable ResourceLocation dimension, BlockPos pos) {

        public AnchorKey {
            pos = pos == null ? BlockPos.ZERO : pos.immutable();
        }

        public static AnchorKey of(@Nullable ResourceLocation dimension, @Nullable BlockPos pos) {
            return pos == null ? null : new AnchorKey(dimension, pos);
        }
    }

    private final Map<UUID, Set<UUID>> bySubject = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> byHolder = new LinkedHashMap<>();
    private final Map<AnchorKey, Set<UUID>> byAnchor = new LinkedHashMap<>();
    private final Map<UUID, TetherRecord> rows = new LinkedHashMap<>();

    /** Forgets everything and re-derives from the authoritative table. */
    public void rebuild(@Nullable Collection<TetherRecord> tethers) {
        clear();
        if (tethers == null) {
            return;
        }
        tethers.forEach(this::put);
    }

    public void clear() {
        bySubject.clear();
        byHolder.clear();
        byAnchor.clear();
        rows.clear();
    }

    /** Records or replaces one tether. Replacing re-keys it, so a moved anchor does not leave a ghost. */
    public void put(@Nullable TetherRecord tether) {
        if (tether == null || tether.id() == null) {
            return;
        }
        remove(tether.id());
        rows.put(tether.id(), tether);
        if (tether.subject() != null) {
            bySubject.computeIfAbsent(tether.subject(), k -> new LinkedHashSet<>()).add(tether.id());
        }
        if (tether.holder() != null) {
            byHolder.computeIfAbsent(tether.holder(), k -> new LinkedHashSet<>()).add(tether.id());
        }
        AnchorKey anchor = AnchorKey.of(tether.dimension(), tether.anchorPos());
        if (anchor != null) {
            byAnchor.computeIfAbsent(anchor, k -> new LinkedHashSet<>()).add(tether.id());
        }
    }

    /** Drops one tether from every index. Idempotent. */
    public void remove(@Nullable UUID tetherId) {
        if (tetherId == null) {
            return;
        }
        TetherRecord existing = rows.remove(tetherId);
        if (existing == null) {
            return;
        }
        drop(bySubject, existing.subject(), tetherId);
        drop(byHolder, existing.holder(), tetherId);
        drop(byAnchor, AnchorKey.of(existing.dimension(), existing.anchorPos()), tetherId);
    }

    private static <K> void drop(Map<K, Set<UUID>> index, @Nullable K key, UUID value) {
        if (key == null) {
            return;
        }
        Set<UUID> ids = index.get(key);
        if (ids == null) {
            return;
        }
        ids.remove(value);
        if (ids.isEmpty()) {
            index.remove(key);
        }
    }

    @Nullable
    public TetherRecord get(@Nullable UUID tetherId) {
        return tetherId == null ? null : rows.get(tetherId);
    }

    /** Every tether holding {@code subject}. */
    public List<TetherRecord> forSubject(@Nullable UUID subject) {
        return resolve(bySubject.get(subject));
    }

    /** Every tether {@code holder} is leading. */
    public List<TetherRecord> forHolder(@Nullable UUID holder) {
        return resolve(byHolder.get(holder));
    }

    /** Every tether tied to one fixed point. */
    public List<TetherRecord> forAnchor(@Nullable ResourceLocation dimension, @Nullable BlockPos pos) {
        AnchorKey key = AnchorKey.of(dimension, pos);
        return key == null ? List.of() : resolve(byAnchor.get(key));
    }

    /** Whether anything at all is tied to this fixed point. A map read, never a world scan. */
    public boolean anchored(@Nullable ResourceLocation dimension, @Nullable BlockPos pos) {
        AnchorKey key = AnchorKey.of(dimension, pos);
        return key != null && byAnchor.containsKey(key);
    }

    /** The holder of {@code subject}, ignoring suspended tethers. Empty when nobody is holding them. */
    @Nullable
    public UUID holderOf(@Nullable UUID subject) {
        for (TetherRecord tether : forSubject(subject)) {
            if (!tether.suspended() && tether.holder() != null) {
                return tether.holder();
            }
        }
        return null;
    }

    /** Every tether currently recorded, in insertion order. */
    public List<TetherRecord> all() {
        return new ArrayList<>(rows.values());
    }

    public int size() {
        return rows.size();
    }

    /** How many subjects one holder is leading right now. */
    public int heldBy(@Nullable UUID holder) {
        Set<UUID> ids = byHolder.get(holder);
        return ids == null ? 0 : ids.size();
    }

    private List<TetherRecord> resolve(@Nullable Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<TetherRecord> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            TetherRecord tether = rows.get(id);
            if (tether != null) {
                out.add(tether);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "TetherIndex[" + rows.size() + " tethers, " + bySubject.size() + " subjects, "
                + byHolder.size() + " holders, " + byAnchor.size() + " anchors]";
    }

    /** Value equality on the rows only; the indices are derived from them. */
    @Override
    public boolean equals(Object other) {
        return other instanceof TetherIndex index && Objects.equals(rows, index.rows);
    }

    @Override
    public int hashCode() {
        return rows.hashCode();
    }
}
