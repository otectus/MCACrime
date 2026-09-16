package dev.otectus.mcacrime.mixin;

import dev.otectus.mcacrime.job.CriminalProfessions;
import dev.otectus.mcacrime.job.NativeOccupationPolicy;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.VillagerProfession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * Keeps every profession except Thief from acquiring a Mask Station (0.7.2 §10.1).
 *
 * <p>The station has to be in {@code minecraft:acquirable_job_site} for vanilla's search to find it at
 * all, and that tag <em>is</em> {@code VillagerProfession.NONE}'s acquirable predicate — so an
 * unemployed villager, and MCA's own "set workplace" action, would otherwise pick a station up without
 * passing a single one of Crime's eligibility checks. Filtering the accessor is narrower than
 * intercepting each search: it fixes both callers at their one shared source, and it leaves
 * {@code ALL_ACQUIRABLE_JOBS} and its tag intact, which is what keeps the inherited potential-ticket
 * release working.
 *
 * <p>Only the Mask Station is removed. Every other point of interest still runs the original
 * predicate, including one another mod may already have narrowed.
 */
@Mixin(VillagerProfession.class)
public abstract class MaskStationAcquisitionMixin {

    /** Cached per profession instance: the accessor is called on every acquisition search. */
    @Unique
    private Predicate<Holder<PoiType>> mcacrime$filteredSource;

    @Unique
    private Predicate<Holder<PoiType>> mcacrime$filtered;

    @Inject(method = "acquirableJobSite", at = @At("RETURN"), cancellable = true)
    private void mcacrime$excludeMaskStation(CallbackInfoReturnable<Predicate<Holder<PoiType>>> cir) {
        Predicate<Holder<PoiType>> original = cir.getReturnValue();
        if (original == null || CriminalProfessions.isThief((VillagerProfession) (Object) this)) {
            return;
        }
        if (mcacrime$filtered == null || mcacrime$filteredSource != original) {
            mcacrime$filteredSource = original;
            mcacrime$filtered = NativeOccupationPolicy.excludingMaskStation(original);
        }
        cir.setReturnValue(mcacrime$filtered);
    }
}
