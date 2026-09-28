package dev.otectus.mcacrime.news;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.CrimeReportEvent;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.compat.McaMailBridge;
import dev.otectus.mcacrime.config.CrimeWorldSettings;
import dev.otectus.mcacrime.detect.CrimeCommunityResolver;
import dev.otectus.mcacrime.enforcement.Jurisdictions;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Event-fed, read-only editorial projection with a retained, append-once native mailbox outbox. */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeNewsService {
    private CrimeNewsService() {}
    @SubscribeEvent public static void reported(CrimeReportEvent.Post event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (!ServerMutationGate.allows(server) || !CrimeWorldSettings.resolve(server).crimeNews()) return;
        var data = CrimeWorldData.get(server);
        long now = server.overworld().getGameTime();
        if (event.getSuspectId() == null) return;
        var report = data.reportsAgainst(event.getSuspectId()).stream()
                .filter(r -> r.reportId().equals(event.getReportId())).findFirst().orElse(null);
        if (report == null || report.expired(now) || now < report.filedAt() || now - report.filedAt() > CrimeNewsData.RETENTION
                || !report.supportsArrest(McaCrimeConfig.COMMON.reportConfidenceThreshold.get())) return;
        var record = data.recordById(report.incidentId()).orElse(null);
        if (record == null || !record.offender().equals(report.suspectId()) || !record.type().equals(report.actionId())) return;
        String community = report.jurisdiction() == null ? "" : report.jurisdiction().toString();
        if (dev.otectus.mcacrime.api.model.CrimePublicView.isPublic(record.view(), report.jurisdiction(),
                CrimeWorldSettings.resolve(server).observations(), id -> id.equals(report.incidentId())))
            data.news().report(record.id(), record.offender(), null, community, report.filedAt());
        // Only an actual player reporter receives a personal digest; no offline player-file scans.
        if (server.getPlayerList().getPlayer(report.reporterId()) != null) {
            data.news().subscribe(report.reporterId(), now);
            data.news().report(record.id(), record.offender(), report.reporterId(), "", report.filedAt());
        }
        data.setDirty();
    }
    public static void recovered(MinecraftServer server, UUID caseId, UUID suspect, UUID owner) {
        if (!ServerMutationGate.allows(server) || !CrimeWorldSettings.resolve(server).crimeNews()) return;
        var data = CrimeWorldData.get(server); long now = server.overworld().getGameTime();
        data.news().subscribe(owner, now);
        data.news().publish(caseId, suspect, owner, "", "recovered", now); data.setDirty();
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer(); long now = server.overworld().getGameTime();
        if (now % 200 != 0 || !ServerMutationGate.allows(server)) return;
        var data = CrimeWorldData.get(server); var news = data.news(); var settings = CrimeWorldSettings.resolve(server);
        news.prune(now);
        if (!settings.crimeNews()) {
            news.envelopes.clear(); news.subscriptions.values().forEach(s -> { s.cursor = news.sequence; s.next = now + 24000L * settings.newsIntervalDays(); });
            data.setDirty(); return;
        }
        // Process at most 64 already-published facts per cadence; never enumerate the crime ledger.
        var facts = new ArrayList<>(news.facts.values());
        int offset = facts.isEmpty() ? 0 : (int)((now / 200 * 64) % facts.size());
        for (int i = 0; i < Math.min(64, facts.size()); i++) refresh(server, facts.get((offset + i) % facts.size()), now);
        var players = server.getPlayerList().getPlayers();
        int start = players.isEmpty() ? 0 : (int)((now / 200 * 8) % players.size());
        for (int i = 0; i < Math.min(8, players.size()); i++) process(players.get((start + i) % players.size()), now);
        data.setDirty();
    }
    private static void refresh(MinecraftServer server, CrimeNewsData.Fact fact, long now) {
        if (fact.kind().equals("recovered")) return;
        var data = CrimeWorldData.get(server); var record = data.recordById(fact.caseId()).orElse(null);
        if (record == null) return;
        var custody = data.getCustody(record.offender());
        boolean lawfullyHeld = custody != null && custody.isLawful() && record.sentenceId() != null && record.sentenceId().equals(custody.getSentenceId());
        String kind = record.resolution() == Resolution.SERVED ? "served" : !record.actionable() ? "closed"
                : lawfullyHeld ? "arrested" : "reported";
        if (record.resolution().name().equals("DECEASED")) kind = "dead";
        data.news().publish(fact.caseId(), fact.suspect(), fact.recipient(), fact.community(), kind, now);
    }
    private static void process(ServerPlayer player, long now) {
        var data = CrimeWorldData.get(player.getServer()); var news = data.news();
        var sub = news.subscribe(player.getUUID(), now); if (sub == null || !sub.enabled) return;
        sub.communities.values().removeIf(at -> now >= at && now - at > CrimeNewsData.RETENTION);
        CrimeCommunityResolver.resolve(player.level().dimension().location(),
                dev.otectus.mcacrime.compat.mca.McaHandles.villageAt(player.serverLevel(), player.blockPosition()))
                .ifPresent(c -> news.connect(sub, c.toString(), now));
        // Contact with loaded, visible local residents establishes relevance, never remote entity lookup.
        player.serverLevel().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(16),
                e -> e != player && dev.otectus.mcacrime.compat.McaCompat.isMcaVillager(e) && player.hasLineOfSight(e))
                .stream().limit(8).forEach(e -> CrimeCommunityResolver.resolve(e, player.serverLevel())
                        .ifPresent(c -> news.connect(sub, c.toString(), now)));
        var settings = CrimeWorldSettings.resolve(player.getServer());
        long interval = 24000L * settings.newsIntervalDays();
        if (sub.interval != 0 && sub.interval != interval) sub.next = Math.max(now, sub.next - sub.interval + interval);
        sub.interval = interval;
        // Retained acknowledged envelopes are deliberately retried: mailbox-first and outbox-first saves both converge.
        news.envelopes.values().stream().filter(e -> e.recipient().equals(player.getUUID())).limit(8)
                .forEach(e -> McaMailBridge.enqueue(player, e.id(), e.letter(), now));
        if (now < sub.next) return;
        sub.next = now + interval;
        List<CrimeNewsData.Fact> selected = select(news, player.getUUID(), sub, now, settings.newsMaxStories());
        if (selected.isEmpty()) { sub.cursor = news.sequence; sub.seen.clear(); return; }
        for (var selectedFact : selected) {
            refresh(player.getServer(), selectedFact, now);
            if (publicWarning(selectedFact)) news.facts.values().stream()
                    .filter(f -> publicWarning(f) && f.community().equals(selectedFact.community()))
                    .limit(64).toList().forEach(f -> refresh(player.getServer(), f, now));
        }
        selected = select(news, player.getUUID(), sub, now, settings.newsMaxStories());
        // A refreshed revision can reorder the queue. Revalidate the final selection without
        // selecting a third set of older rows and accidentally composing a stale snapshot.
        selected.forEach(f -> refresh(player.getServer(), f, now));
        selected = selected.stream().map(news::latest).toList();
        if (news.envelopes.size() >= CrimeNewsData.MAX_ENVELOPES || news.envelopes.values().stream().filter(e -> e.recipient().equals(player.getUUID())).count() >= 8) return;
        Composition composition = composeEdition(player.getServer(), selected, now);
        selected = composition.facts();
        if (selected.isEmpty()) return;
        UUID edition = editionId(news.worldId, player.getUUID(), selected);
        CompoundTag letter = composition.letter();
        news.envelopes.put(edition, new CrimeNewsData.Envelope(edition, player.getUUID(), letter, now, now + CrimeNewsData.RETENTION));
        for (var fact : selected) {
            // Mark public/private variants of this same reduced story together.
            news.facts.values().stream().filter(f -> f.caseId().equals(fact.caseId())
                    && f.kind().equals(fact.kind()) || publicWarning(fact) && publicWarning(f)
                    && fact.community().equals(f.community())).forEach(f -> sub.seen.add(f.revision()));
        }
        while (sub.seen.size() > 256) {
            long oldest = sub.seen.iterator().next(); sub.seen.remove(oldest); sub.cursor = Math.max(sub.cursor, oldest);
        }
        McaMailBridge.enqueue(player, edition, letter, now);
    }
    public static List<CrimeNewsData.Fact> select(CrimeNewsData news, UUID player, CrimeNewsData.Subscription sub, long now, int max) {
        // Deduplicate public/private versions of a case, preferring the personal outcome.
        var candidates = news.facts.values().stream().filter(f -> f.revision() > sub.cursor && !sub.seen.contains(f.revision()) && now >= f.at() && now - f.at() <= CrimeNewsData.RETENTION)
                .filter(f -> player.equals(f.recipient()) || f.recipient() == null && sub.communities.containsKey(f.community()))
                .sorted(Comparator.<CrimeNewsData.Fact>comparingInt(f -> player.equals(f.recipient()) ? 0 : f.kind().equals("reported") ? 2 : 1)
                        .thenComparingLong(CrimeNewsData.Fact::revision)).toList();
        LinkedHashMap<String, CrimeNewsData.Fact> reduced = new LinkedHashMap<>();
        Set<UUID> personalCases = new HashSet<>();
        candidates.stream().filter(f -> player.equals(f.recipient()) && !f.kind().equals("recovered"))
                .forEach(f -> personalCases.add(f.caseId()));
        for (var fact : candidates) {
            if (fact.recipient() == null && personalCases.contains(fact.caseId())) continue;
            String key = publicWarning(fact) ? "warning/" + fact.community()
                    : fact.caseId() + (fact.kind().equals("recovered") ? "/recovery" : "/case");
            reduced.putIfAbsent(key, fact);
        }
        return reduced.values().stream().limit(Math.max(1, Math.min(8, max))).toList();
    }
    private static boolean publicWarning(CrimeNewsData.Fact fact) {
        return fact.recipient() == null && fact.kind().equals("reported");
    }
    static UUID editionId(UUID world, UUID recipient, List<CrimeNewsData.Fact> selected) {
        String identity = world + "/" + recipient + "/" + selected.stream().map(f -> Long.toString(f.revision())).toList();
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }
    private record Composition(CompoundTag letter, List<CrimeNewsData.Fact> facts) {}
    public static CompoundTag compose(MinecraftServer server, List<CrimeNewsData.Fact> facts, long now) {
        return composeEdition(server, facts, now).letter();
    }
    private static Composition composeEdition(MinecraftServer server, List<CrimeNewsData.Fact> facts, long now) {
        List<String> pages = new ArrayList<>(); List<CrimeNewsData.Fact> included = new ArrayList<>();
        net.minecraft.network.chat.MutableComponent page = Component.empty(); int onPage = 0;
        for (var fact : facts.stream().limit(8).toList()) {
            Component place = fact.community().isEmpty() ? Component.translatable("news.mcacrime.personal")
                    : Jurisdictions.label(server, CrimeCommunityKey.tryParse(fact.community()).orElse(null));
            Component body = publicWarning(fact) ? Component.translatable("news.mcacrime.warning",
                    CrimeWorldData.get(server).news().facts.values().stream().filter(f -> publicWarning(f)
                            && f.community().equals(fact.community()) && now >= f.at()
                            && now - f.at() <= CrimeNewsData.RETENTION).map(CrimeNewsData.Fact::caseId).distinct().count())
                    : Component.translatable("news.mcacrime." + fact.kind());
            Component story = Component.translatable("news.mcacrime.heading", truncate(place.getString(), 24), now / 24000 + 1)
                    .append("\n").append(body);
            var next = page.copy().append(onPage == 0 ? "" : "\n\n").append(story);
            String json = Component.Serializer.toJson(next);
            if (json.length() > 500 || onPage == 2) {
                if (onPage > 0) pages.add(Component.Serializer.toJson(page));
                if (pages.size() == 4) { onPage = 0; break; }
                page = Component.empty(); onPage = 0;
                next = page.copy().append(story); json = Component.Serializer.toJson(next);
            }
            int used = pages.stream().mapToInt(String::length).sum();
            if (json.length() > 500 || used + json.length() > 2000) break;
            page = next; onPage++; included.add(fact);
        }
        if (onPage > 0 && pages.size() < 4) pages.add(Component.Serializer.toJson(page));
        ListTag serialized = new ListTag(); pages.forEach(p -> serialized.add(StringTag.valueOf(p)));
        CompoundTag tag = new CompoundTag(); tag.put("pages", serialized);
        return new Composition(tag, List.copyOf(included));
    }
    private static String truncate(String text, int points) {
        return text.codePointCount(0, text.length()) <= points ? text : text.substring(0, text.offsetByCodePoints(0, points)) + "…";
    }
    public static int preference(ServerPlayer player, boolean enabled) {
        if (!ServerMutationGate.allows(player.getServer())) return 0;
        var data = CrimeWorldData.get(player.getServer()); data.news().preference(player.getUUID(), enabled, player.getServer().overworld().getGameTime()); data.setDirty();
        return status(player);
    }
    public static int status(ServerPlayer player) {
        var news = CrimeWorldData.get(player.getServer()).news();
        var sub = news.subscriptions.get(player.getUUID());
        if (sub == null && news.subscriptions.size() >= CrimeNewsData.MAX_SUBSCRIBERS) {
            player.sendSystemMessage(Component.translatable("news.mcacrime.capacity")); return 0;
        }
        player.sendSystemMessage(Component.translatable("news.mcacrime.status", sub == null || sub.enabled,
                CrimeWorldSettings.resolve(player.getServer()).crimeNews(), McaMailBridge.status())); return 1;
    }
}
