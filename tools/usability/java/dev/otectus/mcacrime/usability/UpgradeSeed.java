package dev.otectus.mcacrime.usability;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.block.PilloryBlock;
import dev.otectus.mcacrime.block.entity.GuillotineBlockEntity;
import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detention.DetentionService;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.item.MaskVariant;
import dev.otectus.mcacrime.item.lock.BakedKeyMoldItem;
import dev.otectus.mcacrime.item.lock.KeyItem;
import dev.otectus.mcacrime.ledger.CapitalSentenceService;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.ledger.SentenceAssignmentService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.UUID;

import static dev.otectus.mcacrime.usability.UsabilityRuntimeChecks.require;

/**
 * The upgrade test's first half (0.8.0 runtime scenario 5), run against the <em>previous</em> release.
 *
 * <p>Every call here exists, with the same signature, in 0.7.9 -- checked against the 0.7.9 jar -- so this
 * phase runs on the old mod and leaves what an existing world holds: two locks with bound keys (one renamed),
 * a key ring of both, a raw and a baked mold, masks undyed, dyed, named and custom-coloured, an armour stand
 * wearing one, and a condemned villager locked in a pillory under a guillotine whose blade is mid-fall when
 * the server stops. {@link UpgradeVerify} reopens the same world on 0.8.0.
 */
final class UpgradeSeed {

    static final Path STATE = Path.of("usability-upgrade.properties");
    static final int Y = 100;
    static final BlockPos SAFE_A = new BlockPos(20, Y, 54);
    static final BlockPos SAFE_B = new BlockPos(24, Y, 54);
    static final BlockPos KEY_CHEST = new BlockPos(28, Y, 54);
    static final BlockPos MASK_CHEST = new BlockPos(32, Y, 54);
    static final UUID GUARD = UUID.nameUUIDFromBytes("usability:upgrade-guard".getBytes());

    private final Fixtures f;
    private final UsabilityRuntimeChecks out;
    private final CrimeWorldData data;
    private final Properties state = new Properties();

    UpgradeSeed(Fixtures fixtures, UsabilityRuntimeChecks out) {
        this.f = fixtures;
        this.out = out;
        this.data = CrimeWorldData.get(fixtures.server);
    }

    void run() {
        f.arena(16, 60, 50, 80, Y);
        f.arena(64, 120, 12, 44, Y);
        out.check("upgrade seed: two locks, their keys, a ring, a raw and a baked mold, stored by the old release", this::keys);
        out.check("upgrade seed: masks undyed, dyed, named and custom-coloured, and an armour stand wearing one", this::masks);
        out.check("upgrade seed: a condemned villager locked in a pillory under a guillotine", this::device);
        out.atEnd(this::armAndStop);
    }

    // --- keys ----------------------------------------------------------------------------------------

    private void use(ServerPlayer player, BlockPos pos, Direction face, ItemStack held) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        player.gameMode.useItemOn(player, f.level, held, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5D), face, pos, false));
    }

    private static CraftingContainer grid(ItemStack... stacks) {
        AbstractContainerMenu dummy = new AbstractContainerMenu(null, -1) {
            @Override
            public ItemStack quickMoveStack(Player player, int index) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean stillValid(Player player) {
                return true;
            }
        };
        CraftingContainer grid = new TransientCraftingContainer(dummy, 3, 3);
        for (int i = 0; i < stacks.length; i++) {
            grid.setItem(i, stacks[i]);
        }
        return grid;
    }

    private ItemStack craft(CraftingContainer grid) {
        return f.server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, f.level)
                .map(recipe -> recipe.assemble(grid, f.level.registryAccess())).orElse(ItemStack.EMPTY);
    }

    private ItemStack bind(ServerPlayer owner, BlockPos safe) {
        f.level.setBlockAndUpdate(safe, CrimeBlocks.SAFE.get().defaultBlockState());
        ItemStack key = new ItemStack(CrimeItems.KEY.get());
        use(owner, safe, Direction.UP, key);
        require(KeyItem.binding(key).isPresent(), "the blank key was not bound: " + f.toldTo(owner));
        return key;
    }

    private void keys() throws Exception {
        ServerPlayer owner = f.player("UpgradeOwner", 22.5, Y, 52.5);
        try {
            ItemStack keyA = bind(owner, SAFE_A);
            keyA.setHoverName(Component.literal("Vault key"));
            ItemStack keyB = bind(owner, SAFE_B);
            ItemStack ring = craft(grid(keyA.copy(), keyB.copy()));
            require(!ring.isEmpty(), "the old release would not make a ring");
            ItemStack raw = craft(grid(keyA.copy(), new ItemStack(Items.CLAY_BALL)));
            require(!raw.isEmpty(), "the old release would not take an impression");
            ItemStack baked = BakedKeyMoldItem.fromRawMold(raw);
            f.level.setBlockAndUpdate(KEY_CHEST, Blocks.CHEST.defaultBlockState());
            ChestBlockEntity chest = (ChestBlockEntity) f.level.getBlockEntity(KEY_CHEST);
            List<ItemStack> stored = List.of(keyA, keyB, ring, raw, baked);
            for (int i = 0; i < stored.size(); i++) {
                chest.setItem(i, stored.get(i).copy());
                state.setProperty("key.stack." + i, stored.get(i).save(new CompoundTag()).toString());
            }
            chest.setChanged();
            state.setProperty("key.bindingA", KeyItem.binding(keyA).orElseThrow().save().toString());
            state.setProperty("key.bindingB", KeyItem.binding(keyB).orElseThrow().save().toString());
            save();
        } finally {
            f.remove(owner);
        }
    }

    // --- masks ---------------------------------------------------------------------------------------

    private static Item mask(MaskVariant variant) {
        return CrimeItems.MASKS.get(variant).get();
    }

    private static ItemStack dyed(MaskVariant variant, DyeColor... dyes) {
        List<DyeItem> items = new ArrayList<>();
        for (DyeColor dye : dyes) items.add(DyeItem.byColor(dye));
        return DyeableLeatherItem.dyeArmor(new ItemStack(mask(variant)), items);
    }

    private void masks() throws Exception {
        List<ItemStack> masks = new ArrayList<>();
        masks.add(new ItemStack(mask(MaskVariant.BANDANA)));
        masks.add(dyed(MaskVariant.RAVEN, DyeColor.BLUE));
        masks.add(dyed(MaskVariant.IRON_SKULL, DyeColor.BLACK));
        masks.add(dyed(MaskVariant.HOCKEY, DyeColor.RED, DyeColor.YELLOW));
        ItemStack loved = dyed(MaskVariant.OWL, DyeColor.LIME);
        loved.setDamageValue(9);
        loved.setHoverName(Component.literal("The Old Face"));
        ListTag lore = new ListTag();
        lore.add(StringTag.valueOf("{\"text\":\"Taken from a guard\"}"));
        loved.getOrCreateTagElement("display").put("Lore", lore);
        masks.add(loved);
        ItemStack custom = new ItemStack(mask(MaskVariant.CLAY));
        ((DyeableLeatherItem) custom.getItem()).setColor(custom, 0x123456);
        masks.add(custom);
        f.level.setBlockAndUpdate(MASK_CHEST, Blocks.CHEST.defaultBlockState());
        ChestBlockEntity chest = (ChestBlockEntity) f.level.getBlockEntity(MASK_CHEST);
        for (int i = 0; i < masks.size(); i++) {
            chest.setItem(i, masks.get(i).copy());
            state.setProperty("mask.stack." + i, masks.get(i).save(new CompoundTag()).toString());
        }
        chest.setChanged();
        state.setProperty("mask.count", Integer.toString(masks.size()));

        ArmorStand stand = new ArmorStand(f.level, 36.5, Y, 54.5);
        ItemStack worn = dyed(MaskVariant.JACKAL, DyeColor.ORANGE);
        stand.setItemSlot(EquipmentSlot.HEAD, worn);
        stand.setNoGravity(true);
        f.level.addFreshEntity(stand);
        state.setProperty("mask.stand", stand.getStringUUID());
        state.setProperty("mask.stand.head", worn.save(new CompoundTag()).toString());
        save();
    }

    // --- the occupied device -------------------------------------------------------------------------

    private ServerPlayer executioner;
    private BlockPos lower;

    private void device() throws Exception {
        executioner = f.player("UpgradeExecutioner", 116.5, Y, 30.5);
        executioner.setYRot(180.0F);
        executioner.setXRot(0.0F);
        BlockPos floor = new BlockPos(116, Y - 1, 28);
        use(executioner, floor, Direction.UP, new ItemStack(CrimeItems.PILLORY.get()));
        lower = floor.above();
        use(executioner, lower.above(), Direction.UP, new ItemStack(CrimeItems.GUILLOTINE.get()));
        require(f.level.getBlockEntity(lower.above(2)) instanceof GuillotineBlockEntity, "the guillotine was not placed");
        BlockState pillory = f.level.getBlockState(lower);
        Vec3 spot = PilloryBlock.standingPosition(pillory.getValue(PilloryBlock.FACING), lower);
        LivingEntity subject = f.mca(spot.x, spot.y, spot.z, false);
        UUID id = subject.getUUID();
        require(CustodyService.captureLawful(data, id, false, CustodyOwner.guard(GUARD), f.level.getGameTime(),
                BlockPos.ZERO, f.level.dimension().location()).ok(), "lawful custody");
        CrimeRecord killing = new CrimeRecord(UUID.randomUUID(), id, null, CrimeIds.KILL_GUARD, OptionalInt.empty(),
                true, f.level.getGameTime(), 60L, -80L, 0L, 1200L, Resolution.UNRESOLVED);
        data.addRecord(killing);
        UUID sentence = UUID.randomUUID();
        require(SentenceAssignmentService.assign(data, id, sentence, List.of(killing.id()), f.level.getGameTime()),
                "sentence binding");
        CapitalSentenceService.mark(data, id, sentence, false);
        require(CapitalSentenceService.condemned(f.server, id), "the subject is not condemned");
        use(executioner, lower.above(), Direction.NORTH, ItemStack.EMPTY);
        require(DetentionService.forSubject(data, id).isPresent(), "the pillory did not hold the subject: " + f.toldTo(executioner));
        state.setProperty("device.subject", id.toString());
        state.setProperty("device.record", killing.id().toString());
        state.setProperty("device.sentence", sentence.toString());
        state.setProperty("device.lower", lower.getX() + "," + lower.getY() + "," + lower.getZ());
        save();
    }

    /** The last step: the blade is released with a long fall, so the old order is mid-flight when the server stops. */
    private void armAndStop() {
        if (executioner == null || lower == null) {
            out.results.add("FAIL upgrade seed: the device was never set up");
            return;
        }
        var bladeTicks = McaCrimeConfig.COMMON.guillotineActivationDelayTicks;
        int before = bladeTicks.get();
        out.check("upgrade seed: the blade is released and still falling when the old server stops", () -> {
            bladeTicks.set(200);
            use(executioner, lower.above(2), Direction.UP, ItemStack.EMPTY);
            bladeTicks.set(before);
            GuillotineBlockEntity blade = (GuillotineBlockEntity) f.level.getBlockEntity(lower.above(2));
            require(blade != null && blade.chopDelay() > 150, "the blade is not falling: " + (blade == null ? "gone" : blade.chopDelay()));
            out.info("upgrade seed: blade falling, chopDelay=" + blade.chopDelay());
        });
        f.remove(executioner);
    }

    private void save() throws Exception {
        try (var writer = Files.newBufferedWriter(STATE)) {
            state.store(writer, "upgrade fixture written by the previous release");
        }
    }
}
