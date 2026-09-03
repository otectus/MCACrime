package dev.otectus.mcacrime.crime.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * A data-driven crime definition (spec §5.1), loaded from {@code data/<ns>/mcacrime/crimes/*.json}. The
 * numbers here are the single source of truth for what a crime costs; the detector decides <em>which</em>
 * crime an event is, then looks up these values.
 *
 * <ul>
 *   <li>{@code karmaDelta} — signed; a penalty is <b>negative</b>.</li>
 *   <li>{@code heatDelta} — non-negative law-enforcement pressure added when the crime generates Heat.</li>
 *   <li>{@code witnessedMultiplier} — scales karma + heat when the crime is witnessed (default 1.0).</li>
 *   <li>{@code victimTag} — informational classification hint ("villager"/"guard"); the detector resolves
 *       the actual victim via {@code McaCompat}, so a missing tag is harmless.</li>
 * </ul>
 */
public record CrimeType(ResourceLocation id, long karmaDelta, long heatDelta,
                        double witnessedMultiplier, String victimTag) {

    public static final Codec<CrimeType> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("id").forGetter(CrimeType::id),
            Codec.LONG.fieldOf("karmaDelta").forGetter(CrimeType::karmaDelta),
            Codec.LONG.fieldOf("heatDelta").forGetter(CrimeType::heatDelta),
            Codec.DOUBLE.optionalFieldOf("witnessedMultiplier", 1.0).forGetter(CrimeType::witnessedMultiplier),
            Codec.STRING.optionalFieldOf("victimTag", "").forGetter(CrimeType::victimTag)
    ).apply(instance, CrimeType::new));
}
