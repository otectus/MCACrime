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
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static dev.otectus.mcacrime.usability.UsabilityRuntimeChecks.require;

/**
 * The upgrade test's second half (0.8.0 runtime scenario 5): the world {@link UpgradeSeed} left with the
 * previous release, reopened on 0.8.0. Every key, ring and mold keeps its identity and still works the
 * locks (including through 0.8.0's new blank-mold impression), every mask keeps its components and reads as
 * the colour it was dyed, and the occupied device keeps its prisoner, custody and sentence while the old,
 * transient execution order is not resumed. The Forge build runs the same verification.
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

    private ChestBlockEntity chest(BlockPos pos) {
        require(f.level.getBlockEntity(pos) instanceof ChestBlockEntity, "the chest at " + pos.toShortString() + " did not load");
        return (ChestBlockEntity) f.level.getBlockEntity(pos);
    }

    private ItemStack stack(String snbt) throws Exception {
        return ItemStack.parseOptional(f.level.registryAccess(), (CompoundTag) TagParser.parseTag(snbt));
    }

    private Optional<LockRecord> lockAt(BlockPos pos) {
        return f.level.getBlockEntity(pos) instanceof LockHolder holder ? holder.lockId().map(data::lock) : Optional.empty();
    }

    private ItemStack craft(ItemStack... stacks) {
        List<ItemStack> items = new ArrayList<>(Arrays.asList(stacks));
        while (items.size() < 9) items.add(ItemStack.EMPTY);
        CraftingInput input = CraftingInput.of(3, 3, items);
        return f.server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, f.level)
                .map(holder -> holder.value().assemble(input, f.level.registryAccess())).orElse(ItemStack.EMPTY);
    }

    private void keysKept() throws Exception {
        ChestBlockEntity chest = chest(UpgradeSeed.KEY_CHEST);
        for (int i = 0; i < 5; i++) {
            ItemStack expected = stack(state.getProperty("key.stack." + i));
            ItemStack back = chest.getItem(i);
            require(ItemStack.matches(back, expected), "key-chest slot " + i + " changed: "
                    + back.getComponentsPatch() + " vs " + expected.getComponentsPatch());
        }
        ItemStack keyA = chest.getItem(0);
        require(KeyItem.binding(keyA).orElseThrow().toString().equals(state.getProperty("key.bindingA")), "key A's binding changed");
        require(keyA.getHoverName().getString().equals("Vault key"), "key A lost its name");
        require(KeyRingItem.count(chest.getItem(2)) == 2, "the ring holds " + KeyRingItem.count(chest.getItem(2)));
        require(KeyMoldItem.recorded(chest.getItem(3)).map(KeyBinding::toString).orElse("").equals(state.getProperty("key.bindingA")),
                "the raw mold lost its impression");
        require(BakedKeyMoldItem.quality(chest.getItem(4)) == BakedKeyMoldItem.STARTING_QUALITY
                && KeyMoldItem.recorded(chest.getItem(4)).isPresent(), "the baked mold changed");
    }

    private void keysWork() {
        ChestBlockEntity chest = chest(UpgradeSeed.KEY_CHEST);
        LockRecord lockA = lockAt(UpgradeSeed.SAFE_A).orElseThrow(() -> new AssertionError("lock A is gone"));
        LockRecord lockB = lockAt(UpgradeSeed.SAFE_B).orElseThrow(() -> new AssertionError("lock B is gone"));
        KeyBinding a = KeyItem.binding(chest.getItem(0)).orElseThrow();
        KeyBinding b = KeyItem.binding(chest.getItem(1)).orElseThrow();
        require(a.opens(lockA) && !a.opens(lockB) && b.opens(lockB), "the old keys no longer open their own locks");
        List<KeyBinding> entries = KeyRingBindings.read(chest.getItem(2));
        require(entries.size() == 2 && entries.stream().anyMatch(e -> e.opens(lockA))
                && entries.stream().anyMatch(e -> e.opens(lockB)), "the old ring's entries no longer open both locks");

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

        ItemStack copy = craft(chest.getItem(4).copy(), new ItemStack(Items.IRON_INGOT));
        require(KeyItem.binding(copy).map(binding -> binding.opens(lockA)).orElse(false), "a cast from the old baked mold does not open lock A");
        ItemStack imprinted = craft(new ItemStack(CrimeItems.KEY_MOLD.get()), chest.getItem(1).copy());
        require(KeyMoldItem.recorded(imprinted).map(binding -> binding.opens(lockB)).orElse(false),
                "0.8.0's blank-mold impression did not take the old key's binding");
    }

    private void masks() throws Exception {
        ChestBlockEntity chest = chest(UpgradeSeed.MASK_CHEST);
        int count = Integer.parseInt(state.getProperty("mask.count"));
        for (int i = 0; i < count; i++) {
            ItemStack expected = stack(state.getProperty("mask.stack." + i));
            ItemStack back = chest.getItem(i);
            require(ItemStack.matches(back, expected), "mask slot " + i + " changed: "
                    + back.getComponentsPatch() + " vs " + expected.getComponentsPatch());
        }
        require(!MaskColors.hasColor(chest.getItem(0)), "the undyed bandana reads as dyed");
        require(MaskColors.standardOf(chest.getItem(1)).equals(Optional.of(DyeColor.BLUE)), "the blue mask is not named blue");
        require(MaskColors.standardOf(chest.getItem(2)).equals(Optional.of(DyeColor.BLACK)), "the black mask is not named black");
        require(MaskColors.hasColor(chest.getItem(3)) && MaskColors.standardOf(chest.getItem(3)).isEmpty(), "the blend is misnamed");
        require(chest.getItem(4).getDamageValue() == 9 && chest.getItem(4).has(DataComponents.LORE)
                && chest.getItem(4).getHoverName().getString().equals("The Old Face")
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
            if (!ItemStack.matches(head, expected)) return "the stand wears " + head + " " + head.getComponentsPatch();
            if (!MaskColors.standardOf(head).equals(Optional.of(DyeColor.ORANGE))) return "the stand's mask is not named orange";
            return null;
        });
    }

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
        });
    }
}
