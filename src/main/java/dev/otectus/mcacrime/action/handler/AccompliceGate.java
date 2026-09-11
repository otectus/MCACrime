package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.ActionAvailability;
import dev.otectus.mcacrime.action.CrimeActor;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.relationship.FamilyGraph;
import dev.otectus.mcacrime.relationship.FamilyTier;
import dev.otectus.mcacrime.state.world.AccompliceRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Whether this villager will help this player commit a crime — the one answer all three CONSPIRE
 * actions need, asked in one place so they cannot drift apart.
 *
 * <p>Split the way the rest of the mod splits these: {@link #decide} is a pure function of facts, and
 * {@link #evaluate} is the thin part that reads those facts out of the world and the config. The gate
 * matrix is the interesting half and it is the half that never needs a server.
 *
 * <p>The hidden/blocked distinction follows the existing precedents exactly. Asking a stranger to keep
 * watch is not an option a player should be shown and refused — it names a relationship that does not
 * exist ({@code RansomActionHandler}). Asking your sister who does not trust you enough <em>is</em>
 * shown and refused, with the reason attached, because that is a rule worth learning
 * ({@code BailActionHandler}).
 */
public final class AccompliceGate {

    /** How the row should be presented, without naming a Minecraft type to say it. */
    public enum Visibility { AVAILABLE, BLOCKED, HIDDEN }

    /** Everything the decision depends on. Built at the call site; never read from config in here. */
    public record Input(boolean enabled, boolean relationshipApi, boolean mcaVillager, boolean family,
                        boolean adult, boolean responder, boolean inCustody, boolean wanted,
                        boolean helpingAlready, int hearts, int heartsRequired,
                        long lastAssistedTick, long recruitCooldownTicks, long now) {
    }

    /** The verdict and the key that explains it. {@code reasonKey} is empty only when available. */
    public record Decision(Visibility visibility, String reasonKey) {

        public boolean available() {
            return visibility == Visibility.AVAILABLE;
        }
    }

    private static final Decision AVAILABLE = new Decision(Visibility.AVAILABLE, "");

    private AccompliceGate() {
    }

    /** The pure gate. Order matters: the hidden cases are decided before any of the blocked ones. */
    public static Decision decide(Input in) {
        if (!in.enabled()) {
            return new Decision(Visibility.HIDDEN, "mcacrime.accomplice.disabled");
        }
        if (!in.relationshipApi() || !in.mcaVillager() || !in.family()) {
            // Not family, or MCA cannot say who is: an option that names a relationship the player
            // does not have is noise, not a refusal.
            return new Decision(Visibility.HIDDEN, "mcacrime.accomplice.not_family");
        }
        if (!in.adult()) {
            return new Decision(Visibility.BLOCKED, "mcacrime.accomplice.too_young");
        }
        if (in.responder()) {
            // A guard in the family is still a guard. This is blocked rather than hidden on purpose:
            // it is the rule most worth knowing before you ask.
            return new Decision(Visibility.BLOCKED, "mcacrime.accomplice.is_guard");
        }
        if (in.inCustody()) {
            return new Decision(Visibility.BLOCKED, "mcacrime.accomplice.in_custody");
        }
        if (in.wanted()) {
            return new Decision(Visibility.BLOCKED, "mcacrime.accomplice.already_wanted");
        }
        if (in.helpingAlready()) {
            return new Decision(Visibility.BLOCKED, "mcacrime.accomplice.busy");
        }
        if (in.hearts() < in.heartsRequired()) {
            return new Decision(Visibility.BLOCKED, "mcacrime.accomplice.not_close_enough");
        }
        if (in.recruitCooldownTicks() > 0L && in.lastAssistedTick() > 0L
                && in.now() - in.lastAssistedTick() < in.recruitCooldownTicks()) {
            return new Decision(Visibility.BLOCKED, "mcacrime.accomplice.too_soon");
        }
        return AVAILABLE;
    }

    /** The configured scope, with unknown names dropped ({@code ConfigValidator} reports them). */
    public static Set<FamilyTier> scope(List<? extends String> names) {
        Set<FamilyTier> tiers = EnumSet.noneOf(FamilyTier.class);
        for (String name : names) {
            FamilyTier.parse(name).ifPresent(tiers::add);
        }
        return tiers;
    }

    /** Reads the world and answers as the action screen needs it. */
    public static ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        ServerPlayer player = actor.asPlayer();
        if (player == null || target == null) {
            return ActionAvailability.hidden("mcacrime.action.invalid_target");
        }
        if (!ServerMutationGate.allows(level.getServer())) {
            return ActionAvailability.blocked("mcacrime.readonly");
        }
        Decision decision = decide(input(player, target, level, now));
        return switch (decision.visibility()) {
            case AVAILABLE -> ActionAvailability.available();
            case BLOCKED -> ActionAvailability.blocked(decision.reasonKey());
            case HIDDEN -> ActionAvailability.hidden(decision.reasonKey());
        };
    }

    /** The facts behind {@link #decide}, read once. */
    public static Input input(ServerPlayer player, LivingEntity target, ServerLevel level, long now) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        boolean enabled = c.enableAccomplices.get();
        boolean api = McaCompat.isRelationshipApiAvailable();
        boolean villager = McaCompat.isMcaVillager(target);
        boolean family = false;
        if (enabled && api && villager) {
            Set<FamilyTier> scope = scope(c.accompliceScope.get());
            Map<UUID, FamilyTier> relatives = FamilyGraph.relativesOf(player, scope,
                    c.familyLoyaltyGenerations.get());
            family = relatives.containsKey(target.getUUID());
        }
        AccompliceRecord record = CrimeWorldData.get(level.getServer()).accomplice(target.getUUID());
        return new Input(enabled, api, villager, family,
                McaCompat.isAdult(target),
                EntitySelectors.isResponder(target) || McaCompat.isGuard(target) || McaCompat.isArcher(target),
                CustodyRegistry.isCaptive(level.getServer(), target.getUUID()),
                record != null && record.wanted(),
                record != null && record.active(now),
                McaCompat.getHearts(player, target), c.accompliceHeartsRequired.get(),
                record == null ? 0L : record.lastAssistedTick(),
                c.accompliceRecruitCooldownTicks.get(), now);
    }
}
