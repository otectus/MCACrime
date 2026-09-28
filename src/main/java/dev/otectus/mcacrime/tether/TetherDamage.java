package dev.otectus.mcacrime.tether;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * Being hurt by the thing that is holding you (0.7.5 M4.1).
 *
 * <p>One damage type, {@code mcacrime:hang}, declared as a datapack file so a pack can reweigh it,
 * and <em>attributed</em>: the responsible entity is whoever or whatever holds the other end. The
 * source's {@code ModDamageTypes.GetModSource(entity, type, other)} ignores its type argument and
 * always resolves hang ({@code init/ModDamageTypes.java:23-25}), and substitutes the <em>victim</em>
 * as the responsible entity whenever the caller passes null ({@code :24}) — so upstream's guillotine
 * records every execution as a suicide. Here a source with no responsible entity is exactly that: an
 * environmental death with nobody to blame, which is the honest answer and the one the existing
 * witness and bounty rules already know how to handle.
 *
 * <p>A second damage type, {@code mcacrime:imbue}, is M6.1's and is deliberately not declared here:
 * two distinct types is the fix, and half of it in one milestone would be the bug again.
 */
public final class TetherDamage {

    /** The datapack key's path. Its file is {@code data/mcacrime/damage_type/hang.json}. */
    public static final String HANG_PATH = "hang";

    /**
     * The datapack key, resolved on first use.
     *
     * <p>Lazy rather than a constant, because {@code Registries.DAMAGE_TYPE} is a bootstrap-time
     * static and touching it from a class that gameplay code imports would make every unit test in
     * this package need a booted registry to load a pure arithmetic helper.
     */
    private static volatile ResourceKey<DamageType> hangKey;

    private TetherDamage() {
    }

    /** The {@code mcacrime:hang} key. */
    public static ResourceKey<DamageType> hang() {
        ResourceKey<DamageType> key = hangKey;
        if (key == null) {
            key = ResourceKey.create(Registries.DAMAGE_TYPE, McaCrime.id(HANG_PATH));
            hangKey = key;
        }
        return key;
    }

    /**
     * The hang source in {@code level}, attributed to {@code responsible}.
     *
     * <p>Null when the damage type is not in this world's registry — an operator who removed the
     * datapack file gets no damage rather than a crash in the middle of an escort.
     *
     * @param responsible the holder, the anchor entity or the device; null for an unattributed fall
     */
    @Nullable
    public static DamageSource hang(@Nullable Level level, @Nullable Entity responsible) {
        if (level == null) {
            return null;
        }
        try {
            Holder<DamageType> type = level.registryAccess()
                    .registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(hang());
            return responsible == null ? new DamageSource(type)
                    : new DamageSource(type, responsible, responsible);
        } catch (RuntimeException missing) {
            McaCrime.LOGGER.debug("MCA: Crime could not resolve the mcacrime:hang damage type; "
                    + "suspension damage is skipped this tick");
            return null;
        }
    }

    /**
     * Whether this tether is allowed to injure the subject at all.
     *
     * <p>Pure. An escort under a lawful custody policy does not deliberately harm the prisoner it is
     * walking, which is what {@code transport.guardTransportHarmless} says; a chain held by a
     * kidnapper, or a fixed anchor nobody is minding, has no such policy behind it.
     */
    public static boolean harmless(@Nullable TetherKind kind, boolean guardTransportHarmless) {
        return kind == TetherKind.ESCORT && guardTransportHarmless;
    }

    /** {@code transport.guardTransportHarmless}, defaulting to the documented true with no config. */
    public static boolean guardTransportHarmless() {
        try {
            return McaCrimeConfig.COMMON.guardTransportHarmless.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return true;
        }
    }

    /** {@code transport.suspensionDamagePerTick}, defaulting to the documented 2.0. */
    public static double suspensionDamagePerTick() {
        try {
            return McaCrimeConfig.COMMON.suspensionDamagePerTick.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return 2.0D;
        }
    }

    /**
     * Applies one tick of suspension damage, if any is owed.
     *
     * <p>Through {@code hurt} and the ordinary damage pipeline, so armour, resistance, totems,
     * invulnerability and every other mod's cancellation all still apply — a tether is not allowed to
     * be the one thing in the game that kills past a defence.
     *
     * @return true when damage was actually dealt
     */
    public static boolean applyTick(@Nullable LivingEntity subject, @Nullable Entity responsible,
                                    double distance, double maxLength, double overextensionLength,
                                    @Nullable TetherKind kind) {
        if (subject == null || !subject.isAlive() || subject.level().isClientSide()) {
            return false;
        }
        float amount = TetherPhysics.suspensionDamage(distance, maxLength, overextensionLength,
                suspensionDamagePerTick(), harmless(kind, guardTransportHarmless()));
        if (amount <= 0.0F) {
            return false;
        }
        DamageSource source = hang(subject.level(), responsible);
        return source != null && subject.hurt(source, amount);
    }
}
