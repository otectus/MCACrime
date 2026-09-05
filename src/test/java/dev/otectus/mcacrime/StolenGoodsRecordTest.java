package dev.otectus.mcacrime;

import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The anti-duplication guarantee's other half: what comes back out of the save file is what went in.
 *
 * <p>The stolen item is asserted as its NBT rather than only as an {@code ItemStack} on purpose. The
 * tag <em>is</em> the persisted form — a record that hands back a byte-identical compound cannot have
 * quietly dropped an enchantment or a custom name on the way through, which is exactly the failure a
 * returned sword would show and nothing else would.
 *
 * <p>Two providers appear below because 1.21 items carry components rather than a free-form tag.
 * {@link RegistryAccess#EMPTY} is enough for a named, damaged sword: the item id round-trips through
 * the static item registry's name codec and neither component names a dynamic registry. An
 * enchantment does — {@code ItemEnchantments} holds enchantment holders — so that case asks
 * {@link VanillaRegistries#createLookup()} for the real thing.
 */
class StolenGoodsRecordTest {

    private static final UUID TRANSACTION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID THIEF = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    /** A renamed, enchanted diamond sword in the shape {@code ItemStack.save} once wrote. */
    private static CompoundTag handBuiltSword() {
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

    // ------------------------------------------------------------------ the record shape

    @Test
    void anEnchantedRenamedSwordSurvivesTheRoundTripExactly() {
        CompoundTag stack = handBuiltSword();
        StolenGoodsRecord record = new StolenGoodsRecord(TRANSACTION, THIEF, OWNER, stack, 0L, 1234L);

        StolenGoodsRecord loaded = StolenGoodsRecord.load(RegistryAccess.EMPTY,
                record.save(RegistryAccess.EMPTY));

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
        CompoundTag saved = record.save(RegistryAccess.EMPTY);

        assertFalse(saved.contains("stack"), "a currency theft has no item and must not invent one");
        assertFalse(record.hasStack());

        StolenGoodsRecord loaded = StolenGoodsRecord.load(RegistryAccess.EMPTY, saved);
        assertNull(loaded.stackTag());
        assertFalse(loaded.hasStack());
        assertEquals(12L, loaded.currency());
        assertEquals(record, loaded);
    }

    @Test
    void anEmptyStackTagCountsAsNoStack() {
        StolenGoodsRecord record = new StolenGoodsRecord(TRANSACTION, THIEF, OWNER, new CompoundTag(), 0L, 1L);
        assertFalse(record.hasStack());
        assertFalse(record.save(RegistryAccess.EMPTY).contains("stack"));
    }

    @Test
    void theRecordDoesNotShareItsTagWithTheCaller() {
        CompoundTag stack = handBuiltSword();
        StolenGoodsRecord record = new StolenGoodsRecord(TRANSACTION, THIEF, OWNER, stack, 0L, 1L);

        // A ledger entry anybody can edit in place is not a ledger entry.
        stack.putString("id", "minecraft:stick");
        stack.getCompound("tag").put("Enchantments", new ListTag());
        assertEquals("minecraft:diamond_sword", record.stackTag().getString("id"));
        assertEquals(1, record.stackTag().getCompound("tag").getList("Enchantments", Tag.TAG_COMPOUND).size());
    }

    @Test
    void anEmptyStackIsRecordedAsNoStackRatherThanThrowing() {
        // saveOptional rather than save: currency-only thefts hand this an empty stack every time.
        StolenGoodsRecord record = StolenGoodsRecord.ofStack(RegistryAccess.EMPTY, TRANSACTION, THIEF,
                OWNER, ItemStack.EMPTY, 12L, 4L);
        assertFalse(record.hasStack());
        assertTrue(record.stack(RegistryAccess.EMPTY).isEmpty());
    }

    // ------------------------------------------------------------------ real stacks

    @Test
    void aNamedDamagedSwordComesBackAsTheSameStack() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Grandfather's Sword"));
        sword.set(DataComponents.DAMAGE, 7);

        StolenGoodsRecord record = StolenGoodsRecord.ofStack(RegistryAccess.EMPTY, TRANSACTION, THIEF,
                OWNER, sword, 0L, 1234L);
        StolenGoodsRecord loaded = StolenGoodsRecord.load(RegistryAccess.EMPTY,
                record.save(RegistryAccess.EMPTY));

        assertTrue(loaded.hasStack());
        assertEquals(record.stackTag(), loaded.stackTag(), "the persisted item tag is not byte-identical");

        ItemStack decoded = loaded.stack(RegistryAccess.EMPTY);
        assertTrue(ItemStack.matches(sword, decoded), "the decoded stack is not the stack that was taken");
        assertEquals(Component.literal("Grandfather's Sword"), decoded.get(DataComponents.CUSTOM_NAME));
        assertEquals(7, decoded.getDamageValue());
    }

    @Test
    void anEnchantedSwordComesBackWithItsEnchantment() {
        // Enchantments live in a dynamic registry, so this one needs a real lookup rather than EMPTY.
        HolderLookup.Provider provider = VanillaRegistries.createLookup();
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Grandfather's Sword"));
        sword.enchant(provider.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 4);

        StolenGoodsRecord record = StolenGoodsRecord.ofStack(provider, TRANSACTION, THIEF, OWNER, sword,
                0L, 1234L);
        StolenGoodsRecord loaded = StolenGoodsRecord.load(provider, record.save(provider));

        assertEquals(record.stackTag(), loaded.stackTag(), "the persisted item tag is not byte-identical");

        ItemStack decoded = loaded.stack(provider);
        assertTrue(ItemStack.matches(sword, decoded), "the enchantment did not survive the round trip");
        assertFalse(decoded.getEnchantments().isEmpty(), "the decoded sword lost its enchantment");
    }
}
