package dev.otectus.mcacrime.mask;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Moving one mask's item history onto another mask's stack, as pure {@link DataComponentPatch}
 * arithmetic (0.7.2 §5.4, invariant 8).
 *
 * <p>Split out of {@link MaskCustomization} for one reason: this is the part that has to be provable.
 * "Does a restyle keep a curse, keep provenance, keep a foreign mod's soulbound flag, and refuse data
 * it cannot honestly carry" is a matrix, and a matrix that needs a world to run is a matrix nobody
 * runs. Nothing here touches an {@code ItemStack}, an {@code Item} or a level.
 *
 * <p>The class keeps its 0.7.2 name while the unit of work changed underneath it. On 1.20.1 this was
 * root NBT keys on a {@code CompoundTag}; 1.21.1 replaced item NBT with typed data components, so the
 * same three-way policy is now expressed over component types and the patch that carries them:
 *
 * <ul>
 *   <li><strong>Known</strong> components are carried with understood semantics — the name, the lore,
 *       the wear, the enchantments and curses, the anvil cost, the dye.</li>
 *   <li><strong>Unknown but recoverable</strong> components are copied verbatim. A foreign mod that
 *       stamped {@code somemod:owner} on a mask meant it to follow the item, and dropping it would
 *       make this station a laundering service for exactly the flags §14.4 says must survive
 *       customization. On 1.21.1 that is strictly better than it was: a component the game can read
 *       back is a component this transfer can carry, whoever registered it.</li>
 *   <li><strong>Unsupported</strong> components refuse the conversion outright. These are not data:
 *       {@code block_entity_data} and {@code entity_data} are payloads for a placement this item
 *       cannot perform, so copying them would produce a stack whose contents nothing will ever read
 *       again — silent loss wearing a copy's clothes. {@code ForgeCaps}, the third refusal on 1.20.1,
 *       has no analogue to list: capability serialization is gone, and 1.21.1 item stacks carry no
 *       data attachments either (only entities, chunks and levels do), so the component patch really
 *       is the whole of a stack's state.</li>
 * </ul>
 */
public final class MaskNbtTransfer {

    /** Components carried with understood semantics. */
    public static final List<DataComponentType<?>> KNOWN_TYPES = List.of(
            DataComponents.DAMAGE,
            DataComponents.CUSTOM_NAME,
            DataComponents.LORE,
            DataComponents.ENCHANTMENTS,
            DataComponents.REPAIR_COST,
            DataComponents.UNBREAKABLE,
            DataComponents.DYED_COLOR,
            DataComponents.HIDE_ADDITIONAL_TOOLTIP);

    /**
     * Components that refuse the conversion.
     *
     * <p>Deliberately short and deliberately not "everything unfamiliar": rejecting every unknown
     * component would mean any mod that ever writes to a mask permanently disables restyling for its
     * users, and the spec asks for preserved unknown data with rejection reserved for what genuinely
     * cannot move.
     */
    public static final List<DataComponentType<?>> UNSUPPORTED_TYPES = List.of(
            DataComponents.BLOCK_ENTITY_DATA,
            DataComponents.ENTITY_DATA);

    private MaskNbtTransfer() {
    }

    /** The registered id of a component type, as it is spelled in a patch and in an error message. */
    public static String keyOf(DataComponentType<?> type) {
        ResourceLocation id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
        return id == null ? String.valueOf(type) : id.toString();
    }

    /** The known component ids, for the callers and tests that talk in names. */
    public static List<String> knownKeys() {
        return KNOWN_TYPES.stream().map(MaskNbtTransfer::keyOf).toList();
    }

    /** The refusing component ids. */
    public static List<String> unsupportedKeyNames() {
        return UNSUPPORTED_TYPES.stream().map(MaskNbtTransfer::keyOf).toList();
    }

    /** Whether this component stops a conversion. */
    public static boolean unsupported(DataComponentType<?> type) {
        return UNSUPPORTED_TYPES.contains(type);
    }

    /** Whether this component id stops a conversion. */
    public static boolean unsupported(String key) {
        return unsupportedKeyNames().contains(key);
    }

    /** Every unsupported component on this patch, sorted by id. Empty means the transfer may proceed. */
    public static List<String> unsupportedKeys(@Nullable DataComponentPatch source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<String> refused = new ArrayList<>();
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : source.entrySet()) {
            // A removal is not data: a patch that says "this mask has no lore" carries nothing that
            // could be lost, so only a present value can refuse the conversion.
            if (entry.getValue().isPresent() && unsupported(entry.getKey())) {
                refused.add(keyOf(entry.getKey()));
            }
        }
        return refused.stream().sorted().toList();
    }

    /**
     * The target's component patch after the source's history has been moved onto it.
     *
     * <p>{@code minecraft:damage} is never carried here — the caller sets it through the stack so that
     * the "same wear budget or no conversion" rule is enforced against real maximum durabilities
     * rather than against two integers that happen to be present. Everything else the source has wins
     * over whatever the fresh target stack was born with, because the fresh stack has no history to
     * lose.
     *
     * @param source the source stack's component patch, or {@code null} for a stack with none
     * @param target the fresh target stack's component patch, or {@code null}
     * @return the merged patch, or empty when the source carries an unsupported component
     */
    public static Optional<DataComponentPatch> merge(@Nullable DataComponentPatch source,
                                                     @Nullable DataComponentPatch target) {
        if (!unsupportedKeys(source).isEmpty()) {
            return Optional.empty();
        }
        DataComponentPatch.Builder builder = DataComponentPatch.builder();
        if (target != null) {
            for (Map.Entry<DataComponentType<?>, Optional<?>> entry : target.entrySet()) {
                apply(builder, entry.getKey(), entry.getValue());
            }
        }
        if (source != null) {
            for (Map.Entry<DataComponentType<?>, Optional<?>> entry : source.entrySet()) {
                if (entry.getKey() == DataComponents.DAMAGE) {
                    continue;
                }
                apply(builder, entry.getKey(), entry.getValue());
            }
        }
        return Optional.of(builder.build());
    }

    /**
     * One patch entry onto the builder, present as a value and absent as an explicit removal.
     *
     * <p>Unchecked because a patch has already erased the link between its key and its value; the
     * entry came out of a {@link DataComponentPatch}, so the pairing it carries is the one the game
     * itself built.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void apply(DataComponentPatch.Builder builder, DataComponentType<?> type,
                              Optional<?> value) {
        if (value.isPresent()) {
            builder.set((DataComponentType) type, value.get());
        } else {
            builder.remove((DataComponentType) type);
        }
    }
}
