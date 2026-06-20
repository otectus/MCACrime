package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.captivity.RestraintType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

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

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("crime", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.mcacrime"))
                    .icon(() -> new ItemStack(RESTRAINT_CUFFS.get()))
                    .displayItems((params, output) -> {
                        output.accept(RESTRAINT_ROPE.get());
                        output.accept(RESTRAINT_CUFFS.get());
                        output.accept(RESTRAINT_LOCKED_CUFFS.get());
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
}
