package dev.otectus.mcacrime.menu;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * This mod's menu types (0.7.2 §8.1). One so far: the Mask Station.
 *
 * <p>Registered through {@link IMenuTypeExtension} rather than plain {@code MenuType.create} because
 * the server sends the station's position with the open packet. The client uses that only to know
 * which block it is looking at; the server's own copy is the authority, and no client-supplied
 * position is ever read back for validation or block access (§8.4).
 *
 * <p>1.21.1 note: the extra data buffer is a {@code RegistryFriendlyByteBuf} rather than Forge's
 * plain {@code FriendlyByteBuf}, and the factory interface moved from {@code IForgeMenuType} to
 * {@link IMenuTypeExtension}. The contract — the server writes, the client reads, and only the
 * position travels — is unchanged.
 */
public final class CrimeMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, McaCrime.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<MaskStationMenu>> MASK_STATION =
            MENUS.register("mask_station", () -> IMenuTypeExtension.create((id, inventory, buf) -> {
                BlockPos pos = buf.readBlockPos();
                return new MaskStationMenu(id, inventory, ContainerLevelAccess.NULL, pos);
            }));

    private CrimeMenus() {
    }

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}
