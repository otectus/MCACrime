package dev.otectus.mcacrime.frisk;

import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.economy.Currency;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * What a thief is carrying for somebody else, as searchable slots (0.7.7).
 *
 * <p>A mugging files what it took in the stolen-goods ledger, keyed by the thief, and that ledger is
 * the one authoritative record of it: there is no copy in the thief's MCA inventory, so a search, an
 * arrest, a death and an expiry can never each find a separate claimable copy. This provider is a
 * read-only window onto those records. Each record is one slot, its index derived from the record's
 * own id so it does not shift when another record is taken; what the screen shows is a decoded copy of
 * the stolen stack carrying a provenance marker (whose it is) that the real stack never carries, which
 * also makes a slot's revision change whenever the record behind it changes.
 *
 * <p>It never moves anything itself: {@link #extract} and {@link #restore} refuse, because what a take
 * means depends on who is taking -- the owner recovering it, a lawful search sealing it for the owner,
 * or somebody else stealing it again -- and only {@link StolenGoodsTransfer} knows the searcher. A
 * stolen currency balance is shown as a labelled purse and is never turned into items; it moves only
 * through the provider it was taken by.
 *
 * <p>Players are never subjects here. A player who stole by searching somebody already holds the item
 * in their own inventory, and the ledger row for that is provenance, not a second copy.
 */
public final class StolenGoodsInventoryProvider implements InventoryProvider {

    public static final String ID = "stolen_goods";

    /** The provenance marker on a displayed copy; never written to a real stack. */
    public static final String MARKER = "mcacrime_stolen";

    @Override
    public String id() {
        return ID;
    }

    /** This subject's ledger records, oldest first, with a stable tie-break. */
    public static List<StolenGoodsRecord> held(@Nullable LivingEntity subject) {
        if (subject == null || subject instanceof Player || !(subject.level() instanceof ServerLevel level)) {
            return List.of();
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return List.of();
        }
        List<StolenGoodsRecord> records = new ArrayList<>(CrimeWorldData.get(server).stolenGoodsByThief(subject.getUUID()));
        records.removeIf(record -> !record.recoverable());
        records.sort(Comparator.comparingLong(StolenGoodsRecord::stolenAt)
                .thenComparing(StolenGoodsRecord::transactionId));
        return records;
    }

    /** The slot index a record always has, whatever else the thief is holding. */
    public static int indexOf(StolenGoodsRecord record) {
        return record.transactionId().hashCode() & Integer.MAX_VALUE;
    }

    /** The record behind a slot, or null when it has been taken, expired or never existed. */
    @Nullable
    public static StolenGoodsRecord recordAt(@Nullable LivingEntity subject, @Nullable FriskSlotRef ref) {
        if (ref == null || !ID.equals(ref.providerId())) {
            return null;
        }
        for (StolenGoodsRecord record : held(subject)) {
            if (indexOf(record) == ref.index()) {
                return record;
            }
        }
        return null;
    }

    @Override
    public boolean supports(LivingEntity subject) {
        return !held(subject).isEmpty();
    }

    @Override
    public List<FriskSlotRef> slots(LivingEntity subject) {
        List<FriskSlotRef> refs = new ArrayList<>();
        for (StolenGoodsRecord record : held(subject)) {
            // A record whose item no longer exists (its mod was removed) has nothing to show; it stays in
            // the ledger, recoverable on arrest or death once the item can be decoded again.
            if (!display(subject, record).isEmpty()) {
                refs.add(new FriskSlotRef(ID, FriskSlotKind.STOLEN, indexOf(record)));
            }
        }
        return refs;
    }

    @Override
    public ItemStack peek(LivingEntity subject, FriskSlotRef ref) {
        StolenGoodsRecord record = recordAt(subject, ref);
        return record == null ? ItemStack.EMPTY : display(subject, record);
    }

    /** Never: see {@link StolenGoodsTransfer}. */
    @Override
    public ItemStack extract(LivingEntity subject, FriskSlotRef ref, int count) {
        return ItemStack.EMPTY;
    }

    /** Never: a ledger record is not put back by the generic rollback. */
    @Override
    public boolean restore(LivingEntity subject, FriskSlotRef ref, ItemStack stack) {
        return false;
    }

    /** Whose property the slot holds, or null. */
    @Nullable
    public static UUID ownerOf(@Nullable LivingEntity subject, @Nullable FriskSlotRef ref) {
        StolenGoodsRecord record = recordAt(subject, ref);
        return record == null ? null : record.owner();
    }

    /** What the screen shows for a record: the stolen stack or a labelled purse, with its provenance. */
    static ItemStack display(@Nullable LivingEntity subject, StolenGoodsRecord record) {
        ItemStack shown;
        Component purse = null;
        if (record.hasStack()) {
            shown = record.stack();
            if (shown.isEmpty()) {
                return ItemStack.EMPTY;
            }
        } else if (record.currency() > 0L) {
            shown = new ItemStack(purseIcon(record));
            purse = Component.translatable("mcacrime.stolen.purse", formatCurrency(record));
            shown.setHoverName(purse);
        } else {
            return ItemStack.EMPTY;
        }
        CompoundTag marker = new CompoundTag();
        marker.putUUID("record", record.transactionId());
        marker.putUUID("owner", record.owner());
        shown.getOrCreateTag().put(MARKER, marker);
        CompoundTag display = shown.getOrCreateTagElement("display");
        ListTag lore = display.contains("Lore", Tag.TAG_LIST) ? display.getList("Lore", Tag.TAG_STRING).copy() : new ListTag();
        lore.add(StringTag.valueOf(Component.Serializer.toJson(
                Component.translatable("mcacrime.stolen.from", ownerName(subject, record.owner())))));
        if (purse != null) {
            lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.translatable("mcacrime.stolen.purse_hint"))));
        }
        display.put("Lore", lore);
        return shown;
    }

    private static Item purseIcon(StolenGoodsRecord record) {
        Currency active = Currencies.active();
        if (sameProvider(record, active)) {
            Item item = active.itemForm().map(ForgeRegistries.ITEMS::getValue).orElse(null);
            if (item != null && item != Items.AIR) {
                return item;
            }
        }
        return Items.PAPER;
    }

    private static Component formatCurrency(StolenGoodsRecord record) {
        Currency active = Currencies.active();
        return sameProvider(record, active) ? active.format(record.currency())
                : Component.literal(record.currency() + " (" + record.providerId() + ")");
    }

    /** Whether the record's currency is the one this server pays in now; a legacy record named none. */
    static boolean sameProvider(StolenGoodsRecord record, Currency active) {
        return record.providerId().isEmpty() || record.providerId().equals(active.id().toString());
    }

    private static Component ownerName(@Nullable LivingEntity subject, UUID owner) {
        MinecraftServer server = subject == null || !(subject.level() instanceof ServerLevel level) ? null : level.getServer();
        if (server != null) {
            var online = server.getPlayerList().getPlayer(owner);
            if (online != null) {
                return online.getName();
            }
            var cache = server.getProfileCache();
            if (cache != null) {
                var profile = cache.get(owner);
                if (profile.isPresent()) {
                    return Component.literal(profile.get().getName());
                }
            }
        }
        return Component.translatable("mcacrime.stolen.unknown_owner");
    }
}
