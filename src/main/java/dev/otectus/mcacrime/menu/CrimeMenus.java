package dev.otectus.mcacrime.menu;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * This mod's menu types (0.7.2 §8.1). One so far: the Mask Station.
 *
 * <p>Registered through {@link IForgeMenuType} rather than plain {@code MenuType.create} because the
 * server sends the station's position with the open packet. The client uses that only to know which
 * block it is looking at; the server's own copy is the authority, and no client-supplied position is
 * ever read back for validation or block access (§8.4).
 */
public final class CrimeMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, McaCrime.MOD_ID);

    public static final RegistryObject<MenuType<MaskStationMenu>> MASK_STATION =
            MENUS.register("mask_station", () -> IForgeMenuType.create((id, inventory, buf) -> {
                BlockPos pos = buf.readBlockPos();
                return new MaskStationMenu(id, inventory, ContainerLevelAccess.NULL, pos);
            }));

    private CrimeMenus() {
    }

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}
