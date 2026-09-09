package dev.otectus.mcacrime;

import dev.otectus.mcacrime.loot.DeathLoot;
import dev.otectus.mcacrime.economy.fence.FenceStockRecord;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DeathLootTest {
    @Test void stockedTradesDropExactlyOnePurchase() {
        assertEquals(3, DeathLoot.tradeDropCount(3, 12, 5));
        assertEquals(1, DeathLoot.tradeDropCount(1, 12, 0));
        assertEquals(1, DeathLoot.tradeDropCount(1, 12, 11));
    }
    @Test void exhaustedOrInvalidOffersDoNotInventStock() {
        assertEquals(0, DeathLoot.tradeDropCount(3, 12, 12));
        assertEquals(0, DeathLoot.tradeDropCount(3, 12, 50));
        assertEquals(0, DeathLoot.tradeDropCount(-1, 12, 0));
        assertEquals(0, DeathLoot.tradeDropCount(3, -1, 0));
        assertEquals(3, DeathLoot.tradeDropCount(3, 12, -5));
    }
    @Test void hugeUseLimitsNeverMultiplyTheTradeOutput() {
        assertEquals(Integer.MAX_VALUE,
                DeathLoot.tradeDropCount(Integer.MAX_VALUE, Integer.MAX_VALUE, 0));
    }
    @Test void lootedFenceStockStaysExhaustedAfterReload() {
        FenceStockRecord stock = new FenceStockRecord(UUID.randomUUID(), 4, 24000);
        assertTrue(stock.tryConsume("minecraft:diamond|from_fence", 8));
        stock.exhaust("minecraft:diamond|from_fence", 8);
        FenceStockRecord loaded = FenceStockRecord.load(stock.save());
        assertEquals(0, loaded.remaining("minecraft:diamond|from_fence", 8));
        assertFalse(loaded.tryConsume("minecraft:diamond|from_fence", 8));
        assertEquals(4, loaded.epoch());
    }
}
