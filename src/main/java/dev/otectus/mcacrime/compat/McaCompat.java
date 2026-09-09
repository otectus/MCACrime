package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.compat.mca.McaHandles;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The single point of contact with Minecraft Comes Alive: Reborn (spec §13). Every MCA symbol the mod
 * touches lives here, so MCA API drift is always a one-file fix and a wrong cast can never escape into
 * gameplay code (the bug that crashed legacy MCA, spec §0 rule 3).
 *
 * <p><b>No MCA type appears anywhere in this file.</b> Every MCA class and member is reached by name at
 * runtime through {@link McaHandles}, which probes MCA's package root rather than assuming one. That is
 * not gold-plating: MCA repackaged mid-version-line. Through 7.7.0-beta.2 its Forge classes live at
 * {@code forge.net.mca.*}; 7.7.1-alpha.1 renamed the base package and they became
 * {@code forge.net.conczin.mca.*}. This file previously opened with {@code instanceof VillagerEntityMCA}
 * and no {@code try}, so on the renamed build it threw
 * {@code NoClassDefFoundError: forge/net/mca/entity/VillagerEntityMCA} out of {@code CrimeGate} on
 * <em>every</em> {@code LivingHurtEvent} — a dedicated server died the instant anything took damage, and
 * no {@code catch} could have helped, because the JVM failed while resolving the type named in the
 * {@code instanceof} rather than while running the body.
 *
 * <p>"Karma/heat" are this mod's own concepts; MCA's per-player relationship value is "hearts", reached
 * through the villager's brain. Every method fails safe — on a non-MCA entity, absent MCA data, an
 * unbound member, or any throwable it returns a documented default and logs at DEBUG, never crashing the
 * server (spec §0 rule 4).
 */
public final class McaCompat {

    private McaCompat() {
    }

    /** True for an MCA human villager (adult or child; not the zombie variant). Never casts blindly. */
    public static boolean isMcaVillager(Entity entity) {
        return McaHandles.isVillager(entity);
    }

    public static UUID getVillagerUuid(Entity entity) {
        return entity.getUUID();
    }

    /** MCA overrides {@code getDisplayName} to return the villager's given name, so vanilla dispatch suffices. */
    public static Component getVillagerDisplayName(Entity entity) {
        return entity.getDisplayName();
    }

    /** Normalises the villager's profession to a {@link ResourceLocation} (spec §12). Safe default: empty. */
    public static Optional<ResourceLocation> getProfessionId(Entity entity) {
        try {
            return Optional.ofNullable(McaHandles.professionId(entity));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA getProfessionId failed; defaulting empty", t);
            return Optional.empty();
        }
    }

    /**
     * True when the villager's profession is a guard, under the server's {@code professionMatchingMode}.
     * Safe default: {@code false}.
     *
     * <p>The comparison used to be a hard-coded path equality, which is what {@code NORMALIZED} still
     * does and remains the default. What changed is that a server running a build or a companion mod
     * whose guards are named differently can now widen or narrow the match without a code change.
     */
    /**
     * Strictly an adult MCA villager: an unreadable age excludes rather than admits.
     *
     * <p>Not a reuse of {@link #isAdult}, and the difference is the point. {@code isAdult} treats an
     * unknown age state as adult by design, so a missing age never wrongly excludes somebody from
     * paying a ransom. That default is exactly wrong for deciding who may be turned into a guard,
     * where an unreadable age must mean "leave them alone" rather than "go ahead".
     */
    public static boolean isAdultVillager(Entity entity) {
        if (!isMcaVillager(entity)) {
            return false;
        }
        String age = McaHandles.ageStateName(entity);
        return "adult".equals(age); // ageStateName is already lowercased
    }

    /**
     * MCA's guard profession, resolved from the <em>vanilla</em> registry.
     *
     * <p>{@code VillagerProfession} is a Minecraft type and MCA registers its professions into the
     * vanilla registry, so the value can be fetched without naming a single MCA symbol -- which is what
     * keeps {@code NoMcaStaticLinkTest} green while still writing MCA state.
     *
     * <p>The id is tried first and a scan is the fallback, so the configured
     * {@code professionMatchingMode} governs writes as well as reads and an MCA that renames its own
     * profession still resolves. Population balancing produces guards; existing archers are also
     * recognized as law responders by {@code EntitySelectors.isResponder}.
     */
    public static Optional<VillagerProfession> guardProfession() {
        Optional<VillagerProfession> direct = BuiltInRegistries.VILLAGER_PROFESSION
                .getOptional(ResourceLocation.fromNamespaceAndPath("mca", "guard"));
        if (direct.isPresent()) {
            return direct;
        }
        for (ResourceLocation id : BuiltInRegistries.VILLAGER_PROFESSION.keySet()) {
            if (ProfessionMatcher.matches(id, "guard")) {
                return BuiltInRegistries.VILLAGER_PROFESSION.getOptional(id);
            }
        }
        return Optional.empty();
    }

    /** Turns an MCA villager into a guard through MCA's own setter. Best-effort; false on any failure. */
    public static boolean makeGuard(Entity villager) {
        VillagerProfession profession = guardProfession().orElse(null);
        if (profession == null || !isMcaVillager(villager)) {
            return false;
        }
        try {
            return McaHandles.setProfession(villager, profession);
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA makeGuard failed; ignoring", t);
            return false;
        }
    }

    public static boolean isGuard(Entity entity) {
        return getProfessionId(entity)
                .map(id -> dev.otectus.mcacrime.compat.ProfessionMatcher.matches(id, "guard"))
                .orElse(false);
    }

    /**
     * Sets a villager's <em>visible</em> profession by registry id, through MCA's own setter.
     *
     * <p>Used for criminal-job presentation (0.5.1): the id may be one of this mod's own professions
     * or the one a villager had before it became a fence. False when the id is not registered, when
     * the entity is not an MCA villager, or when MCA's setter is not bound -- and false means "the
     * villager still has whatever profession it had", never a half-applied state, because the crime
     * side reads {@code CrimeWorldData.criminalVillagers} and not this.
     */
    public static boolean setVillagerProfession(Entity villager, ResourceLocation professionId) {
        if (villager == null || professionId == null || !isMcaVillager(villager)) {
            return false;
        }
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getOptional(professionId).orElse(null);
        if (profession == null) {
            return false;
        }
        try {
            return McaHandles.setProfession(villager, profession);
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA setVillagerProfession failed; ignoring", t);
            return false;
        }
    }

    /**
     * True for MCA's archer profession.
     *
     * <p>Separate from {@link #isGuard} because MCA's two law roles are separate professions and only
     * one of them matches "guard". Both are law for the purpose of never being recruited as a
     * criminal, which is the question this exists to answer.
     */
    public static boolean isArcher(Entity entity) {
        return getProfessionId(entity)
                .map(id -> ProfessionMatcher.matches(id, "archer"))
                .orElse(false);
    }

    /**
     * Reads the player's current relationship hearts with this villager. Server-authoritative; returns
     * 0 for non-MCA entities or on any error. (Hearts are MCA's relationship currency, distinct from
     * this mod's Karma.)
     */
    public static int getHearts(ServerPlayer player, Entity villager) {
        return McaHandles.hearts(villager, player);
    }

    /**
     * Adds relationship hearts via MCA's own reward path (the one MCA's gifting uses). <b>Server side
     * only.</b> No-op for non-MCA entities or a zero amount. Used by later phases for relationship
     * consequences (spec §10.1) — wired now, exercised then.
     */
    public static void addHearts(ServerPlayer player, Entity villager, int amount) {
        if (amount == 0) {
            return;
        }
        McaHandles.rewardHearts(villager, player, amount);
    }

    /**
     * The id of the villager's MCA home village, or empty when it has none / on any error. The id is
     * MCA's own {@code int} village id, used as the key for per-village reputation (spec §2.5).
     */
    public static OptionalInt getHomeVillageId(Entity villager) {
        return McaHandles.homeVillageId(villager);
    }

    /**
     * The name MCA gives a village, by its own village id, or empty.
     *
     * <p>This exists because a {@code CrimeCommunityKey} is {@code minecraft:overworld/0} — the right
     * thing to write into NBT and the wrong thing to put on a screen. MCA names its villages and lets
     * players rename them, so the name is the only jurisdiction label that means anything to somebody
     * being told which one is charging them. Empty when MCA is absent, the village has been dissolved,
     * or it has no name set; the caller must then say something readable rather than fall back to the key.
     */
    public static Optional<String> getVillageName(ServerLevel level, int villageId) {
        return McaHandles.villageName(level, villageId);
    }

    /**
     * The villager's current AI attack target, if any. Used to detect self-defense: if a villager is
     * already targeting the player, the player retaliating is lawful, not a crime (spec §5.2). An MCA
     * villager is a {@code Mob}, so {@code getTarget()} is valid; kept here so the cast never escapes.
     * Safe default: {@code empty}.
     */
    public static Optional<LivingEntity> getMcaTarget(Entity villager) {
        if (isMcaVillager(villager) && villager instanceof Mob mob) {
            try {
                return Optional.ofNullable(mob.getTarget());
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA getMcaTarget failed; defaulting empty", t);
            }
        }
        return Optional.empty();
    }

    /**
     * Makes a responder pursue a player (spec §4.4). <b>Server side only.</b>
     *
     * <h2>Why this writes a brain memory and not just {@code setTarget}</h2>
     *
     * <p>This method used to call {@code Mob.setTarget} alone and return {@code true} unconditionally,
     * which meant every caller announced an aggro that never happened — the visible symptom being a
     * guard who says a refusal puts them within their rights and then stands there. An MCA villager is
     * a {@code Villager}, and the whole {@code Villager} family is driven by a {@code Brain} rather
     * than by goal selectors, so nothing in its AI ever reads the field {@code setTarget} writes.
     *
     * <p>MCA's guard combat behaviours ({@code ExtendedMeleeAttackTask},
     * {@code SetWalkTargetFromAttackTargetIfTargetOutOfReach}, {@code StopAttackingIfTargetInvalid})
     * are registered under {@link net.minecraft.world.entity.schedule.Activity#CORE}, so they are
     * always running — there is no combat activity to switch into. MCA picks their victim from its own
     * {@code NEAREST_GUARD_ENEMY} memory <em>and falls back to vanilla
     * {@link MemoryModuleType#ATTACK_TARGET} when that memory is empty</em>, which is exactly the seam
     * this needs: {@code ATTACK_TARGET} is a vanilla module, it is present in MCA's registered memory
     * set (so {@code setMemory} is not silently dropped), and MCA's own "is this still a valid target"
     * predicate is satisfied by the same fallback that produced it. No MCA symbol is involved, which is
     * why this can live outside {@link McaHandles} without breaking the no-static-linkage rule.
     *
     * <p>{@code setTarget} is still written, because a responder added through the {@code responderEntities}
     * config may well be an ordinary goal-driven mob for which it is the only lever that works.
     *
     * <p>Deliberately <b>not</b> gated on {@link #isGuard}. Callers select responders through
     * {@code EntitySelectors.isResponder}, which is broader; re-narrowing here is what made a configured
     * non-MCA responder able to challenge but never to act. Who counts as law is that selector's
     * decision, not this shim's.
     *
     * @return true when a lever actually took effect, so a caller can tell aggro from silence
     */
    public static boolean setGuardTarget(Entity guard, LivingEntity target) {
        if (!(guard instanceof Mob mob) || target == null || !dev.otectus.mcacrime.ai.NpcAwareness.isAwake(guard)) {
            return false;
        }
        boolean applied = false;
        try {
            if (mob.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)) {
                mob.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, target);
                applied = true;
            }
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA setGuardTarget brain write failed; falling back", t);
        }
        try {
            mob.setTarget(target);
            applied = true;
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA setGuardTarget failed; ignoring", t);
        }
        return applied;
    }

    /**
     * The player-typed overload the enforcement layer has always called. Kept so widening the target
     * type to {@link LivingEntity} (NPC offenders exist as of 0.5.1) is not a call-site migration.
     */
    public static boolean setGuardTarget(Entity guard, ServerPlayer target) {
        return setGuardTarget(guard, (LivingEntity) target);
    }

    /** Clears a responder's target if it has one. Best-effort, server side only. */
    public static void clearGuardTarget(Entity guard) {
        clearGuardTarget(guard, null);
    }

    /**
     * Clears a responder's target, preserving unrelated combat.
     *
     * <p>With {@code formerTarget} non-null only a target aimed at that player is dropped, so a guard
     * fighting a zombie is not disarmed by a pardon issued to somebody else. Both levers are cleared
     * together: leaving the brain memory set while nulling the field would leave the guard swinging.
     */
    public static void clearGuardTarget(Entity guard, @Nullable LivingEntity formerTarget) {
        if (!(guard instanceof Mob mob)) {
            return;
        }
        try {
            if (mob.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)
                    && mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET)
                            .filter(held -> formerTarget == null || held == formerTarget).isPresent()) {
                mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            }
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA clearGuardTarget brain erase failed; ignoring", t);
        }
        try {
            if (mob.getTarget() != null && (formerTarget == null || mob.getTarget() == formerTarget)) {
                mob.setTarget(null);
            }
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA clearGuardTarget failed; ignoring", t);
        }
    }

    /** The player-typed overload, for the same reason {@link #setGuardTarget(Entity, ServerPlayer)} is. */
    public static void clearGuardTarget(Entity guard, @Nullable ServerPlayer formerTarget) {
        clearGuardTarget(guard, (LivingEntity) formerTarget);
    }

    /**
     * Best-effort "flee" for an MCA villager away from a (Red) player (spec §4.1): paths the villager away
     * using vanilla navigation, so it works without depending on MCA-internal AI. Fail-safe no-op on any
     * error. <b>Server side only.</b>
     */
    public static boolean makeVillagerFlee(Entity villager, ServerPlayer from) {
        if (!isMcaVillager(villager) || !(villager instanceof LivingEntity living)
                || !(villager.level() instanceof ServerLevel level)
                || dev.otectus.mcacrime.detect.EntitySelectors.isResponder(living)) {
            return false;
        }
        // A second navigator used to override compliance, captivity and the bounded flee route.
        if (dev.otectus.mcacrime.ai.CrimeReactionService.stateOf(villager.getUUID())
                != dev.otectus.mcacrime.ai.VictimReactionState.CALM) return false;
        return dev.otectus.mcacrime.ai.CrimeReactionService.trigger(level, living, from.getUUID(),
                dev.otectus.mcacrime.ai.VictimReactionState.FLEEING, null) != null;
    }

    // ------------------------------------------------------------------ reaction navigation (§11.3)
    // These are the "least invasive verified hook" the plan asks for: vanilla PathfinderMob navigation,
    // driven only while a reaction controller is active and cleared the moment it ends. MCA's villagers
    // run their own brain, so anything issued here has to be issued sparingly (the controller reissues
    // on a bounded interval, never per tick) and withdrawn cleanly, or the two systems fight and the
    // villager visibly stutters between them.

    /**
     * Paths an MCA villager toward a point. Returns false when the entity cannot navigate or no path
     * exists, which the controller treats as a path failure rather than as arrival.
     */
    public static boolean moveVillagerTo(Entity villager, double x, double y, double z, double speed) {
        if (!(villager instanceof Mob mob) || !dev.otectus.mcacrime.ai.NpcAwareness.isAwake(villager)) {
            return false;
        }
        try {
            net.minecraft.core.BlockPos destination = net.minecraft.core.BlockPos.containing(x, y, z);
            return dev.otectus.mcacrime.ai.CrimeNavigation.start(mob,
                    mob.getNavigation().createPath(destination, 1), destination, speed, isMcaVillager(villager));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA moveVillagerTo failed; ignoring", t);
            return false;
        }
    }

    /**
     * Stops navigation this mod issued and clears any attack target, handing the villager back to MCA.
     * Called on every controller exit — a reaction that ends without this leaves the villager frozen
     * on a stale path with nothing left to update it.
     */
    public static void releaseVillagerControl(Entity villager) {
        stopModNavigation(villager);
        clearGuardTarget(villager, null);
    }

    /**
     * Stops only the navigation this mod issued, leaving any attack target alone.
     *
     * <p>This half exists because the two used to be inseparable, and that was a race the law lost.
     * A guard is an MCA villager, so a guard who witnesses a crime gets a reaction controller; that
     * controller ticks on {@code reactionTickIntervalTicks} (5) against the enforcement scan's
     * {@code guardScanIntervalTicks} (10), and every controller exit called
     * {@link #releaseVillagerControl}. The enforcement layer would set a guard on a criminal and the
     * reaction layer would clear it within five ticks — twice as often as it could be re-applied.
     * A reaction ending is a reason to stop walking somewhere, not a reason to stop an arrest.
     */
    public static void stopModNavigation(Entity villager) {
        clearWalkTargets(villager);
    }

    /**
     * Stops a mob walking and erases the brain memories that would start it walking again.
     *
     * <p>{@code getNavigation().stop()} alone is not enough for an MCA villager. They are vanilla
     * {@link net.minecraft.world.entity.npc.Villager}s underneath, so they are brain-driven: the path
     * belongs to {@code MoveToTargetSink}, which re-issues {@code moveTo} from
     * {@link MemoryModuleType#WALK_TARGET} on the very next tick. Stopping the navigator without
     * erasing the memory means the villager keeps walking away between reassertions, one step at a
     * time — which is exactly what let a mugging victim wander out of the mugging.
     *
     * <p>{@code LOOK_TARGET} is deliberately left alone: where a villager is looking is not what moves
     * them, and callers that want a specific gaze use {@link #faceEntity}.
     */
    private static void clearWalkTargets(Entity villager) {
        if (!(villager instanceof Mob mob)) {
            return;
        }
        try {
            var brain = mob.getBrain();
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            brain.eraseMemory(MemoryModuleType.PATH);
            brain.eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        } catch (Throwable t) {
            // A mob whose brain has no such memory, or no brain worth the name, is not an error.
            McaCrime.LOGGER.debug("MCA clearWalkTargets brain erase failed; ignoring", t);
        }
        try {
            mob.getNavigation().stop();
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA stopModNavigation failed; ignoring", t);
        }
    }

    /**
     * Pins a villager where it stands: walk memories erased, navigation stopped and horizontal momentum
     * cancelled, with the vertical component left alone so gravity still applies and a villager frozen
     * mid-air still falls.
     *
     * <p>Deliberately not done with a movement-speed modifier. A zero-speed attribute would also stop
     * knockback, water flow and every other thing that moves an entity for reasons that have nothing to
     * do with this mod, and it would leave a very visible mess behind if it were ever orphaned. Zeroing
     * the delta each think is reversible by simply not doing it again.
     */
    public static void holdPosition(Entity villager) {
        if (villager == null) {
            return;
        }
        stopModNavigation(villager);
        try {
            Vec3 motion = villager.getDeltaMovement();
            villager.setDeltaMovement(0.0D, motion.y, 0.0D);
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA holdPosition failed; ignoring", t);
        }
    }

    /**
     * Makes a villager fight back. Unlike {@link #setGuardTarget}, this works for any MCA villager,
     * because §11.2's {@code RESISTING} state is explicitly not guards-only — a bold farmer shoving
     * back at a mugger is the behaviour, and refusing it for non-guards would mean every civilian
     * always runs.
     */
    public static boolean makeVillagerResist(Entity villager, LivingEntity offender) {
        if (!isMcaVillager(villager) || !(villager instanceof Mob mob) || !dev.otectus.mcacrime.ai.NpcAwareness.isAwake(villager)) {
            return false;
        }
        try {
            mob.setTarget(offender);
            return true;
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA makeVillagerResist failed; ignoring", t);
            return false;
        }
    }

    /**
     * The villager's remembered home, when the brain exposes one. Used as a flee destination, which is
     * why an absent answer is fine: the destination selector simply scores one fewer candidate.
     */
    public static Optional<net.minecraft.core.BlockPos> homePosition(Entity villager) {
        if (villager instanceof net.minecraft.world.entity.LivingEntity living) {
            try {
                return living.getBrain()
                        .getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME)
                        .map(net.minecraft.core.GlobalPos::pos);
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA homePosition failed; defaulting empty", t);
            }
        }
        return Optional.empty();
    }

    /** Turns a villager to face an entity. Cosmetic, and safe to fail. */
    public static void faceEntity(Entity villager, Entity target) {
        if (villager instanceof Mob mob && target != null && dev.otectus.mcacrime.ai.NpcAwareness.isAwake(villager)) {
            try {
                mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA faceEntity failed; ignoring", t);
            }
        }
    }

    /** Whether the villager's current path has finished or was never started. */
    public static boolean navigationDone(Entity villager) {
        if (villager instanceof PathfinderMob mob) {
            try {
                return mob.getNavigation().isDone();
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA navigationDone failed; defaulting true", t);
            }
        }
        return true;
    }

    /**
     * Best-effort physical hold of a captured NPC by leashing it to its captor (spec §8.4). Uses the
     * vanilla {@code Mob} leash, persisted on the entity, so it survives chunk unload/reload without
     * depending on MCA-internal AI. The captive is never deleted — only leashed/moved. Fail-safe no-op on
     * any error. <b>Server side only.</b> ⚠ Leash interaction with MCA villager AI is an in-world
     * verification target (the dev runtime cannot load MCA).
     */
    public static boolean leashTo(Entity captive, Entity holder) {
        if (captive instanceof Mob mob) {
            try {
                mob.setLeashedTo(holder, true);
                // Physical restraint wakes the captive; mere proximity never does.
                if (mob.isSleeping()) mob.stopSleeping();
                return true;
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA leashTo failed; ignoring", t);
            }
        }
        return false;
    }

    /** Releases a leashed NPC captive on release (spec §8.4). Best-effort, never deletes the entity. */
    public static void clearLeash(Entity captive) {
        if (captive instanceof Mob mob) {
            try {
                if (mob.isLeashed()) {
                    mob.dropLeash(true, false);
                }
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA clearLeash failed; ignoring", t);
            }
        }
    }

    /**
     * Whether an NPC can fight back — so capture is never instant against it (spec §8.2). Conservative
     * default: a guard. (Broader MCA combat-archetype detection — Outlaws/Cultists — is a Phase 6 ⚠
     * verification target; until then guards are the combat NPCs that demand the full vulnerability+channel.)
     */
    public static boolean isCombatCapable(Entity entity) {
        return isGuard(entity);
    }

    /**
     * Whether an MCA villager is currently sleeping — a capture vulnerability (spec §8.2). Reads the vanilla
     * {@code LivingEntity} sleep state (MCA villagers sleep at night via the vanilla pose). Safe default:
     * {@code false}. (Player sleep is read directly off the player, not here.) ⚠ verified in-world.
     */
    public static boolean isVillagerSleeping(Entity entity) {
        if (isMcaVillager(entity) && entity instanceof LivingEntity living) {
            try {
                return living.isSleeping();
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA isVillagerSleeping failed; defaulting false", t);
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ relationship / family graph (§8.5)
    // Signatures verified byte-identical on 7.6.20+1.20.1, 7.7.0-beta.2+1.20.1 and 7.7.1-alpha.2+1.20.1;
    // McaBindingProbeTest re-checks all three on every build. Runtime behavior is an in-world ⚠
    // verification target. Every method fails safe so a differing MCA version degrades ransom to the
    // village-authority fallback rather than crashing.

    /**
     * Whether MCA's relationship API resolved. When false, {@code RansomService} skips the family payer
     * tiers and uses the village-authority fallback only (spec §8.5). Answered from the binding, so it
     * reflects the MCA that is actually installed rather than the one this mod was built against — the
     * old {@code Class.forName("forge.net.mca...")} probe could only ever confirm one package root, and
     * named classes this mod does not otherwise use.
     */
    public static boolean isRelationshipApiAvailable() {
        return McaHandles.relationshipApiAvailable();
    }

    /** The villager/player's spouse UUID (MCA partner), or empty. Safe default: empty. */
    public static Optional<UUID> getSpouseUuid(Entity entity) {
        return McaHandles.partnerUuid(entity);
    }

    public static List<UUID> getParentUuids(Entity entity) {
        return McaHandles.parentUuids(entity);
    }

    public static List<UUID> getChildUuids(Entity entity) {
        return McaHandles.childUuids(entity);
    }

    public static List<UUID> getSiblingUuids(Entity entity) {
        return McaHandles.siblingUuids(entity);
    }

    /** UUIDs of relatives up to {@code generations} away (grandparents/grandchildren/etc.). Safe default: empty. */
    public static List<UUID> getCloseRelativeUuids(Entity entity, int generations) {
        return McaHandles.closeRelativeUuids(entity, generations);
    }

    /**
     * Whether the entity is an adult (so adult-child ransom gating is correct, spec §8.5). Players/unknown
     * are treated as adults so a missing age never wrongly excludes a valid payer. Safe default: {@code true}
     * — which is also what a null age name means, covering both "not an MCA villager" and "unreadable".
     */
    public static boolean isAdult(Entity entity) {
        String age = McaHandles.ageStateName(entity);
        return age == null || "adult".equals(age);
    }
}
