package dev.otectus.mcacrime;

import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The anti-duplication guarantee's other half: what comes back out of the save file is what went in.
 *
 * <p>The stolen item is asserted as its NBT rather than as an {@code ItemStack} on purpose, and not
 * only because an item registry does not exist in a unit test. The tag <em>is</em> the persisted
 * form — a record that hands back a byte-identical compound cannot have quietly dropped an
 * enchantment or a custom name on the way through, which is exactly the failure a returned sword
 * would show and nothing else would.
 */
class StolenGoodsRecordTest {

    private static final UUID TRANSACTION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID THIEF = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    /** A renamed, enchanted diamond sword in the shape {@code ItemStack.save} writes. */
    private static CompoundTag enchantedSword() {
        CompoundTag enchantment = new CompoundTag();
        enchantment.putString("id", "minecraft:sharpness");
        enchantment.putShort("lvl", (short) 4);
        ListTag enchantments = new ListTag();
        enchantments.add(enchantment);

        CompoundTag display = new CompoundTag();
        display.putString("Name", "{\"text\":\"Grandfather's Sword\"}");

        CompoundTag extra = new CompoundTag();
        extra.put("Enchantments", enchantments);
        extra.put("display", display);
        extra.putInt("Damage", 7);

        CompoundTag stack = new CompoundTag();
        stack.putString("id", "minecraft:diamond_sword");
        stack.putByte("Count", (byte) 1);
        stack.put("tag", extra);
        return stack;
    }

    @Test
    void anEnchantedRenamedSwordSurvivesTheRoundTripExactly() {
        CompoundTag stack = enchantedSword();
        StolenGoodsRecord record = new StolenGoodsRecord(TRANSACTION, THIEF, OWNER, stack, 0L, 1234L);

        StolenGoodsRecord loaded = StolenGoodsRecord.load(record.save());

        assertEquals(TRANSACTION, loaded.transactionId());
        assertEquals(THIEF, loaded.thief());
        assertEquals(OWNER, loaded.owner());
        assertEquals(0L, loaded.currency());
        assertEquals(1234L, loaded.stolenAt());
        assertTrue(loaded.hasStack());
        assertEquals(stack, loaded.stackTag(), "the persisted item tag is not byte-identical");
        assertEquals(record, loaded, "the whole record round-trips, not only its item");
    }

    @Test
    void aCurrencyOnlyRecordWritesNoStackAtAll() {
        StolenGoodsRecord record = new StolenGoodsRecord(TRANSACTION, THIEF, OWNER, null, 12L, 99L);
        CompoundTag saved = record.save();

        assertFalse(saved.contains("stack"), "a currency theft has no item and must not invent one");
        assertFalse(record.hasStack());

        StolenGoodsRecord loaded = StolenGoodsRecord.load(saved);
        assertNull(loaded.stackTag());
        assertFalse(loaded.hasStack());
        assertEquals(12L, loaded.currency());
        assertEquals(record, loaded);
    }

    @Test
    void anEmptyStackTagCountsAsNoStack() {
        StolenGoodsRecord record = new StolenGoodsRecord(TRANSACTION, THIEF, OWNER, new CompoundTag(), 0L, 1L);
        assertFalse(record.hasStack());
        assertFalse(record.save().contains("stack"));
    }

    @Test
    void theRecordDoesNotShareItsTagWithTheCaller() {
        CompoundTag stack = enchantedSword();
        StolenGoodsRecord record = new StolenGoodsRecord(TRANSACTION, THIEF, OWNER, stack, 0L, 1L);

        // A ledger entry anybody can edit in place is not a ledger entry.
        stack.putString("id", "minecraft:stick");
        stack.getCompound("tag").put("Enchantments", new ListTag());
        assertEquals("minecraft:diamond_sword", record.stackTag().getString("id"));
        assertEquals(1, record.stackTag().getCompound("tag").getList("Enchantments", Tag.TAG_COMPOUND).size());
    }
}
