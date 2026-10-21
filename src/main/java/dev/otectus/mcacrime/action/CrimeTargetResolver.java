package dev.otectus.mcacrime.action;

import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.help.CrimeHelpTopic;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Which entity a {@code /crime} command means, and, when it cannot say, exactly why (0.8.0).
 *
 * <p>Selection is kept apart from permission to act. This class answers "which entity, and is it the
 * right kind"; the owning service still answers "may this action happen to it". The distinction is what
 * lets a command say "you are looking at a vanilla villager" or "several villagers are nearby" instead of
 * "no MCA villager is around" -- the 0.7.x message for every one of those cases, and the wording of the
 * player report this release answers.
 *
 * <h2>The rules</h2>
 *
 * <ol>
 *   <li>An explicit entity argument selects exactly that entity. It is checked for kind and liveness and
 *       never replaced by a fallback.</li>
 *   <li>Otherwise the entity under the player's crosshair wins: a ray from the eye along the view, clipped
 *       against blocks first, then tested against entity hitboxes with vanilla's pick radius -- the same
 *       order the client's own crosshair uses, never a test against entity centres.</li>
 *   <li>Only an operator policy falls back, and only to the single visible supported candidate in range.
 *       Two or more is {@link Outcome#AMBIGUOUS}. Gameplay never picks a convenient victim or guard.</li>
 *   <li>An aimed entity that is the wrong kind is reported as such. Nothing behind or beside it is
 *       chosen instead.</li>
 * </ol>
 *
 * <p><b>Distance is measured once, feet to feet</b> ({@link Entity#distanceToSqr(Entity)}), the measure
 * the action services themselves apply. The broad-phase box only finds candidates; the spherical test
 * afterwards decides, so a box corner cannot stretch a documented range. The crosshair ray is
 * {@link #AIM_MARGIN} longer than the range so an aimed target just past the edge is explained as out
 * of reach rather than reported as nothing at all.
 */
public final class CrimeTargetResolver {

    /** How far beyond a policy's range the crosshair still looks, to explain "too far" precisely. */
    public static final double AIM_MARGIN = 1.0D;

    /** How many candidates a diagnostic lists before it stops. */
    public static final int DIAGNOSTIC_LIST_LIMIT = 5;

    /** How a target came to be chosen. */
    public enum Mode {
        EXPLICIT,
        CROSSHAIR,
        SOLE_CANDIDATE,
        NONE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Whether there is a usable target and, when there is not, which distinct reason applies. */
    public enum Outcome {
        SELECTED,
        /** Nothing under the crosshair, and (for operator policies) no candidate in range. */
        NO_ENTITY,
        /** The aimed or named entity is not the kind this command needs. */
        NOT_SUPPORTED,
        /** MCA's villager identity did not bind, so no entity can be recognised. */
        RECOGNITION_UNAVAILABLE,
        OUT_OF_REACH,
        OBSTRUCTED,
        /** Dead or removed. */
        GONE,
        /** Several candidates and nothing aimed: the operator has to say which. */
        AMBIGUOUS,
        /** Implicit selection asked of a source with no eyes: the console or a command block. */
        NEEDS_EXPLICIT_TARGET;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What an entity is, for the wrong-kind explanation. */
    public enum Kind {
        MCA_VILLAGER("mcacrime.target.kind.mca_villager"),
        MCA_ZOMBIE_VILLAGER("mcacrime.target.kind.mca_zombie_villager"),
        VANILLA_VILLAGER("mcacrime.target.kind.vanilla_villager"),
        ZOMBIE_VILLAGER("mcacrime.target.kind.zombie_villager"),
        WANDERING_TRADER("mcacrime.target.kind.wandering_trader"),
        PLAYER("mcacrime.target.kind.player"),
        OTHER("mcacrime.target.kind.other");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /**
     * How far a command looks and whether it may fall back.
     *
     * @param range                 the documented distance, in blocks, feet to feet
     * @param soleCandidateFallback whether, with nothing aimed, the single visible candidate counts
     * @param requireSight          whether the target has to be in clear view of the player's eyes
     */
    public record Policy(String id, double range, boolean soleCandidateFallback, boolean requireSight) {

        /** Mutating operator commands: {@code /crime job ...}. */
        public static final Policy OPERATOR = new Policy("operator", 8.0D, true, true);
        /** Read-only operator diagnostics: {@code /crime debug villager|target|custody}. */
        public static final Policy DIAGNOSTIC = new Policy("diagnostic", 10.0D, true, true);
        /** Close-range gameplay: {@code /crime mug}, {@code /crime report}. The action services' 4 blocks. */
        public static final Policy GAMEPLAY = new Policy("gameplay", 4.0D, false, true);

        public double rangeSqr() {
            return range * range;
        }

        /** The range as players read it: "4", "8", "10". */
        public String rangeText() {
            return range == Math.rint(range) ? Integer.toString((int) range)
                    : String.format(Locale.ROOT, "%.1f", range);
        }
    }

    /** What the command needs the target to be. */
    public enum Need {
        /** An MCA human villager, adult or child: every villager-directed command. */
        MCA_VILLAGER,
        /** A law responder, the way a report needs one: an MCA guard or archer, or a configured one. */
        RESPONDER
    }

    /**
     * One selection question.
     *
     * @param usage a short example of the explicit form, shown when the command needs a target named
     */
    public record Request(Policy policy, Need need, String usage) {

        public static Request of(Policy policy, Need need, String usage) {
            return new Request(policy, need, usage);
        }
    }

    /**
     * Facts about one entity the resolver may consider. Computed from the world by
     * {@link #resolve}, or by hand in a test.
     */
    public record Candidate(boolean supported, Kind kind, double distanceSqr, boolean visible, boolean alive) {
    }

    /** The decision over {@link Candidate}s: which one, how, and how many were in play. */
    public record Decision(Outcome outcome, Mode mode, int index, int candidates) {

        static Decision of(Outcome outcome, Mode mode, int index, int candidates) {
            return new Decision(outcome, mode, index, candidates);
        }
    }

    private CrimeTargetResolver() {
    }

    // --- the pure rule ---------------------------------------------------------------------------------

    /**
     * The whole selection rule, as a function of facts.
     *
     * @param recognitionAvailable whether MCA identity bound (pass true when the need does not depend
     *                             on it)
     * @param aimed                the entity under the crosshair, or null when the ray hit nothing
     * @param nearby               supported or not, every candidate the broad phase found, used only
     *                             when nothing was aimed and the policy falls back
     */
    public static Decision decide(Policy policy, boolean recognitionAvailable, @Nullable Candidate aimed,
                                  List<Candidate> nearby) {
        if (!recognitionAvailable) {
            return Decision.of(Outcome.RECOGNITION_UNAVAILABLE, Mode.NONE, -1, 0);
        }
        double rangeSqr = policy.rangeSqr();
        if (aimed != null) {
            if (!aimed.alive()) {
                return Decision.of(Outcome.GONE, Mode.CROSSHAIR, -1, 1);
            }
            if (!aimed.supported()) {
                return Decision.of(Outcome.NOT_SUPPORTED, Mode.CROSSHAIR, -1, 1);
            }
            if (aimed.distanceSqr() > rangeSqr) {
                return Decision.of(Outcome.OUT_OF_REACH, Mode.CROSSHAIR, -1, 1);
            }
            if (policy.requireSight() && !aimed.visible()) {
                return Decision.of(Outcome.OBSTRUCTED, Mode.CROSSHAIR, -1, 1);
            }
            return Decision.of(Outcome.SELECTED, Mode.CROSSHAIR, -1, 1);
        }
        if (!policy.soleCandidateFallback()) {
            return Decision.of(Outcome.NO_ENTITY, Mode.NONE, -1, 0);
        }
        int chosen = -1;
        int visibleInRange = 0;
        int hiddenInRange = 0;
        for (int i = 0; i < nearby.size(); i++) {
            Candidate candidate = nearby.get(i);
            if (candidate == null || !candidate.supported() || !candidate.alive()
                    || candidate.distanceSqr() > rangeSqr) {
                continue;
            }
            if (policy.requireSight() && !candidate.visible()) {
                hiddenInRange++;
                continue;
            }
            visibleInRange++;
            chosen = i;
        }
        if (visibleInRange == 1) {
            return Decision.of(Outcome.SELECTED, Mode.SOLE_CANDIDATE, chosen, 1);
        }
        if (visibleInRange > 1) {
            return Decision.of(Outcome.AMBIGUOUS, Mode.NONE, -1, visibleInRange);
        }
        if (hiddenInRange > 0) {
            return Decision.of(Outcome.OBSTRUCTED, Mode.NONE, -1, hiddenInRange);
        }
        return Decision.of(Outcome.NO_ENTITY, Mode.NONE, -1, 0);
    }

    // --- the world adapter -----------------------------------------------------------------------------

    /**
     * The answer, with the entity attached when there is one.
     *
     * @param distance feet-to-feet distance to the entity in blocks, or {@code -1} when there is none
     */
    public record Resolution(Outcome outcome, Mode mode, @Nullable Entity entity, Kind kind, double distance,
                             int candidates, Request request) {

        public boolean selected() {
            return outcome == Outcome.SELECTED && entity != null;
        }

        /** The player-facing reason, or null when a target was selected. */
        @Nullable
        public Component explain() {
            return CrimeTargetResolver.explain(this);
        }
    }

    /**
     * Implicit selection for a command source: the crosshair, then the operator fallback.
     *
     * <p>A source without a player behind it (the console, a command block, a function) has no
     * crosshair and no "nearby", so it is told to name a target instead of being handed whoever happens
     * to stand near the command block.
     */
    public static Resolution implicit(CommandSourceStack source, Request request) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return new Resolution(Outcome.NEEDS_EXPLICIT_TARGET, Mode.NONE, null, Kind.OTHER, -1.0D, 0, request);
        }
        return resolve(player, request);
    }

    /** Implicit selection for a player: what they aim at, then (operator policies) the sole candidate. */
    public static Resolution resolve(ServerPlayer player, Request request) {
        boolean recognition = recognitionMatters(request.need()) ? McaCompat.villagerRecognitionAvailable() : true;
        Policy policy = request.policy();
        Entity aimed = crosshair(player, policy.range() + AIM_MARGIN);
        Candidate aimedFacts = aimed == null ? null : facts(player, aimed, request.need());
        if (aimed == null && recognition) {
            // The crosshair stopped at a block. If a supported entity stands on the same line behind it,
            // the player is aiming at them through the wall: that is "move where you can see them", and
            // never a cue to pick some other villager beside them.
            Entity hidden = crosshairThroughBlocks(player, policy.range() + AIM_MARGIN, request.need());
            if (hidden != null) {
                aimed = hidden;
                aimedFacts = new Candidate(true, kindOf(hidden), player.distanceToSqr(hidden), false,
                        hidden.isAlive() && !hidden.isRemoved());
            }
        }
        List<Entity> nearby = List.of();
        List<Candidate> nearbyFacts = List.of();
        if (aimed == null && policy.soleCandidateFallback() && recognition) {
            nearby = broadPhase(player, policy.range(), request.need());
            nearbyFacts = new ArrayList<>(nearby.size());
            for (Entity entity : nearby) {
                nearbyFacts.add(facts(player, entity, request.need()));
            }
        }
        Decision decision = decide(policy, recognition, aimedFacts, nearbyFacts);
        Entity entity = decision.mode() == Mode.CROSSHAIR ? aimed
                : decision.index() >= 0 ? nearby.get(decision.index()) : null;
        if (decision.outcome() == Outcome.GONE) {
            entity = null;
        }
        Outcome outcome = decision.outcome();
        if (outcome == Outcome.NOT_SUPPORTED && request.need() == Need.RESPONDER
                && !McaCompat.villagerRecognitionAvailable() && !(aimed instanceof Player)) {
            // A configured responder needs no MCA binding, but an MCA guard does: with identity unbound,
            // "that is not a guard" would be a guess, so the binding problem is reported instead.
            outcome = Outcome.RECOGNITION_UNAVAILABLE;
        }
        return new Resolution(outcome, decision.mode(), entity,
                entity == null ? Kind.OTHER : kindOf(entity),
                entity == null ? -1.0D : Math.sqrt(player.distanceToSqr(entity)),
                decision.candidates(), request);
    }

    /**
     * An entity named by an argument. Kind and liveness only: explicit operator selection uses normal
     * loaded-entity selector semantics and does not pretend to be a close-range gameplay action.
     */
    public static Resolution explicit(@Nullable Entity named, @Nullable Entity viewer, Request request) {
        boolean recognition = recognitionMatters(request.need()) ? McaCompat.villagerRecognitionAvailable() : true;
        double distance = named != null && viewer != null && named.level() == viewer.level()
                ? Math.sqrt(viewer.distanceToSqr(named)) : -1.0D;
        if (!recognition) {
            return new Resolution(Outcome.RECOGNITION_UNAVAILABLE, Mode.EXPLICIT, named,
                    named == null ? Kind.OTHER : kindOf(named), distance, 1, request);
        }
        if (named == null || !named.isAlive() || named.isRemoved()) {
            return new Resolution(Outcome.GONE, Mode.EXPLICIT, null, Kind.OTHER, -1.0D, 1, request);
        }
        Outcome outcome = supports(request.need(), named) ? Outcome.SELECTED : Outcome.NOT_SUPPORTED;
        return new Resolution(outcome, Mode.EXPLICIT, named, kindOf(named), distance, 1, request);
    }

    /** Whether this need depends on MCA's identity binding at all. A configured responder does not. */
    private static boolean recognitionMatters(Need need) {
        return need == Need.MCA_VILLAGER;
    }

    /** Whether an entity is what the need asks for. */
    public static boolean supports(Need need, Entity entity) {
        return switch (need) {
            case MCA_VILLAGER -> McaCompat.isMcaVillager(entity);
            case RESPONDER -> dev.otectus.mcacrime.detect.EntitySelectors.isResponder(entity);
        };
    }

    private static Candidate facts(ServerPlayer player, Entity entity, Need need) {
        return new Candidate(supports(need, entity), kindOf(entity), player.distanceToSqr(entity),
                player.hasLineOfSight(entity), entity.isAlive() && !entity.isRemoved());
    }

    /**
     * The entity under the player's crosshair within {@code length} of their eyes, or null.
     *
     * <p>Blocks are clipped first, so an entity behind a wall is never "aimed at"; then every pickable
     * entity's hitbox, inflated by its own pick radius, is tested against what is left of the ray --
     * vanilla's {@code GameRenderer#pick} order. Riders and the ridden are skipped, as vanilla skips them.
     */
    @Nullable
    public static Entity crosshair(ServerPlayer player, double length) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(length));
        BlockHitResult block = player.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
        if (block.getType() != HitResult.Type.MISS) {
            end = block.getLocation();
        }
        double reachSqr = eye.distanceToSqr(end);
        AABB search = player.getBoundingBox().expandTowards(look.scale(length)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, search,
                entity -> entity != player && !entity.isSpectator() && entity.isPickable()
                        && entity.getRootVehicle() != player.getRootVehicle(),
                reachSqr);
        return hit == null ? null : hit.getEntity();
    }

    /**
     * The nearest <em>supported</em> entity on the crosshair line within {@code length}, ignoring
     * blocks: only ever used after the block-clipped ray found nothing, to tell "you are looking at a
     * villager through a wall" apart from "you are looking at nothing".
     */
    @Nullable
    private static Entity crosshairThroughBlocks(ServerPlayer player, double length, Need need) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(length));
        AABB search = player.getBoundingBox().expandTowards(look.scale(length)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, search,
                entity -> entity != player && !entity.isSpectator() && entity.isPickable()
                        && supports(need, entity),
                length * length);
        return hit == null ? null : hit.getEntity();
    }

    /** Supported candidates in the inflated box, nearest first. The box is broad phase only. */
    private static List<Entity> broadPhase(ServerPlayer player, double range, Need need) {
        AABB box = player.getBoundingBox().inflate(range);
        Predicate<Entity> wanted = entity -> entity != player && supports(need, entity);
        List<Entity> found = new ArrayList<>(player.level().getEntities(player, box, wanted));
        found.sort(Comparator.comparingDouble(player::distanceToSqr));
        return found;
    }

    /** What an entity is, as far as the wrong-kind explanation cares. */
    public static Kind kindOf(Entity entity) {
        if (McaCompat.isMcaVillager(entity)) {
            return Kind.MCA_VILLAGER;
        }
        if (McaCompat.isMcaVillagerLike(entity)) {
            return Kind.MCA_ZOMBIE_VILLAGER;
        }
        if (entity instanceof ZombieVillager) {
            return Kind.ZOMBIE_VILLAGER;
        }
        if (entity instanceof Villager) {
            return Kind.VANILLA_VILLAGER;
        }
        if (entity instanceof AbstractVillager) {
            return Kind.WANDERING_TRADER;
        }
        if (entity instanceof Player) {
            return Kind.PLAYER;
        }
        return Kind.OTHER;
    }

    // --- explanations -------------------------------------------------------------------------------

    /** "a vanilla villager", "a Pig": the noun phrase a wrong-kind message names. */
    public static Component describe(Kind kind, @Nullable Entity entity) {
        if (kind == Kind.OTHER || kind == Kind.PLAYER) {
            Component name = entity == null ? Component.translatable("mcacrime.target.kind.unknown")
                    : entity.getDisplayName();
            return Component.translatable(kind.key(), name);
        }
        return Component.translatable(kind.key());
    }

    /** The player-facing reason for an unselected resolution, with a link to the targeting help. */
    @Nullable
    public static Component explain(Resolution resolution) {
        if (resolution.selected()) {
            return null;
        }
        Request request = resolution.request();
        Policy policy = request.policy();
        Entity entity = resolution.entity();
        Component name = entity == null ? Component.translatable("mcacrime.target.kind.unknown")
                : entity.getDisplayName();
        MutableComponent line = switch (resolution.outcome()) {
            case SELECTED -> Component.empty();
            case NO_ENTITY -> policy.soleCandidateFallback()
                    ? Component.translatable("mcacrime.target.none.operator", policy.rangeText(), request.usage())
                    : Component.translatable(request.need() == Need.RESPONDER
                            ? "mcacrime.target.none.responder" : "mcacrime.target.none.gameplay",
                            policy.rangeText());
            case NOT_SUPPORTED -> request.need() == Need.RESPONDER
                    ? (resolution.kind() == Kind.MCA_VILLAGER
                            ? Component.translatable("mcacrime.target.not_responder", name)
                            : Component.translatable("mcacrime.target.wrong_kind.responder",
                                    describe(resolution.kind(), entity)))
                    : Component.translatable("mcacrime.target.wrong_kind", describe(resolution.kind(), entity));
            case RECOGNITION_UNAVAILABLE -> Component.translatable("mcacrime.target.unavailable");
            case OUT_OF_REACH -> Component.translatable("mcacrime.target.out_of_reach", name,
                    String.format(Locale.ROOT, "%.1f", Math.max(0.0D, resolution.distance())), policy.rangeText());
            case OBSTRUCTED -> resolution.mode() == Mode.CROSSHAIR
                    ? Component.translatable("mcacrime.target.obstructed", name)
                    : Component.translatable("mcacrime.target.obstructed.nearby", policy.rangeText(), request.usage());
            case GONE -> Component.translatable("mcacrime.target.gone");
            case AMBIGUOUS -> Component.translatable("mcacrime.target.ambiguous", resolution.candidates(),
                    policy.rangeText(), request.usage());
            case NEEDS_EXPLICIT_TARGET -> Component.translatable("mcacrime.target.needs_explicit", request.usage());
        };
        return line.append(Component.literal(" ")).append(CrimeHelpTopic.TARGETING.link());
    }

    /** One diagnostic line per nearby candidate, nearest first, bounded. */
    public static List<Component> describeNearby(ServerPlayer player, Request request) {
        List<Component> lines = new ArrayList<>();
        List<Entity> nearby = broadPhase(player, request.policy().range() + AIM_MARGIN, request.need());
        for (Entity entity : nearby) {
            if (lines.size() >= DIAGNOSTIC_LIST_LIMIT) {
                lines.add(Component.translatable("mcacrime.target.debug.more", nearby.size() - lines.size())
                        .withStyle(ChatFormatting.DARK_GRAY));
                break;
            }
            lines.add(Component.translatable("mcacrime.target.debug.candidate", entity.getDisplayName(),
                    String.format(Locale.ROOT, "%.1f", Math.sqrt(player.distanceToSqr(entity))),
                    Component.translatable(player.hasLineOfSight(entity)
                            ? "mcacrime.target.debug.visible" : "mcacrime.target.debug.hidden"))
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }
}
