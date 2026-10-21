package dev.otectus.mcacrime.usability;

import dev.otectus.mcacrime.block.entity.GuillotineBlockEntity;
import dev.otectus.mcacrime.detention.DetentionService;
import dev.otectus.mcacrime.detention.ExecutionAuthorization;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.item.lock.BakedKeyMoldItem;
import dev.otectus.mcacrime.item.lock.KeyItem;
import dev.otectus.mcacrime.item.lock.KeyMoldItem;
import dev.otectus.mcacrime.item.lock.KeyRingItem;
import dev.otectus.mcacrime.ledger.CapitalSentenceService;
import dev.otectus.mcacrime.locks.KeyBinding;
import dev.otectus.mcacrime.locks.KeyRingBindings;
import dev.otectus.mcacrime.locks.LockHolder;
import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.mask.MaskColors;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static dev.otectus.mcacrime.usability.UsabilityRuntimeChecks.require;

/**
 * The upgrade test's second half (0.8.0 runtime scenario 5): the world {@link UpgradeSeed} left with the
 * previous release, reopened on 0.8.0. Every key, ring and mold keeps its identity and still works the
 * locks (including through 0.8.0's new blank-mold impression), every mask keeps its data and reads as the
 * colour it was dyed, and the occupied device keeps its prisoner, custody and sentence while the old,
 * transient execution order is not resumed.
 */
final class UpgradeVerify {

    private final Fixtures f;
    private final UsabilityRuntimeChecks out;
    private final CrimeWorldData data;
    private final Properties state = new Properties();

    UpgradeVerify(Fixtures fixtures, UsabilityRuntimeChecks out) {
        this.f = fixtures;
        this.out = out;
        this.data = CrimeWorldData.get(fixtures.server);
    }

    void run() {
        try (var reader = Files.newBufferedReader(UpgradeSeed.STATE)) {
            state.load(reader);
        } catch (Exception missing) {
            out.results.add("FAIL upgrade: the previous release left no fixture: " + missing);
            return;
        }
        out.check("upgrade: no execution order survived the restart into the new release", () ->
                require(ExecutionAuthorization.all().isEmpty(), "orders after the upgrade: " + ExecutionAuthorization.all()));
        out.check("upgrade: keys, the ring and both molds kept their bindings and data exactly", this::keysKept);
        out.check("upgrade: old keys, ring entries and molds still work the locks under 0.8.0's recipes", this::keysWork);
        out.check("upgrade: every stored mask kept its data and reads as the colour it was dyed", this::masks);
        device();
        stand();
    }

    // --- keys ----------------------------------------------------------------------------------------

    private ChestBlockEntity chest(BlockPos pos) {
        require(f.level.getBlockEntity(pos) instanceof ChestBlockEntity, "the chest at " + pos.toShortString() + " did not load");
        return (ChestBlockEntity) f.level.getBlockEntity(pos);
    }

    private static ItemStack stack(String snbt) throws Exception {
        return ItemStack.of(TagParser.parseTag(snbt));
    }

    private Optional<LockRecord> lockAt(BlockPos pos) {
        return f.level.getBlockEntity(pos) instanceof LockHolder holder ? holder.lockId().map(data::lock) : Optional.empty();
    }

    private void keysKept() throws Exception {
        ChestBlockEntity chest = chest(UpgradeSeed.KEY_CHEST);
        for (int i = 0; i < 5; i++) {
            ItemStack expected = stack(state.getProperty("key.stack." + i));
            ItemStack back = chest.getItem(i);
            require(ItemStack.matches(back, expected), "key-chest slot " + i + " changed: " + back.getTag() + " vs " + expected.getTag());
        }
        ItemStack keyA = chest.getItem(0);
        require(KeyItem.binding(keyA).orElseThrow().save().toString().equals(state.getProperty("key.bindingA")), "key A's binding changed");
        require(keyA.getHoverName().getString().equals("Vault key"), "key A lost its name");
        require(KeyRingItem.count(chest.getItem(2)) == 2, "the ring holds " + KeyRingItem.count(chest.getItem(2)));
        require(KeyMoldItem.recorded(chest.getItem(3)).map(b -> b.save().toString()).orElse("").equals(state.getProperty("key.bindingA")),
                "the raw mold lost its impression");
        require(BakedKeyMoldItem.quality(chest.getItem(4)) == BakedKeyMoldItem.STARTING_QUALITY
                && KeyMoldItem.recorded(chest.getItem(4)).isPresent(), "the baked mold changed");
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

    private void keysWork() {
        ChestBlockEntity chest = chest(UpgradeSeed.KEY_CHEST);
        LockRecord lockA = lockAt(UpgradeSeed.SAFE_A).orElseThrow(() -> new AssertionError("lock A is gone"));
        LockRecord lockB = lockAt(UpgradeSeed.SAFE_B).orElseThrow(() -> new AssertionError("lock B is gone"));
        KeyBinding a = KeyItem.binding(chest.getItem(0)).orElseThrow();
        KeyBinding b = KeyItem.binding(chest.getItem(1)).orElseThrow();
        require(a.opens(lockA) && !a.opens(lockB) && b.opens(lockB), "the old keys no longer open their own locks");
        List<KeyRingBindings.Entry> entries = KeyRingBindings.entries(chest.getItem(2).getTag());
        require(entries.size() == 2 && entries.stream().anyMatch(e -> e.binding().opens(lockA))
                && entries.stream().anyMatch(e -> e.binding().opens(lockB)), "the old ring's entries no longer open both locks");

        ServerPlayer owner = f.player("UpgradeOwner", 22.5, UpgradeSeed.Y, 52.5);
        try {
            f.forget(owner);
            owner.setItemInHand(InteractionHand.MAIN_HAND, chest.getItem(0).copy());
            owner.gameMode.useItemOn(owner, f.level, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(UpgradeSeed.SAFE_A).relative(Direction.UP, 0.5D), Direction.UP, UpgradeSeed.SAFE_A, false));
            require(f.wasTold(owner, "mcacrime.lock.locked_now") || f.wasTold(owner, "mcacrime.lock.unlocked_now"),
                    "the old key did not work its safe: " + f.toldTo(owner));
        } finally {
            f.remove(owner);
        }

        ItemStack copy = craft(grid(chest.getItem(4).copy(), new ItemStack(Items.IRON_INGOT)));
        require(KeyItem.binding(copy).map(binding -> binding.opens(lockA)).orElse(false), "a cast from the old baked mold does not open lock A");
        ItemStack imprinted = craft(grid(new ItemStack(CrimeItems.KEY_MOLD.get()), chest.getItem(1).copy()));
        require(KeyMoldItem.recorded(imprinted).map(binding -> binding.opens(lockB)).orElse(false),
                "0.8.0's blank-mold impression did not take the old key's binding");
    }

    // --- masks ---------------------------------------------------------------------------------------

    private void masks() throws Exception {
        ChestBlockEntity chest = chest(UpgradeSeed.MASK_CHEST);
        int count = Integer.parseInt(state.getProperty("mask.count"));
        for (int i = 0; i < count; i++) {
            ItemStack expected = stack(state.getProperty("mask.stack." + i));
            ItemStack back = chest.getItem(i);
            require(ItemStack.matches(back, expected), "mask slot " + i + " changed: " + back.getTag() + " vs " + expected.getTag());
        }
        require(!MaskColors.hasColor(chest.getItem(0)), "the undyed bandana reads as dyed");
        require(MaskColors.standardOf(chest.getItem(1)).equals(Optional.of(DyeColor.BLUE)), "the blue mask is not named blue");
        require(MaskColors.standardOf(chest.getItem(2)).equals(Optional.of(DyeColor.BLACK)), "the black mask is not named black");
        require(MaskColors.hasColor(chest.getItem(3)) && MaskColors.standardOf(chest.getItem(3)).isEmpty(), "the blend is misnamed");
        require(chest.getItem(4).getDamageValue() == 9 && chest.getItem(4).getHoverName().getString().equals("The Old Face")
                && MaskColors.standardOf(chest.getItem(4)).equals(Optional.of(DyeColor.LIME)), "the named, worn lime mask changed");
        require(MaskColors.storedColor(chest.getItem(5)).equals(Optional.of(0x123456)) && MaskColors.standardOf(chest.getItem(5)).isEmpty(),
                "the custom colour changed");
    }

    private void stand() {
        UUID id = UUID.fromString(state.getProperty("mask.stand"));
        out.await("upgrade: the armour stand still wears its dyed mask", 400, () -> {
            Entity entity = f.level.getEntity(id);
            if (!(entity instanceof LivingEntity stand)) return "the stand has not loaded";
            ItemStack expected;
            try {
                expected = stack(state.getProperty("mask.stand.head"));
            } catch (Exception broken) {
                return "unreadable fixture: " + broken;
            }
            ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
            if (!ItemStack.matches(head, expected)) return "the stand wears " + head + " " + head.getTag();
            if (!MaskColors.standardOf(head).equals(Optional.of(DyeColor.ORANGE))) return "the stand's mask is not named orange";
            return null;
        }, null);
    }

    // --- the device ----------------------------------------------------------------------------------

    private void device() {
        UUID subject = UUID.fromString(state.getProperty("device.subject"));
        String[] xyz = state.getProperty("device.lower").split(",");
        BlockPos lower = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
        out.await("upgrade: the old blade falls on nobody; prisoner, custody, sentence and occupancy survive", 400, () -> {
            if (!ExecutionAuthorization.all().isEmpty()) return "an execution order exists: " + ExecutionAuthorization.all();
            if (!(f.level.getBlockEntity(lower.above(2)) instanceof GuillotineBlockEntity blade)) return "the guillotine is gone";
            if (!blade.completed() || blade.chopDelay() > 0) return "blade still falling (" + blade.chopDelay() + ")";
            Entity entity = f.level.getEntity(subject);
            if (entity == null) return "the subject did not load";
            if (!entity.isAlive()) return "the upgrade resumed the old execution";
            if (DetentionService.forSubject(data, subject).isEmpty()) return "the pillory let the subject go";
            if (data.getCustody(subject) == null) return "custody was lost";
            if (!CapitalSentenceService.condemned(f.server, subject)) return "the sentence was lost";
            if (data.recordById(UUID.fromString(state.getProperty("device.record"))).isEmpty()) return "the crime record was lost";
            return null;
        }, null);
    }
}
