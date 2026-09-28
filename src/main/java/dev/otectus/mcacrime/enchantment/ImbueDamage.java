package dev.otectus.mcacrime.enchantment;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * The {@code mcacrime:imbue} damage type (0.7.5 §3.10, M6.1).
 *
 * <p>The second of the two distinct types the plan's defect table asks for. Upstream has one
 * factory, {@code ModDamageTypes.GetModSource(entity, type, other)}, which ignores its type argument
 * and always resolves hang ({@code init/ModDamageTypes.java:23-25}); its Imbue transfer therefore
 * arrives as {@code captor.damageSources().magic()} and a prisoner killed by it is credited to
 * generic magic with nobody responsible. Here the transfer is its own type, attributed to the
 * captor, so the death reads as what it is and the witness, bounty and grief paths can see who did
 * it.
 *
 * <p>Deliberately a sibling of {@code tether/TetherDamage} rather than a shared helper with a type
 * parameter: one factory that takes "which type" is the upstream bug, and two four-line classes
 * cannot have it.
 */
public final class ImbueDamage {

    /** The datapack key's path. Its file is {@code data/mcacrime/damage_type/imbue.json}. */
    public static final String IMBUE_PATH = "imbue";

    private static volatile ResourceKey<DamageType> imbueKey;

    private ImbueDamage() {
    }

    /** The {@code mcacrime:imbue} key, resolved on first use. */
    public static ResourceKey<DamageType> imbue() {
        ResourceKey<DamageType> key = imbueKey;
        if (key == null) {
            key = ResourceKey.create(Registries.DAMAGE_TYPE, McaCrime.id(IMBUE_PATH));
            imbueKey = key;
        }
        return key;
    }

    /** Whether a source is the passive Imbue transfer rather than a voluntary attack. */
    public static boolean isImbue(@Nullable DamageSource source) {
        return source != null && source.is(imbue());
    }

    /**
     * The Imbue source in {@code level}, attributed to the captor.
     *
     * <p>Null when the damage type is not in this world's registry, and a null source transfers
     * nothing: an operator who removed the datapack file gets no transfer rather than a crash inside
     * a damage handler.
     */
    @Nullable
    public static DamageSource source(@Nullable Level level, @Nullable Entity captor) {
        if (level == null) {
            return null;
        }
        try {
            Holder<DamageType> type = level.registryAccess()
                    .registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(imbue());
            return captor == null ? new DamageSource(type) : new DamageSource(type, captor, captor);
        } catch (RuntimeException missing) {
            McaCrime.LOGGER.debug("MCA: Crime could not resolve the mcacrime:imbue damage type; "
                    + "the transfer is skipped");
            return null;
        }
    }
}
