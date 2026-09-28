package dev.otectus.mcacrime.news;

import net.minecraft.nbt.*;
import java.util.*;

/** Bounded optional projection. Never owns custody, property or legal evidence. */
public final class CrimeNewsData {
    public static final int MAX_FACTS = 4096, MAX_SUBSCRIBERS = 4096, MAX_ENVELOPES = 8192;
    public static final long RETENTION = 7L * 24000;
    public record Fact(UUID caseId, UUID suspect, UUID recipient, String community, String kind, long at, long revision) {}
    public static final class Subscription {
        public boolean enabled = true;
        public long cursor, next, interval;
        public final LinkedHashSet<Long> seen = new LinkedHashSet<>();
        public final LinkedHashMap<String, Long> communities = new LinkedHashMap<>();
    }
    public record Envelope(UUID id, UUID recipient, CompoundTag letter, long created, long expires) {}
    public final LinkedHashMap<String, Fact> facts = new LinkedHashMap<>();
    public final LinkedHashMap<UUID, Subscription> subscriptions = new LinkedHashMap<>();
    public final LinkedHashMap<UUID, Envelope> envelopes = new LinkedHashMap<>();
    private final ListTag quarantine = new ListTag();
    public UUID worldId = UUID.randomUUID();
    public long sequence, dropped;
    private static String factKey(UUID caseId, UUID recipient, String community, String kind) {
        return caseId + "/" + (recipient == null ? community : recipient) + "/" + (kind.equals("recovered") ? "recovery" : "case");
    }
    public void report(UUID caseId, UUID suspect, UUID recipient, String community, long filedAt) {
        if (!facts.containsKey(factKey(caseId, recipient, community, "reported")))
            publish(caseId, suspect, recipient, community, "reported", filedAt);
    }
    public Fact latest(Fact fact) {
        return facts.getOrDefault(factKey(fact.caseId, fact.recipient, fact.community, fact.kind), fact);
    }
    public void publish(UUID caseId, UUID suspect, UUID recipient, String community, String kind, long at) {
        if (caseId == null || suspect == null || at < 0 || (recipient == null && community.isEmpty())) return;
        String key = factKey(caseId, recipient, community, kind);
        Fact old = facts.get(key);
        if (old != null && old.kind.equals(kind)) return;
        prune(at);
        if (old == null && (facts.size() >= MAX_FACTS || !community.isEmpty() && facts.values().stream().filter(f -> f.community.equals(community)).count() >= 64)) {
            dropped++; return;
        }
        facts.put(key, new Fact(caseId, suspect, recipient, community, kind, at, ++sequence));
    }
    public Subscription subscribe(UUID player, long now) {
        Subscription existing = subscriptions.get(player);
        if (existing != null) return existing;
        if (subscriptions.size() >= MAX_SUBSCRIBERS) return null;
        Subscription added = new Subscription(); added.cursor = sequence; added.next = now;
        subscriptions.put(player, added); return added;
    }
    public void connect(Subscription sub, String community, long now) {
        sub.communities.remove(community); sub.communities.put(community, now);
        while (sub.communities.size() > 3) sub.communities.remove(sub.communities.keySet().iterator().next());
    }
    public void preference(UUID player, boolean enabled, long now) {
        Subscription sub = subscribe(player, now);
        if (sub == null) return;
        sub.enabled = enabled; sub.cursor = sequence; sub.next = now; sub.seen.clear();
        envelopes.values().removeIf(e -> e.recipient.equals(player));
    }
    public void prune(long now) {
        facts.values().removeIf(f -> now >= f.at && now - f.at > RETENTION);
        envelopes.values().removeIf(e -> now >= e.expires);
    }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putUUID("world", worldId); tag.putLong("sequence", sequence); tag.putLong("dropped", dropped);
        ListTag fs = new ListTag(); facts.forEach((key, f) -> {
            CompoundTag row = new CompoundTag(); row.putString("key", key); row.putUUID("case", f.caseId); row.putUUID("suspect", f.suspect);
            if (f.recipient != null) row.putUUID("recipient", f.recipient);
            row.putString("community", f.community); row.putString("kind", f.kind); row.putLong("at", f.at); row.putLong("revision", f.revision); fs.add(row);
        }); tag.put("facts", fs);
        ListTag ss = new ListTag(); subscriptions.forEach((id, sub) -> {
            CompoundTag row = new CompoundTag(); row.putUUID("player", id); row.putBoolean("enabled", sub.enabled);
            row.putLongArray("seen", sub.seen.stream().mapToLong(Long::longValue).toArray()); row.putLong("cursor", sub.cursor); row.putLong("next", sub.next); row.putLong("interval", sub.interval);
            ListTag cs = new ListTag(); sub.communities.forEach((key, at) -> { CompoundTag c = new CompoundTag(); c.putString("key", key); c.putLong("at", at); cs.add(c); });
            row.put("communities", cs); ss.add(row);
        }); tag.put("subscriptions", ss);
        ListTag es = new ListTag(); envelopes.values().forEach(e -> {
            CompoundTag row = new CompoundTag(); row.putUUID("id", e.id); row.putUUID("recipient", e.recipient); row.put("letter", e.letter.copy());
            row.putLong("created", e.created); row.putLong("expires", e.expires); es.add(row);
        }); tag.put("envelopes", es); tag.put("quarantine", quarantine.copy()); return tag;
    }
    public static boolean validLetter(CompoundTag letter) {
        if (!letter.contains("pages", Tag.TAG_LIST) || letter.toString().length() > 8192) return false;
        ListTag pages = letter.getList("pages", Tag.TAG_STRING);
        if (pages.isEmpty() || pages.size() > 4) return false;
        int total = 0;
        for (int i = 0; i < pages.size(); i++) {
            String json = pages.getString(i); total += json.length();
            if (total > 2000) return false;
            try {
                if (net.minecraft.network.chat.Component.Serializer.fromJson(json) == null) return false;
            } catch (RuntimeException invalid) { return false; }
        }
        return true;
    }
    private void quarantine(String table, CompoundTag invalid) {
        dropped++;
        if (quarantine.size() >= 128 || invalid.toString().length() > 8192) return;
        CompoundTag entry = new CompoundTag(); entry.putString("table", table); entry.put("row", invalid.copy()); quarantine.add(entry);
    }
    public static CrimeNewsData load(CompoundTag tag) {
        CrimeNewsData data = new CrimeNewsData();
        if (tag.hasUUID("world")) data.worldId = tag.getUUID("world");
        data.sequence = Math.max(0, Math.min(Long.MAX_VALUE / 2, tag.getLong("sequence"))); data.dropped = Math.max(0, tag.getLong("dropped"));
        ListTag oldQuarantine = tag.getList("quarantine", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(128, oldQuarantine.size()); i++)
            if (oldQuarantine.getCompound(i).toString().length() <= 8192) data.quarantine.add(oldQuarantine.getCompound(i).copy());
        ListTag fs = tag.getList("facts", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(MAX_FACTS, fs.size()); i++) {
            CompoundTag f = fs.getCompound(i); String kind = f.getString("kind"), community = f.getString("community");
            if (!f.hasUUID("case") || !f.hasUUID("suspect") || f.getLong("at") < 0 || f.getLong("revision") <= 0 || f.getLong("revision") >= Long.MAX_VALUE / 2
                    || !Set.of("reported", "arrested", "served", "closed", "dead", "recovered").contains(kind)
                    || community.length() > 256 || !community.isEmpty() && dev.otectus.mcacrime.api.model.CrimeCommunityKey.tryParse(community).isEmpty()) { data.quarantine("facts", f); continue; }
            UUID recipient = f.hasUUID("recipient") ? f.getUUID("recipient") : null;
            if (recipient == null && community.isEmpty() || recipient != null && !community.isEmpty()) { data.quarantine("facts", f); continue; }
            long revision = f.getLong("revision");
            if (data.facts.values().stream().anyMatch(x -> x.revision == revision)) { data.quarantine("facts", f); continue; }
            String key = factKey(f.getUUID("case"), recipient, community, kind);
            data.sequence = Math.max(data.sequence, revision);
            data.facts.put(key, new Fact(f.getUUID("case"), f.getUUID("suspect"), f.hasUUID("recipient") ? f.getUUID("recipient") : null,
                    community, kind, f.getLong("at"), f.getLong("revision")));
        }
        ListTag ss = tag.getList("subscriptions", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(MAX_SUBSCRIBERS, ss.size()); i++) {
            CompoundTag row = ss.getCompound(i); if (!row.hasUUID("player")) { data.quarantine("subscriptions", row); continue; }
            Subscription sub = new Subscription(); sub.enabled = row.getBoolean("enabled"); sub.cursor = Math.max(0, Math.min(data.sequence, row.getLong("cursor")));
            for (long seen : row.getLongArray("seen")) if (seen > sub.cursor && seen <= data.sequence && sub.seen.size() < 256) sub.seen.add(seen);
            sub.next = Math.max(0, row.getLong("next")); sub.interval = Math.max(0, row.getLong("interval"));
            ListTag cs = row.getList("communities", Tag.TAG_COMPOUND);
            for (int n = 0; n < Math.min(3, cs.size()); n++) {
                CompoundTag c = cs.getCompound(n); String key = c.getString("key");
                if (key.length() <= 256 && dev.otectus.mcacrime.api.model.CrimeCommunityKey.tryParse(key).isPresent() && c.getLong("at") >= 0) sub.communities.put(key, c.getLong("at"));
            }
            data.subscriptions.put(row.getUUID("player"), sub);
        }
        ListTag es = tag.getList("envelopes", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(MAX_ENVELOPES, es.size()); i++) {
            CompoundTag e = es.getCompound(i);
            if (!e.hasUUID("id") || !e.hasUUID("recipient") || e.getLong("created") < 0 || e.getLong("expires") < e.getLong("created")
                    || e.getLong("expires") - e.getLong("created") > RETENTION || !validLetter(e.getCompound("letter"))) { data.quarantine("envelopes", e); continue; }
            UUID recipient = e.getUUID("recipient");
            if (data.envelopes.values().stream().filter(x -> x.recipient.equals(recipient)).count() >= 8) continue;
            data.envelopes.put(e.getUUID("id"), new Envelope(e.getUUID("id"), recipient, e.getCompound("letter").copy(), e.getLong("created"), e.getLong("expires")));
        }
        return data;
    }
}
