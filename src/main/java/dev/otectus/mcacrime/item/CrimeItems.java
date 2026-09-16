package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.captivity.RestraintReservation;
import dev.otectus.mcacrime.captivity.RestraintType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The mod's item registrations (spec §8.3) — its first items: the three restraints plus a creative tab.
 * Rope also accepts any {@code forge:rope}-tagged item for broad mod compatibility (see {@link
 * #restraintFor}); cuffs and locked cuffs are custom items (vanilla has no equivalent). The Locks Reforged
 * key/removal path for locked cuffs is a Phase 7 seam.
 */
public final class CrimeItems {

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, McaCrime.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, McaCrime.MOD_ID);

    public static final RegistryObject<Item> RESTRAINT_ROPE = ITEMS.register("restraint_rope",
            () -> new RestraintItem(RestraintType.ROPE, new Item.Properties()));
    public static final RegistryObject<Item> RESTRAINT_CUFFS = ITEMS.register("restraint_cuffs",
            () -> new RestraintItem(RestraintType.CUFFS, new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> RESTRAINT_LOCKED_CUFFS = ITEMS.register("restraint_locked_cuffs",
            () -> new RestraintItem(RestraintType.LOCKED_CUFFS, new Item.Properties().stacksTo(1)));

    /**
     * The sixteen mask styles (0.7.2 section 4.1), one registration each, keyed by the style itself.
     *
     * <p>A map rather than sixteen hand-written constants: the creative tab, the client colour handler,
     * the tag test and the recipe test all want "every mask", and sixteen fields is sixteen chances to
     * add the seventeenth style to three of those four places. {@code clay_mask} and {@code
     * leather_mask} keep their original ids, so every saved stack, recipe and datapack reference that
     * named them in 0.7.0 still resolves.
     */
    public static final Map<MaskVariant, RegistryObject<Item>> MASKS = registerMasks();

    private static Map<MaskVariant, RegistryObject<Item>> registerMasks() {
        Map<MaskVariant, RegistryObject<Item>> masks = new EnumMap<>(MaskVariant.class);
        for (MaskVariant variant : MaskVariant.values()) {
            masks.put(variant, ITEMS.register(variant.textureName(), () -> new MaskItem(variant)));
        }
        return Collections.unmodifiableMap(masks);
    }

    public static final RegistryObject<Item> CLAY_MASK = MASKS.get(MaskVariant.CLAY);
    public static final RegistryObject<Item> LEATHER_MASK = MASKS.get(MaskVariant.LEATHER);

    /** Every registered mask item, in catalogue order. */
    public static List<Item> masks() {
        List<Item> items = new ArrayList<>();
        for (MaskFamily family : MaskFamily.values()) {
            for (MaskVariant variant : MaskVariant.of(family)) {
                items.add(MASKS.get(variant).get());
            }
        }
        return items;
    }

    /**
     * A throwable bottle of sand (0.7.2 §13.1). Sixteen to a stack: it is a thrown disruption tool,
     * not a drink and not an explosive, and a stack that size is a pocketful rather than an arsenal.
     */
    public static final RegistryObject<Item> SAND_BOTTLE = ITEMS.register("sand_bottle",
            () -> new SandBottleItem(new Item.Properties().stacksTo(16)));

    /**
     * The Mask Station's block item (0.7.2 §6.1). Registered here rather than in {@code CrimeBlocks}
     * so that every item this mod owns is in one registry list and the creative tab below can name it.
     */
    public static final RegistryObject<Item> MASK_STATION = ITEMS.register("mask_station",
            () -> new BlockItem(CrimeBlocks.MASK_STATION.get(), new Item.Properties()));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("crime", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.mcacrime"))
                    .icon(() -> new ItemStack(RESTRAINT_CUFFS.get()))
                    .displayItems((params, output) -> {
                        output.accept(RESTRAINT_ROPE.get());
                        output.accept(RESTRAINT_CUFFS.get());
                        output.accept(RESTRAINT_LOCKED_CUFFS.get());
                        masks().forEach(output::accept);
                        output.accept(SAND_BOTTLE.get());
                        output.accept(MASK_STATION.get());
                    })
                    .build());

    private CrimeItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        TABS.register(modBus);
    }

    /**
     * The restraint a stack represents: this mod's {@link RestraintItem} carries its own type; any other
     * {@code forge:rope}-tagged item counts as a {@link RestraintType#ROPE}; everything else is {@link
     * RestraintType#NONE} (not a restraint).
     */
    public static RestraintType restraintFor(ItemStack stack) {
        if (stack.getItem() instanceof RestraintItem restraint) {
            return restraint.getRestraintType();
        }
        if (stack.is(RestraintTags.ROPE)) {
            return RestraintType.ROPE;
        }
        return RestraintType.NONE;
    }

    /**
     * Sets aside the restraint a capture is about to spend, without spending it.
     *
     * <p>The consumption used to happen first and the capture second, so every refusal the custody
     * table could raise — already held, over the allowance — still cost the player their rope. Finding
     * the stack and taking it are separated here so the taking can wait until the capture stands.
     */
    public static Optional<RestraintReservation> reserveRestraint(ServerPlayer player, RestraintType type) {
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (!stack.isEmpty() && restraintFor(stack) == type) {
                return Optional.of(new RestraintReservation(i, stack.copy()));
            }
        }
        return Optional.empty();
    }

    /**
     * Spends a reservation, if the slot still holds what was reserved.
     *
     * <p>The identity check is not paranoia: the commit runs on the server thread but not in the same
     * instant the reservation was taken, and a player who swapped that slot in between must not have a
     * different stack shrunk on their behalf.
     */
    public static boolean consumeReserved(ServerPlayer player, RestraintReservation reservation) {
        if (reservation == null || reservation.slot() < 0
                || reservation.slot() >= player.getInventory().items.size()) {
            return false;
        }
        ItemStack stack = player.getInventory().items.get(reservation.slot());
        if (stack.isEmpty() || !ItemStack.isSameItemSameTags(stack, reservation.snapshot())) {
            return false;
        }
        if (!player.getAbilities().instabuild) stack.shrink(1);
        player.getInventory().setChanged();
        return true;
    }

    /** Whether the player is carrying anything that can cut a rope. */
    public static boolean hasCuttingTool(ServerPlayer player) {
        return carries(player, RestraintTags.CUTTING_TOOLS);
    }

    /** Whether the player is carrying anything that can open a lock. */
    public static boolean hasKey(ServerPlayer player) {
        return carries(player, RestraintTags.KEYS);
    }

    private static boolean carries(ServerPlayer player, net.minecraft.tags.TagKey<Item> tag) {
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.is(tag)) return true;
        }
        return !player.getOffhandItem().isEmpty() && player.getOffhandItem().is(tag);
    }

    public static RestraintType bestRestraint(ServerPlayer player) {
        RestraintType best = RestraintType.NONE;
        for (ItemStack stack : player.getInventory().items) {
            RestraintType type = restraintFor(stack);
            if (type.ordinal() > best.ordinal()) best = type;
        }
        return best;
    }
}
