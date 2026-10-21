package dev.otectus.mcacrime.gametest;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.item.MaskVariant;
import dev.otectus.mcacrime.mask.MaskColors;
import dev.otectus.mcacrime.mask.MaskCustomization;
import dev.otectus.mcacrime.mask.MaskRestyleRejection;
import dev.otectus.mcacrime.menu.MaskStationMenu;
import dev.otectus.mcacrime.recipe.CrimeRecipes;
import dev.otectus.mcacrime.recipe.MaskCraftRejection;
import dev.otectus.mcacrime.recipe.MaskMakingRecipe;
import dev.otectus.mcacrime.recipe.MaskOperation;
import dev.otectus.mcacrime.recipe.MaskRecipeGeneration;
import dev.otectus.mcacrime.state.CrimeAttachments;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Workstream D on a real server (0.8.0): every style in every standard dye through the real Mask Station
 * menu and the real recipe manager, recolouring, blending, restyling, washing in a real cauldron, the
 * creative catalogue, two players at once and a recipe reload under an open menu. The Forge build runs the
 * same matrix in its dedicated-server harness ({@code tools/usability}).
 *
 * <p>A fake player drives the menu the way a client's packets would: inputs through the menu's container,
 * the style through the server's selection handler, the result through the slot's own pickup and
 * shift-click paths, so every debit and every refusal is the production one.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MaskMatrixGameTests {

    private static final BlockPos STATION = new BlockPos(2, 1, 2);
    private static final BlockPos CAULDRON = new BlockPos(0, 1, 0);

    private MaskMatrixGameTests() {
    }

    // ------------------------------------------------------------------ the matrix

    @GameTest(template = "platform", timeoutTicks = 200)
    public static void everyStyleCraftsUndyedAndInAllSixteenDyesAsTheCanonicalSample(GameTestHelper helper) {
        Recipes recipes = Recipes.of(helper);
        Bench bench = new Bench(helper, "matrix-maker");
        List<String> problems = new ArrayList<>();
        int made = 0;
        try {
            for (MaskVariant variant : MaskVariant.values()) {
                RecipeHolder<MaskMakingRecipe> recipe = recipes.craft(variant);
                ItemStack undyed = bench.make(recipe, material(recipe), binding(recipe), ItemStack.EMPTY);
                made += inspect(helper, problems, variant, null, undyed, new ItemStack(mask(variant)), bench, 0);
                for (DyeColor dye : MaskColors.STANDARD) {
                    ItemStack result = bench.make(recipe, material(recipe), binding(recipe), dyes(dye, 2));
                    made += inspect(helper, problems, variant, dye, result, MaskColors.sample(mask(variant), dye), bench, 1);
                }
            }
        } finally {
            bench.close();
        }
        helper.assertTrue(problems.isEmpty(), problems.size() + " problems, first: "
                + problems.subList(0, Math.min(6, problems.size())));
        helper.assertTrue(made == 16 * 17, "made " + made + " masks");
        helper.succeed();
    }

    private static int inspect(GameTestHelper helper, List<String> problems, MaskVariant variant, DyeColor dye,
                               ItemStack made, ItemStack expected, Bench bench, int dyeSpent) {
        String what = variant + " " + (dye == null ? "undyed" : dye.getName());
        int before = problems.size();
        if (!ItemStack.matches(made, expected)) {
            problems.add(what + ": made " + made + " " + made.getComponentsPatch() + ", expected " + expected.getComponentsPatch());
        }
        if (!ItemStack.matches(bench.lastPreview, made)) {
            problems.add(what + ": the preview disagreed with the result");
        }
        if (MaskVariant.byStack(made).orElse(null) != variant) {
            problems.add(what + ": wrong style " + made);
        }
        if (made.getDamageValue() != 0 || made.getMaxDamage() != variant.durability() || made.isEnchantable()) {
            problems.add(what + ": damage " + made.getDamageValue() + "/" + made.getMaxDamage() + " enchantable=" + made.isEnchantable());
        }
        if (!made.getAttributeModifiers().equals(new ItemStack(mask(variant)).getAttributeModifiers())) {
            problems.add(what + ": colour changed the item's attributes");
        }
        if (dye == null ? MaskColors.hasColor(made) : !MaskColors.standardOf(made).equals(Optional.of(dye))) {
            problems.add(what + ": stored colour " + MaskColors.storedColor(made));
        }
        String nameKey = made.getHoverName().getContents() instanceof TranslatableContents name ? name.getKey() : "";
        if (!nameKey.equals(dye == null ? variant.displayKey() : "item.mcacrime.mask.colored")) {
            problems.add(what + ": named " + nameKey);
        }
        if (!bench.slot(MaskStationMenu.MATERIAL_SLOT).isEmpty() || !bench.slot(MaskStationMenu.BINDING_SLOT).isEmpty()
                || bench.slot(MaskStationMenu.DYE_SLOT).getCount() != (dye == null ? 0 : 2 - dyeSpent)) {
            problems.add(what + ": inputs left " + bench.slot(0) + " " + bench.slot(1) + " " + bench.slot(2));
        }
        if (!roundTrips(helper, made)) {
            problems.add(what + ": did not survive the item codec and the network codec");
        }
        bench.clear();
        return problems.size() == before ? 1 : 0;
    }

    @GameTest(template = "platform", timeoutTicks = 200)
    public static void everyStyleRecoloursItselfInAllSixteenDyesKeepingItsHistory(GameTestHelper helper) {
        Recipes recipes = Recipes.of(helper);
        Bench bench = new Bench(helper, "matrix-recolourer");
        List<String> problems = new ArrayList<>();
        try {
            for (MaskVariant variant : MaskVariant.values()) {
                RecipeHolder<MaskMakingRecipe> recipe = recipes.restyle(variant);
                for (DyeColor dye : MaskColors.STANDARD) {
                    ItemStack loved = loved(helper, variant);
                    ItemStack result = bench.make(recipe, loved.copy(), binding(recipe), dyes(dye, 1));
                    String what = variant + " " + dye.getName();
                    if (!result.is(mask(variant))) {
                        problems.add(what + ": recolouring made " + result);
                    } else {
                        if (!MaskColors.standardOf(result).equals(Optional.of(dye))) {
                            problems.add(what + ": colour " + MaskColors.storedColor(result));
                        }
                        if (!withoutColor(result).equals(withoutColor(loved))) {
                            problems.add(what + ": data changed " + withoutColor(loved) + " -> " + withoutColor(result));
                        }
                        if (!bench.slot(0).isEmpty() || !bench.slot(1).isEmpty() || !bench.slot(2).isEmpty()) {
                            problems.add(what + ": inputs left " + bench.slot(0) + " " + bench.slot(1) + " " + bench.slot(2));
                        }
                    }
                    bench.clear();
                }
            }
        } finally {
            bench.close();
        }
        helper.assertTrue(problems.isEmpty(), problems.size() + " problems, first: "
                + problems.subList(0, Math.min(6, problems.size())));
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void repeatedDyeingBlendsLikeLeatherAndABlendIsNeverNamedStandard(GameTestHelper helper) {
        Recipes recipes = Recipes.of(helper);
        Bench bench = new Bench(helper, "matrix-blender");
        try {
            RecipeHolder<MaskMakingRecipe> recipe = recipes.restyle(MaskVariant.BANDANA);
            ItemStack current = MaskColors.sample(mask(MaskVariant.BANDANA), DyeColor.RED);
            for (DyeColor dye : List.of(DyeColor.BLUE, DyeColor.YELLOW, DyeColor.BLACK)) {
                ItemStack expected = DyedItemColor.applyDyes(current.copy(), List.of(DyeItem.byColor(dye)));
                ItemStack result = bench.make(recipe, current.copy(), binding(recipe), dyes(dye, 1));
                helper.assertTrue(result.is(mask(MaskVariant.BANDANA)), "blending " + dye.getName() + " made " + result);
                helper.assertTrue(MaskColors.storedColor(result).equals(MaskColors.storedColor(expected)),
                        "blending " + dye.getName() + " stored " + MaskColors.storedColor(result) + ", leather gives " + MaskColors.storedColor(expected));
                Optional<DyeColor> named = MaskColors.standardOf(result);
                helper.assertTrue(named.isEmpty() || MaskColors.standardColor(named.get()) == MaskColors.storedColor(result).orElseThrow(),
                        "a blend was named " + named);
                bench.clear();
                current = result;
            }
        } finally {
            bench.close();
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void exactNoOpsAreRefusedAndConsumeNothing(GameTestHelper helper) {
        Recipes recipes = Recipes.of(helper);
        Bench bench = new Bench(helper, "matrix-noop");
        try {
            Object[][] cases = {
                    {"undyed with no dye", MaskVariant.BANDANA, new ItemStack(mask(MaskVariant.BANDANA)), ItemStack.EMPTY},
                    {"white on white", MaskVariant.CLAY, MaskColors.sample(mask(MaskVariant.CLAY), DyeColor.WHITE), dyes(DyeColor.WHITE, 1)},
                    {"black on black", MaskVariant.IRON_SKULL, MaskColors.sample(mask(MaskVariant.IRON_SKULL), DyeColor.BLACK), dyes(DyeColor.BLACK, 1)},
                    {"blue with no dye", MaskVariant.OWL, MaskColors.sample(mask(MaskVariant.OWL), DyeColor.BLUE), ItemStack.EMPTY}};
            for (Object[] c : cases) {
                RecipeHolder<MaskMakingRecipe> recipe = recipes.restyle((MaskVariant) c[1]);
                ItemStack mask = ((ItemStack) c[2]).copy();
                ItemStack binding = binding(recipe);
                ItemStack dye = ((ItemStack) c[3]).copy();
                bench.inputs(mask.copy(), binding.copy(), dye.copy());
                helper.assertTrue(!bench.select(recipe), c[0] + ": the no-op restyle was offered");
                helper.assertTrue(MaskCustomization.wouldChange(mask, recipe.value().getResultItem(helper.getLevel().registryAccess()),
                                dye.getItem() instanceof DyeItem chosen ? chosen : null) == MaskRestyleRejection.NO_CHANGE,
                        c[0] + ": the screen would not say it is unchanged");
                helper.assertTrue(bench.take().isEmpty(), c[0] + ": a click took a mask");
                bench.menu.quickMoveStack(bench.player, MaskStationMenu.RESULT_SLOT);
                helper.assertTrue(ItemStack.matches(bench.slot(0), mask) && ItemStack.matches(bench.slot(1), binding)
                        && ItemStack.matches(bench.slot(2), dye), c[0] + ": an input was consumed");
                helper.assertTrue(bench.player.getInventory().countItem(mask((MaskVariant) c[1])) == 0, c[0] + ": a mask was minted");
                bench.clear();
            }
        } finally {
            bench.close();
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void explicitWhiteCustomWhiteAndUndyedStayDistinct(GameTestHelper helper) {
        Recipes recipes = Recipes.of(helper);
        Bench bench = new Bench(helper, "matrix-whites");
        try {
            RecipeHolder<MaskMakingRecipe> recipe = recipes.restyle(MaskVariant.HOCKEY);
            ItemStack undyed = new ItemStack(mask(MaskVariant.HOCKEY));
            ItemStack customWhite = undyed.copy();
            customWhite.set(DataComponents.DYED_COLOR, new DyedItemColor(0xFFFFFF, true));
            helper.assertTrue(!MaskColors.hasColor(undyed) && MaskColors.hasColor(customWhite), "undyed and custom white read alike");

            ItemStack dyedWhite = bench.make(recipe, undyed.copy(), binding(recipe), dyes(DyeColor.WHITE, 1));
            helper.assertTrue(dyedWhite.is(mask(MaskVariant.HOCKEY)) && MaskColors.standardOf(dyedWhite).equals(Optional.of(DyeColor.WHITE)),
                    "white dye on an undyed mask made " + dyedWhite.getComponentsPatch());
            bench.clear();

            bench.inputs(customWhite.copy(), binding(recipe), ItemStack.EMPTY);
            helper.assertTrue(!bench.select(recipe), "custom white with no dye was offered as a change");
            bench.clear();

            ItemStack expected = DyedItemColor.applyDyes(customWhite.copy(), List.of(DyeItem.byColor(DyeColor.WHITE)));
            boolean changes = !MaskColors.storedColor(expected).equals(MaskColors.storedColor(customWhite));
            bench.inputs(customWhite.copy(), binding(recipe), dyes(DyeColor.WHITE, 1));
            helper.assertTrue(bench.select(recipe) == changes, "white dye on custom white: offered=" + !changes + " but it changes=" + changes);
            if (changes) {
                ItemStack result = bench.take();
                helper.assertTrue(MaskColors.storedColor(result).equals(MaskColors.storedColor(expected)), "custom white + white made " + result.getComponentsPatch());
            }
            bench.clear();
        } finally {
            bench.close();
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void aSameFamilyRestyleKeepsColourAndDataAndACrossFamilyMaskIsNotOffered(GameTestHelper helper) {
        Recipes recipes = Recipes.of(helper);
        Bench bench = new Bench(helper, "matrix-restyler");
        try {
            ItemStack raven = loved(helper, MaskVariant.RAVEN);
            raven.set(DataComponents.DYED_COLOR, new DyedItemColor(MaskColors.standardColor(DyeColor.BLUE), true));
            RecipeHolder<MaskMakingRecipe> toJackal = recipes.restyle(MaskVariant.JACKAL);
            ItemStack jackal = bench.make(toJackal, raven.copy(), binding(toJackal), ItemStack.EMPTY);
            helper.assertTrue(jackal.is(mask(MaskVariant.JACKAL)), "raven -> jackal made " + jackal);
            helper.assertTrue(MaskColors.standardOf(jackal).equals(Optional.of(DyeColor.BLUE)), "the restyle lost the colour");
            helper.assertTrue(withoutColor(jackal).equals(withoutColor(raven)), "the restyle changed data " + withoutColor(jackal));
            helper.assertTrue(bench.slot(0).isEmpty(), "the source mask was not consumed");
            bench.clear();

            RecipeHolder<MaskMakingRecipe> toStitched = recipes.restyle(MaskVariant.STITCHED);
            ItemStack stitched = bench.make(toStitched, jackal.copy(), binding(toStitched), dyes(DyeColor.RED, 1));
            ItemStack blend = DyedItemColor.applyDyes(jackal.copy(), List.of(DyeItem.byColor(DyeColor.RED)));
            helper.assertTrue(stitched.is(mask(MaskVariant.STITCHED)) && MaskColors.storedColor(stitched).equals(MaskColors.storedColor(blend)),
                    "restyle + dye made " + stitched.getComponentsPatch());
            bench.clear();

            // The legacy id keeps its family: a Brigand Visor becomes a Balaclava (iron_skull_mask).
            RecipeHolder<MaskMakingRecipe> toBalaclava = recipes.restyle(MaskVariant.IRON_SKULL);
            ItemStack balaclava = bench.make(toBalaclava, MaskColors.sample(mask(MaskVariant.BRIGAND_VISOR), DyeColor.GREEN),
                    binding(toBalaclava), ItemStack.EMPTY);
            helper.assertTrue(balaclava.is(mask(MaskVariant.IRON_SKULL)) && MaskColors.standardOf(balaclava).equals(Optional.of(DyeColor.GREEN)),
                    "visor -> balaclava made " + balaclava);
            bench.clear();

            bench.inputs(MaskColors.sample(mask(MaskVariant.BANDANA), DyeColor.RED), binding(toJackal), ItemStack.EMPTY);
            helper.assertTrue(!bench.select(toJackal), "a cloth mask was offered a leather restyle");
            helper.assertTrue(bench.menu.previewStack().isEmpty(), "a cross-family preview appeared");
            bench.clear();
        } finally {
            bench.close();
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void aMaskCarryingUnsupportedDataIsRefusedWithItsReason(GameTestHelper helper) {
        Recipes recipes = Recipes.of(helper);
        Bench bench = new Bench(helper, "matrix-unsupported");
        try {
            ItemStack capped = loved(helper, MaskVariant.RAVEN);
            CompoundTag entity = new CompoundTag();
            entity.putString("id", "minecraft:pig");
            capped.set(DataComponents.ENTITY_DATA, CustomData.of(entity));
            RecipeHolder<MaskMakingRecipe> toJackal = recipes.restyle(MaskVariant.JACKAL);
            ItemStack binding = binding(toJackal);
            ItemStack dye = dyes(DyeColor.LIME, 1);
            bench.inputs(capped.copy(), binding.copy(), dye.copy());
            bench.select(toJackal);
            helper.assertTrue(bench.menu.previewStack().isEmpty(), "an unsupported mask previewed " + bench.menu.previewStack());
            helper.assertTrue(bench.menu.rejection() == MaskCraftRejection.RESTYLE_UNSUPPORTED, "the station said " + bench.menu.rejection());
            helper.assertTrue(bench.take().isEmpty(), "an unsupported mask was restyled");
            helper.assertTrue(ItemStack.matches(bench.slot(0), capped) && ItemStack.matches(bench.slot(1), binding)
                    && ItemStack.matches(bench.slot(2), dye), "an input was consumed");
            bench.clear();
        } finally {
            bench.close();
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ washing

    private static ItemInteractionResult wash(GameTestHelper helper, ServerPlayer player, ItemStack held) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        BlockPos pos = helper.absolutePos(CAULDRON);
        BlockState state = helper.getLevel().getBlockState(pos);
        return state.useItemOn(held, helper.getLevel(), player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private static int cauldronLevel(GameTestHelper helper) {
        BlockState state = helper.getBlockState(CAULDRON);
        return state.is(Blocks.WATER_CAULDRON) ? state.getValue(LayeredCauldronBlock.LEVEL) : 0;
    }

    private static void fillCauldron(GameTestHelper helper) {
        helper.setBlock(CAULDRON, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
    }

    @GameTest(template = "platform")
    public static void aWaterCauldronWashesOutOnlyTheColourOnceAndIgnoresAnUndyedMask(GameTestHelper helper) {
        ServerPlayer washer = player(helper, "matrix-washer");
        fillCauldron(helper);
        ItemStack owl = loved(helper, MaskVariant.OWL);
        owl.set(DataComponents.DYED_COLOR, new DyedItemColor(MaskColors.standardColor(DyeColor.BLUE), true));
        var before = withoutColor(owl);
        int cleaned = washer.getStats().getValue(Stats.CUSTOM.get(Stats.CLEAN_ARMOR));

        ItemInteractionResult first = wash(helper, washer, owl);
        ItemStack washed = washer.getMainHandItem();
        helper.assertTrue(first.consumesAction(), "washing a dyed mask returned " + first);
        helper.assertTrue(!MaskColors.hasColor(washed), "the colour survived the wash");
        helper.assertTrue(withoutColor(washed).equals(before), "the wash changed more than the colour: " + washed.getComponentsPatch());
        helper.assertTrue(washed.has(DataComponents.CUSTOM_NAME) && washed.has(DataComponents.LORE), "the wash erased the name or lore");
        helper.assertTrue(cauldronLevel(helper) == 2, "the cauldron is at level " + cauldronLevel(helper) + " after one wash");
        helper.assertTrue(washer.getStats().getValue(Stats.CUSTOM.get(Stats.CLEAN_ARMOR)) == cleaned + 1, "the wash was not counted once");

        ItemInteractionResult second = wash(helper, washer, washed.copy());
        helper.assertTrue(!second.consumesAction(), "washing an undyed mask returned " + second);
        helper.assertTrue(cauldronLevel(helper) == 2, "an undyed mask used water");

        for (MaskVariant variant : MaskVariant.values()) {
            if (cauldronLevel(helper) == 0) fillCauldron(helper);
            ItemStack dyed = MaskColors.sample(mask(variant), DyeColor.PURPLE);
            helper.assertTrue(wash(helper, washer, dyed).consumesAction() && !MaskColors.hasColor(washer.getMainHandItem())
                    && ItemStack.matches(washer.getMainHandItem(), new ItemStack(mask(variant))), variant + " did not wash clean");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ legal state

    @GameTest(template = "platform")
    public static void dyeingWashingAndRestylingLeaveHeatDeferredHeatAndPursuitUntouched(GameTestHelper helper) {
        Recipes recipes = Recipes.of(helper);
        Bench bench = new Bench(helper, "matrix-outlaw");
        try {
            ServerPlayer outlaw = bench.player;
            PlayerCrimeData data = CrimeAttachments.get(outlaw);
            long now = helper.getLevel().getGameTime();
            data.setHeat(23);
            data.getPendingMaskedHeat().add(new PlayerCrimeData.PendingMaskedHeat(
                    McaCrime.id("assault"), "gametest-mask", 9, now));
            data.setMaskedPursuitUntilTick(now + 20_000);
            outlaw.setItemSlot(EquipmentSlot.HEAD, MaskColors.sample(mask(MaskVariant.HALF_VEIL), DyeColor.GRAY));
            String before = legal(data);

            RecipeHolder<MaskMakingRecipe> make = recipes.craft(MaskVariant.HIGHWAYMANS_DOMINO);
            ItemStack domino = bench.make(make, material(make), binding(make), dyes(DyeColor.CYAN, 1));
            bench.clear();
            RecipeHolder<MaskMakingRecipe> recolour = recipes.restyle(MaskVariant.HIGHWAYMANS_DOMINO);
            ItemStack recoloured = bench.make(recolour, domino, binding(recolour), dyes(DyeColor.ORANGE, 1));
            bench.clear();
            RecipeHolder<MaskMakingRecipe> toScarf = recipes.restyle(MaskVariant.WRAPPED_SCARF);
            ItemStack scarf = bench.make(toScarf, recoloured, binding(toScarf), ItemStack.EMPTY);
            bench.clear();
            helper.assertTrue(scarf.is(mask(MaskVariant.WRAPPED_SCARF)), "the chain stopped at " + scarf);
            fillCauldron(helper);
            wash(helper, outlaw, scarf);
            helper.assertTrue(!MaskColors.hasColor(outlaw.getMainHandItem()), "the scarf did not wash");

            helper.assertTrue(legal(data).equals(before), "legal state moved: " + before + " -> " + legal(data));
            helper.assertTrue(outlaw.getItemBySlot(EquipmentSlot.HEAD).is(mask(MaskVariant.HALF_VEIL)), "the worn mask was touched");
        } finally {
            bench.close();
        }
        helper.succeed();
    }

    private static String legal(PlayerCrimeData data) {
        return "heat=" + data.getHeat() + " pending=" + data.getPendingMaskedHeat()
                + " pursuitUntil=" + data.getMaskedPursuitUntilTick();
    }

    // ------------------------------------------------------------------ the menu

    @GameTest(template = "platform")
    public static void twoPlayersAtOneStationEachGetTheirOwnResultFromTheirOwnInputs(GameTestHelper helper) {
        Recipes recipes = Recipes.of(helper);
        Bench first = new Bench(helper, "matrix-a");
        Bench second = new Bench(helper, "matrix-b");
        try {
            RecipeHolder<MaskMakingRecipe> bandana = recipes.craft(MaskVariant.BANDANA);
            RecipeHolder<MaskMakingRecipe> owl = recipes.craft(MaskVariant.OWL);
            first.inputs(material(bandana), binding(bandana), dyes(DyeColor.RED, 1));
            helper.assertTrue(second.slot(0).isEmpty() && second.menu.previewStack().isEmpty(), "the second menu saw the first's inputs");
            second.inputs(material(owl), binding(owl), dyes(DyeColor.BLUE, 1));
            helper.assertTrue(first.select(bandana) && second.select(owl), "a selection was refused");
            ItemStack fromSecond = second.take();
            helper.assertTrue(ItemStack.matches(fromSecond, MaskColors.sample(mask(MaskVariant.OWL), DyeColor.BLUE)), "the second player got " + fromSecond);
            helper.assertTrue(ItemStack.matches(first.menu.previewStack(), MaskColors.sample(mask(MaskVariant.BANDANA), DyeColor.RED))
                    && !first.slot(0).isEmpty() && !first.slot(2).isEmpty(), "the second craft touched the first menu");
            ItemStack fromFirst = first.take();
            helper.assertTrue(ItemStack.matches(fromFirst, MaskColors.sample(mask(MaskVariant.BANDANA), DyeColor.RED)), "the first player got " + fromFirst);
            helper.assertTrue(first.slot(0).isEmpty() && second.slot(0).isEmpty(), "inputs were not each spent once");
        } finally {
            first.close();
            second.close();
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void thePreviewFollowsDyeChangesStyleChangesAndRemovedInputs(GameTestHelper helper) {
        Recipes recipes = Recipes.of(helper);
        Bench bench = new Bench(helper, "matrix-previewer");
        try {
            RecipeHolder<MaskMakingRecipe> bandana = recipes.craft(MaskVariant.BANDANA);
            RecipeHolder<MaskMakingRecipe> veil = recipes.craft(MaskVariant.HALF_VEIL);
            ItemStack material = material(bandana);
            material.setCount(Math.max(material.getCount(), veil.value().materialCount()));
            bench.inputs(material, binding(bandana), ItemStack.EMPTY);
            helper.assertTrue(bench.select(bandana), "the bandana was not offered");
            helper.assertTrue(ItemStack.matches(bench.menu.previewStack(), new ItemStack(mask(MaskVariant.BANDANA))), "undyed preview");
            bench.menu.inputsView().setItem(MaskStationMenu.DYE_SLOT, dyes(DyeColor.LIME, 1));
            helper.assertTrue(ItemStack.matches(bench.menu.previewStack(), MaskColors.sample(mask(MaskVariant.BANDANA), DyeColor.LIME)), "adding lime");
            bench.menu.inputsView().setItem(MaskStationMenu.DYE_SLOT, dyes(DyeColor.PURPLE, 1));
            helper.assertTrue(ItemStack.matches(bench.menu.previewStack(), MaskColors.sample(mask(MaskVariant.BANDANA), DyeColor.PURPLE)), "switching to purple");
            bench.menu.inputsView().setItem(MaskStationMenu.DYE_SLOT, ItemStack.EMPTY);
            helper.assertTrue(ItemStack.matches(bench.menu.previewStack(), new ItemStack(mask(MaskVariant.BANDANA))), "removing the dye");
            helper.assertTrue(bench.select(veil), "the veil was not offered");
            helper.assertTrue(bench.menu.previewStack().is(mask(MaskVariant.HALF_VEIL)), "switching styles previewed " + bench.menu.previewStack());
            bench.menu.inputsView().setItem(MaskStationMenu.BINDING_SLOT, ItemStack.EMPTY);
            helper.assertTrue(bench.menu.previewStack().isEmpty() && bench.menu.selected() == null
                    && bench.menu.rejection() == MaskCraftRejection.NO_SELECTION, "removing the binding left a preview or a selection");
            helper.assertTrue(bench.take().isEmpty(), "a craft was taken without its binding");
        } finally {
            bench.close();
        }
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void shiftClickMakesABoundedBatchOneDyePerMaskAndAFullInventoryConsumesNothing(GameTestHelper helper) {
        Recipes recipes = Recipes.of(helper);
        Bench bench = new Bench(helper, "matrix-batcher");
        try {
            ServerPlayer player = bench.player;
            player.getInventory().clearContent();
            RecipeHolder<MaskMakingRecipe> clay = recipes.craft(MaskVariant.CLAY);
            ItemStack material = material(clay);
            material.setCount(clay.value().materialCount() * 3);
            ItemStack binding = binding(clay);
            binding.setCount(clay.value().bindingCount() * 3);
            bench.inputs(material, binding, dyes(DyeColor.MAGENTA, 2));
            helper.assertTrue(bench.select(clay), "clay was not offered");
            bench.menu.quickMoveStack(player, MaskStationMenu.RESULT_SLOT);
            int magenta = 0;
            for (ItemStack stack : player.getInventory().items) {
                if (stack.is(mask(MaskVariant.CLAY)) && MaskColors.standardOf(stack).equals(Optional.of(DyeColor.MAGENTA))) magenta++;
            }
            helper.assertTrue(magenta == 2 && player.getInventory().countItem(mask(MaskVariant.CLAY)) == 2, "two dyes made " + magenta + " magenta masks");
            helper.assertTrue(bench.slot(2).isEmpty() && bench.slot(0).getCount() == clay.value().materialCount(),
                    "the batch spent " + bench.slot(0) + " " + bench.slot(1) + " " + bench.slot(2));
            bench.menu.quickMoveStack(player, MaskStationMenu.RESULT_SLOT);
            helper.assertTrue(player.getInventory().countItem(mask(MaskVariant.CLAY)) == 3 && bench.slot(0).isEmpty(), "the last, undyed craft");

            for (int i = 0; i < player.getInventory().items.size(); i++) {
                player.getInventory().items.set(i, new ItemStack(Items.DIRT, 64));
            }
            ItemStack lastMaterial = material(clay);
            ItemStack lastBinding = binding(clay);
            bench.inputs(lastMaterial.copy(), lastBinding.copy(), dyes(DyeColor.MAGENTA, 1));
            helper.assertTrue(bench.select(clay), "clay was not offered into a full inventory");
            bench.menu.quickMoveStack(player, MaskStationMenu.RESULT_SLOT);
            helper.assertTrue(ItemStack.matches(bench.slot(0), lastMaterial) && ItemStack.matches(bench.slot(1), lastBinding)
                    && bench.slot(2).getCount() == 1, "a full inventory consumed inputs");
            bench.clear();
            player.getInventory().clearContent();
        } finally {
            bench.close();
        }
        helper.succeed();
    }

    /**
     * A recipe reload under an open menu drops the stale selection and the station works again (0.7.2 §7.5,
     * D3). The reload's own signal is the generation bump its listener makes; this sends that signal rather
     * than reloading every datapack under the other GameTests running beside it. The Forge harness and both
     * client runs do the real {@code /reload}.
     */
    @GameTest(template = "platform")
    public static void aRecipeReloadUnderAnOpenMenuDropsTheStaleSelection(GameTestHelper helper) {
        Recipes recipes = Recipes.of(helper);
        Bench bench = new Bench(helper, "matrix-reloader");
        try {
            RecipeHolder<MaskMakingRecipe> clay = recipes.craft(MaskVariant.CLAY);
            bench.inputs(material(clay), binding(clay), dyes(DyeColor.BLUE, 4));
            helper.assertTrue(bench.select(clay), "clay was not offered");
            int generation = bench.menu.recipeGeneration();
            MaskRecipeGeneration.bump();
            bench.menu.broadcastChanges();
            helper.assertTrue(bench.menu.recipeGeneration() != generation, "the generation did not move");
            helper.assertTrue(bench.menu.selected() == null && bench.menu.previewStack().isEmpty(), "the stale selection survived");
            helper.assertTrue(bench.select(clay), "the station refused the style after the reload");
            ItemStack made = bench.take();
            helper.assertTrue(ItemStack.matches(made, MaskColors.sample(mask(MaskVariant.CLAY), DyeColor.BLUE)), "after the reload the station made " + made);
        } finally {
            bench.close();
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ the creative catalogue

    @GameTest(template = "platform")
    public static void theCreativeMasksTabHoldsAllStacksInCatalogueOrder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CreativeModeTabs.tryRebuildTabContents(level.enabledFeatures(), true, level.registryAccess());
        List<ItemStack> shown = List.copyOf(CrimeItems.MASKS_TAB.get().getDisplayItems());
        List<ItemStack> expected = MaskColors.catalogue(MaskMatrixGameTests::mask);
        helper.assertTrue(shown.size() == 272, "the Masks tab holds " + shown.size() + " stacks");
        for (int i = 0; i < expected.size(); i++) {
            helper.assertTrue(ItemStack.matches(shown.get(i), expected.get(i)), "stack " + i + " is " + shown.get(i));
            helper.assertTrue(roundTrips(helper, shown.get(i)), shown.get(i) + " does not survive serialisation");
        }
        helper.assertTrue(CrimeItems.MASKS_TAB.get().getSearchTabDisplayItems().size() == 272, "creative search sees "
                + CrimeItems.MASKS_TAB.get().getSearchTabDisplayItems().size() + " mask stacks");
        Collection<ItemStack> crime = CrimeItems.TAB.get().getDisplayItems();
        for (MaskVariant variant : MaskVariant.values()) {
            helper.assertTrue(crime.stream().anyMatch(stack -> stack.is(mask(variant)) && !MaskColors.hasColor(stack)),
                    "the Crime tab lost the original " + variant);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ fixture

    private record Recipes(Map<MaskVariant, RecipeHolder<MaskMakingRecipe>> crafts,
                           Map<MaskVariant, RecipeHolder<MaskMakingRecipe>> restyles) {
        static Recipes of(GameTestHelper helper) {
            Map<MaskVariant, RecipeHolder<MaskMakingRecipe>> crafts = new EnumMap<>(MaskVariant.class);
            Map<MaskVariant, RecipeHolder<MaskMakingRecipe>> restyles = new EnumMap<>(MaskVariant.class);
            for (RecipeHolder<MaskMakingRecipe> holder : helper.getLevel().getRecipeManager().getAllRecipesFor(CrimeRecipes.MASK_MAKING.get())) {
                MaskMakingRecipe recipe = holder.value();
                Optional<MaskVariant> style = MaskVariant.byStack(recipe.getResultItem(helper.getLevel().registryAccess()));
                helper.assertTrue(style.isPresent(), holder.id() + " does not make a registered style");
                helper.assertTrue(recipe.allowDye(), holder.id() + " refuses the optional dye");
                (recipe.operation() == MaskOperation.CRAFT ? crafts : restyles).put(style.get(), holder);
            }
            helper.assertTrue(crafts.size() == 16 && restyles.size() == 16, "recipes: " + crafts.keySet() + " / " + restyles.keySet());
            return new Recipes(crafts, restyles);
        }

        RecipeHolder<MaskMakingRecipe> craft(MaskVariant variant) {
            return crafts.get(variant);
        }

        RecipeHolder<MaskMakingRecipe> restyle(MaskVariant variant) {
            return restyles.get(variant);
        }
    }

    private static Item mask(MaskVariant variant) {
        return CrimeItems.MASKS.get(variant).get();
    }

    private static ItemStack dyes(DyeColor dye, int count) {
        return new ItemStack(DyeItem.byColor(dye), count);
    }

    private static ItemStack material(RecipeHolder<MaskMakingRecipe> recipe) {
        ItemStack stack = recipe.value().material().getItems()[0].copy();
        stack.setCount(recipe.value().materialCount());
        return stack;
    }

    private static ItemStack binding(RecipeHolder<MaskMakingRecipe> recipe) {
        ItemStack stack = recipe.value().binding().getItems()[0].copy();
        stack.setCount(recipe.value().bindingCount());
        return stack;
    }

    /** A well-used mask with a history: wear, a name, lore, a curse, this mod's provenance and a foreign flag. */
    private static ItemStack loved(GameTestHelper helper, MaskVariant variant) {
        ItemStack stack = new ItemStack(mask(variant));
        stack.setDamageValue(Math.min(13, variant.durability() - 1));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("The Old Face"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Taken from a guard"))));
        ItemEnchantments.Mutable curses = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        curses.set(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.BINDING_CURSE), 1);
        stack.set(DataComponents.ENCHANTMENTS, curses.toImmutable());
        CompoundTag custom = new CompoundTag();
        CompoundTag mine = new CompoundTag();
        mine.putString("provenance", "gametest-91");
        custom.put("mcacrime", mine);
        custom.putString("othermod:soulbound", "player-a");
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        return stack;
    }

    /** Every component the stack carries, except the colour. */
    private static Object withoutColor(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.remove(DataComponents.DYED_COLOR);
        return copy.getComponentsPatch();
    }

    /** Through the item codec (a save) and the network codec (a creative pick, a slot update). */
    private static boolean roundTrips(GameTestHelper helper, ItemStack stack) {
        var registries = helper.getLevel().registryAccess();
        ItemStack saved = ItemStack.parseOptional(registries, (CompoundTag) stack.save(registries));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            ItemStack.STREAM_CODEC.encode(buffer, stack);
            ItemStack wire = ItemStack.STREAM_CODEC.decode(buffer);
            return ItemStack.matches(stack, saved) && ItemStack.matches(stack, wire);
        } finally {
            buffer.release();
        }
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name);
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player,
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

    /** One player's open Mask Station menu, driven through its container, its selection handler and its result slot. */
    private static final class Bench {
        final ServerPlayer player;
        final MaskStationMenu menu;
        ItemStack lastPreview = ItemStack.EMPTY;
        private static int nextId = 700;

        Bench(GameTestHelper helper, String name) {
            helper.setBlock(STATION, CrimeBlocks.MASK_STATION.get().defaultBlockState());
            this.player = player(helper, name);
            BlockPos pos = helper.absolutePos(STATION);
            this.menu = new MaskStationMenu(nextId++, player.getInventory(), ContainerLevelAccess.create(helper.getLevel(), pos), pos);
        }

        void inputs(ItemStack material, ItemStack binding, ItemStack dye) {
            menu.inputsView().setItem(MaskStationMenu.MATERIAL_SLOT, material);
            menu.inputsView().setItem(MaskStationMenu.BINDING_SLOT, binding);
            menu.inputsView().setItem(MaskStationMenu.DYE_SLOT, dye);
        }

        boolean select(RecipeHolder<MaskMakingRecipe> recipe) {
            return menu.select(player, menu.containerId, recipe.id(), menu.recipeGeneration()).accepted();
        }

        ItemStack take() {
            menu.setCarried(ItemStack.EMPTY);
            menu.clicked(MaskStationMenu.RESULT_SLOT, 0, ClickType.PICKUP, player);
            ItemStack taken = menu.getCarried().copy();
            menu.setCarried(ItemStack.EMPTY);
            return taken;
        }

        ItemStack make(RecipeHolder<MaskMakingRecipe> recipe, ItemStack material, ItemStack binding, ItemStack dye) {
            inputs(material, binding, dye);
            lastPreview = ItemStack.EMPTY;
            if (!select(recipe)) {
                return ItemStack.EMPTY;
            }
            lastPreview = menu.previewStack().copy();
            return take();
        }

        ItemStack slot(int index) {
            return menu.inputsView().getItem(index);
        }

        void clear() {
            for (int slot = 0; slot < 3; slot++) {
                menu.inputsView().setItem(slot, ItemStack.EMPTY);
            }
        }

        void close() {
            clear();
            menu.setCarried(ItemStack.EMPTY);
            menu.removed(player);
        }
    }
}
