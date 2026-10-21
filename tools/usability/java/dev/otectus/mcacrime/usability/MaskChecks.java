package dev.otectus.mcacrime.usability;

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
import dev.otectus.mcacrime.state.CrimeCapabilities;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.otectus.mcacrime.usability.UsabilityRuntimeChecks.require;

/**
 * Workstream D on a real server (0.8.0): every style in every standard dye through the real Mask Station
 * menu and the real recipe manager, recolouring, blending, restyling, washing in a real cauldron, the
 * creative catalogue, two players at once, a datapack reload under an open menu, and a restart that has
 * to bring every coloured stack back exactly.
 *
 * <p>A fake player drives the menu the way a client's packets would: inputs through slots, the style
 * through the server's selection handler, the result through the slot's own pickup and shift-click
 * paths, so every debit and every refusal is the production one.
 */
final class MaskChecks {

    private static final int Y = 100;
    private static final BlockPos STATION = new BlockPos(20, Y, 88);
    private static final BlockPos SECOND_STATION = new BlockPos(24, Y, 88);
    private static final BlockPos CAULDRON = new BlockPos(28, Y, 88);
    /** Eleven single chests hold the 272 catalogue stacks and the edge cases across the restart. */
    private static final int CHESTS = 11;

    private final Fixtures f;
    private final UsabilityRuntimeChecks out;
    private final Map<MaskVariant, MaskMakingRecipe> crafts = new EnumMap<>(MaskVariant.class);
    private final Map<MaskVariant, MaskMakingRecipe> restyles = new EnumMap<>(MaskVariant.class);
    private int nextMenuId = 900;

    MaskChecks(Fixtures fixtures, UsabilityRuntimeChecks out) {
        this.f = fixtures;
        this.out = out;
    }

    void run() {
        if (UsabilityRuntimeChecks.phase() == 1) {
            out.check("masks restart: the whole catalogue and every edge case came back exactly", this::restartVerify);
            return;
        }
        f.arena(16, 60, 84, 112, Y);
        f.level.setBlockAndUpdate(STATION, CrimeBlocks.MASK_STATION.get().defaultBlockState());
        f.level.setBlockAndUpdate(SECOND_STATION, CrimeBlocks.MASK_STATION.get().defaultBlockState());
        out.check("masks: 16 make and 16 restyle recipes, every one of them taking the optional dye", this::indexRecipes);
        out.check("masks: every style crafts undyed and in all 16 dyes at a real station, each the canonical sample", this::craftMatrix);
        out.check("masks: every style recolours itself in all 16 dyes, keeping wear, name, lore, curse and provenance", this::recolorMatrix);
        out.check("masks: repeated dyeing blends like leather and a blend is never named as a standard colour", this::blending);
        out.check("masks: exact no-ops (undyed with no dye, white on white, black on black) are refused and consume nothing", this::noOps);
        out.check("masks: explicit white, custom white and undyed stay distinct states", this::whites);
        out.check("masks: a same-family restyle keeps colour and data; a cross-family mask is not offered", this::restyle);
        out.check("masks: a mask carrying unsupported data is refused with its reason and keeps every input", this::unsupported);
        out.check("masks: a water cauldron washes out only the colour, once, and ignores an undyed mask", this::washing);
        out.check("masks: dyeing, washing and restyling leave Heat, deferred masked Heat and pursuit untouched", this::legalState);
        out.check("masks: two players at one station at once each get their own result from their own inputs", this::twoPlayers);
        out.check("masks: the preview follows dye changes, style changes and removed inputs", this::previewFollows);
        out.check("masks: shift-click makes a bounded batch, one dye per mask, and a full inventory consumes nothing", this::shiftClick);
        out.check("masks: the creative Masks tab holds 272 stacks in catalogue order; the Crime tab keeps the originals", this::creativeTab);
        out.check("masks: restart setup stores the whole catalogue and the edge cases in chests", this::restartSetup);
        staleSelectionAfterReload();
    }

    // --- the recipes -----------------------------------------------------------------------------------

    private void indexRecipes() {
        for (MaskMakingRecipe recipe : f.level.getRecipeManager().getAllRecipesFor(CrimeRecipes.MASK_MAKING.get())) {
            Optional<MaskVariant> style = MaskVariant.byStack(result(recipe));
            require(style.isPresent(), id(recipe) + " does not make a registered style");
            require(recipe.allowDye(), id(recipe) + " refuses the optional dye");
            Map<MaskVariant, MaskMakingRecipe> into = recipe.operation() == MaskOperation.CRAFT ? crafts : restyles;
            require(into.put(style.get(), recipe) == null, "two " + recipe.operation() + " recipes make " + style.get());
        }
        require(crafts.size() == 16, "make recipes for " + crafts.keySet());
        require(restyles.size() == 16, "restyle recipes for " + restyles.keySet());
    }

    // --- the matrix ------------------------------------------------------------------------------------

    private void craftMatrix() {
        Bench bench = new Bench(f.player("MaskMaker", 20.5, Y, 86.5), STATION);
        try {
            List<String> problems = new ArrayList<>();
            int made = 0;
            for (MaskVariant variant : MaskVariant.values()) {
                MaskMakingRecipe recipe = crafts.get(variant);
                Item item = item(variant);
                ItemStack undyed = bench.make(recipe, material(recipe), binding(recipe), ItemStack.EMPTY);
                made += inspect(problems, variant, null, undyed, new ItemStack(item), bench, 0);
                for (DyeColor dye : MaskColors.STANDARD) {
                    ItemStack result = bench.make(recipe, material(recipe), binding(recipe), dyes(dye, 2));
                    made += inspect(problems, variant, dye, result, MaskColors.sample(item, dye), bench, 1);
                }
            }
            require(problems.isEmpty(), problems.size() + " problems, first: " + problems.subList(0, Math.min(6, problems.size())));
            require(made == 16 * 17, "made " + made + " masks");
            out.info("masks: crafted " + made + " masks (16 originals + 256 dyed) through the station menu");
        } finally {
            bench.close();
        }
    }

    /** One crafted mask against what it should be. Returns 1 when it is right, so the caller can count. */
    private int inspect(List<String> problems, MaskVariant variant, DyeColor dye, ItemStack made, ItemStack expected,
                        Bench bench, int dyeSpent) {
        String what = variant + " " + (dye == null ? "undyed" : dye.getName());
        int before = problems.size();
        if (!ItemStack.matches(made, expected)) {
            problems.add(what + ": made " + made + " " + made.getTag() + ", expected " + expected.getTag());
        }
        if (!ItemStack.matches(bench.lastPreview, made)) {
            problems.add(what + ": the preview " + bench.lastPreview.getTag() + " disagreed with the result " + made.getTag());
        }
        if (MaskVariant.byStack(made).orElse(null) != variant) {
            problems.add(what + ": wrong style " + made);
        }
        if (made.getDamageValue() != 0 || made.getMaxDamage() != variant.durability() || made.isEnchantable()) {
            problems.add(what + ": damage " + made.getDamageValue() + "/" + made.getMaxDamage() + " enchantable=" + made.isEnchantable());
        }
        if (!made.getAttributeModifiers(EquipmentSlot.HEAD).equals(new ItemStack(item(variant)).getAttributeModifiers(EquipmentSlot.HEAD))) {
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
        if (!roundTrips(made)) {
            problems.add(what + ": did not survive NBT and network serialisation");
        }
        bench.clear();
        return problems.size() == before ? 1 : 0;
    }

    private void recolorMatrix() {
        Bench bench = new Bench(f.player("MaskRecolourer", 22.5, Y, 86.5), STATION);
        try {
            List<String> problems = new ArrayList<>();
            for (MaskVariant variant : MaskVariant.values()) {
                MaskMakingRecipe recipe = restyles.get(variant);
                for (DyeColor dye : MaskColors.STANDARD) {
                    ItemStack loved = loved(variant);
                    ItemStack result = bench.make(recipe, loved.copy(), binding(recipe), dyes(dye, 1));
                    String what = variant + " " + dye.getName();
                    if (!result.is(item(variant))) {
                        problems.add(what + ": recolouring made " + result);
                        bench.clear();
                        continue;
                    }
                    if (!MaskColors.standardOf(result).equals(Optional.of(dye))) {
                        problems.add(what + ": colour " + MaskColors.storedColor(result));
                    }
                    if (!withoutColor(result).equals(withoutColor(loved))) {
                        problems.add(what + ": data changed " + withoutColor(loved) + " -> " + withoutColor(result));
                    }
                    if (!bench.slot(0).isEmpty() || !bench.slot(1).isEmpty() || !bench.slot(2).isEmpty()) {
                        problems.add(what + ": inputs left " + bench.slot(0) + " " + bench.slot(1) + " " + bench.slot(2));
                    }
                    bench.clear();
                }
            }
            require(problems.isEmpty(), problems.size() + " problems, first: " + problems.subList(0, Math.min(6, problems.size())));
        } finally {
            bench.close();
        }
    }

    private void blending() {
        Bench bench = new Bench(f.player("MaskBlender", 24.5, Y, 86.5), STATION);
        try {
            MaskMakingRecipe recipe = restyles.get(MaskVariant.BANDANA);
            ItemStack mask = MaskColors.sample(item(MaskVariant.BANDANA), DyeColor.RED);
            for (DyeColor dye : List.of(DyeColor.BLUE, DyeColor.YELLOW, DyeColor.BLACK)) {
                ItemStack expected = DyeableLeatherItem.dyeArmor(mask.copy(), List.of(dyeItem(dye)));
                ItemStack result = bench.make(recipe, mask.copy(), binding(recipe), dyes(dye, 1));
                require(result.is(item(MaskVariant.BANDANA)), "blending " + dye.getName() + " made " + result);
                require(MaskColors.storedColor(result).equals(MaskColors.storedColor(expected)),
                        "blending " + dye.getName() + " stored " + MaskColors.storedColor(result) + ", leather gives " + MaskColors.storedColor(expected));
                MaskColors.standardOf(result).ifPresent(named -> require(MaskColors.standardColor(named) == MaskColors.storedColor(result).orElseThrow(),
                        "a blend was named " + named.getName()));
                out.info("masks: red bandana + " + dye.getName() + " -> " + MaskColors.hex(MaskColors.storedColor(result).orElseThrow())
                        + (MaskColors.standardOf(result).isPresent() ? " (standard)" : " (custom)"));
                bench.clear();
                mask = result;
            }
        } finally {
            bench.close();
        }
    }

    private void noOps() {
        Bench bench = new Bench(f.player("MaskNoOp", 26.5, Y, 86.5), STATION);
        try {
            record Case(String name, MaskVariant variant, ItemStack mask, ItemStack dye) {
            }
            List<Case> cases = List.of(
                    new Case("undyed with no dye", MaskVariant.BANDANA, new ItemStack(item(MaskVariant.BANDANA)), ItemStack.EMPTY),
                    new Case("white on white", MaskVariant.CLAY, MaskColors.sample(item(MaskVariant.CLAY), DyeColor.WHITE), dyes(DyeColor.WHITE, 1)),
                    new Case("black on black", MaskVariant.IRON_SKULL, MaskColors.sample(item(MaskVariant.IRON_SKULL), DyeColor.BLACK), dyes(DyeColor.BLACK, 1)),
                    new Case("blue with no dye", MaskVariant.OWL, MaskColors.sample(item(MaskVariant.OWL), DyeColor.BLUE), ItemStack.EMPTY));
            for (Case c : cases) {
                MaskMakingRecipe recipe = restyles.get(c.variant());
                ItemStack mask = c.mask().copy();
                ItemStack binding = binding(recipe);
                ItemStack dye = c.dye().copy();
                bench.inputs(mask.copy(), binding.copy(), dye.copy());
                require(!bench.select(recipe), c.name() + ": the no-op restyle was offered");
                require(MaskCustomization.wouldChange(mask, result(recipe),
                                dye.getItem() instanceof DyeItem chosen ? chosen : null) == MaskRestyleRejection.NO_CHANGE,
                        c.name() + ": the screen would not say it is unchanged");
                ItemStack taken = bench.take();
                require(taken.isEmpty(), c.name() + ": a click took " + taken);
                bench.vanilla.quickMoveStack(bench.player, MaskStationMenu.RESULT_SLOT);
                require(ItemStack.matches(bench.slot(0), mask) && ItemStack.matches(bench.slot(1), binding)
                        && ItemStack.matches(bench.slot(2), dye), c.name() + ": an input was consumed");
                require(bench.player.getInventory().countItem(item(c.variant())) == 0, c.name() + ": a mask was minted");
                bench.clear();
            }
        } finally {
            bench.close();
        }
    }

    private void whites() {
        Bench bench = new Bench(f.player("MaskWhites", 28.5, Y, 86.5), STATION);
        try {
            MaskMakingRecipe recipe = restyles.get(MaskVariant.HOCKEY);
            ItemStack undyed = new ItemStack(item(MaskVariant.HOCKEY));
            ItemStack customWhite = undyed.copy();
            ((DyeableLeatherItem) customWhite.getItem()).setColor(customWhite, 0xFFFFFF);
            require(!MaskColors.hasColor(undyed) && MaskColors.hasColor(customWhite), "undyed and custom white read alike");
            require(((DyeableLeatherItem) undyed.getItem()).getColor(undyed) == 0xFFFFFF, "an undyed mask is not untinted");

            ItemStack dyedWhite = bench.make(recipe, undyed.copy(), binding(recipe), dyes(DyeColor.WHITE, 1));
            require(dyedWhite.is(item(MaskVariant.HOCKEY)) && MaskColors.standardOf(dyedWhite).equals(Optional.of(DyeColor.WHITE)),
                    "white dye on an undyed mask made " + dyedWhite + " " + dyedWhite.getTag());
            bench.clear();

            bench.inputs(customWhite.copy(), binding(recipe), ItemStack.EMPTY);
            require(!bench.select(recipe), "custom white with no dye was offered as a change");
            bench.clear();

            ItemStack expected = DyeableLeatherItem.dyeArmor(customWhite.copy(), List.of(dyeItem(DyeColor.WHITE)));
            boolean changes = !MaskColors.storedColor(expected).equals(MaskColors.storedColor(customWhite));
            bench.inputs(customWhite.copy(), binding(recipe), dyes(DyeColor.WHITE, 1));
            require(bench.select(recipe) == changes, "white dye on custom white: offered=" + !changes + " but it changes=" + changes);
            if (changes) {
                ItemStack result = bench.take();
                require(MaskColors.storedColor(result).equals(MaskColors.storedColor(expected)), "custom white + white made " + result.getTag());
                require(MaskColors.standardOf(result).isEmpty() || MaskColors.storedColor(result).orElseThrow()
                        == MaskColors.standardColor(DyeColor.WHITE), "custom white + white was misnamed");
            }
            bench.clear();
            out.info("masks: undyed=" + MaskColors.storedColor(undyed) + " custom white=" + MaskColors.hex(0xFFFFFF)
                    + " white dye=" + MaskColors.hex(MaskColors.standardColor(DyeColor.WHITE)));
        } finally {
            bench.close();
        }
    }

    private void restyle() {
        Bench bench = new Bench(f.player("MaskRestyler", 30.5, Y, 86.5), STATION);
        try {
            ItemStack raven = loved(MaskVariant.RAVEN);
            ((DyeableLeatherItem) raven.getItem()).setColor(raven, MaskColors.standardColor(DyeColor.BLUE));
            MaskMakingRecipe toJackal = restyles.get(MaskVariant.JACKAL);
            ItemStack jackal = bench.make(toJackal, raven.copy(), binding(toJackal), ItemStack.EMPTY);
            require(jackal.is(item(MaskVariant.JACKAL)), "raven -> jackal made " + jackal);
            require(MaskColors.standardOf(jackal).equals(Optional.of(DyeColor.BLUE)), "the restyle lost the colour: " + jackal.getTag());
            require(withoutColor(jackal).equals(withoutColor(raven)), "the restyle changed data " + withoutColor(jackal));
            require(bench.slot(0).isEmpty(), "the source mask was not consumed");
            bench.clear();

            MaskMakingRecipe toStitched = restyles.get(MaskVariant.STITCHED);
            ItemStack stitched = bench.make(toStitched, jackal.copy(), binding(toStitched), dyes(DyeColor.RED, 1));
            ItemStack blend = DyeableLeatherItem.dyeArmor(jackal.copy(), List.of(dyeItem(DyeColor.RED)));
            require(stitched.is(item(MaskVariant.STITCHED)) && MaskColors.storedColor(stitched).equals(MaskColors.storedColor(blend)),
                    "restyle + dye made " + stitched.getTag());
            bench.clear();

            // The legacy id keeps its family: a Brigand Visor becomes a Balaclava (iron_skull_mask).
            ItemStack visor = MaskColors.sample(item(MaskVariant.BRIGAND_VISOR), DyeColor.GREEN);
            MaskMakingRecipe toBalaclava = restyles.get(MaskVariant.IRON_SKULL);
            ItemStack balaclava = bench.make(toBalaclava, visor, binding(toBalaclava), ItemStack.EMPTY);
            require(balaclava.is(item(MaskVariant.IRON_SKULL)) && MaskColors.standardOf(balaclava).equals(Optional.of(DyeColor.GREEN)),
                    "visor -> balaclava made " + balaclava + " " + balaclava.getTag());
            bench.clear();

            bench.inputs(MaskColors.sample(item(MaskVariant.BANDANA), DyeColor.RED), binding(toJackal), ItemStack.EMPTY);
            require(!bench.select(toJackal), "a cloth mask was offered a leather restyle");
            require(bench.menu.previewStack().isEmpty(), "a cross-family preview appeared");
            bench.clear();
        } finally {
            bench.close();
        }
    }

    private void unsupported() {
        Bench bench = new Bench(f.player("MaskUnsupported", 32.5, Y, 86.5), STATION);
        try {
            ItemStack capped = loved(MaskVariant.RAVEN);
            CompoundTag caps = new CompoundTag();
            caps.putString("somemod:attached", "data");
            capped.getOrCreateTag().put("ForgeCaps", caps);
            MaskMakingRecipe toJackal = restyles.get(MaskVariant.JACKAL);
            ItemStack binding = binding(toJackal);
            ItemStack dye = dyes(DyeColor.LIME, 1);
            bench.inputs(capped.copy(), binding.copy(), dye.copy());
            bench.select(toJackal);
            require(bench.menu.previewStack().isEmpty(), "an unsupported mask previewed " + bench.menu.previewStack());
            require(bench.menu.rejection() == MaskCraftRejection.RESTYLE_UNSUPPORTED, "the station said " + bench.menu.rejection());
            require(bench.take().isEmpty(), "an unsupported mask was restyled");
            require(ItemStack.matches(bench.slot(0), capped) && ItemStack.matches(bench.slot(1), binding)
                    && ItemStack.matches(bench.slot(2), dye), "an input was consumed");
            bench.clear();
        } finally {
            bench.close();
        }
    }

    // --- washing ---------------------------------------------------------------------------------------

    private InteractionResult wash(ServerPlayer player, ItemStack held) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        BlockState state = f.level.getBlockState(CAULDRON);
        return state.use(f.level, player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(CAULDRON), Direction.UP, CAULDRON, false));
    }

    private int cauldronLevel() {
        BlockState state = f.level.getBlockState(CAULDRON);
        return state.is(Blocks.WATER_CAULDRON) ? state.getValue(LayeredCauldronBlock.LEVEL) : 0;
    }

    private void washing() {
        ServerPlayer washer = f.player("MaskWasher", 28.5, Y, 90.5);
        try {
            f.level.setBlockAndUpdate(CAULDRON, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
            ItemStack owl = loved(MaskVariant.OWL);
            ((DyeableLeatherItem) owl.getItem()).setColor(owl, MaskColors.standardColor(DyeColor.BLUE));
            CompoundTag before = withoutColor(owl);
            int cleaned = washer.getStats().getValue(Stats.CUSTOM.get(Stats.CLEAN_ARMOR));

            InteractionResult first = wash(washer, owl);
            ItemStack washed = washer.getMainHandItem();
            require(first.consumesAction(), "washing a dyed mask returned " + first);
            require(!MaskColors.hasColor(washed), "the colour survived the wash: " + washed.getTag());
            require(withoutColor(washed).equals(before), "the wash changed more than the colour: " + washed.getTag());
            require(washed.getTag().getCompound("display").contains("Name") && washed.getTag().getCompound("display").contains("Lore"),
                    "the wash erased the display block");
            require(cauldronLevel() == 2, "the cauldron is at level " + cauldronLevel() + " after one wash");
            require(washer.getStats().getValue(Stats.CUSTOM.get(Stats.CLEAN_ARMOR)) == cleaned + 1, "the wash was not counted once");

            ItemStack again = washed.copy();
            InteractionResult second = wash(washer, again);
            require(!second.consumesAction(), "washing an undyed mask returned " + second);
            require(ItemStack.matches(washer.getMainHandItem(), washed), "washing an undyed mask changed it");
            require(cauldronLevel() == 2, "an undyed mask used water");

            InteractionResult fresh = wash(washer, new ItemStack(item(MaskVariant.BANDANA)));
            require(!fresh.consumesAction() && cauldronLevel() == 2, "a fresh undyed mask was washed");

            // Every style washes, not just the one above.
            f.level.setBlockAndUpdate(CAULDRON, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
            for (MaskVariant variant : MaskVariant.values()) {
                ItemStack dyed = MaskColors.sample(item(variant), DyeColor.PURPLE);
                if (cauldronLevel() == 0) {
                    f.level.setBlockAndUpdate(CAULDRON, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
                }
                // Vanilla's wash leaves an emptied display block behind, as it does on leather; what matters is
                // that nothing but the colour went and the mask draws as undyed again.
                require(wash(washer, dyed).consumesAction() && !MaskColors.hasColor(washer.getMainHandItem())
                        && washer.getMainHandItem().is(item(variant))
                        && withoutColor(washer.getMainHandItem()).equals(withoutColor(new ItemStack(item(variant)))),
                        variant + " did not wash clean: " + washer.getMainHandItem().getTag());
            }
        } finally {
            f.remove(washer);
        }
    }

    // --- legal state -----------------------------------------------------------------------------------

    private static String legalSnapshot(PlayerCrimeData data) {
        return "heat=" + data.getHeat() + " pending=" + data.getPendingMaskedHeat()
                + " pursuitUntil=" + data.getMaskedPursuitUntilTick();
    }

    private void legalState() {
        ServerPlayer outlaw = f.player("MaskOutlaw", 34.5, Y, 90.5);
        Bench bench = new Bench(outlaw, STATION);
        try {
            PlayerCrimeData data = CrimeCapabilities.get(outlaw).orElseThrow();
            long now = f.level.getGameTime();
            data.setHeat(23);
            data.getPendingMaskedHeat().add(new PlayerCrimeData.PendingMaskedHeat(
                    new ResourceLocation("mcacrime", "assault"), "usability-mask", 9, now));
            data.setMaskedPursuitUntilTick(now + 20_000);
            outlaw.setItemSlot(EquipmentSlot.HEAD, MaskColors.sample(item(MaskVariant.HALF_VEIL), DyeColor.GRAY));
            String before = legalSnapshot(data);

            MaskMakingRecipe make = crafts.get(MaskVariant.HIGHWAYMANS_DOMINO);
            ItemStack domino = bench.make(make, material(make), binding(make), dyes(DyeColor.CYAN, 1));
            bench.clear();
            MaskMakingRecipe recolour = restyles.get(MaskVariant.HIGHWAYMANS_DOMINO);
            ItemStack recoloured = bench.make(recolour, domino, binding(recolour), dyes(DyeColor.ORANGE, 1));
            bench.clear();
            MaskMakingRecipe toScarf = restyles.get(MaskVariant.WRAPPED_SCARF);
            ItemStack scarf = bench.make(toScarf, recoloured, binding(toScarf), ItemStack.EMPTY);
            bench.clear();
            require(scarf.is(item(MaskVariant.WRAPPED_SCARF)), "the chain stopped at " + scarf);
            f.level.setBlockAndUpdate(CAULDRON, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
            wash(outlaw, scarf);
            require(!MaskColors.hasColor(outlaw.getMainHandItem()), "the scarf did not wash");

            require(legalSnapshot(data).equals(before), "legal state moved: " + before + " -> " + legalSnapshot(data));
            require(outlaw.getItemBySlot(EquipmentSlot.HEAD).is(item(MaskVariant.HALF_VEIL)), "the worn mask was touched");
        } finally {
            bench.close();
        }
    }

    // --- the menu --------------------------------------------------------------------------------------

    private void twoPlayers() {
        Bench first = new Bench(f.player("StationA", 20.5, Y, 90.5), STATION);
        Bench second = new Bench(f.player("StationB", 21.5, Y, 90.5), STATION);
        try {
            MaskMakingRecipe bandana = crafts.get(MaskVariant.BANDANA);
            MaskMakingRecipe owl = crafts.get(MaskVariant.OWL);
            first.inputs(material(bandana), binding(bandana), dyes(DyeColor.RED, 1));
            require(second.slot(0).isEmpty() && second.menu.previewStack().isEmpty(), "the second menu saw the first's inputs");
            second.inputs(material(owl), binding(owl), dyes(DyeColor.BLUE, 1));
            require(first.select(bandana) && second.select(owl), "a selection was refused");
            require(ItemStack.matches(first.menu.previewStack(), MaskColors.sample(item(MaskVariant.BANDANA), DyeColor.RED)),
                    "the first preview is " + first.menu.previewStack());
            ItemStack fromSecond = second.take();
            require(ItemStack.matches(fromSecond, MaskColors.sample(item(MaskVariant.OWL), DyeColor.BLUE)), "the second player got " + fromSecond);
            require(ItemStack.matches(first.menu.previewStack(), MaskColors.sample(item(MaskVariant.BANDANA), DyeColor.RED))
                    && !first.slot(0).isEmpty() && !first.slot(2).isEmpty(), "the second craft touched the first menu");
            ItemStack fromFirst = first.take();
            require(ItemStack.matches(fromFirst, MaskColors.sample(item(MaskVariant.BANDANA), DyeColor.RED)), "the first player got " + fromFirst);
            require(first.slot(0).isEmpty() && second.slot(0).isEmpty(), "inputs were not each spent once");
        } finally {
            first.close();
            second.close();
        }
    }

    private void previewFollows() {
        Bench bench = new Bench(f.player("MaskPreviewer", 22.5, Y, 90.5), STATION);
        try {
            MaskMakingRecipe bandana = crafts.get(MaskVariant.BANDANA);
            MaskMakingRecipe veil = crafts.get(MaskVariant.HALF_VEIL);
            ItemStack material = material(bandana);
            material.setCount(Math.max(material.getCount(), veil.materialCount()));
            bench.inputs(material, binding(bandana), ItemStack.EMPTY);
            require(bench.select(bandana), "the bandana was not offered");
            require(ItemStack.matches(bench.menu.previewStack(), new ItemStack(item(MaskVariant.BANDANA))), "undyed preview " + bench.menu.previewStack());
            bench.vanilla.getSlot(MaskStationMenu.DYE_SLOT).set(dyes(DyeColor.LIME, 1));
            require(ItemStack.matches(bench.menu.previewStack(), MaskColors.sample(item(MaskVariant.BANDANA), DyeColor.LIME)),
                    "adding lime previewed " + bench.menu.previewStack().getTag());
            bench.vanilla.getSlot(MaskStationMenu.DYE_SLOT).set(dyes(DyeColor.PURPLE, 1));
            require(ItemStack.matches(bench.menu.previewStack(), MaskColors.sample(item(MaskVariant.BANDANA), DyeColor.PURPLE)),
                    "switching to purple previewed " + bench.menu.previewStack().getTag());
            bench.vanilla.getSlot(MaskStationMenu.DYE_SLOT).set(ItemStack.EMPTY);
            require(ItemStack.matches(bench.menu.previewStack(), new ItemStack(item(MaskVariant.BANDANA))), "removing the dye left " + bench.menu.previewStack().getTag());
            require(bench.select(veil), "the veil was not offered");
            require(bench.menu.previewStack().is(item(MaskVariant.HALF_VEIL)), "switching styles previewed " + bench.menu.previewStack());
            bench.vanilla.getSlot(MaskStationMenu.BINDING_SLOT).set(ItemStack.EMPTY);
            require(bench.menu.previewStack().isEmpty() && bench.menu.selected() == null
                    && bench.menu.rejection() == MaskCraftRejection.NO_SELECTION, "removing the binding left a preview or a selection");
            require(bench.take().isEmpty(), "a craft was taken without its binding");
        } finally {
            bench.close();
        }
    }

    private void shiftClick() {
        Bench bench = new Bench(f.player("MaskBatcher", 24.5, Y, 90.5), STATION);
        try {
            ServerPlayer player = bench.player;
            player.getInventory().clearContent();
            MaskMakingRecipe clay = crafts.get(MaskVariant.CLAY);
            ItemStack material = material(clay);
            material.setCount(clay.materialCount() * 3);
            ItemStack binding = binding(clay);
            binding.setCount(clay.bindingCount() * 3);
            bench.inputs(material, binding, dyes(DyeColor.MAGENTA, 2));
            require(bench.select(clay), "clay was not offered");
            bench.vanilla.quickMoveStack(player, MaskStationMenu.RESULT_SLOT);
            List<ItemStack> masks = new ArrayList<>();
            for (ItemStack stack : player.getInventory().items) {
                if (stack.is(item(MaskVariant.CLAY))) masks.add(stack);
            }
            require(masks.size() == 2 && masks.stream().allMatch(m -> MaskColors.standardOf(m).equals(Optional.of(DyeColor.MAGENTA))),
                    "two dyes made " + masks);
            require(bench.slot(2).isEmpty() && bench.slot(0).getCount() == clay.materialCount()
                    && bench.slot(1).getCount() == clay.bindingCount(), "the batch spent " + bench.slot(0) + " " + bench.slot(1) + " " + bench.slot(2));
            bench.vanilla.quickMoveStack(player, MaskStationMenu.RESULT_SLOT);
            int now = player.getInventory().countItem(item(MaskVariant.CLAY));
            require(now == 3 && bench.slot(0).isEmpty(), "the last, undyed craft gave " + now + " masks");

            for (int i = 0; i < player.getInventory().items.size(); i++) {
                player.getInventory().items.set(i, new ItemStack(Items.DIRT, 64));
            }
            ItemStack lastMaterial = material(clay);
            ItemStack lastBinding = binding(clay);
            bench.inputs(lastMaterial.copy(), lastBinding.copy(), dyes(DyeColor.MAGENTA, 1));
            require(bench.select(clay), "clay was not offered into a full inventory");
            bench.vanilla.quickMoveStack(player, MaskStationMenu.RESULT_SLOT);
            require(ItemStack.matches(bench.slot(0), lastMaterial) && ItemStack.matches(bench.slot(1), lastBinding)
                    && bench.slot(2).getCount() == 1, "a full inventory consumed inputs");
            bench.clear();
            player.getInventory().clearContent();
        } finally {
            bench.close();
        }
    }

    // --- the creative catalogue --------------------------------------------------------------------------

    private void creativeTab() {
        CreativeModeTabs.tryRebuildTabContents(f.level.enabledFeatures(), true, f.level.registryAccess());
        List<ItemStack> shown = List.copyOf(CrimeItems.MASKS_TAB.get().getDisplayItems());
        List<ItemStack> expected = MaskColors.catalogue(this::item);
        require(shown.size() == 272, "the Masks tab holds " + shown.size() + " stacks");
        for (int i = 0; i < expected.size(); i++) {
            require(ItemStack.matches(shown.get(i), expected.get(i)), "stack " + i + " is " + shown.get(i) + " " + shown.get(i).getTag());
        }
        require(CrimeItems.MASKS_TAB.get().getSearchTabDisplayItems().size() == 272, "creative search sees "
                + CrimeItems.MASKS_TAB.get().getSearchTabDisplayItems().size() + " mask stacks");
        Collection<ItemStack> crime = CrimeItems.TAB.get().getDisplayItems();
        for (MaskVariant variant : MaskVariant.values()) {
            require(crime.stream().anyMatch(stack -> stack.is(item(variant)) && !MaskColors.hasColor(stack)),
                    "the Crime tab lost the original " + variant);
        }
        for (ItemStack stack : shown) {
            require(roundTrips(stack), stack + " does not survive serialisation");
        }
    }

    // --- restart -----------------------------------------------------------------------------------------

    /** Edge cases that must come back from disk exactly: wear, names, lore, curses, custom and blended colours. */
    private List<ItemStack> edgeCases() {
        List<ItemStack> cases = new ArrayList<>();
        ItemStack lovedCustom = loved(MaskVariant.RAVEN);
        ((DyeableLeatherItem) lovedCustom.getItem()).setColor(lovedCustom, 0x123456);
        cases.add(lovedCustom);
        ItemStack customWhite = new ItemStack(item(MaskVariant.HOCKEY));
        ((DyeableLeatherItem) customWhite.getItem()).setColor(customWhite, 0xFFFFFF);
        cases.add(customWhite);
        cases.add(DyeableLeatherItem.dyeArmor(MaskColors.sample(item(MaskVariant.BANDANA), DyeColor.RED), List.of(dyeItem(DyeColor.BLUE))));
        cases.add(loved(MaskVariant.BLANK_IRON));
        return cases;
    }

    private List<ItemStack> kept() {
        List<ItemStack> all = new ArrayList<>(MaskColors.catalogue(this::item));
        all.addAll(edgeCases());
        return all;
    }

    private BlockPos chest(int index) {
        return new BlockPos(16 + 2 * index, Y, 108);
    }

    private void restartSetup() {
        List<ItemStack> all = kept();
        require(all.size() <= CHESTS * 27, "too many stacks for the chests");
        for (int c = 0; c < CHESTS; c++) {
            f.level.setBlockAndUpdate(chest(c), Blocks.CHEST.defaultBlockState());
        }
        for (int i = 0; i < all.size(); i++) {
            ChestBlockEntity chest = (ChestBlockEntity) f.level.getBlockEntity(chest(i / 27));
            chest.setItem(i % 27, all.get(i).copy());
            chest.setChanged();
        }
        out.info("masks: stored " + all.size() + " stacks for the restart");
    }

    private void restartVerify() {
        List<ItemStack> expected = kept();
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < expected.size(); i++) {
            var entity = f.level.getBlockEntity(chest(i / 27));
            require(entity instanceof ChestBlockEntity, "chest " + (i / 27) + " did not load");
            ItemStack back = ((ChestBlockEntity) entity).getItem(i % 27);
            if (!ItemStack.matches(back, expected.get(i))) {
                problems.add(i + ": " + back + " " + back.getTag() + " expected " + expected.get(i).getTag());
            }
        }
        require(problems.isEmpty(), problems.size() + " stacks changed, first: " + problems.subList(0, Math.min(4, problems.size())));
        out.info("masks restart: " + expected.size() + " stacks compared");
    }

    // --- reload ------------------------------------------------------------------------------------------

    private void staleSelectionAfterReload() {
        try {
            startReload();
        } catch (Throwable failure) {
            out.check("masks: /reload while a station is open drops the stale selection and the station works again", () -> {
                throw new AssertionError("setup failed: " + failure, failure);
            });
        }
    }

    private void startReload() {
        ServerPlayer viewer = f.player("MaskReloader", 26.5, Y, 90.5);
        Bench bench = new Bench(viewer, SECOND_STATION);
        MaskMakingRecipe clay = crafts.get(MaskVariant.CLAY);
        if (clay == null) {
            out.check("masks: /reload while a station is open drops the stale selection and the station works again",
                    () -> require(false, "no clay recipe indexed"));
            bench.close();
            return;
        }
        bench.inputs(material(clay), binding(clay), dyes(DyeColor.BLUE, 4));
        boolean selected = bench.select(clay);
        Object before = f.server.getRecipeManager();
        int generation = bench.menu.recipeGeneration();
        f.runConsole(new Fixtures.Console(), "reload");
        out.await("masks: /reload while a station is open drops the stale selection and the station works again", 1200, () -> {
            if (!selected) return "the clay mask was not offered before the reload";
            if (f.server.getRecipeManager() == before) return "the reload has not finished";
            bench.vanilla.broadcastChanges();
            if (bench.menu.recipeGeneration() == generation) return "the recipe generation did not move";
            if (bench.menu.selected() != null) return "the stale selection survived: " + bench.menu.selected();
            if (!bench.menu.previewStack().isEmpty()) return "the stale preview survived";
            MaskMakingRecipe fresh = f.level.getRecipeManager().getAllRecipesFor(CrimeRecipes.MASK_MAKING.get()).stream()
                    .filter(recipe -> id(recipe).equals(id(clay))).findFirst().orElse(null);
            if (fresh == null) return "the clay recipe is gone after the reload";
            if (!bench.select(fresh)) return "the reloaded recipe could not be selected";
            ItemStack made = bench.take();
            if (!ItemStack.matches(made, MaskColors.sample(item(MaskVariant.CLAY), DyeColor.BLUE))) return "after the reload the station made " + made;
            return null;
        }, bench::close);
    }

    // --- fixtures ----------------------------------------------------------------------------------------

    private Item item(MaskVariant variant) {
        return CrimeItems.MASKS.get(variant).get();
    }

    /** Vanilla's {@code Recipe} members, through the vanilla type for the same reobfuscation reason as {@link Bench#vanilla}. */
    private static ResourceLocation id(MaskMakingRecipe recipe) {
        return ((Recipe<?>) recipe).getId();
    }

    private ItemStack result(MaskMakingRecipe recipe) {
        return ((Recipe<?>) recipe).getResultItem(f.level.registryAccess());
    }

    private static DyeItem dyeItem(DyeColor dye) {
        return DyeItem.byColor(dye);
    }

    private static ItemStack dyes(DyeColor dye, int count) {
        return new ItemStack(DyeItem.byColor(dye), count);
    }

    private static ItemStack material(MaskMakingRecipe recipe) {
        ItemStack stack = recipe.material().getItems()[0].copy();
        stack.setCount(recipe.materialCount());
        return stack;
    }

    private static ItemStack binding(MaskMakingRecipe recipe) {
        ItemStack stack = recipe.binding().getItems()[0].copy();
        stack.setCount(recipe.bindingCount());
        return stack;
    }

    /** A well-used mask with a history: wear, a name, lore, a curse, this mod's provenance and a foreign flag. */
    private ItemStack loved(MaskVariant variant) {
        ItemStack stack = new ItemStack(item(variant));
        stack.setDamageValue(Math.min(13, variant.durability() - 1));
        stack.setHoverName(Component.literal("The Old Face"));
        ListTag lore = new ListTag();
        lore.add(StringTag.valueOf("{\"text\":\"Taken from a guard\"}"));
        stack.getOrCreateTagElement("display").put("Lore", lore);
        ListTag enchantments = new ListTag();
        CompoundTag curse = new CompoundTag();
        curse.putString("id", "minecraft:binding_curse");
        curse.putShort("lvl", (short) 1);
        enchantments.add(curse);
        stack.getOrCreateTag().put("Enchantments", enchantments);
        stack.getOrCreateTagElement("mcacrime").putString("provenance", "usability-91");
        stack.getOrCreateTag().putString("othermod:soulbound", "player-a");
        return stack;
    }

    /** The whole root tag with only {@code display.color} taken out (and an emptied display block with it). */
    private static CompoundTag withoutColor(ItemStack stack) {
        CompoundTag tag = stack.getTag() == null ? new CompoundTag() : stack.getTag().copy();
        if (tag.contains("display")) {
            CompoundTag display = tag.getCompound("display");
            display.remove("color");
            if (display.isEmpty()) {
                tag.remove("display");
            }
        }
        return tag;
    }

    /** Through NBT (a save) and through the wire format (a creative pick, a slot update). */
    private static boolean roundTrips(ItemStack stack) {
        ItemStack saved = ItemStack.of(stack.save(new CompoundTag()));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeItem(stack);
            ItemStack wire = buffer.readItem();
            return ItemStack.matches(stack, saved) && ItemStack.matches(stack, wire);
        } finally {
            buffer.release();
        }
    }

    /** One player's open Mask Station menu, driven through its slots, its selection handler and its result slot. */
    private final class Bench {
        final ServerPlayer player;
        final MaskStationMenu menu;
        /**
         * The same menu typed as vanilla's. Every inherited or overridden vanilla member is called through
         * this: the harness jar is reobfuscated without this mod's class hierarchy, so a call whose owner is
         * {@code MaskStationMenu} would keep its development name and miss the production method.
         */
        final AbstractContainerMenu vanilla;
        ItemStack lastPreview = ItemStack.EMPTY;

        Bench(ServerPlayer player, BlockPos station) {
            this.player = player;
            this.menu = new MaskStationMenu(nextMenuId++, player.getInventory(), ContainerLevelAccess.create(f.level, station), station);
            this.vanilla = menu;
        }

        void inputs(ItemStack material, ItemStack binding, ItemStack dye) {
            vanilla.getSlot(MaskStationMenu.MATERIAL_SLOT).set(material);
            vanilla.getSlot(MaskStationMenu.BINDING_SLOT).set(binding);
            vanilla.getSlot(MaskStationMenu.DYE_SLOT).set(dye);
        }

        boolean select(MaskMakingRecipe recipe) {
            return menu.select(player, vanilla.containerId, id(recipe), menu.recipeGeneration()).accepted();
        }

        /** Picks the result up with an ordinary left click, as a client's click packet would. */
        ItemStack take() {
            vanilla.clicked(MaskStationMenu.RESULT_SLOT, 0, ClickType.PICKUP, player);
            ItemStack taken = vanilla.getCarried();
            vanilla.setCarried(ItemStack.EMPTY);
            return taken;
        }

        /** Inputs, a selection, the preview recorded, and one craft taken. Empty when the style was refused. */
        ItemStack make(MaskMakingRecipe recipe, ItemStack material, ItemStack binding, ItemStack dye) {
            inputs(material, binding, dye);
            lastPreview = ItemStack.EMPTY;
            if (!select(recipe)) {
                return ItemStack.EMPTY;
            }
            lastPreview = menu.previewStack().copy();
            return take();
        }

        ItemStack slot(int index) {
            return vanilla.getSlot(index).getItem();
        }

        void clear() {
            for (int slot = 0; slot < 3; slot++) {
                vanilla.getSlot(slot).set(ItemStack.EMPTY);
            }
        }

        void close() {
            clear();
            vanilla.removed(player);
            f.remove(player);
        }
    }
}
