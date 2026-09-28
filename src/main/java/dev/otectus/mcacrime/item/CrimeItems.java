package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.item.creative.BindBreakerItem;
import dev.otectus.mcacrime.item.creative.CreativeKeyItem;
import dev.otectus.mcacrime.item.creative.CreativeRestraintCutter;
import dev.otectus.mcacrime.item.lock.BakedKeyMoldItem;
import dev.otectus.mcacrime.item.lock.KeyItem;
import dev.otectus.mcacrime.item.lock.KeyMoldItem;
import dev.otectus.mcacrime.item.lock.KeyRingItem;
import dev.otectus.mcacrime.item.lock.LockpickItem;
import dev.otectus.mcacrime.item.lock.PadlockItem;
import dev.otectus.mcacrime.item.restraint.RestraintKeyItem;
import dev.otectus.mcacrime.item.tool.DuckTapeItem;
import dev.otectus.mcacrime.restraint.RestraintFamily;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The mod's item registrations.
 *
 * <p>Two things live here that are easy to get wrong separately: the id scheme, and the direction
 * between items and restraint definitions. {@code restraint_cuffs} and {@code restraint_locked_cuffs}
 * keep the ids and the protected textures they have always had, and now mean the Shackles and
 * Handcuffs families respectively (§3.1); no {@code mcacrime:handcuffs} or {@code mcacrime:shackles}
 * item exists, because registering one would orphan every saved stack and break the fence price
 * table. A definition names its item; an item never names a definition, since one pair of cuffs is
 * the arm definition or the leg definition depending only on where the server decided it went.
 *
 * <p>{@code restraint_rope} stays registered as a legacy conversion carrier and is out of the
 * creative tab. Any {@code c:ropes}-tagged item still reads as that carrier, which is what keeps
 * pre-0.7.5 worlds and other mods' rope working (see {@link #familyFor}).
 */
public final class CrimeItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McaCrime.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, McaCrime.MOD_ID);

    /**
     * The pre-0.7.5 rope, kept registered as a legacy conversion carrier (§3.18).
     *
     * <p>Still in the registry so every saved stack, recipe reference and fence price that names it
     * resolves, and deliberately out of the creative tab: it is not part of the 0.7.5 restraint set,
     * and a {@code rope_to_duck_tape} recipe is the supported way to spend one.
     */
    public static final DeferredItem<Item> RESTRAINT_ROPE = ITEMS.register("restraint_rope",
            () -> new RestraintItem(RestraintFamily.LEGACY_ROPE, new Item.Properties()));

    /**
     * The Shackles family's item, keeping MCA: Crime's own protected cuff icon (§3.1).
     *
     * <p>The id and the texture are unchanged; what the item <em>means</em> is now the lighter
     * restraint. The alternative — registering fresh {@code handcuffs}/{@code shackles} items — would
     * orphan every existing stack and break the fence price table that names these two ids.
     */
    public static final DeferredItem<Item> RESTRAINT_CUFFS = ITEMS.register("restraint_cuffs",
            () -> new RestraintItem(RestraintFamily.SHACKLES, new Item.Properties().stacksTo(1)));

    /** The Handcuffs family's item, keeping MCA: Crime's protected locked-cuff icon (§3.1). */
    public static final DeferredItem<Item> RESTRAINT_LOCKED_CUFFS = ITEMS.register("restraint_locked_cuffs",
            () -> new RestraintItem(RestraintFamily.HANDCUFFS, new Item.Properties().stacksTo(1)));

    /** Duct tape: the only item that restrains all three slots. Path {@code duck_tape} (§3.1). */
    public static final DeferredItem<Item> DUCK_TAPE = ITEMS.register("duck_tape",
            () -> new DuckTapeItem(new Item.Properties()));

    // --- restraint keys: one per family (§3.7) ---------------------------------------------------

    public static final DeferredItem<Item> HANDCUFFS_KEY = ITEMS.register("handcuffs_key",
            () -> new RestraintKeyItem(RestraintFamily.HANDCUFFS, new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> SHACKLES_KEY = ITEMS.register("shackles_key",
            () -> new RestraintKeyItem(RestraintFamily.SHACKLES, new Item.Properties().stacksTo(1)));

    // --- locks and picks: registered here, given behaviour in M3 ---------------------------------

    public static final DeferredItem<Item> KEY = ITEMS.register("key",
            () -> new KeyItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> KEY_RING = ITEMS.register("key_ring",
            () -> new KeyRingItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> KEY_MOLD = ITEMS.register("key_mold",
            () -> new KeyMoldItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> BAKED_KEY_MOLD = ITEMS.register("baked_key_mold",
            () -> new BakedKeyMoldItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> PADLOCK = ITEMS.register("padlock",
            () -> new PadlockItem(new Item.Properties().stacksTo(16)));
    /** Three uses, as upstream: a pick is a consumable, and a free one makes every lock scenery. */
    public static final DeferredItem<Item> LOCKPICK = ITEMS.register("lockpick",
            () -> new LockpickItem(new Item.Properties().stacksTo(1).durability(3)));

    // --- operator tools, gated by item/creative/CreativeAuthorization on the server ---------------

    public static final DeferredItem<Item> CREATIVE_RESTRAINT_CUTTER =
            ITEMS.register("creative_restraint_cutter",
                    () -> new CreativeRestraintCutter(new Item.Properties().rarity(Rarity.EPIC)));
    public static final DeferredItem<Item> CREATIVE_KEY = ITEMS.register("creative_key",
            () -> new CreativeKeyItem(new Item.Properties().rarity(Rarity.EPIC)));
    public static final DeferredItem<Item> CREATIVE_BIND_BREAKER = ITEMS.register("creative_bind_breaker",
            () -> new BindBreakerItem(new Item.Properties().rarity(Rarity.EPIC)));

    /**
     * The sixteen mask styles (0.7.2 section 4.1), one registration each, keyed by the style itself.
     *
     * <p>A map rather than sixteen hand-written constants: the creative tab, the client colour handler,
     * the tag test and the recipe test all want "every mask", and sixteen fields is sixteen chances to
     * add the seventeenth style to three of those four places. {@code clay_mask} and {@code
     * leather_mask} keep their original ids, so every saved stack, recipe and datapack reference that
     * named them in 0.7.0 still resolves.
     */
    public static final Map<MaskVariant, DeferredItem<Item>> MASKS = registerMasks();

    private static Map<MaskVariant, DeferredItem<Item>> registerMasks() {
        Map<MaskVariant, DeferredItem<Item>> masks = new EnumMap<>(MaskVariant.class);
        for (MaskVariant variant : MaskVariant.values()) {
            masks.put(variant, ITEMS.register(variant.textureName(), () -> new MaskItem(variant)));
        }
        return Collections.unmodifiableMap(masks);
    }

    public static final DeferredItem<Item> CLAY_MASK = MASKS.get(MaskVariant.CLAY);
    public static final DeferredItem<Item> LEATHER_MASK = MASKS.get(MaskVariant.LEATHER);

    /** Every registered mask item, in family and then catalogue order. */
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
    public static final DeferredItem<Item> SAND_BOTTLE = ITEMS.register("sand_bottle",
            () -> new SandBottleItem(new Item.Properties().stacksTo(16)));

    /**
     * The Mask Station's block item (0.7.2 §6.1). Registered here rather than in {@code CrimeBlocks}
     * so that every item this mod owns is in one registry list and the creative tab below can name it.
     */
    public static final DeferredItem<Item> MASK_STATION = ITEMS.register("mask_station",
            () -> new BlockItem(CrimeBlocks.MASK_STATION.get(), new Item.Properties()));

    /** The cell door's block item (0.7.5 M3.4). */
    public static final DeferredItem<Item> CELL_DOOR = ITEMS.register("cell_door",
            () -> new BlockItem(CrimeBlocks.CELL_DOOR.get(), new Item.Properties()));

    /** The safe's block item (0.7.5 M3.5). */
    public static final DeferredItem<Item> SAFE = ITEMS.register("safe",
            () -> new BlockItem(CrimeBlocks.SAFE.get(), new Item.Properties()));

    /** The pillory: a two-block detention device (M4.5). */
    public static final DeferredItem<Item> PILLORY = ITEMS.register("pillory",
            () -> new BlockItem(CrimeBlocks.PILLORY.get(), new Item.Properties()));

    /** The guillotine, which sits on a pillory and never acts without an authorisation (M4.6). */
    public static final DeferredItem<Item> GUILLOTINE = ITEMS.register("guillotine",
            () -> new BlockItem(CrimeBlocks.GUILLOTINE.get(), new Item.Properties()));

    /** The prison bunk (M4.7). */
    public static final DeferredItem<Item> BUNK = ITEMS.register("bunk",
            () -> new BlockItem(CrimeBlocks.BUNK.get(), new Item.Properties()));

    // --- the reinforced construction set's block items (0.7.5 M5.4) -------------------------------

    public static final DeferredItem<Item> REINFORCED_STONE = ITEMS.register("reinforced_stone",
            () -> new BlockItem(CrimeBlocks.REINFORCED_STONE.get(), new Item.Properties()));
    public static final DeferredItem<Item> REINFORCED_SMOOTH_STONE =
            ITEMS.register("reinforced_smooth_stone",
                    () -> new BlockItem(CrimeBlocks.REINFORCED_SMOOTH_STONE.get(), new Item.Properties()));
    public static final DeferredItem<Item> CHISELED_REINFORCED_STONE =
            ITEMS.register("chiseled_reinforced_stone",
                    () -> new BlockItem(CrimeBlocks.CHISELED_REINFORCED_STONE.get(), new Item.Properties()));
    public static final DeferredItem<Item> REINFORCED_LAMP = ITEMS.register("reinforced_lamp",
            () -> new BlockItem(CrimeBlocks.REINFORCED_LAMP.get(), new Item.Properties()));
    public static final DeferredItem<Item> REINFORCED_STONE_SLAB =
            ITEMS.register("reinforced_stone_slab",
                    () -> new BlockItem(CrimeBlocks.REINFORCED_STONE_SLAB.get(), new Item.Properties()));
    public static final DeferredItem<Item> REINFORCED_STONE_STAIRS =
            ITEMS.register("reinforced_stone_stairs",
                    () -> new BlockItem(CrimeBlocks.REINFORCED_STONE_STAIRS.get(), new Item.Properties()));
    public static final DeferredItem<Item> REINFORCED_BARS = ITEMS.register("reinforced_bars",
            () -> new BlockItem(CrimeBlocks.REINFORCED_BARS.get(), new Item.Properties()));
    public static final DeferredItem<Item> REINFORCED_BARS_GAP = ITEMS.register("reinforced_bars_gap",
            () -> new BlockItem(CrimeBlocks.REINFORCED_BARS_GAP.get(), new Item.Properties()));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("crime", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.mcacrime"))
                    .icon(() -> new ItemStack(RESTRAINT_CUFFS.get()))
                    .displayItems((params, output) -> {
                        // restraint_rope is deliberately absent: it is a legacy carrier, not content.
                        output.accept(RESTRAINT_CUFFS.get());
                        output.accept(RESTRAINT_LOCKED_CUFFS.get());
                        output.accept(DUCK_TAPE.get());
                        output.accept(HANDCUFFS_KEY.get());
                        output.accept(SHACKLES_KEY.get());
                        output.accept(KEY.get());
                        output.accept(KEY_RING.get());
                        output.accept(KEY_MOLD.get());
                        output.accept(BAKED_KEY_MOLD.get());
                        output.accept(PADLOCK.get());
                        output.accept(LOCKPICK.get());
                        masks().forEach(output::accept);
                        output.accept(SAND_BOTTLE.get());
                        output.accept(MASK_STATION.get());
                        output.accept(CELL_DOOR.get());
                        output.accept(SAFE.get());
                        output.accept(PILLORY.get());
                        output.accept(GUILLOTINE.get());
                        output.accept(BUNK.get());
                        output.accept(REINFORCED_STONE.get());
                        output.accept(REINFORCED_SMOOTH_STONE.get());
                        output.accept(CHISELED_REINFORCED_STONE.get());
                        output.accept(REINFORCED_LAMP.get());
                        output.accept(REINFORCED_STONE_SLAB.get());
                        output.accept(REINFORCED_STONE_STAIRS.get());
                        output.accept(REINFORCED_BARS.get());
                        output.accept(REINFORCED_BARS_GAP.get());
                        output.accept(CREATIVE_RESTRAINT_CUTTER.get());
                        output.accept(CREATIVE_KEY.get());
                        output.accept(CREATIVE_BIND_BREAKER.get());
                    })
                    .build());

    private CrimeItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        TABS.register(modBus);
        MaskArmorMaterial.register(modBus);
    }

    /** The restraint family a stack applies, or empty when it applies none. */
    public static Optional<RestraintFamily> familyFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        RestraintFamily family = RestraintItem.familyOf(stack.getItem());
        if (family != null) {
            return Optional.of(family);
        }
        // Any c:ropes-tagged item from another mod still counts as the legacy rope carrier.
        return stack.is(RestraintTags.ROPE) ? Optional.of(RestraintFamily.LEGACY_ROPE) : Optional.empty();
    }

    /** The restraint family a key opens, or empty when the stack is not a restraint key. */
    public static Optional<RestraintFamily> keyOpens(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(RestraintKeyItem.opensFamily(stack.getItem()));
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

}
