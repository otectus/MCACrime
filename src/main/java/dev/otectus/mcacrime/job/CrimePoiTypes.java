package dev.otectus.mcacrime.job;

import com.google.common.collect.ImmutableSet;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.block.CrimeBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Set;

/**
 * The Mask Station point of interest (0.7.2 §10.1–10.2).
 *
 * <p>One ticket and a valid range of one, exactly like every vanilla workstation: one station supplies
 * one villager's claim, which is the invariant spec §10.2 states as "one station must never supply two
 * active villager worksite claims", and the range is what makes standing at the block count as
 * arriving at it.
 *
 * <p>All four horizontal states are listed. A POI type matches block <em>states</em>, not blocks, so
 * omitting a facing would make a station placed that way invisible to every search — the JOB-04 case.
 *
 * <p>Forge attaches the block-state mapping itself through {@code PointOfInterestTypeCallbacks} on the
 * POI registry, so nothing here calls vanilla's private {@code PoiTypes.registerBlockStates} and this
 * mod still needs no access transformer.
 */
public final class CrimePoiTypes {

    public static final DeferredRegister<PoiType> POI_TYPES =
            DeferredRegister.create(ForgeRegistries.POI_TYPES, McaCrime.MOD_ID);

    /** The key the Thief profession's predicates and every ticket operation compare against. */
    public static final ResourceKey<PoiType> MASK_STATION_KEY =
            ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, McaCrime.id("mask_station"));

    public static final RegistryObject<PoiType> MASK_STATION = POI_TYPES.register("mask_station",
            () -> new PoiType(states(), 1, 1));

    private CrimePoiTypes() {
    }

    public static void register(IEventBus modBus) {
        POI_TYPES.register(modBus);
    }

    private static Set<BlockState> states() {
        return ImmutableSet.copyOf(CrimeBlocks.MASK_STATION.get().getStateDefinition().getPossibleStates());
    }
}
