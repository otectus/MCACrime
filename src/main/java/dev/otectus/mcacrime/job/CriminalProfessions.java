package dev.otectus.mcacrime.job;

import com.google.common.collect.ImmutableSet;
import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import org.jetbrains.annotations.Nullable;

/**
 * The two vanilla {@link VillagerProfession}s a criminal job can be <em>shown</em> as (0.5.1).
 *
 * <p>Vanilla types on purpose: a profession is synced by vanilla, rendered by vanilla and named by
 * vanilla's own {@code entity.minecraft.villager.<namespace>.<path>} key, so nothing here links an MCA
 * class or invents a second sync path. They are presentation only — {@code CrimeWorldData.criminalVillagers}
 * is what makes somebody a thief, and a server whose MCA build has no profession setter simply shows
 * nothing while the crime side keeps working.
 *
 * <p>Nitwit-shaped: {@link PoiType#NONE} for both the held and the acquirable job site, so a fence
 * never claims a workstation, never wanders off to one, and never displaces a villager who wanted it.
 * No work sound, for the same reason a fence does not advertise.
 */
public final class CriminalProfessions {

    public static final DeferredRegister<VillagerProfession> PROFESSIONS =
            DeferredRegister.create(Registries.VILLAGER_PROFESSION, McaCrime.MOD_ID);

    public static final ResourceLocation THIEF_ID = McaCrime.id("thief");
    public static final ResourceLocation FENCE_ID = McaCrime.id("fence");

    public static final DeferredHolder<VillagerProfession, VillagerProfession> THIEF =
            PROFESSIONS.register("thief", () -> profession("thief"));
    public static final DeferredHolder<VillagerProfession, VillagerProfession> FENCE =
            PROFESSIONS.register("fence", () -> profession("fence"));

    private CriminalProfessions() {
    }

    public static void register(IEventBus modBus) {
        PROFESSIONS.register(modBus);
    }

    /** The registry id a job is presented as, or {@code null} for {@link CriminalJob#NONE}. */
    @Nullable
    public static ResourceLocation professionIdFor(CriminalJob job) {
        return switch (job) {
            case THIEF -> THIEF_ID;
            case FENCE -> FENCE_ID;
            case NONE -> null;
        };
    }

    private static VillagerProfession profession(String name) {
        return new VillagerProfession(name, PoiType.NONE, PoiType.NONE,
                ImmutableSet.of(), ImmutableSet.of(), null);
    }
}
