package dev.otectus.mcacrime.ai;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.compat.TownsteadSnapshotCache;
import dev.otectus.mcacrime.detect.EntitySelectors;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import javax.annotation.Nullable;

/**
 * Sleep blocks perception and voluntary action; it never removes protection or legal identity.
 *
 * <p>Sand (0.7.2 §13.5) is deliberately <em>not</em> folded into {@link #isAwake}. A sanded NPC is
 * awake: they talk, they walk, they can be interacted with, and they remain a witness to everything
 * they already saw. Making them read as asleep would have been one line and would have silently
 * disabled conversation, protection and legal identity along with their eyesight. What sand changes
 * is a single question — {@link #canSeeNow} — and every caller that establishes <em>new</em> visual
 * tracking asks that one instead of asking for line of sight directly.
 *
 * <h2>Capabilities, and why {@code isAwake} was not enough</h2>
 *
 * <p>For most of this mod's life "awake" was the whole question, because vanilla and MCA give a
 * villager exactly two states worth distinguishing. A settlement companion gives them more: a villager
 * can be on their feet and unable to move, or present and unable to be talked to, or collapsed from
 * exhaustion in the middle of the square. Folding any of that into {@link #isAwake} would repeat the
 * sand mistake in a worse form — a collapsed villager would silently stop being a legal person, lose
 * protection, and stop being a witness to a crime they had already seen.
 *
 * <p>So the answer is split by <em>what is being asked</em>. Each predicate below is
 * {@link #isAwake} plus, at most, the one extra condition that question actually depends on, and the
 * rules are:
 *
 * <ul>
 *   <li><b>Unavailable data never says "cannot".</b> No Townstead, no capability, no reading, config
 *       switched off — every one of those falls back to exactly the {@link #isAwake} behaviour this
 *       mod had before, so an install without the companion is byte-for-byte unchanged.</li>
 *   <li><b>Incapacity is about acting, never about remembering.</b> A collapsed witness keeps every
 *       observation they have already stored; what they cannot do is see, identify or report something
 *       <em>new</em> while they are down. Nothing in this file erases anything.</li>
 * </ul>
 */
public final class NpcAwareness {
    private NpcAwareness() {}

    public static boolean isAwake(Entity entity) {
        return entity instanceof LivingEntity living && living.isAlive() && !living.isSleeping();
    }

    // ------------------------------------------------------------------ capability predicates

    /** What an entity is being asked to do, which is what decides which extra condition applies. */
    public enum Capability {
        /** Perceive an act happening in front of them now. */
        OBSERVE,
        /** Put a name to the person doing it. */
        IDENTIFY,
        /** Say something, or carry a report to somebody. */
        SPEAK,
        /** Be sent somewhere under MCA: Crime's navigation. */
        NAVIGATE,
        /** Act as law: challenge, pursue, arrest, escort, or be counted as a responder who could. */
        GUARD_RESPONSE,
        /** Commit a crime of their own. */
        CRIMINAL_ACTION,
        /** Hold, take, give or lose an item. */
        HANDS
    }

    /**
     * The whole rule, from plain facts.
     *
     * <p>Separated from the live predicates so it can be pinned without a server, an entity or a
     * companion mod, and so the fallbacks are visible in one place: with {@code respectIncapacity}
     * off, or with no reading behind {@code incapacitated}/{@code mobile}/{@code talkable}, this is
     * exactly {@code awake}.
     *
     * @param awake             {@link #isAwake}
     * @param respectIncapacity {@code townstead.respectIncapacity}
     * @param incapacitated     a <em>real</em> reading that the villager is off their feet
     * @param mobile            whether the life stage can move; true when unknown
     * @param talkable          whether the life stage can be talked to; true when unknown
     */
    public static boolean permits(Capability capability, boolean awake, boolean respectIncapacity,
                                  boolean incapacitated, boolean mobile, boolean talkable) {
        if (!awake || capability == null) {
            return false;
        }
        if (!respectIncapacity) {
            return true;
        }
        if (incapacitated) {
            return false;
        }
        return switch (capability) {
            case NAVIGATE, GUARD_RESPONSE, CRIMINAL_ACTION -> mobile;
            case SPEAK -> talkable;
            case OBSERVE, IDENTIFY, HANDS -> true;
        };
    }

    /**
     * The same rule against the live world, through the bounded snapshot cache.
     *
     * <p>Ordered for the install that does not have a settlement companion, which is nearly all of
     * them: {@code isAwake} first, then one volatile read that says nothing is bound, and out. Nothing
     * below that line is reached without a companion actually running, which matters because these
     * predicates are asked inside entity filters — the witness scan, the hearing scan, the ally count.
     */
    public static boolean permits(Capability capability, @Nullable Entity entity) {
        boolean awake = isAwake(entity);
        if (!awake || !dev.otectus.mcacrime.compat.TownsteadBridge.isAvailable() || !respectIncapacity()) {
            return awake;
        }
        TownsteadSnapshotCache.Snapshot snapshot = TownsteadSnapshotCache.snapshot(entity);
        return permits(capability, true, true, snapshot.incapacitated(), snapshot.mobile(),
                snapshot.talkable());
    }

    private static boolean respectIncapacity() {
        try {
            return McaCrimeConfig.COMMON.townsteadEnabled.get()
                    && McaCrimeConfig.COMMON.townsteadRespectIncapacity.get();
        } catch (Throwable t) {
            // No config loaded means no game running; the honest answer is the pre-Townstead one.
            return false;
        }
    }

    /** Can perceive an act happening in front of them right now. */
    public static boolean canObserveAct(@Nullable Entity entity) {
        return permits(Capability.OBSERVE, entity);
    }

    /**
     * Can put a name to whoever is doing it.
     *
     * <p>Identical to {@link #canObserveAct} today, and kept separate because the two questions are
     * not the same one: identification is where a mask, a disguise or a future recognition rule will
     * attach, and folding it into perception now would mean unpicking every call site later.
     */
    public static boolean canIdentifyActor(@Nullable Entity entity) {
        return permits(Capability.IDENTIFY, entity);
    }

    /** Can speak, be spoken to, or carry a report to a responder. */
    public static boolean canSpeakOrReport(@Nullable Entity entity) {
        return permits(Capability.SPEAK, entity);
    }

    /** Can be given a walk order by MCA: Crime. */
    public static boolean canNavigate(@Nullable Entity entity) {
        return permits(Capability.NAVIGATE, entity);
    }

    /** Can act as law — challenge, pursue, arrest, escort — or be counted as one who could. */
    public static boolean canRespondAsGuard(@Nullable Entity entity) {
        return permits(Capability.GUARD_RESPONSE, entity);
    }

    /** Can commit a crime of their own. */
    public static boolean canPerformCriminalAction(@Nullable Entity entity) {
        return permits(Capability.CRIMINAL_ACTION, entity);
    }

    /** Can hold, take, give or lose an item. */
    public static boolean canUseHands(@Nullable Entity entity) {
        return permits(Capability.HANDS, entity);
    }

    /**
     * Line of sight for a fresh visual fix, with sand taken into account.
     *
     * <p>Use this where the answer starts or continues live tracking of a target. Do not use it where
     * the answer is about something already known — a remembered identity, a last-seen position, an
     * open case or a target already held — because losing sight of somebody is not forgetting them.
     */
    public static boolean canSeeNow(LivingEntity observer, Entity target) {
        return dev.otectus.mcacrime.effect.SandBlindness.canSee(observer, target);
    }

    /** Clear stale orders before AI ticks without cancelling the tick or changing the sleep state. */
    public static void settleSleeping(LivingEntity entity) {
        if (!entity.isSleeping() || !(entity instanceof Mob mob)
                || !(McaCompat.isMcaVillager(entity) || EntitySelectors.isResponder(entity))) return;
        McaCompat.releaseVillagerControl(entity);
        ReactionSpeedModifier.remove(entity);
        entity.stopUsingItem(); // Abandon a drawn bow without releasing an arrow.
        mob.setAggressive(false);
        mob.setSpeed(0);
        mob.setXxa(0);
        mob.setZza(0);
    }
}
