package dev.otectus.mcacrime.state;

import com.mojang.serialization.Codec;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.locks.KeyBinding;
import dev.otectus.mcacrime.network.CrimeStreamCodecs;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * This mod's registered item data components (port-only; plan §7.1 item 2).
 *
 * <p>The Forge 1.20.1 baseline keeps a key's binding, a ring's contents and a mold's impression in
 * item NBT. 1.21.1 has no item NBT: a stack carries a typed, registered component map instead, so
 * each of those facts is a {@link DataComponentType} with a {@link Codec} for the save file and a
 * {@link net.minecraft.network.codec.StreamCodec} for the wire. This class is the whole registry and
 * the only place a component id is written down.
 *
 * <p>Only what the mod's items actually carry is registered: the four lock components of M3. A
 * registered component nothing writes would be a promise with no behaviour behind it, so nothing is
 * registered ahead of the item that carries it; the tray, poster and possessions-box components of
 * M5 went with their items before release.
 *
 * <p>Restraint durability is deliberately <b>not</b> here. It lives on the applied instance in
 * {@code CrimeWorldData} ({@code restraint/RestraintDurability}), because two prisoners in two pairs
 * of handcuffs need two counters and a stack could only ever hold one.
 */
public final class CrimeDataComponents {

    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, McaCrime.MOD_ID);

    /**
     * A bounded list of bindings on the wire.
     *
     * <p>Bounded at the ring's own hard ceiling rather than at {@code Integer.MAX_VALUE}: a component
     * arrives from a creative-mode stack a client sent, and an unbounded list length is an allocation
     * a stranger chooses.
     */
    private static final StreamCodec<ByteBuf, List<KeyBinding>> RING_STREAM_CODEC =
            ByteBufCodecs.collection(ArrayList::new, KeyBinding.STREAM_CODEC,
                    dev.otectus.mcacrime.locks.KeyRingBindings.MAX_CAPACITY);

    /** The lock a key was cut for: identity, binding revision and the name it displays. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<KeyBinding>> LOCK_BINDING =
            COMPONENTS.registerComponentType("lock_binding", builder -> builder
                    .persistent(KeyBinding.CODEC)
                    .networkSynchronized(KeyBinding.STREAM_CODEC));

    /**
     * Every binding a key ring carries, in the order they were added.
     *
     * <p>A list rather than a set: order is what makes disassembly predictable, and the ring's own
     * rules (no duplicates, bounded capacity) are enforced by {@code KeyRingBindings} rather than by
     * the shape of the component.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<List<KeyBinding>>>
            KEY_RING_CONTENTS = COMPONENTS.registerComponentType("key_ring_contents", builder -> builder
                    .persistent(KeyBinding.CODEC.listOf())
                    .networkSynchronized(RING_STREAM_CODEC));

    /**
     * The key a mold took an impression of, raw or fired.
     *
     * <p>A separate type from {@link #LOCK_BINDING} on purpose: a mold is not usable in a lock, and
     * one component for both would make "is this a key" a question about the item id alone.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<KeyBinding>>
            KEY_MOLD_BINDING = COMPONENTS.registerComponentType("key_mold_binding", builder -> builder
                    .persistent(KeyBinding.CODEC)
                    .networkSynchronized(KeyBinding.STREAM_CODEC));

    /** How many keys a fired mold can still cast. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>>
            KEY_MOLD_QUALITY = COMPONENTS.registerComponentType("key_mold_quality", builder -> builder
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));


    private CrimeDataComponents() {
    }

    public static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
    }
}
