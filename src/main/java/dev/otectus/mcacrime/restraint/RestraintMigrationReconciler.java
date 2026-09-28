package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.captivity.CustodyOwnerType;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import dev.otectus.mcacrime.tether.TetherKind;
import dev.otectus.mcacrime.tether.TetherRecord;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * The once-per-store pass that turns pre-0.7.5 custody rows into physical restraint state (§3.18).
 *
 * <p>Separate from {@link CrimeDataMigrations#v14to15} because it is not tag-to-tag work: it needs
 * the definition registry, it writes item snapshots, and it has to decide provenance. It is also the
 * only part of the upgrade that can get somebody's inventory wrong, which is why it is idempotent and
 * stamped. The failure mode the specification names — "a migration that creates a free extra cuff on
 * every login" — is prevented twice over: by the {@code reconciledSchema} marker, and by every
 * conversion refusing a slot that is already occupied.
 *
 * <h2>The conversions</h2>
 * <ul>
 *   <li>{@code ROPE} → {@code duck_tape_arms} — the rope item stays registered as a legacy carrier
 *       and is normalised to tape at controlled boundaries, never by rewriting NBT in bulk;</li>
 *   <li>{@code CUFFS} → {@code shackles_arms}, keeping the {@code restraint_cuffs} item;</li>
 *   <li>{@code LOCKED_CUFFS} → {@code handcuffs_arms}, keeping the {@code restraint_locked_cuffs}
 *       item;</li>
 *   <li>{@code NONE} → nothing at all. A custody with no restraint was a custody with no restraint.</li>
 * </ul>
 *
 * <p>Every converted instance is {@link AppliedRestraint.Provenance#LEGACY_CONVERSION} with
 * {@link AppliedRestraint.ReturnPolicy#NONE} and a conservative item snapshot: the right item, full
 * durability, no enchantments claimed. The old record stored none of those, so claiming any would be
 * invention, and a return policy of anything but {@code NONE} would mint an item that never existed.
 *
 * <p>A legacy kidnapping {@code holdPos} becomes a {@link dev.otectus.mcacrime.tether.TetherRecord}
 * of kind {@code LEGACY_HOLD}: no fence knot is fabricated and no chain item is dropped, because
 * nobody ever paid for one. The retired {@code cuffCombination} is filed under the existing
 * {@code archives} reserved slot and then cleared — it is preserved, and it unlocks nothing.
 *
 * <p>In-flight capture and escape work needs no cancellation message here: the capture channel was
 * never persisted, and the persisted half of a timed escape is the {@code escapeActive} flag that
 * {@link CrimeDataMigrations#v14to15} already cleared. This clears it again for a store that reached
 * schema 15 by another route, and charges nobody an item for it.
 */
public final class RestraintMigrationReconciler {

    /** Where retired cuff combinations are filed. */
    public static final String ARCHIVE_SECTION = "legacyCuffCombinations";

    /**
     * The tether length a converted legacy hold gets, in blocks.
     *
     * <p>A constant rather than a config read, because this runs inside {@code load} where the
     * config may not be loaded, and because it has to produce the same answer in a unit test. It is
     * the shipped default of the {@code captiveTetherBlocks} key the legacy hold was governed by.
     */
    public static final double LEGACY_TETHER_BLOCKS = 6.0D;

    private RestraintMigrationReconciler() {
    }

    /** What one reconciliation pass did. */
    public record Result(boolean ran, int restraintsConverted, int tethersCreated,
                         int combinationsArchived, int escapesCancelled) {

        /** The store was frozen, or the pass had already run. */
        public static Result skipped() {
            return new Result(false, 0, 0, 0, 0);
        }
    }

    /**
     * Runs the pass if it has not run before.
     *
     * @param data     the store, already at schema 15
     * @param gameTime the game time stamped on every converted instance
     */
    public static Result reconcile(@Nullable CrimeWorldData data, long gameTime) {
        if (data == null || !ServerMutationGate.allows(data)) {
            return Result.skipped();
        }
        if (data.reconciledSchema() >= CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL) {
            return Result.skipped(); // already done; a repeated load changes nothing
        }

        int converted = 0;
        int tethers = 0;
        int archived = 0;
        int cancelled = 0;

        for (CustodyRecord record : data.custodyRecords()) {
            UUID captive = record.getCaptive();
            if (captive == null) {
                continue;
            }

            if (convert(data, record, gameTime)) {
                converted++;
            }
            if (createLegacyHold(data, record)) {
                tethers++;
            }
            if (record.getCuffCombination().length > 0) {
                if (data.archive(ARCHIVE_SECTION, captive.toString(),
                        new ByteArrayTag(record.getCuffCombination()))) {
                    archived++;
                }
                record.setCuffCombination(new byte[0]);
                data.putCustody(record);
            }
            if (record.isEscapeActive()) {
                record.setEscapeActive(false);
                record.setEscapeProgress(0);
                data.putCustody(record);
                cancelled++;
            }
        }

        data.markReconciled(CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL);
        return new Result(true, converted, tethers, archived, cancelled);
    }

    /**
     * Gives an arrested subject with no explicit gear one system-issued pair of handcuffs.
     *
     * <p>Split out from {@link #reconcile} because arrest phase lives on the player's own capability
     * and is therefore only readable once that player is online — the world pass cannot see it. Safe
     * to call on every login: an occupied arm slot is left exactly as it is, so the second call adds
     * nothing.
     *
     * @param restrainedPhase whether the subject's arrest phase is {@code RESTRAINED}
     * @return true when this call is what added the gear
     */
    public static boolean reconcileArrestPhase(@Nullable CrimeWorldData data, @Nullable UUID subject,
                                               boolean restrainedPhase, long gameTime) {
        if (data == null || subject == null || !restrainedPhase || !ServerMutationGate.allows(data)) {
            return false;
        }
        PhysicalRestraintState state = data.physicalRestraint(subject);
        if (state != null && state.occupied(RestraintSlot.ARMS)) {
            return false; // already wearing something on the arms; adding a second pair is the bug
        }
        RestraintDefinition definition = RestraintDefinitions.get(RestraintDefinitions.HANDCUFFS_ARMS)
                .orElse(null);
        if (definition == null) {
            return false;
        }
        CustodyRecord record = data.getCustody(subject);
        PhysicalRestraintState base = state != null ? state
                : PhysicalRestraintState.empty(subject, true,
                        record == null ? null : record.getHoldDim());
        AppliedRestraint applied = new AppliedRestraint(
                UUID.randomUUID(), definition.id(), snapshot(definition),
                RestraintDurability.startingDurability(definition), definition.revision(),
                RestraintApplier.system(),
                AppliedRestraint.ApplicationContext.LAWFUL,
                AppliedRestraint.Provenance.SYSTEM_ISSUED,
                AppliedRestraint.ReturnPolicy.NONE,
                record == null ? null : record.getCustodyId(), gameTime, 1L);
        if (!data.putPhysicalRestraint(base.with(RestraintSlot.ARMS, applied))) {
            return false;
        }
        // Converted, so the row stops carrying a second answer to the same question. From here the
        // gear is the physical instance and nothing else (§3.2).
        record.setLegacyRestraint(dev.otectus.mcacrime.captivity.RestraintType.NONE);
        data.putCustody(record);
        return true;
    }

    /** The definition a pre-0.7.5 {@link RestraintType} becomes, empty for {@code NONE}. */
    public static Optional<ResourceLocation> definitionFor(@Nullable RestraintType type) {
        if (type == null) {
            return Optional.empty();
        }
        return switch (type) {
            case NONE -> Optional.empty();
            case ROPE -> Optional.of(RestraintDefinitions.DUCK_TAPE_ARMS);
            case CUFFS -> Optional.of(RestraintDefinitions.SHACKLES_ARMS);
            case LOCKED_CUFFS -> Optional.of(RestraintDefinitions.HANDCUFFS_ARMS);
        };
    }

    private static boolean convert(CrimeWorldData data, CustodyRecord record, long gameTime) {
        Optional<ResourceLocation> definitionId = definitionFor(record.getLegacyRestraint());
        if (definitionId.isEmpty()) {
            return false;
        }
        RestraintDefinition definition = RestraintDefinitions.get(definitionId.get()).orElse(null);
        if (definition == null) {
            return false;
        }
        UUID captive = record.getCaptive();
        PhysicalRestraintState existing = data.physicalRestraint(captive);
        if (existing != null && existing.occupied(RestraintSlot.ARMS)) {
            return false; // idempotent: the slot this conversion writes to is already accounted for
        }
        PhysicalRestraintState base = existing != null ? existing
                : PhysicalRestraintState.empty(captive, record.isCaptivePlayer(), record.getHoldDim());
        AppliedRestraint applied = new AppliedRestraint(
                UUID.randomUUID(),
                definition.id(),
                snapshot(definition),
                RestraintDurability.startingDurability(definition),
                definition.revision(),
                applier(record),
                record.isLawful() ? AppliedRestraint.ApplicationContext.LAWFUL
                        : AppliedRestraint.ApplicationContext.UNLAWFUL,
                AppliedRestraint.Provenance.LEGACY_CONVERSION,
                AppliedRestraint.ReturnPolicy.NONE,
                record.getCustodyId(),
                gameTime,
                1L);
        return data.putPhysicalRestraint(base.with(RestraintSlot.ARMS, applied));
    }

    private static boolean createLegacyHold(CrimeWorldData data, CustodyRecord record) {
        if (record.isLawful() || !record.hasValidHold()) {
            return false; // a lawful hold is a cell or an escort, neither of which is a tether
        }
        UUID captive = record.getCaptive();
        for (TetherRecord existing : data.tethersForSubject(captive)) {
            if (existing.kind() == TetherKind.LEGACY_HOLD) {
                return false; // idempotent
            }
        }
        UUID id = UUID.nameUUIDFromBytes(("mcacrime:legacyHold:" + captive)
                .getBytes(StandardCharsets.UTF_8));
        TetherRecord tether = TetherRecord.toAnchor(id, captive, TetherKind.LEGACY_HOLD,
                record.getHoldDim(), record.getHoldPos(), LEGACY_TETHER_BLOCKS, null, false);
        if (!data.putTether(tether)) {
            return false;
        }
        PhysicalRestraintState state = data.physicalRestraint(captive);
        if (state != null) {
            data.putPhysicalRestraint(state.withTether(id));
        }
        return true;
    }

    /**
     * Who the converted gear is attributed to.
     *
     * <p>A kidnapper is a player, a guard is a villager, and a jail or an authority is the server
     * itself. Nothing is invented: an owner the old record did not name stays unattributed.
     */
    private static RestraintApplier applier(CustodyRecord record) {
        CustodyOwnerType type = record.getOwner() == null ? CustodyOwnerType.NONE : record.getOwner().type();
        Optional<UUID> owner = record.getOwner() == null ? Optional.empty() : record.getOwner().ownerUuid();
        return switch (type) {
            case KIDNAPPER -> owner.map(RestraintApplier::player).orElseGet(RestraintApplier::none);
            case GUARD, BOUNTY_HUNTER -> owner.map(RestraintApplier::npc).orElseGet(RestraintApplier::system);
            case JAIL, AUTHORITY -> RestraintApplier.system();
            case NONE -> RestraintApplier.none();
        };
    }

    /**
     * A conservative item snapshot: the right item, one of it, and nothing else claimed.
     *
     * <p>Built as the tag {@code ItemStack.saveOptional} writes rather than as a stack, because this
     * runs inside the load path where the item registry is not a safe thing to touch, and because the
     * tag is the persisted form anyway. That shape is 1.21's {@code ItemStack.CODEC} one — {@code id}
     * and a lower-case {@code count} — with no {@code components} block at all, which is precisely the
     * "no enchantments claimed" the old record could not have stored.
     */
    private static CompoundTag snapshot(RestraintDefinition definition) {
        ResourceLocation item = definition.item().orElse(null);
        if (item == null) {
            return null;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("id", item.toString());
        tag.putInt("count", 1);
        return tag;
    }
}
