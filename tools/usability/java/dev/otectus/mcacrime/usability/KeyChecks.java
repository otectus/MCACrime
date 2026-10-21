package dev.otectus.mcacrime.usability;

import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.item.lock.BakedKeyMoldItem;
import dev.otectus.mcacrime.item.lock.KeyItem;
import dev.otectus.mcacrime.item.lock.KeyMoldItem;
import dev.otectus.mcacrime.item.lock.KeyRingItem;
import dev.otectus.mcacrime.locks.KeyBinding;
import dev.otectus.mcacrime.locks.LockHolder;
import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.locks.LockService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static dev.otectus.mcacrime.usability.UsabilityRuntimeChecks.require;

/**
 * Workstream C on a real server (0.8.0): the dynamic key recipes through the real recipe manager and a
 * real crafting menu (shift-click, remainders, a full inventory), the mold fired in a real furnace both
 * directly and from a hopper, and the lock interactions a player meets, with each refusal named.
 */
final class KeyChecks {

    private static final int Y = 100;
    private final Fixtures f;
    private final UsabilityRuntimeChecks out;
    private final CrimeWorldData data;

    KeyChecks(Fixtures fixtures, UsabilityRuntimeChecks out) {
        this.f = fixtures;
        this.out = out;
        this.data = CrimeWorldData.get(fixtures.server);
    }

    private static final java.nio.file.Path STATE = java.nio.file.Path.of("usability-keys.properties");
    private static final BlockPos CHEST = new BlockPos(40, Y, 76);
    private static final BlockPos KEPT_SAFE = new BlockPos(44, Y, 76);

    void run() {
        if (UsabilityRuntimeChecks.phase() == 1) {
            restartVerify();
            return;
        }
        f.arena(16, 60, 50, 80, Y);
        out.check("keys: a blank key binds to its owner's new safe and opens it; a second blank key is refused", this::bindAndOpen);
        out.check("keys: clay impression and blank-mold impression through the real recipe manager", this::impressions);
        out.check("keys: the baked mold casts four copies and the fifth is unavailable", this::fourCasts);
        out.check("keys: ring create, use on two locks, remove one, add it back, keep its name", this::ringWorkflow);
        out.check("keys: refused crafts (duplicate, full ring, two rings, recorded mold) consume nothing", this::refusedCrafts);
        out.check("keys: repeated previews change no input", this::purePreviews);
        out.check("keys: a crafting menu shift-click hands back the key and is refused into a full inventory", this::craftingMenu);
        out.check("keys: a rekey makes old keys, ring entries and later casts from an old mold refuse", this::rekey);
        out.check("keys: a full ring on a new lock says it is full", this::fullRing);
        out.check("keys: picking up an iron nugget unlocks the key, padlock and lockpick recipes; dynamic recipes stay out of the book", this::recipeBook);
        out.check("keys: restart setup stores a key, a ring with a renamed key and both molds in a chest", this::restartSetup);
        furnace();
    }

    /** Section 7's recipe discovery, through the shipped unlock advancements and the real criterion trigger. */
    private void recipeBook() {
        ServerPlayer learner = f.player("RecipeLearner", 30.5, Y, 60.5);
        try {
            require(!learner.getRecipeBook().contains(new net.minecraft.resources.ResourceLocation("mcacrime", "key")),
                    "the key recipe was known before the nugget");
            ItemStack nugget = new ItemStack(Items.IRON_NUGGET);
            learner.getInventory().add(nugget.copy());
            net.minecraft.advancements.CriteriaTriggers.INVENTORY_CHANGED.trigger(learner, learner.getInventory(), nugget);
            for (String recipe : List.of("key", "padlock", "lockpick")) {
                require(learner.getRecipeBook().contains(new net.minecraft.resources.ResourceLocation("mcacrime", recipe)),
                        "an iron nugget did not unlock mcacrime:" + recipe);
            }
            require(!learner.getRecipeBook().contains(new net.minecraft.resources.ResourceLocation("mcacrime", "key_mold_copy")),
                    "a dynamic key recipe was put in the recipe book");
        } finally {
            f.remove(learner);
        }
    }

    private void restartSetup() throws Exception {
        ServerPlayer owner = f.player("KeptOwner", 40.5, Y, 74.5);
        try {
            f.level.setBlockAndUpdate(KEPT_SAFE, CrimeBlocks.SAFE.get().defaultBlockState());
            ItemStack key = named(bindNewKey(owner, KEPT_SAFE), "Vault key");
            ItemStack other = bindNewKey(owner, safeAt(46, 76));
            ItemStack ring = craft(grid(key.copy(), other.copy()));
            ItemStack raw = craft(grid(key.copy(), new ItemStack(Items.CLAY_BALL)));
            ItemStack baked = BakedKeyMoldItem.fromRawMold(raw);
            f.level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState());
            var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) f.level.getBlockEntity(CHEST);
            chest.setItem(0, key);
            chest.setItem(1, ring);
            chest.setItem(2, raw);
            chest.setItem(3, baked);
            chest.setChanged();
            java.util.Properties state = new java.util.Properties();
            state.setProperty("binding", KeyItem.binding(key).orElseThrow().save().toString());
            try (var writer = java.nio.file.Files.newBufferedWriter(STATE)) {
                state.store(writer, "key restart fixture");
            }
        } finally {
            f.remove(owner);
        }
    }

    private void restartVerify() {
        out.check("keys restart: the key, the ring (with the renamed key) and both molds kept their bindings", () -> {
            java.util.Properties state = new java.util.Properties();
            try (var reader = java.nio.file.Files.newBufferedReader(STATE)) {
                state.load(reader);
            }
            var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) f.level.getBlockEntity(CHEST);
            require(chest != null, "the chest did not load");
            ItemStack key = chest.getItem(0);
            ItemStack ring = chest.getItem(1);
            KeyBinding binding = KeyItem.binding(key).orElseThrow();
            require(binding.save().toString().equals(state.getProperty("binding")), "the key's binding changed: " + binding);
            require(key.getHoverName().getString().equals("Vault key"), "the key lost its name");
            require(KeyRingItem.count(ring) == 2, "the ring holds " + KeyRingItem.count(ring));
            require(dev.otectus.mcacrime.locks.KeyRingBindings.entries(ring.getTag()).get(0).customName() != null,
                    "the ring entry lost the key's name");
            require(KeyMoldItem.recorded(chest.getItem(2)).map(binding::equals).orElse(false), "the raw mold lost its impression");
            require(KeyMoldItem.recorded(chest.getItem(3)).map(binding::equals).orElse(false)
                    && BakedKeyMoldItem.quality(chest.getItem(3)) == BakedKeyMoldItem.STARTING_QUALITY, "the baked mold changed");
            LockRecord lock = lockAt(KEPT_SAFE).orElseThrow();
            require(binding.opens(lock), "the key no longer opens its lock after the restart");
        });
    }

    // --- fixtures ------------------------------------------------------------------------------------

    private BlockPos safeAt(int x, int z) {
        BlockPos pos = new BlockPos(x, Y, z);
        f.level.setBlockAndUpdate(pos, CrimeBlocks.SAFE.get().defaultBlockState());
        return pos;
    }

    private void use(ServerPlayer player, BlockPos pos, ItemStack held) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        player.gameMode.useItemOn(player, f.level, held, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos).relative(Direction.UP, 0.5D), Direction.UP, pos, false));
    }

    private Optional<LockRecord> lockAt(BlockPos pos) {
        return f.level.getBlockEntity(pos) instanceof LockHolder holder ? holder.lockId().map(data::lock) : Optional.empty();
    }

    private ItemStack bindNewKey(ServerPlayer owner, BlockPos safe) {
        ItemStack key = new ItemStack(CrimeItems.KEY.get());
        use(owner, safe, key);
        require(KeyItem.binding(key).isPresent(), "the blank key was not bound: " + f.toldTo(owner));
        return key;
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

    private Optional<CraftingRecipe> recipe(CraftingContainer grid) {
        return f.server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, f.level);
    }

    private ItemStack craft(CraftingContainer grid) {
        return recipe(grid).map(r -> r.assemble(grid, f.level.registryAccess())).orElse(ItemStack.EMPTY);
    }

    /** Whether the player was just told a lock turned, either way. */
    private boolean toggled(ServerPlayer player) {
        return f.wasTold(player, "mcacrime.lock.locked_now") || f.wasTold(player, "mcacrime.lock.unlocked_now");
    }

    private ItemStack named(ItemStack stack, String name) {
        stack.setHoverName(Component.literal(name));
        return stack;
    }

    // --- checks --------------------------------------------------------------------------------------

    private void bindAndOpen() {
        ServerPlayer owner = f.player("KeyOwner", 20.5, Y, 52.5);
        ServerPlayer stranger = f.player("KeyStranger", 21.5, Y, 52.5);
        try {
            BlockPos safe = safeAt(20, 54);
            ItemStack key = bindNewKey(owner, safe);
            require(f.wasTold(owner, "mcacrime.lock.bound"), "binding said " + f.toldTo(owner));
            LockRecord lock = lockAt(safe).orElseThrow();
            require(KeyItem.binding(key).orElseThrow().opens(lock), "the bound key does not open its lock");
            f.forget(owner);
            use(owner, safe, key);
            require(toggled(owner), "the key did not work the safe: " + f.toldTo(owner));
            f.forget(owner);
            ItemStack second = new ItemStack(CrimeItems.KEY.get());
            use(owner, safe, second);
            require(KeyItem.binding(second).isEmpty(), "a second first key was issued");
            require(f.wasTold(owner, "mcacrime.lock.first_key_issued"), "second blank said " + f.toldTo(owner));
            ItemStack theirs = new ItemStack(CrimeItems.KEY.get());
            use(stranger, safe, theirs);
            require(KeyItem.binding(theirs).isEmpty(), "a stranger bound a key to somebody's lock");
            require(f.wasTold(stranger, "mcacrime.lock.first_key_issued"), "the stranger was told " + f.toldTo(stranger));
            f.forget(owner);
            use(owner, safe, key);
            require(toggled(owner), "the key did not work the safe again: " + f.toldTo(owner));
        } finally {
            f.remove(owner);
            f.remove(stranger);
        }
    }

    private void impressions() {
        ServerPlayer owner = f.player("MoldOwner", 24.5, Y, 52.5);
        try {
            ItemStack key = named(bindNewKey(owner, safeAt(24, 54)), "Front door key");
            KeyBinding binding = KeyItem.binding(key).orElseThrow();
            CraftingContainer clay = grid(key.copy(), new ItemStack(Items.CLAY_BALL));
            ItemStack mold = craft(clay);
            require(mold.is(CrimeItems.KEY_MOLD.get()), "key + clay made " + mold);
            require(KeyMoldItem.recorded(mold).orElseThrow().equals(binding), "the clay impression lost the binding");
            var clayRemainder = recipe(clay).orElseThrow().getRemainingItems(clay);
            require(clayRemainder.get(0).is(CrimeItems.KEY.get()) && clayRemainder.get(0).getHoverName().getString()
                    .equals("Front door key"), "the key did not come back with its name: " + clayRemainder.get(0));
            require(clayRemainder.get(1).isEmpty(), "the clay was not consumed");
            CraftingContainer blank = grid(new ItemStack(CrimeItems.KEY_MOLD.get()), key.copy());
            ItemStack imprinted = craft(blank);
            require(KeyMoldItem.recorded(imprinted).orElseThrow().equals(binding), "the blank-mold impression lost the binding");
            require(recipe(blank).orElseThrow().getRemainingItems(blank).get(1).is(CrimeItems.KEY.get()), "the key was not returned");
            require(craft(grid(imprinted.copy(), key.copy())).isEmpty(), "a recorded mold was overwritten");
            require(craft(grid(new ItemStack(CrimeItems.BAKED_KEY_MOLD.get()), key.copy())).isEmpty(), "a baked mold took an impression");
            require(craft(grid(new ItemStack(CrimeItems.KEY_MOLD.get()), new ItemStack(CrimeItems.KEY.get()))).isEmpty(),
                    "a blank key made an impression");
        } finally {
            f.remove(owner);
        }
    }

    private void fourCasts() {
        ServerPlayer owner = f.player("CastOwner", 28.5, Y, 52.5);
        try {
            BlockPos safe = safeAt(28, 54);
            ItemStack key = bindNewKey(owner, safe);
            ItemStack mold = BakedKeyMoldItem.fromRawMold(craft(grid(key.copy(), new ItemStack(Items.CLAY_BALL))));
            LockRecord lock = lockAt(safe).orElseThrow();
            int casts = 0;
            while (!mold.isEmpty()) {
                CraftingContainer cast = grid(mold, new ItemStack(Items.IRON_INGOT));
                ItemStack copy = craft(cast);
                require(KeyItem.binding(copy).orElseThrow().opens(lock), "cast " + (casts + 1) + " does not open the lock");
                mold = recipe(cast).orElseThrow().getRemainingItems(cast).get(0);
                casts++;
                require(casts <= 4, "a fifth cast was possible");
            }
            require(casts == 4, "the mold cast " + casts + " keys");
        } finally {
            f.remove(owner);
        }
    }

    private void ringWorkflow() {
        ServerPlayer owner = f.player("RingOwner", 32.5, Y, 52.5);
        try {
            BlockPos first = safeAt(32, 54);
            BlockPos second = safeAt(34, 54);
            ItemStack a = named(bindNewKey(owner, first), "Gate key");
            ItemStack b = bindNewKey(owner, second);
            ItemStack ring = craft(grid(a.copy(), b.copy()));
            require(ring.is(CrimeItems.KEY_RING.get()) && KeyRingItem.count(ring) == 2, "two keys made " + ring);
            f.forget(owner);
            use(owner, first, ring);
            require(toggled(owner), "the ring did not work the first lock: " + f.toldTo(owner));
            f.forget(owner);
            use(owner, second, ring);
            require(toggled(owner), "the ring did not work the second lock: " + f.toldTo(owner));
            CraftingContainer off = grid(ring.copy());
            ItemStack removed = craft(off);
            ItemStack remainingRing = recipe(off).orElseThrow().getRemainingItems(off).get(0);
            require(KeyRingItem.count(remainingRing) == 1, "the ring kept " + KeyRingItem.count(remainingRing) + " keys");
            require(KeyItem.binding(removed).orElseThrow().equals(KeyItem.binding(b).orElseThrow()),
                    "the last key added did not come off first");
            ItemStack restored = craft(grid(remainingRing.copy(), removed.copy()));
            require(KeyRingItem.count(restored) == 2, "adding the key back failed");
            // Both keys off again: the renamed key comes back under its own name.
            CraftingContainer again = grid(restored.copy());
            craft(again);
            ItemStack ringWithGate = recipe(again).orElseThrow().getRemainingItems(again).get(0);
            ItemStack gate = craft(grid(ringWithGate.copy()));
            require(gate.getHoverName().getString().equals("Gate key"),
                    "the renamed key came back as " + gate.getHoverName().getString());
        } finally {
            f.remove(owner);
        }
    }

    private void refusedCrafts() {
        ServerPlayer owner = f.player("RefusedOwner", 36.5, Y, 52.5);
        try {
            ItemStack a = bindNewKey(owner, safeAt(36, 54));
            ItemStack ring = craft(grid(a.copy(), bindNewKey(owner, safeAt(38, 54))));
            require(craft(grid(a.copy(), a.copy())).isEmpty(), "two keys for one lock made a ring");
            require(craft(grid(ring.copy(), a.copy())).isEmpty(), "a duplicate key was added to a ring");
            require(craft(grid(ring.copy(), ring.copy(), bindNewKey(owner, safeAt(40, 54)))).isEmpty(), "two rings merged");
            require(craft(grid(ring.copy(), new ItemStack(CrimeItems.KEY.get()))).isEmpty(), "a blank key went onto a ring");
        } finally {
            f.remove(owner);
        }
    }

    private void purePreviews() {
        ServerPlayer owner = f.player("PreviewOwner", 42.5, Y, 52.5);
        try {
            ItemStack key = bindNewKey(owner, safeAt(42, 54));
            ItemStack mold = BakedKeyMoldItem.fromRawMold(craft(grid(key.copy(), new ItemStack(Items.CLAY_BALL))));
            CraftingContainer cast = grid(mold.copy(), new ItemStack(Items.IRON_INGOT));
            ItemStack before = cast.getItem(0).copy();
            for (int i = 0; i < 3; i++) {
                craft(cast);
                recipe(cast).orElseThrow().getRemainingItems(cast);
            }
            require(ItemStack.matches(before, cast.getItem(0)), "previewing a cast changed the mold");
            require(BakedKeyMoldItem.quality(cast.getItem(0)) == BakedKeyMoldItem.STARTING_QUALITY, "quality was spent by a preview");
        } finally {
            f.remove(owner);
        }
    }

    private void craftingMenu() {
        ServerPlayer owner = f.player("MenuOwner", 46.5, Y, 52.5);
        try {
            ItemStack key = named(bindNewKey(owner, safeAt(46, 54)), "Shed key");
            BlockPos table = new BlockPos(47, Y, 52);
            f.level.setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState());
            owner.getInventory().clearContent();
            CraftingMenu menu = new CraftingMenu(1, owner.getInventory(), ContainerLevelAccess.create(f.level, table));
            menu.getSlot(1).set(key.copy());
            menu.getSlot(2).set(new ItemStack(Items.CLAY_BALL));
            require(menu.getSlot(0).getItem().is(CrimeItems.KEY_MOLD.get()), "the result slot shows " + menu.getSlot(0).getItem());
            menu.quickMoveStack(owner, 0);
            require(owner.getInventory().countItem(CrimeItems.KEY_MOLD.get()) == 1, "shift-click did not deliver the mold");
            require(menu.getSlot(1).getItem().is(CrimeItems.KEY.get())
                    && menu.getSlot(1).getItem().getHoverName().getString().equals("Shed key"), "the key did not stay in the grid");
            require(menu.getSlot(2).getItem().isEmpty(), "the clay was not consumed");
            // A full inventory: shift-click moves nothing and consumes nothing.
            for (int i = 0; i < owner.getInventory().items.size(); i++) {
                owner.getInventory().items.set(i, new ItemStack(Items.DIRT, 64));
            }
            menu.getSlot(2).set(new ItemStack(Items.CLAY_BALL));
            ItemStack moved = menu.quickMoveStack(owner, 0);
            require(moved.isEmpty() || owner.getInventory().countItem(CrimeItems.KEY_MOLD.get()) == 0,
                    "a mold was crafted into a full inventory");
            require(menu.getSlot(2).getItem().is(Items.CLAY_BALL), "the clay was consumed with nowhere to put the mold");
            require(menu.getSlot(1).getItem().is(CrimeItems.KEY.get()), "the key was lost");
            menu.removed(owner);
        } finally {
            f.remove(owner);
        }
    }

    private void rekey() {
        ServerPlayer owner = f.player("RekeyOwner", 50.5, Y, 52.5);
        try {
            BlockPos safe = safeAt(50, 54);
            BlockPos other = safeAt(52, 54);
            ItemStack key = bindNewKey(owner, safe);
            ItemStack mold = BakedKeyMoldItem.fromRawMold(craft(grid(key.copy(), new ItemStack(Items.CLAY_BALL))));
            ItemStack ring = craft(grid(key.copy(), bindNewKey(owner, other)));
            LockRecord before = lockAt(safe).orElseThrow();
            require(LockService.rekey(data, before.lockId()).isPresent(), "rekey failed");
            LockRecord after = lockAt(safe).orElseThrow();
            require(!KeyItem.binding(key).orElseThrow().opens(after), "the old key still opens the rekeyed lock");
            f.forget(owner);
            use(owner, safe, key);
            require(f.wasTold(owner, "mcacrime.lock.rekeyed"), "the old key was told " + f.toldTo(owner));
            ItemStack copy = craft(grid(mold.copy(), new ItemStack(Items.IRON_INGOT)));
            require(!KeyItem.binding(copy).orElseThrow().opens(after), "a cast from the old mold opens the rekeyed lock");
            f.forget(owner);
            use(owner, safe, ring);
            require(!f.wasTold(owner, "mcacrime.lock.locked_now") && !f.wasTold(owner, "mcacrime.lock.unlocked_now"),
                    "the ring's stale entry still worked the lock: " + f.toldTo(owner));
        } finally {
            f.remove(owner);
        }
    }

    private void fullRing() {
        ServerPlayer owner = f.player("FullRingOwner", 54.5, Y, 52.5);
        var capacity = dev.otectus.mcacrime.McaCrimeConfig.COMMON.maxKeysPerRing;
        int before = capacity.get();
        try {
            ItemStack ring = craft(grid(bindNewKey(owner, safeAt(54, 56)), bindNewKey(owner, safeAt(56, 56))));
            capacity.set(2);
            f.forget(owner);
            use(owner, safeAt(58, 56), ring);
            require(KeyRingItem.count(ring) == 2, "a full ring took a third key");
            require(f.wasTold(owner, "mcacrime.lock.ring_full"), "the full ring said " + f.toldTo(owner));
        } finally {
            capacity.set(before);
            f.remove(owner);
        }
    }

    private void furnace() {
        ServerPlayer owner = f.player("FurnaceOwner", 20.5, Y, 70.5);
        ItemStack key = bindNewKey(owner, safeAt(20, 72));
        KeyBinding binding = KeyItem.binding(key).orElseThrow();
        ItemStack raw = craft(grid(key.copy(), new ItemStack(Items.CLAY_BALL)));
        BlockPos direct = new BlockPos(24, Y, 72);
        f.level.setBlockAndUpdate(direct, Blocks.FURNACE.defaultBlockState());
        FurnaceBlockEntity furnace = (FurnaceBlockEntity) f.level.getBlockEntity(direct);
        furnace.setItem(0, raw.copy());
        furnace.setItem(1, new ItemStack(Items.COAL));
        BlockPos fed = new BlockPos(28, Y, 72);
        f.level.setBlockAndUpdate(fed, Blocks.FURNACE.defaultBlockState());
        ((FurnaceBlockEntity) f.level.getBlockEntity(fed)).setItem(1, new ItemStack(Items.COAL));
        f.level.setBlockAndUpdate(fed.above(), Blocks.HOPPER.defaultBlockState());
        ((HopperBlockEntity) f.level.getBlockEntity(fed.above())).setItem(0, raw.copy());
        f.remove(owner);
        out.await("keys: a real furnace fires the mold, directly and from a hopper, keeping the binding and four casts", 400, () -> {
            for (BlockPos pos : List.of(direct, fed)) {
                ItemStack baked = ((FurnaceBlockEntity) f.level.getBlockEntity(pos)).getItem(2);
                if (baked.isEmpty()) return "the furnace at " + pos.toShortString() + " has not finished";
                if (!baked.is(CrimeItems.BAKED_KEY_MOLD.get())) return "the furnace made " + baked;
                if (!KeyMoldItem.recorded(baked).map(binding::equals).orElse(false)) return "the binding was lost in the furnace";
                if (BakedKeyMoldItem.quality(baked) != BakedKeyMoldItem.STARTING_QUALITY) return "quality " + BakedKeyMoldItem.quality(baked);
            }
            return null;
        }, null);
    }
}
