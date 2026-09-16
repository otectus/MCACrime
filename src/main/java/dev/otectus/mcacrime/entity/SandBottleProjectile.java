package dev.otectus.mcacrime.entity;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.detect.WitnessResult;
import dev.otectus.mcacrime.effect.SandExposurePolicy;
import dev.otectus.mcacrime.effect.SandExposureService;
import dev.otectus.mcacrime.effect.SandIncidentPolicy;
import dev.otectus.mcacrime.item.CrimeItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A thrown Sand Bottle (0.7.2 §13.2).
 *
 * <p>It carries its own provenance. {@link net.minecraft.world.entity.projectile.Projectile} already
 * records who threw it; what this adds is <em>what was true when they threw it</em> — the tick, the
 * mask they were wearing, and who had already seen them throw. Reading any of that at impact instead
 * would let three seconds of flight rewrite history: change mask mid-air and the witness statement
 * changes with it (§14.3, SAND-15).
 *
 * <p>Two guarantees the rest of the system leans on. The burst happens at most once — a hit result
 * delivered twice, or a hit followed by another before the discard lands, cannot double-apply — and
 * the entity never outlives {@link SandExposurePolicy#DEFAULT_LIFETIME_TICKS}, so a bottle thrown
 * into an unloaded direction is not a permanent passenger of its chunk.
 *
 * <p>No glass bottle is returned on impact. It broke.
 */
public class SandBottleProjectile extends ThrowableItemProjectile {

    private static final String TAG_LAUNCH_ID = "mcacrime_launch_id";
    private static final String TAG_LAUNCH_TICK = "mcacrime_launch_tick";
    private static final String TAG_MASKED = "mcacrime_masked_at_launch";
    private static final String TAG_WITNESSES = "mcacrime_launch_witnesses";
    private static final String TAG_WITNESS_TOTAL = "mcacrime_launch_witness_total";
    private static final String TAG_AGE = "mcacrime_age";
    /** The entity event that tells watching clients to draw the dust. */
    private static final byte EVENT_BURST = 3;

    private UUID launchId = UUID.randomUUID();
    private long launchTick;
    private boolean maskedAtLaunch;
    private Set<UUID> launchWitnesses = Set.of();
    private int launchWitnessTotal;
    private int age;
    private boolean burst;

    public SandBottleProjectile(EntityType<? extends SandBottleProjectile> type, Level level) {
        super(type, level);
    }

    public SandBottleProjectile(Level level, LivingEntity thrower) {
        super(CrimeEntities.SAND_BOTTLE.get(), thrower, level);
    }

    /** Records what the thrower and the street looked like at the instant of release. */
    public void captureLaunch(UUID launchId, long launchTick, boolean masked, WitnessResult witnesses) {
        this.launchId = launchId;
        this.launchTick = launchTick;
        this.maskedAtLaunch = masked;
        this.launchWitnesses = witnesses == null ? Set.of() : Set.copyOf(witnesses.witnessIds());
        this.launchWitnessTotal = witnesses == null ? 0 : witnesses.totalWitnesses();
    }

    /** The launch-time witness scan, rebuilt as the incident pipeline expects it. */
    public WitnessResult launchWitnesses() {
        return new WitnessResult(launchWitnesses, !launchWitnesses.isEmpty(), launchWitnesses.size(),
                Math.max(launchWitnessTotal, launchWitnesses.size()));
    }

    /** The provenance the legal system commits against, or null when the thrower is unidentifiable. */
    @Nullable
    public SandIncidentPolicy.LaunchSnapshot launchSnapshot() {
        UUID owner = getOwner() == null ? null : getOwner().getUUID();
        return owner == null ? null
                : SandIncidentPolicy.LaunchSnapshot.of(owner, launchId, launchTick, maskedAtLaunch,
                        launchWitnesses);
    }

    @Override
    protected Item getDefaultItem() {
        return CrimeItems.SAND_BOTTLE.get();
    }

    @Override
    public void tick() {
        super.tick();
        if (++age < SandExposurePolicy.DEFAULT_LIFETIME_TICKS) {
            // A bottle whose feature was switched off mid-flight stops being a weapon immediately.
            // The sand it was carrying is not refunded: it was already thrown (SAND-18).
            if (!level().isClientSide() && !McaCrimeConfig.COMMON.enableSandBottles.get()) {
                discard();
            }
            return;
        }
        discard();
    }

    /**
     * One impact, one burst.
     *
     * <p>{@code burst} is set before the burst runs rather than after, so a re-entrant hit — a second
     * collision resolved inside the same tick, or the same result delivered twice — finds the flag
     * already raised instead of racing it.
     */
    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (level().isClientSide() || burst) {
            return;
        }
        burst = true;
        if (McaCrimeConfig.COMMON.enableSandBottles.get() && level() instanceof ServerLevel serverLevel) {
            SandExposureService.burst(serverLevel, this, result);
            // Vanilla's own thrown-item pattern: one entity event before the discard, so every client
            // that can see the impact draws it and each one decides how much of it to draw.
            serverLevel.broadcastEntityEvent(this, EVENT_BURST);
        }
        discard();
    }

    /**
     * The decorative dust, drawn by the client that is watching it.
     *
     * <p>The server broadcasts the event; the amount of dust is the viewer's own
     * {@code client.sandParticles} setting. That split is the point: a player who turns particles down
     * for performance sees less dust and is blinded for exactly as long, because nothing the client
     * decides here reaches a perception rule (§13.4, SAND-20).
     */
    @Override
    public void handleEntityEvent(byte id) {
        if (id != EVENT_BURST || !level().isClientSide()) {
            super.handleEntityEvent(id);
            return;
        }
        int count = switch (McaCrimeConfig.CLIENT.sandParticles.get()) {
            case NORMAL -> 24;
            case REDUCED -> 6;
            case OFF -> 0;
        };
        var dust = new net.minecraft.core.particles.BlockParticleOption(
                net.minecraft.core.particles.ParticleTypes.BLOCK,
                net.minecraft.world.level.block.Blocks.SAND.defaultBlockState());
        for (int i = 0; i < count; i++) {
            level().addParticle(dust,
                    getX() + (random.nextDouble() - 0.5D) * 0.8D,
                    getY() + random.nextDouble() * 0.6D,
                    getZ() + (random.nextDouble() - 0.5D) * 0.8D,
                    (random.nextDouble() - 0.5D) * 0.15D, random.nextDouble() * 0.1D,
                    (random.nextDouble() - 0.5D) * 0.15D);
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putUUID(TAG_LAUNCH_ID, launchId);
        tag.putLong(TAG_LAUNCH_TICK, launchTick);
        tag.putBoolean(TAG_MASKED, maskedAtLaunch);
        tag.putInt(TAG_WITNESS_TOTAL, launchWitnessTotal);
        tag.putInt(TAG_AGE, age);
        ListTag witnesses = new ListTag();
        launchWitnesses.forEach(id -> witnesses.add(NbtUtils.createUUID(id)));
        tag.put(TAG_WITNESSES, witnesses);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID(TAG_LAUNCH_ID)) {
            launchId = tag.getUUID(TAG_LAUNCH_ID);
        }
        launchTick = tag.getLong(TAG_LAUNCH_TICK);
        maskedAtLaunch = tag.getBoolean(TAG_MASKED);
        launchWitnessTotal = tag.getInt(TAG_WITNESS_TOTAL);
        age = tag.getInt(TAG_AGE);
        Set<UUID> witnesses = new LinkedHashSet<>();
        for (Tag element : tag.getList(TAG_WITNESSES, Tag.TAG_INT_ARRAY)) {
            witnesses.add(NbtUtils.loadUUID(element));
        }
        launchWitnesses = Set.copyOf(witnesses);
    }
}
