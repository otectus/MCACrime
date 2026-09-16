package dev.otectus.mcacrime.gametest;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.item.MaskFamily;
import dev.otectus.mcacrime.item.MaskItem;
import dev.otectus.mcacrime.item.MaskVariant;
import dev.otectus.mcacrime.mask.MaskCustomization;
import dev.otectus.mcacrime.mask.MaskRestyleRejection;
import dev.otectus.mcacrime.menu.MaskStationMenu;
import dev.otectus.mcacrime.recipe.MaskStationCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * The Mask Station against a real placed block, a real recipe manager and a real menu (0.7.2 §6–§8).
 *
 * <p>Everything here is deliberately out of reach of the JUnit suite. The recipes are datapack files
 * that only exist once a server has loaded them, the catalogue is built from
 * {@code RecipeManager.getAllRecipesFor}, and the one transaction that turns a preview into an item
 * runs inside vanilla's {@code doClick} — so "the wool actually became a bandana, and the wool is
 * actually gone" cannot be asserted anywhere but here.
 *
 * <p>The menu is built directly rather than through {@code openMenu}. A menu needs a container id, an
 * inventory and a {@link ContainerLevelAccess}; opening one through the player would additionally need
 * a live client connection to accept the open packet, and every rule under test lives on the server
 * side of that boundary anyway. The block is still placed, and the access still points at it, because
 * {@link MaskStationMenu#stillValid} reads the world through it.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MaskStationGameTests {

    /** Where the station goes on the template, and where every menu's access points. */
    private static final BlockPos STATION = new BlockPos(2, 1, 2);

    private MaskStationGameTests() {
    }

    /**
     * One craft recipe per family: the cheapest raw materials in, the shipped mask out.
     *
     * <p>Four families, four different material/binding pairs and four different costs, so a recipe
     * file whose ingredient or count drifted would fail here rather than silently stop being offered.
     */
    @GameTest(template = "platform")
    public static void everyFamilyCraftsItsMaskFromRawMaterials(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        MaskStationMenu menu = openStation(helper, player);
        try {
            craft(helper, menu, player, "bandana", new ItemStack(Items.WHITE_WOOL, 4),
                    new ItemStack(Items.STRING, 4), MaskVariant.BANDANA, 1, 1);
            craft(helper, menu, player, "leather_mask", new ItemStack(Items.LEATHER, 4),
                    new ItemStack(Items.STRING, 4), MaskVariant.LEATHER, 2, 1);
            craft(helper, menu, player, "clay_mask", new ItemStack(Items.CLAY_BALL, 8),
                    new ItemStack(Items.STRING, 4), MaskVariant.CLAY, 4, 2);
            craft(helper, menu, player, "iron_skull_mask", new ItemStack(Items.IRON_INGOT, 4),
                    new ItemStack(Items.LEATHER, 4), MaskVariant.IRON_SKULL, 2, 1);
        } finally {
            close(menu, player);
        }
        helper.succeed();
    }

    /**
     * A restyle inside one family carries the mask's identity across, and a cross-family pair is not a
     * conversion at all (§5.4, §7.4, MASK-07/08).
     *
     * <p>The refusal is asserted twice, at both of the places the spec puts it: the clay styles are
     * never offered for a cloth mask, and {@code restyleResult} names {@code WRONG_FAMILY} rather than
     * failing quietly.
     */
    @GameTest(template = "platform")
    public static void restylingStaysInsideOneFamilyAndKeepsDamageAndName(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        MaskStationMenu menu = openStation(helper, player);
        try {
            ItemStack worn = new ItemStack(mask(MaskVariant.BANDANA));
            worn.setDamageValue(7);
            worn.set(DataComponents.CUSTOM_NAME, Component.literal("Old Rag"));

            menu.inputsView().setItem(MaskStationMenu.MATERIAL_SLOT, worn);
            menu.inputsView().setItem(MaskStationMenu.BINDING_SLOT, new ItemStack(Items.STRING, 4));

            List<ResourceLocation> offered = menu.catalog().entries().stream()
                    .map(MaskStationCatalog.Entry::id).toList();
            helper.assertTrue(offered.stream().noneMatch(id -> id.getPath().contains("/clay/")),
                    "A cloth mask was offered a clay style: " + offered);
            helper.assertTrue(offered.stream().noneMatch(id -> id.getPath().endsWith("/restyle_bandana")),
                    "The station offered to restyle a bandana into a bandana: " + offered);

            ItemStack restyled = craftSelected(helper, menu, player, entry(helper, menu, "restyle_half_veil"));
            helper.assertTrue(MaskVariant.byStack(restyled).orElse(null) == MaskVariant.HALF_VEIL,
                    "Restyle produced " + restyled);
            helper.assertTrue(restyled.getDamageValue() == 7,
                    "Restyle did not carry the damage across: " + restyled.getDamageValue());
            helper.assertTrue(restyled.getMaxDamage() == MaskFamily.CLOTH.durability(),
                    "Restyle changed the wear budget: " + restyled.getMaxDamage());
            Component name = restyled.get(DataComponents.CUSTOM_NAME);
            helper.assertTrue(name != null && "Old Rag".equals(name.getString()),
                    "Restyle lost the custom name: " + name);
            helper.assertTrue(menu.inputsView().getItem(MaskStationMenu.MATERIAL_SLOT).isEmpty(),
                    "The restyled mask was not consumed; it would have been duplicated");

            // A fresh stack: the one above was consumed by the craft, and an empty stack would be
            // refused as NOT_A_MASK rather than as the family rule under test.
            MaskCustomization.Result crossFamily = MaskCustomization.restyleResult(
                    new ItemStack(mask(MaskVariant.BANDANA)), new ItemStack(mask(MaskVariant.CLAY)));
            helper.assertTrue(crossFamily.rejection() == MaskRestyleRejection.WRONG_FAMILY,
                    "Cloth to clay was refused as " + crossFamily.rejection());
            helper.assertTrue(crossFamily.stack().isEmpty(), "A refused conversion still produced a mask");
        } finally {
            close(menu, player);
        }
        helper.succeed();
    }

    /**
     * The optional dye slot: the mask that comes out of the station wears exactly the colour
     * {@link MaskCustomization#tint} produces, and {@link MaskItem#colorOf} reads it back (§5.3, §7.2).
     */
    @GameTest(template = "platform")
    public static void dyeingAMaskRoundTripsThroughTheItemColour(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        MaskStationMenu menu = openStation(helper, player);
        try {
            ItemStack plain = new ItemStack(mask(MaskVariant.BANDANA));
            helper.assertTrue(MaskItem.colorOf(plain) == MaskItem.OPAQUE_WHITE,
                    "An undyed mask is not untinted: " + Integer.toHexString(MaskItem.colorOf(plain)));

            ItemStack expected = MaskCustomization.tint(plain.copy(), (DyeItem) Items.RED_DYE);
            helper.assertTrue(MaskItem.colorOf(expected) != MaskItem.OPAQUE_WHITE, "Dye changed nothing");
            helper.assertTrue(MaskCustomization.colorOf(expected) == MaskItem.colorOf(expected),
                    "The two colour readers disagree about the same stack");

            menu.inputsView().setItem(MaskStationMenu.MATERIAL_SLOT, new ItemStack(Items.WHITE_WOOL, 4));
            menu.inputsView().setItem(MaskStationMenu.BINDING_SLOT, new ItemStack(Items.STRING, 4));
            menu.inputsView().setItem(MaskStationMenu.DYE_SLOT, new ItemStack(Items.RED_DYE, 4));

            ItemStack dyed = craftSelected(helper, menu, player, entry(helper, menu, "bandana"));
            helper.assertTrue(MaskVariant.byStack(dyed).orElse(null) == MaskVariant.BANDANA,
                    "The dye slot changed which mask was made: " + dyed);
            helper.assertTrue(MaskItem.colorOf(dyed) == MaskItem.colorOf(expected),
                    "Station dye " + Integer.toHexString(MaskItem.colorOf(dyed)) + " is not the item dye "
                            + Integer.toHexString(MaskItem.colorOf(expected)));
            helper.assertTrue(menu.inputsView().getItem(MaskStationMenu.DYE_SLOT).getCount() == 3,
                    "One craft did not spend exactly one dye");

            // §7.2: the slot takes dye and nothing else, so a valuable cannot be left in a station.
            helper.assertTrue(!menu.getSlot(MaskStationMenu.DYE_SLOT).mayPlace(new ItemStack(Items.DIAMOND)),
                    "The dye slot accepted a non-dye");
        } finally {
            close(menu, player);
        }
        helper.succeed();
    }

    /**
     * {@code maskStation.enableMaskStationCrafting} off means the station offers nothing and closes
     * the sessions that are already open, rather than quietly making masks anyway (§7.5, §8.2).
     */
    @GameTest(template = "platform")
    public static void disablingStationCraftingRefusesEveryCraft(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        MaskStationMenu menu = openStation(helper, player);
        var crafting = McaCrimeConfig.COMMON.enableMaskStationCrafting;
        boolean previous = crafting.get();
        try {
            menu.inputsView().setItem(MaskStationMenu.MATERIAL_SLOT, new ItemStack(Items.WHITE_WOOL, 4));
            menu.inputsView().setItem(MaskStationMenu.BINDING_SLOT, new ItemStack(Items.STRING, 4));
            helper.assertTrue(!menu.catalog().isEmpty(), "The enabled station offered nothing to craft");
            helper.assertTrue(menu.stillValid(player), "The menu was invalid while its station stood");

            crafting.set(false);
            MaskStationMenu closed = openStation(helper, player);
            try {
                closed.inputsView().setItem(MaskStationMenu.MATERIAL_SLOT, new ItemStack(Items.WHITE_WOOL, 4));
                closed.inputsView().setItem(MaskStationMenu.BINDING_SLOT, new ItemStack(Items.STRING, 4));
                helper.assertTrue(closed.catalog().isEmpty(),
                        "A disabled station still offered " + closed.catalog().entries());
                helper.assertTrue(closed.previewStack().isEmpty(), "A disabled station previewed a mask");
                helper.assertTrue(!closed.plan().craftable(), "A disabled station was still craftable");
                helper.assertTrue(!closed.stillValid(player),
                        "Turning crafting off left an open session alive");
                closed.clicked(MaskStationMenu.RESULT_SLOT, 0, ClickType.PICKUP, player);
                helper.assertTrue(closed.getCarried().isEmpty(), "A disabled station handed over a mask");
                helper.assertTrue(closed.inputsView().getItem(MaskStationMenu.MATERIAL_SLOT).getCount() == 4,
                        "A disabled station consumed material");
            } finally {
                close(closed, player);
            }
        } finally {
            crafting.set(previous);
            close(menu, player);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ fixture

    /** Places the station and opens one private session against it. */
    private static MaskStationMenu openStation(GameTestHelper helper, ServerPlayer player) {
        helper.setBlock(STATION, CrimeBlocks.MASK_STATION.get().defaultBlockState());
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(STATION);
        return new MaskStationMenu(1, player.getInventory(), ContainerLevelAccess.create(level, pos), pos);
    }

    /** Loads the inputs, makes one mask and asserts both the output and the debit. */
    private static void craft(GameTestHelper helper, MaskStationMenu menu, ServerPlayer player,
                              String recipe, ItemStack material, ItemStack binding,
                              MaskVariant expected, int materialCost, int bindingCost) {
        int materialBefore = material.getCount();
        int bindingBefore = binding.getCount();
        menu.inputsView().setItem(MaskStationMenu.DYE_SLOT, ItemStack.EMPTY);
        menu.inputsView().setItem(MaskStationMenu.MATERIAL_SLOT, material);
        menu.inputsView().setItem(MaskStationMenu.BINDING_SLOT, binding);

        ItemStack made = craftSelected(helper, menu, player, entry(helper, menu, recipe));
        helper.assertTrue(MaskVariant.byStack(made).orElse(null) == expected,
                recipe + " produced " + made + " instead of " + expected);
        helper.assertTrue(made.getCount() == 1, recipe + " produced " + made.getCount() + " masks");
        helper.assertTrue(made.getMaxDamage() == expected.family().durability(),
                recipe + " has the wrong wear budget: " + made.getMaxDamage());
        helper.assertTrue(menu.inputsView().getItem(MaskStationMenu.MATERIAL_SLOT).getCount()
                        == materialBefore - materialCost,
                recipe + " spent the wrong amount of material");
        helper.assertTrue(menu.inputsView().getItem(MaskStationMenu.BINDING_SLOT).getCount()
                        == bindingBefore - bindingCost,
                recipe + " spent the wrong amount of binding");
    }

    /**
     * Selects one style and takes the result the way a player does — through
     * {@code AbstractContainerMenu.doClick}, so the commit runs in the slot rather than in the test.
     */
    private static ItemStack craftSelected(GameTestHelper helper, MaskStationMenu menu, ServerPlayer player,
                                           ResourceLocation recipe) {
        helper.assertTrue(menu.select(player, menu.containerId, recipe, menu.recipeGeneration()).accepted(),
                "The server refused the selection " + recipe);
        helper.assertTrue(recipe.equals(menu.selected()), "The selection did not stick: " + menu.selected());
        helper.assertTrue(!menu.previewStack().isEmpty(), "Selecting " + recipe + " previewed nothing");
        helper.assertTrue(menu.plan().craftable(), "The preview for " + recipe + " was not payable: " + menu.rejection());

        menu.setCarried(ItemStack.EMPTY);
        menu.clicked(MaskStationMenu.RESULT_SLOT, 0, ClickType.PICKUP, player);
        ItemStack taken = menu.getCarried().copy();
        menu.setCarried(ItemStack.EMPTY);
        helper.assertTrue(!taken.isEmpty(), "Taking the result of " + recipe + " produced nothing");
        return taken;
    }

    /** The one offered recipe whose file is named {@code name}. */
    private static ResourceLocation entry(GameTestHelper helper, MaskStationMenu menu, String name) {
        for (MaskStationCatalog.Entry candidate : menu.catalog().entries()) {
            if (candidate.id().getPath().endsWith("/" + name)) {
                return candidate.id();
            }
        }
        helper.fail("The station did not offer " + name + "; it offered " + menu.catalog().entries());
        throw new IllegalStateException("unreachable");
    }

    private static Item mask(MaskVariant variant) {
        return CrimeItems.MASKS.get(variant).get();
    }

    /** Returns the inputs the way a closing screen does, so nothing is stranded in a dead session. */
    private static void close(MaskStationMenu menu, ServerPlayer player) {
        menu.setCarried(ItemStack.EMPTY);
        menu.removed(player);
    }

    /**
     * A server player with a connection that records instead of sending: the menu tells its viewer
     * which style is selected, and a null connection would make that a crash rather than a packet.
     */
    private static ServerPlayer player(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "mcacrime-station");
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile,
                ClientInformation.createDefault());
        player.connection = new ServerGamePacketListenerImpl(level.getServer(),
                new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(profile, false)) {
            @Override
            public void send(Packet<?> packet) {
            }

            @Override
            public void send(Packet<?> packet, PacketSendListener listener) {
            }
        };
        BlockPos pos = helper.absolutePos(STATION);
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 1.5);
        return player;
    }
}
