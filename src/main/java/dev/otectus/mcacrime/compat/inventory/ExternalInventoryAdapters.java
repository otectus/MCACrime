package dev.otectus.mcacrime.compat.inventory;

import dev.otectus.mcacrime.frisk.FriskSlotKind;
import dev.otectus.mcacrime.frisk.FriskSlotRef;
import dev.otectus.mcacrime.frisk.InventoryProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Curios and Cosmetic Armor as searchable inventories (0.7.5 M6.2, specification §15.1).
 *
 * <p>Isolated, reached by name from {@code compat/InventoryCompat}, and naming neither mod as a Java
 * type: every member is resolved by string, because neither is on this mod's compile classpath and
 * because nothing outside this package may be loadable-only-with-them.
 *
 * <p>Both adapters are read-and-extract through the owning mod's own handler, never through a raw
 * NBT edit. That matters for the transaction: {@code frisk/FriskTransaction} rolls a failed transfer
 * back by putting the stack where it came from, and a restore has to land in the same slot the
 * owning mod thinks it took it from.
 *
 * <p>An unsupported build is not a silent no-op: {@link #probe()} reports it, and a provider whose
 * probe failed is never registered, so a search simply does not show those slots rather than showing
 * them and failing to move anything.
 */
public final class ExternalInventoryAdapters {

    private static final String CURIOS_ID = "curios";
    private static final String COSMETIC_ID = "cosmeticarmorreworked";

    private ExternalInventoryAdapters() {
    }

    /**
     * Binds whichever are present and returns one line per outcome.
     *
     * @return the providers to register, each already proved to work on this build
     */
    public static List<InventoryProvider> probe(List<String> report) {
        List<InventoryProvider> providers = new ArrayList<>(2);
        bindCurios(report).ifPresent(providers::add);
        bindCosmetic(report).ifPresent(providers::add);
        return providers;
    }

    /** The same, without a report list. */
    public static List<InventoryProvider> probe() {
        return probe(new ArrayList<>());
    }

    // --- Curios -------------------------------------------------------------------------------------

    private static java.util.Optional<InventoryProvider> bindCurios(List<String> report) {
        if (!loaded(CURIOS_ID)) {
            return java.util.Optional.empty();
        }
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Method inventory = api.getMethod("getCuriosInventory",
                    Class.forName("net.minecraft.world.entity.LivingEntity"));
            Class<?> handler = Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
            Method equipped = handler.getMethod("getEquippedCurios");
            report.add(CURIOS_ID + " " + version(CURIOS_ID) + ": supported");
            return java.util.Optional.of(new ReflectiveProvider("curios", inventory, equipped, true));
        } catch (Throwable t) {
            report.add(CURIOS_ID + " " + version(CURIOS_ID) + ": unsupported build ("
                    + t.getClass().getSimpleName() + "); curio slots are not searchable");
            return java.util.Optional.empty();
        }
    }

    // --- Cosmetic Armor Reworked ---------------------------------------------------------------------

    private static java.util.Optional<InventoryProvider> bindCosmetic(List<String> report) {
        if (!loaded(COSMETIC_ID)) {
            return java.util.Optional.empty();
        }
        try {
            Class<?> api = Class.forName("lain.mods.cos.api.CosArmorAPI");
            Method inventory = api.getMethod("getCosArmorInventory", java.util.UUID.class);
            report.add(COSMETIC_ID + " " + version(COSMETIC_ID) + ": supported");
            return java.util.Optional.of(new ReflectiveProvider("cosmetic_armor", inventory, null, false));
        } catch (Throwable t) {
            report.add(COSMETIC_ID + " " + version(COSMETIC_ID) + ": unsupported build ("
                    + t.getClass().getSimpleName() + "); cosmetic slots are not searchable");
            return java.util.Optional.empty();
        }
    }

    /**
     * One reflective provider over a foreign item handler.
     *
     * <p>Every operation re-resolves the handler from the subject rather than caching one: a cached
     * handler is exactly the stale reference that makes a rollback land in the wrong container after
     * the owning mod has swapped its backing store.
     */
    private record ReflectiveProvider(String id, Method accessor, Method equipped, boolean byEntity)
            implements InventoryProvider {

        @Override
        public boolean supports(LivingEntity subject) {
            return handler(subject) != null;
        }

        @Override
        public List<FriskSlotRef> slots(LivingEntity subject) {
            Object handler = handler(subject);
            int size = size(handler);
            List<FriskSlotRef> refs = new ArrayList<>(Math.max(0, size));
            for (int index = 0; index < size; index++) {
                refs.add(new FriskSlotRef(id, FriskSlotKind.EQUIPMENT, index));
            }
            return refs;
        }

        @Override
        public ItemStack peek(LivingEntity subject, FriskSlotRef ref) {
            Object handler = handler(subject);
            if (handler == null || ref == null) {
                return ItemStack.EMPTY;
            }
            try {
                Object stack = handler.getClass()
                        .getMethod("getStackInSlot", int.class).invoke(handler, ref.index());
                return stack instanceof ItemStack found ? found : ItemStack.EMPTY;
            } catch (Throwable t) {
                return ItemStack.EMPTY;
            }
        }

        @Override
        public ItemStack extract(LivingEntity subject, FriskSlotRef ref, int count) {
            Object handler = handler(subject);
            if (handler == null || ref == null || count <= 0) {
                return ItemStack.EMPTY;
            }
            try {
                Object taken = handler.getClass()
                        .getMethod("extractItem", int.class, int.class, boolean.class)
                        .invoke(handler, ref.index(), count, Boolean.FALSE);
                return taken instanceof ItemStack found ? found : ItemStack.EMPTY;
            } catch (Throwable t) {
                return ItemStack.EMPTY;
            }
        }

        @Override
        public boolean restore(LivingEntity subject, FriskSlotRef ref, ItemStack stack) {
            Object handler = handler(subject);
            if (handler == null || ref == null || stack == null || stack.isEmpty()) {
                return false;
            }
            try {
                Object left = handler.getClass()
                        .getMethod("insertItem", int.class, ItemStack.class, boolean.class)
                        .invoke(handler, ref.index(), stack, Boolean.FALSE);
                return left instanceof ItemStack remainder && remainder.isEmpty();
            } catch (Throwable t) {
                return false;
            }
        }

        private Object handler(LivingEntity subject) {
            if (subject == null) {
                return null;
            }
            try {
                Object result = byEntity ? accessor.invoke(null, subject)
                        : accessor.invoke(null, subject.getUUID());
                if (result instanceof java.util.Optional<?> optional) {
                    result = optional.orElse(null);
                }
                if (result != null && equipped != null) {
                    // Curios hands back a holder; the equipped map is where the stacks are.
                    Object curios = equipped.invoke(result);
                    return curios == null ? result : curios;
                }
                return result;
            } catch (Throwable t) {
                return null;
            }
        }

        private int size(Object handler) {
            if (handler == null) {
                return 0;
            }
            try {
                Object slots = handler.getClass().getMethod("getSlots").invoke(handler);
                return slots instanceof Number number ? Math.max(0, Math.min(64, number.intValue())) : 0;
            } catch (Throwable t) {
                return 0;
            }
        }
    }

    private static boolean loaded(String modId) {
        ModList list = ModList.get();
        return list != null && list.isLoaded(modId);
    }

    private static String version(String modId) {
        try {
            ModList list = ModList.get();
            return list == null ? "unknown" : list.getModContainerById(modId)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
