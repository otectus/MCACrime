package dev.otectus.mcacrime.news;

import dev.otectus.mcacrime.compat.McaMailBridge;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CrimeNewsDataTest {
    @Test void maximumPersonalDigestFitsFourValidNativePagesInStoryOrder() {
        List<CrimeNewsData.Fact> facts = new ArrayList<>(); UUID player = UUID.randomUUID();
        var kinds = List.of("reported", "arrested", "served", "closed", "dead", "recovered", "reported", "arrested");
        for (int i = 0; i < 8; i++) facts.add(new CrimeNewsData.Fact(UUID.randomUUID(), UUID.randomUUID(), player, "", kinds.get(i), 1, i + 1));
        CompoundTag letter = CrimeNewsService.compose(null, facts, 42 * 24000);
        assertTrue(CrimeNewsData.validLetter(letter));
        var pages = letter.getList("pages", 8); assertEquals(4, pages.size());
        for (int i = 0; i < 4; i++) {
            assertTrue(pages.getString(i).contains("news.mcacrime." + kinds.get(2 * i)));
            assertTrue(pages.getString(i).contains("news.mcacrime." + kinds.get(2 * i + 1)));
        }
    }
    @Test void backlogBatchesHaveDistinctEditionsButRetriesKeepTheirIdentity() {
        var news = new CrimeNewsData(); UUID player = UUID.randomUUID(), suspect = UUID.randomUUID();
        var sub = news.subscribe(player, 0);
        for (int i = 0; i < 6; i++) news.publish(UUID.randomUUID(), suspect, player, "", "reported", 1);
        var first = CrimeNewsService.select(news, player, sub, 2, 4);
        UUID firstId = CrimeNewsService.editionId(news.worldId, player, first);
        first.forEach(f -> sub.seen.add(f.revision()));
        var second = CrimeNewsService.select(news, player, sub, 3, 4);
        assertEquals(2, second.size());
        assertNotEquals(firstId, CrimeNewsService.editionId(news.worldId, player, second));
        assertEquals(firstId, CrimeNewsService.editionId(news.worldId, player, first));
        var restarted = CrimeNewsData.load(news.save());
        assertEquals(second, CrimeNewsService.select(restarted, player, restarted.subscriptions.get(player), 3, 4));
    }
    @Test void malformedOptionalRowsAreQuarantinedWithoutLosingHealthyFacts() {
        var news = new CrimeNewsData();
        news.publish(UUID.randomUUID(), UUID.randomUUID(), null, "minecraft:overworld/1", "reported", 1);
        CompoundTag saved = news.save();
        CompoundTag invalid = new CompoundTag(); invalid.putString("kind", "future_unknown");
        saved.getList("facts", 10).add(invalid);
        var loaded = CrimeNewsData.load(saved);
        assertEquals(news.facts, loaded.facts);
        assertEquals(1, loaded.save().getList("quarantine", 10).size());
        assertEquals(invalid, loaded.save().getList("quarantine", 10).getCompound(0).getCompound("row"));
    }
    @Test void publicWarningsAggregateByCommunityWithoutHidingPersonalOutcomes() {
        var news = new CrimeNewsData(); UUID player = UUID.randomUUID(), suspect = UUID.randomUUID();
        var sub = news.subscribe(player, 0);
        news.connect(sub, "minecraft:overworld/1", 0); news.connect(sub, "minecraft:overworld/2", 0);
        UUID personal = UUID.randomUUID();
        news.publish(personal, suspect, null, "minecraft:overworld/1", "reported", 1);
        news.publish(personal, suspect, player, "", "reported", 1);
        for (int i = 0; i < 10; i++) news.publish(UUID.randomUUID(), suspect, null, "minecraft:overworld/1", "reported", 2);
        news.publish(UUID.randomUUID(), suspect, null, "minecraft:overworld/2", "reported", 3);
        var selected = CrimeNewsService.select(news, player, sub, 4, 8);
        assertEquals(3, selected.size());
        assertEquals(player, selected.get(0).recipient());
        assertEquals(List.of("minecraft:overworld/1", "minecraft:overworld/2"), selected.subList(1, 3).stream().map(CrimeNewsData.Fact::community).toList());
    }
    @Test void reduceTransitionsKeepRecoveryPrivateAndPersistSubscription() {
        var news = new CrimeNewsData(); UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID(), suspect = UUID.randomUUID(), id = UUID.randomUUID();
        var sub = news.subscribe(owner, 0); news.connect(sub, "minecraft:overworld/1", 0);
        news.publish(id, suspect, null, "minecraft:overworld/1", "reported", 1);
        news.publish(id, suspect, null, "minecraft:overworld/1", "arrested", 2);
        news.publish(id, suspect, null, "minecraft:overworld/1", "served", 3);
        long completedRevision = news.sequence;
        news.report(id, suspect, null, "minecraft:overworld/1", 1);
        assertEquals(completedRevision, news.sequence, "replayed accepted-report event cannot reopen a completed news story");
        news.publish(id, suspect, owner, "", "recovered", 3);
        assertEquals(2, news.facts.size());
        assertEquals(List.of("recovered", "served"), CrimeNewsService.select(news, owner, sub, 4, 4).stream().map(CrimeNewsData.Fact::kind).toList());
        var other = new CrimeNewsData.Subscription(); other.communities.put("minecraft:overworld/1", 0L);
        assertEquals(List.of("served"), CrimeNewsService.select(news, stranger, other, 4, 4).stream().map(CrimeNewsData.Fact::kind).toList());
        var restored = CrimeNewsData.load(news.save()); assertEquals(news.facts, restored.facts);
        assertEquals(sub.communities, restored.subscriptions.get(owner).communities);
    }
    @Test void stressCapsOfflineSubscribersFactsAndPendingEnvelopesDeterministically() {
        var news = new CrimeNewsData(); long start = System.nanoTime(); UUID suspect = UUID.randomUUID();
        for (int n = 0; n < 5000; n++) {
            news.subscribe(new UUID(0,n), 0);
            news.publish(new UUID(1,n), suspect, null, "minecraft:overworld/" + n / 64, "reported", 1);
        }
        assertEquals(4096, news.facts.size()); assertEquals(4096, news.subscriptions.size()); assertEquals(904, news.dropped);
        var loaded = CrimeNewsData.load(news.save()); assertEquals(news.facts, loaded.facts);
        loaded.prune(CrimeNewsData.RETENTION + 2); assertTrue(loaded.facts.isEmpty());
        System.out.println("Crime news stress: 5000 facts/subscribers, bounded at 4096; " + (System.nanoTime()-start)/1_000_000 + " ms");
    }
    @Test void nativeReceiptSurvivesMailboxCollectionAndSaveOrderReplay() {
        Object mailbox = new Object(); UUID edition = UUID.randomUUID();
        CompoundTag saved = new CompoundTag(); var rows = new net.minecraft.nbt.ListTag(); CompoundTag row = new CompoundTag();
        row.putUUID("edition", edition); row.putLong("at", 123); rows.add(row); saved.put("mcacrime:news_receipts", rows);
        McaMailBridge.loadReceipts(mailbox, saved);
        CompoundTag afterCollection = McaMailBridge.saveReceipts(mailbox, new CompoundTag());
        assertEquals(saved, afterCollection);
        Object restartedMailbox = new Object(); McaMailBridge.loadReceipts(restartedMailbox, afterCollection);
        assertEquals(saved, McaMailBridge.saveReceipts(restartedMailbox, new CompoundTag()));
        // Outbox-first save has no receipt; replay can append. Mailbox-first retains receipt after collection.
        assertTrue(McaMailBridge.saveReceipts(new Object(), new CompoundTag()).getList("mcacrime:news_receipts", 10).isEmpty());
    }
    @Test void optOutDropsOptionalEnvelopesAndOptInDoesNotReplayHistory() {
        var news = new CrimeNewsData(); UUID player = UUID.randomUUID(); news.subscribe(player, 0);
        news.publish(UUID.randomUUID(), UUID.randomUUID(), player, "", "reported", 1);
        UUID edition = UUID.randomUUID(); news.envelopes.put(edition, new CrimeNewsData.Envelope(edition, player, new CompoundTag(), 1, 100));
        news.preference(player, false, 2); assertTrue(news.envelopes.isEmpty());
        news.preference(player, true, 3); assertTrue(CrimeNewsService.select(news, player, news.subscriptions.get(player), 3, 4).isEmpty());
    }
}
