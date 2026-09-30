package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.ai.CrimeEntityTags;
import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * The server-side face of the physical restraint engine (0.7.5 M2.3).
 *
 * <p>Owns everything {@link ApplicationTransaction} deliberately does not: entities, reach, line of
 * sight, item movement, sound, sync and the tick. The transaction owns the decision and the single
 * write. Keeping the two apart is what lets the contested cases be tested without a server, and it is
 * also the shape that replaces {@code captivity/CaptureService} plus {@code CaptureTicker} plus
 * {@code CaptureChannel} — one service with a commit inside it rather than a channel object whose
 * lifetime nothing owned.
 *
 * <p>Nothing here decides law. A restraint going on is a physical event; whether it is an arrest, a
 * kidnapping or a game between friends is {@code restraint/CustodyTransitionService}'s question, and
 * this class only records which authority the caller claimed.
 */
public final class RestraintService {

    private RestraintService() {
    }

    // --- who can be restrained ------------------------------------------------------------------

    /**
     * Whether {@code subject} is something this mod restrains at all.
     *
     * <p>Players and MCA villagers by default, plus whatever a datapack puts in
     * {@code mcacrime:restrainable_entities}. The tag widens the set and never narrows it: a pack
     * that wants a modded NPC restrainable adds it, and a pack cannot make players unrestrainable by
     * emptying a tag, because a restraint the player is already wearing would then have no route off.
     */
    public static boolean restrainable(@Nullable Entity subject) {
        if (subject == null || !subject.isAlive()) {
            return false;
        }
        if (subject instanceof Player) {
            return true;
        }
        if (McaCompat.isMcaVillager(subject)) {
            return true;
        }
        return subject.getType().is(CrimeEntityTags.RESTRAINABLE);
    }

    /** The rig {@code subject} wears gear on. */
    public static RigProfile rig(@Nullable Entity subject) {
        return RigResolver.of(subject);
    }

    // --- evaluation ------------------------------------------------------------------------------

    /**
     * Whether this application would stand, without doing it.
     *
     * <p>The availability probe that {@code CaptureService}'s used to be, and the one the action menu
     * and the interaction router both consult so a player is told "that slot is taken" rather than
     * having a right-click quietly do nothing.
     */
    public static ApplicationTransaction.Refusal evaluate(@Nullable ServerPlayer actor,
                                                          @Nullable LivingEntity subject,
                                                          @Nullable ItemStack stack,
                                                          @Nullable RestraintSlot slot) {
        if (actor == null || subject == null || slot == null || !restrainable(subject)) {
            return ApplicationTransaction.Refusal.NO_SUBJECT;
        }
        if (stack == null || stack.isEmpty()) {
            return ApplicationTransaction.Refusal.NO_DEFINITION;
        }
        Optional<ResourceLocation> definitionId = CrimeItems.familyFor(stack)
                .flatMap(family -> ApplicationTransaction.definitionFor(family, slot));
        if (definitionId.isEmpty()) {
            return ApplicationTransaction.Refusal.NO_DEFINITION;
        }
        boolean self = actor.getUUID().equals(subject.getUUID());
        if (self && !allowSelfApplication()) {
            return ApplicationTransaction.Refusal.SELF_APPLICATION_DISABLED;
        }
        // Two physical systems, one body (§3.17, M6.2). Application only: this never stops anybody
        // taking a restraint off, recovering a stuck subject or loading an existing row.
        if (!dev.otectus.mcacrime.compat.CuffedCoexistence.mayApplyRestraints()) {
            return ApplicationTransaction.Refusal.COEXISTENCE_REFUSED;
        }
        // Reach and sight (§3.12 restraints.application). Checked here rather than at the interaction
        // because the crime menu is a second door: it validates distance when it opens and then accepts
        // a Restrain click for as long as it stays open, so without this a player could walk away from
        // the menu and still cuff somebody across the village.
        if (!self && !withinApplicationReach(actor, subject)) {
            return ApplicationTransaction.Refusal.OUT_OF_REACH;
        }
        CrimeWorldData data = data(subject);
        if (!self && !VulnerabilityGates.permits(VulnerabilityGates.configured(),
                vulnerability(subject, data))) {
            return ApplicationTransaction.Refusal.NOT_VULNERABLE;
        }
        return ApplicationTransaction.check(data, request(actor, subject, slot, definitionId.get(),
                null, self), rig(subject));
    }

    /**
     * Whether {@code actor} is close enough to {@code subject}, and can see them, to put a restraint on.
     *
     * <p>Reads {@code restraints.application.maxRangeBlocks} and {@code requireLineOfSight} live, so a
     * reload applies to the next attempt. A different level is never in reach.
     */
    public static boolean withinApplicationReach(@Nullable LivingEntity actor, @Nullable LivingEntity subject) {
        if (actor == null || subject == null || actor.level() != subject.level()) {
            return false;
        }
        double maxRange;
        boolean requireSight;
        try {
            maxRange = McaCrimeConfig.COMMON.applicationMaxRangeBlocks.get();
            requireSight = McaCrimeConfig.COMMON.applicationRequireLineOfSight.get();
        } catch (IllegalStateException e) {
            maxRange = 4.0D;
            requireSight = true;
        }
        return reachAllows(actor.distanceToSqr(subject), !requireSight || actor.hasLineOfSight(subject),
                maxRange, requireSight);
    }

    /** The reach rule as a pure function of what the server observed, so it is testable without a level. */
    public static boolean reachAllows(double distanceSqr, boolean lineOfSight, double maxRangeBlocks,
                                      boolean requireLineOfSight) {
        if (!(distanceSqr >= 0.0D) || maxRangeBlocks <= 0.0D) {
            return false;
        }
        if (distanceSqr > maxRangeBlocks * maxRangeBlocks) {
            return false;
        }
        return !requireLineOfSight || lineOfSight;
    }

    /**
     * The subject's openings, read from the live entity.
     *
     * <p>A self-application never consults this — a subject asking for a restraint has already
     * consented, and requiring them to be injured first would make the self panel unusable.
     */
    public static VulnerabilityGates.Context vulnerability(@Nullable LivingEntity subject,
                                                           @Nullable CrimeWorldData data) {
        if (subject == null) {
            return VulnerabilityGates.Context.none();
        }
        float lowHealthFraction;
        try {
            lowHealthFraction = McaCrimeConfig.COMMON.lowHealthFraction.get().floatValue();
        } catch (IllegalStateException e) {
            lowHealthFraction = 0.35F;
        }
        // A downed player is vulnerable whatever their hearts say: PlayerRevive keeps them at their
        // last health while they bleed out, so without this they read as a healthy target (M6.2).
        boolean lowHealth = (subject.getMaxHealth() > 0.0F
                && subject.getHealth() <= subject.getMaxHealth() * lowHealthFraction)
                || dev.otectus.mcacrime.compat.ReviveCompat.downed(subject);
        boolean sleeping = subject.isSleeping() || McaCompat.isVillagerSleeping(subject);
        PhysicalRestraintState state = data == null ? null : data.physicalRestraint(subject.getUUID());
        CustodyRecord custody = data == null ? null : data.getCustody(subject.getUUID());
        return new VulnerabilityGates.Context(
                lowHealth,
                sleeping,
                !subject.isAlive() || subject.isDeadOrDying(),
                state != null && state.restrained(),
                custody != null && custody.isLawful(),
                state != null && state.detentionId() != null);
    }

    // --- application -----------------------------------------------------------------------------

    /**
     * Applies the restraint {@code hand} is holding to {@code subject}'s {@code slot}.
     *
     * <p>The six-step order of specification §7.2, with the two halves that need a world here: the
     * exact stack is snapshotted <em>before</em> the commit and shrunk <em>after</em> it, so a store
     * refusal at the last moment costs nobody an item and a successful commit can never be followed
     * by a failed spend — the stack identity is re-checked against the snapshot before the shrink.
     */
    public static ApplicationTransaction.Result apply(@Nullable ServerPlayer actor,
                                                      @Nullable LivingEntity subject,
                                                      @Nullable InteractionHand hand,
                                                      @Nullable RestraintSlot slot,
                                                      AppliedRestraint.ApplicationContext context) {
        if (actor == null || subject == null || hand == null || slot == null) {
            return ApplicationTransaction.Result.refused(ApplicationTransaction.Refusal.NO_SUBJECT);
        }
        ItemStack held = actor.getItemInHand(hand);
        ApplicationTransaction.Refusal refusal = evaluate(actor, subject, held, slot);
        if (refusal != ApplicationTransaction.Refusal.NONE) {
            return ApplicationTransaction.Result.refused(refusal);
        }
        ResourceLocation definitionId = CrimeItems.familyFor(held)
                .flatMap(family -> ApplicationTransaction.definitionFor(family, slot))
                .orElse(null);
        if (definitionId == null) {
            return ApplicationTransaction.Result.refused(ApplicationTransaction.Refusal.NO_DEFINITION);
        }
        boolean self = actor.getUUID().equals(subject.getUUID());
        ItemStack reserved = held.copy();
        reserved.setCount(1);
        CrimeWorldData data = data(subject);
        ApplicationTransaction.Request request = request(actor, subject, slot, definitionId,
                snapshot(subject, reserved), self);
        ApplicationTransaction.Request classified = new ApplicationTransaction.Request(
                request.subject(), request.subjectIsPlayer(), request.dimension(), request.slot(),
                request.definitionId(), request.applier(), request.actorId(),
                self ? AppliedRestraint.ApplicationContext.VOLUNTARY : context,
                AppliedRestraint.Provenance.PLAYER_OWNED,
                returnPolicy(), request.custodyId(), request.itemSnapshot());

        ApplicationTransaction.Result result = ApplicationTransaction.commit(data, classified,
                rig(subject), subject.level().getGameTime());
        if (!result.applied()) {
            return result;
        }
        spend(actor, hand, reserved);
        publish(subject, data);
        CrimeSounds.restrainApplied(subject, applySound(definitionId));
        // The physical event has committed; what the law makes of it is the bridge's question and
        // nobody else's (§1.4, M4.8). Before this call a right-click that cuffed somebody filed no
        // arrest, no kidnapping and no incident at all.
        CustodyTransitionService.onRestraintApplied(actor, subject,
                self ? AppliedRestraint.ApplicationContext.VOLUNTARY : context);
        awardRestrained(subject, definitionId);
        // Post-commit, and last: other mods hear about what happened, never about what is about to
        // (M6.4).
        PhysicalApiEvents.applied(subject, actor, definitionId, slot, result.applied() ? result.restraint() : null);
        return result;
    }

    /**
     * Applies a restraint on a device's authority: a dispenser, a trap, a pillory.
     *
     * <p>The applier is the <em>device</em>, at its own position. The source passes the victim as both
     * actor and target for a dispenser application, which records the subject as having restrained
     * herself — so a kidnapping is attributed to its victim, and the release path then looks for an
     * applier who is the person in the cuffs.
     */
    public static ApplicationTransaction.Result applyFromDevice(@Nullable ServerLevel level,
                                                                @Nullable BlockPos devicePos,
                                                                @Nullable LivingEntity subject,
                                                                @Nullable ItemStack source,
                                                                @Nullable RestraintSlot slot) {
        if (level == null || devicePos == null || subject == null || slot == null
                || source == null || source.isEmpty() || !restrainable(subject)) {
            return ApplicationTransaction.Result.refused(ApplicationTransaction.Refusal.NO_SUBJECT);
        }
        ResourceLocation definitionId = CrimeItems.familyFor(source)
                .flatMap(family -> ApplicationTransaction.definitionFor(family, slot))
                .orElse(null);
        if (definitionId == null) {
            return ApplicationTransaction.Result.refused(ApplicationTransaction.Refusal.NO_DEFINITION);
        }
        CrimeWorldData data = data(subject);
        if (!VulnerabilityGates.permits(VulnerabilityGates.configured(), vulnerability(subject, data))) {
            return ApplicationTransaction.Result.refused(ApplicationTransaction.Refusal.NOT_VULNERABLE);
        }
        ItemStack reserved = source.copy();
        reserved.setCount(1);
        ApplicationTransaction.Request request = new ApplicationTransaction.Request(
                subject.getUUID(), subject instanceof Player, dimensionOf(subject), slot, definitionId,
                RestraintApplier.device(dimensionOf(subject), devicePos), null,
                AppliedRestraint.ApplicationContext.DEVICE,
                AppliedRestraint.Provenance.PLAYER_OWNED, returnPolicy(), null,
                snapshot(subject, reserved));
        ApplicationTransaction.Result result = ApplicationTransaction.commit(data, request, rig(subject),
                level.getGameTime());
        if (result.applied()) {
            publish(subject, data);
            CrimeSounds.restrainApplied(subject, applySound(definitionId));
            awardRestrained(subject, definitionId);
        }
        return result;
    }

    /**
     * Puts gear on a subject on the law's own authority, with no actor and no item behind it.
     *
     * <p>What a lawful arrest of a villager used to express by writing {@code RestraintType.CUFFS}
     * into a custody row. It is {@link AppliedRestraint.Provenance#SYSTEM_ISSUED} with a
     * {@link AppliedRestraint.ReturnPolicy#NONE}, which is the whole point: nobody paid for these
     * cuffs, so taking them off must not hand anybody a pair.
     *
     * <p>Idempotent per slot: an occupied slot is left exactly as it is, so a re-arrest or a reload
     * cannot stack two pairs on one prisoner.
     *
     * @param custodyId the captivity these belong to, so releasing it takes them off again
     * @return true when this call is what put them on
     */
    public static boolean applySystemIssued(@Nullable LivingEntity subject,
                                            @Nullable ResourceLocation definitionId,
                                            @Nullable RestraintSlot slot,
                                            AppliedRestraint.ApplicationContext context,
                                            @Nullable UUID custodyId) {
        if (subject == null || definitionId == null || slot == null || !restrainable(subject)) {
            return false;
        }
        CrimeWorldData data = data(subject);
        if (data == null) {
            return false;
        }
        RestraintDefinition definition = RestraintDefinitions.get(definitionId).orElse(null);
        if (definition == null) {
            return false;
        }
        PhysicalRestraintState state = data.physicalRestraint(subject.getUUID());
        if (state != null && state.occupied(slot)) {
            return false;
        }
        PhysicalRestraintState base = state != null ? state
                : PhysicalRestraintState.empty(subject.getUUID(), subject instanceof Player,
                        dimensionOf(subject));
        AppliedRestraint applied = new AppliedRestraint(
                UUID.randomUUID(), definition.id(), null,
                RestraintDurability.startingDurability(definition), definition.revision(),
                RestraintApplier.system(), context,
                AppliedRestraint.Provenance.SYSTEM_ISSUED,
                AppliedRestraint.ReturnPolicy.NONE,
                custodyId, subject.level().getGameTime(), 1L);
        if (!data.putPhysicalRestraint(base.with(slot, applied))) {
            return false;
        }
        publish(subject, data);
        CrimeSounds.restrainApplied(subject, definition.applySound().orElse(null));
        awardRestrained(subject, definition.id());
        return true;
    }

    /**
     * Takes off everything a particular captivity put on, and nothing else.
     *
     * <p>Called when a custody ends. Scoped by {@code custodyId} rather than emptying every slot,
     * because a released kidnapping victim who had also taped their own hood on is owed their hood:
     * what the captivity owned comes off with the captivity, and what the subject brought stays.
     * A record with no id at all releases nothing, which is the safe direction.
     *
     * @return how many slots were emptied
     */
    public static int releaseFor(@Nullable MinecraftServer server, @Nullable UUID subjectId,
                                 @Nullable UUID custodyId) {
        if (server == null || subjectId == null || custodyId == null) {
            return 0;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        PhysicalRestraintState state = data.physicalRestraint(subjectId);
        if (state == null) {
            return 0;
        }
        LivingEntity subject = findLiving(server, subjectId);
        int removed = 0;
        for (RestraintSlot slot : RestraintSlot.values()) {
            AppliedRestraint worn = state.slot(slot).orElse(null);
            if (worn == null || !custodyId.equals(worn.custodyId())) {
                continue;
            }
            if (subject != null) {
                if (RemovalService.remove(subject, slot, RemovalService.Reason.ADMINISTRATIVE, null)
                        .removed()) {
                    removed++;
                }
                state = data.physicalRestraint(subjectId);
                if (state == null) {
                    break;
                }
            } else if (data.putPhysicalRestraint(state.without(slot))) {
                // Not loaded: the row is still authoritative and the gear was never anybody's, so the
                // slot is emptied in place. Nothing is dropped into a world nobody is standing in.
                removed++;
                state = data.physicalRestraint(subjectId);
                if (state == null) {
                    break;
                }
            }
        }
        if (removed > 0) {
            if (subject != null) {
                publish(subject, data);
            } else {
                RestraintSyncService.broadcastRemoval(server, subjectId);
            }
        }
        return removed;
    }

    /** The subject across every loaded level, or null when nobody is loaded under that id. */
    @Nullable
    private static LivingEntity findLiving(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    // --- publication ------------------------------------------------------------------------------

    /**
     * Tells every client that can see {@code subject} what changed.
     *
     * <p>The rig goes with it, because a client cannot work out on its own whether a settlement life
     * stage gave this villager arms — the bridge that knows only exists on the server.
     */
    public static void publish(@Nullable LivingEntity subject, @Nullable CrimeWorldData data) {
        if (subject == null || data == null || subject.level().isClientSide()) {
            return;
        }
        CrimeNetwork.broadcastRestraintRig(subject, RigResolver.humanoid(subject), RigResolver.rigId(subject));
        RestraintSyncService.broadcastDelta(subject, data);
        // The display badge follows the state it is drawn from, never the other way round (§3.10).
        PhysicalRestraintState published = data.physicalRestraint(subject.getUUID());
        dev.otectus.mcacrime.effect.RestrainedBadge.refresh(subject, published);
        // An enchanted restraint coming off takes its effects with it, and only its own (M6.1). Here
        // rather than in the removal path because every commit publishes, including a device's.
        if (published == null || published.vacant()) {
            dev.otectus.mcacrime.enchantment.RestraintEffects.clear(subject);
        }
    }

    // --- tick -------------------------------------------------------------------------------------

    /**
     * The server tick hook that replaces {@code captivity/CaptureTicker}.
     *
     * <p>All it does is expire sessions. The channel object the ticker used to drive is gone: an
     * application either stands when it is attempted or is refused, and the work that genuinely takes
     * time — struggling, picking, searching — is a session with its own expiry rather than a
     * per-subject timer that nothing owned.
     */
    public static void serverTick(@Nullable MinecraftServer server) {
        if (server == null) {
            return;
        }
        SessionRegistry.server().expire(server.overworld().getGameTime());
    }

    /**
     * Counts one committed application against the subject who is now wearing it (M5.11).
     *
     * <p>Called from the commit paths and nowhere else, so the figure is the number of times somebody
     * was actually restrained -- not the number of right-clicks, refusals or replayed packets.
     * Awarded to the <em>subject</em>, which is whose statistic screen the count belongs on.
     */
    static void awardRestrained(@Nullable LivingEntity subject, @Nullable ResourceLocation definitionId) {
        if (subject instanceof ServerPlayer restrained) {
            dev.otectus.mcacrime.stat.CrimeStats.awardRestraint(restrained, definitionId,
                    dev.otectus.mcacrime.stat.CrimeStatIds.Kind.TIMES_RESTRAINED);
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    /**
     * The persisted form of one reserved stack.
     *
     * <p>{@code saveOptional} with the subject's own registry lookup, because in 1.21 an item stack
     * carries component values that resolve through the registries; the 1.20.1 baseline writes the
     * same snapshot with {@code ItemStack.save(CompoundTag)}, which needed no lookup. Same field, same
     * moment, same losslessness guarantee.
     */
    private static CompoundTag snapshot(LivingEntity subject, ItemStack reserved) {
        if (reserved == null || reserved.isEmpty()) {
            return new CompoundTag();
        }
        return (CompoundTag) reserved.saveOptional(subject.level().registryAccess());
    }


    private static ApplicationTransaction.Request request(ServerPlayer actor, LivingEntity subject,
                                                          RestraintSlot slot, ResourceLocation definitionId,
                                                          @Nullable CompoundTag itemSnapshot, boolean self) {
        return new ApplicationTransaction.Request(
                subject.getUUID(),
                subject instanceof Player,
                dimensionOf(subject),
                slot,
                definitionId,
                RestraintApplier.player(actor.getUUID()),
                actor.getUUID(),
                self ? AppliedRestraint.ApplicationContext.VOLUNTARY
                        : AppliedRestraint.ApplicationContext.UNLAWFUL,
                itemSnapshot == null ? AppliedRestraint.Provenance.SYSTEM_ISSUED
                        : AppliedRestraint.Provenance.PLAYER_OWNED,
                returnPolicy(),
                null,
                itemSnapshot);
    }

    /**
     * Spends the reserved stack, only if the hand still holds what was reserved.
     *
     * <p>The identity check is not paranoia: the commit and the spend are two statements, and a player
     * who swapped hands in between must not have a different stack shrunk on their behalf. Creative
     * mode spends nothing, as everywhere else in this mod.
     */
    private static void spend(ServerPlayer actor, InteractionHand hand, ItemStack reserved) {
        ItemStack held = actor.getItemInHand(hand);
        if (held.isEmpty() || !ItemStack.isSameItemSameComponents(held, reserved)) {
            return;
        }
        if (!actor.getAbilities().instabuild) {
            held.shrink(1);
        }
        actor.containerMenu.broadcastChanges();
    }

    private static AppliedRestraint.ReturnPolicy returnPolicy() {
        boolean returns;
        try {
            returns = McaCrimeConfig.COMMON.escapeReturnsWornItem.get();
        } catch (IllegalStateException e) {
            returns = true;
        }
        return returns ? AppliedRestraint.ReturnPolicy.DROP_AT_SUBJECT : AppliedRestraint.ReturnPolicy.NONE;
    }

    private static boolean allowSelfApplication() {
        try {
            return McaCrimeConfig.COMMON.allowSelfApplication.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    @Nullable
    private static ResourceLocation dimensionOf(Entity subject) {
        return subject.level().dimension().location();
    }

    /**
     * The sound id one definition names on application, or null when it names none.
     *
     * <p>Read from the definition rather than from the item, because the definition is what the
     * durability, the restrictions and the statistics already come from; an item that applies two
     * definitions on two slots should sound like the slot it went on.
     */
    @Nullable
    private static ResourceLocation applySound(@Nullable ResourceLocation definitionId) {
        return definitionId == null ? null
                : RestraintDefinitions.get(definitionId)
                        .flatMap(RestraintDefinition::applySound)
                        .orElse(null);
    }

    /** The world store, or null off a server. */
    @Nullable
    public static CrimeWorldData data(@Nullable Entity subject) {
        if (subject == null || subject.level().isClientSide()) {
            return null;
        }
        MinecraftServer server = subject.getServer();
        return server == null ? null : CrimeWorldData.get(server);
    }

    /** What is physically on {@code subject} right now, or null. */
    @Nullable
    public static PhysicalRestraintState state(@Nullable Entity subject) {
        CrimeWorldData data = data(subject);
        return data == null ? null : data.physicalRestraint(subject.getUUID());
    }

    /**
     * The composed restriction policy for {@code subject}. Unrestricted when nothing is on them.
     *
     * <p>A device composes on top of the worn gear rather than replacing it (0.7.5 M4.5): a pilloried
     * subject who is also hooded is both, and the {@code mcacrime:pillory} definition is what the
     * device contributes — an <em>extension</em> profile occupying no body slot, which is why a hood
     * survives being locked in one where upstream's head-slot pillory restraint takes it off.
     */
    public static RestrictionPolicy policy(@Nullable Entity subject) {
        CrimeWorldData data = data(subject);
        PhysicalRestraintState state = data == null || subject == null ? null
                : data.physicalRestraint(subject.getUUID());
        ResourceLocation detention = detentionProfile(data, state);
        if (state == null && detention == null) {
            return RestrictionPolicy.unrestricted();
        }
        return RestrictionResolver.resolve(state, detention);
    }

    /**
     * The definition id a device contributes, or null when no device is holding this subject.
     *
     * <p>Both devices resolve to {@code mcacrime:pillory}: a guillotine holds its occupant in exactly
     * the same way a pillory does, and the difference between them is what the device may then
     * <em>do</em>, not what it stops the occupant doing.
     */
    @Nullable
    private static ResourceLocation detentionProfile(@Nullable CrimeWorldData data,
                                                     @Nullable PhysicalRestraintState state) {
        if (data == null || state == null || state.detentionId() == null) {
            return null;
        }
        dev.otectus.mcacrime.detention.DetentionRecord record = data.detention(state.detentionId());
        if (record == null) {
            return null;
        }
        return switch (record.kind()) {
            case PILLORY, GUILLOTINE -> RestraintDefinitions.PILLORY;
            case BUNK -> null; // a bunk is a bed, not a hold: sleeping in one restricts nothing
        };
    }

    /** The subject id an entity is keyed by. Exists so callers stop reaching for {@code getUUID}. */
    public static UUID subjectId(Entity subject) {
        return subject.getUUID();
    }
}
