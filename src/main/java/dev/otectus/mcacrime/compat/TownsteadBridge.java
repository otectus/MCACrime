package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The optional-classloading seam for Townstead, built to the same discipline as
 * {@link ReputationBridge}: {@link ModList} decides, {@code Class.forName} loads, and no class in this
 * file names anything that would fail to resolve without the companion installed.
 *
 * <h2>Why the implementation is reflective all the way down</h2>
 *
 * <p>Townstead is compiled against MCA, so its own descriptors carry MCA types. Naming a Townstead
 * class here would put a <em>relocated MCA</em> type into MCA: Crime's constant pool and re-link this
 * mod to one MCA package layout — the failure {@code NoMcaStaticLinkTest} exists to prevent. So the
 * whole implementation lives under {@code compat.townstead}, matches Townstead members by name and
 * arity, and is reached only through the dotted string literal below. {@code NoTownsteadStaticLinkTest}
 * is what keeps it that way, with no exemption list.
 *
 * <h2>What this bridge promises gameplay code</h2>
 *
 * <p><b>It never throws.</b> Every query answers with a {@link TownsteadQueryResult}, and every empty
 * answer is a first-class value rather than a zeroed record — an absent Townstead must never look like
 * a village of starving villagers. Binding happens once at {@code ServerStartedEvent}, beside the
 * MCA: Reputation handshake, and is released on server stop.
 */
public final class TownsteadBridge {

    /** The Townstead mod id, as it appears in {@code ModList}. */
    public static final String MOD_ID = "townstead";

    /** How much of the declared read surface is live. */
    public enum State {
        /** Townstead is not installed. The normal, silent path — never a warning. */
        ABSENT,
        /** Installed, but the config switched the integration off. */
        OFF,
        /** Installed and enabled, but not one capability could be bound. */
        DISABLED,
        /** Some capabilities bound; the rest report unavailable and their features degrade. */
        PARTIAL,
        /** Every capability the manifest declares bound. */
        FULL
    }

    /**
     * What the reflective implementation provides. Declared here rather than in the guarded package so
     * this file can hold the reference without naming anything under {@code compat.townstead}.
     *
     * <p>Every method returns a value; none throws. The implementation catches {@link Throwable} at its
     * own boundary, and this bridge catches it again, because a compat layer that can take a tick
     * handler down is worse than one that reports nothing.
     */
    public interface Ops {

        State state();

        Set<TownsteadCapability> capabilities();

        String detectedVersion();

        /** The MCA package root Townstead was compiled against. Diagnostics only. */
        Optional<String> variant();

        /** Manifest members that did not bind, for diagnostics. */
        List<String> unresolvedMembers();

        TownsteadQueryResult<TownsteadVillagerView> villager(@Nullable Entity entity);

        TownsteadQueryResult<TownsteadNeedsView> needs(@Nullable Entity entity);

        TownsteadQueryResult<TownsteadScheduleView> schedule(@Nullable Entity entity);

        TownsteadQueryResult<TownsteadLifeStageView> lifeStage(@Nullable Entity entity);

        TownsteadQueryResult<TownsteadBuildingView> buildingAt(@Nullable ServerLevel level,
                                                               @Nullable BlockPos pos);

        TownsteadQueryResult<List<TownsteadBuildingView>> buildingsAt(@Nullable ServerLevel level,
                                                                      @Nullable BlockPos pos);

        TownsteadQueryResult<Integer> villageRevision(@Nullable ServerLevel level, int villageId);

        TownsteadQueryResult<Boolean> feedInCustody(@Nullable LivingEntity prisoner,
                                                    @Nullable ItemStack food,
                                                    @Nullable BlockPos source);

        TownsteadQueryResult<TownsteadCalendarView> calendar(@Nullable MinecraftServer server);

        TownsteadQueryResult<TownsteadSpiritView> spirit(@Nullable ServerLevel level, int villageId);

        TownsteadQueryResult<Integer> dispatchReaction(@Nullable ServerLevel level,
                                                       @Nullable LivingEntity villager,
                                                       @Nullable ResourceLocation taskId,
                                                       String phase);
    }

    private static volatile Ops ops;
    private static volatile boolean bound;
    private static volatile State state = State.ABSENT;
    private static volatile String status = "not initialised";

    private TownsteadBridge() {
    }

    /**
     * Binds the integration, once per server.
     *
     * <p>At {@code ServerStartedEvent} rather than at mod setup because that is when both the
     * datapack-backed content and the server itself exist, and because it puts the Townstead line next
     * to the MCA: Reputation handshake in the log, where an operator reads both at once. Repeated
     * calls are no-ops until {@link #release()}.
     */
    public static synchronized void bind() {
        if (bound) {
            return;
        }
        bound = true;

        if (!ModList.get().isLoaded(MOD_ID)) {
            state = State.ABSENT;
            status = "not installed";
            return;
        }
        if (!McaCrimeConfig.COMMON.townsteadEnabled.get()) {
            state = State.OFF;
            status = "disabled by config";
            McaCrime.LOGGER.info("MCA: Crime — Townstead is installed but the integration is switched off "
                    + "in the config; crime behaves exactly as it does without it.");
            return;
        }
        try {
            // Class.forName rather than a direct reference: naming the class here would put it in this
            // class's constant pool and defeat the whole seam.
            Object candidate = Class.forName("dev.otectus.mcacrime.compat.townstead.ReflectiveTownsteadBridge")
                    .getDeclaredConstructor().newInstance();
            if (!(candidate instanceof Ops implementation)) {
                state = State.DISABLED;
                status = "adapter did not install";
                McaCrime.LOGGER.error("MCA: Crime — the Townstead adapter loaded but did not implement the "
                        + "bridge; the integration is off and crime behaves as it does without Townstead.");
                return;
            }
            ops = implementation;
            state = implementation.state();
            status = describeBinding(implementation);
            logBinding(implementation);
        } catch (Throwable t) {
            ops = null;
            state = State.DISABLED;
            status = "failed: " + t.getClass().getSimpleName();
            McaCrime.LOGGER.error("MCA: Crime — Townstead is installed but the integration could not start; "
                    + "every Townstead-aware feature degrades and MCA: Crime remains fully playable.", t);
        }
    }

    private static String describeBinding(Ops implementation) {
        return implementation.state().name().toLowerCase(java.util.Locale.ROOT)
                + " (" + implementation.capabilities().size() + " capability/ies)";
    }

    private static void logBinding(Ops implementation) {
        switch (implementation.state()) {
            case FULL -> McaCrime.LOGGER.info("MCA: Crime — Townstead {} detected; all {} read capabilities "
                            + "bound (MCA root {}).", implementation.detectedVersion(),
                    implementation.capabilities().size(), implementation.variant().orElse("unknown"));
            case PARTIAL -> McaCrime.LOGGER.warn("MCA: Crime — Townstead {} detected, but only {} capability/ies "
                            + "bound. The features behind the rest report as degraded rather than silently "
                            + "off; run /crime debug townstead. Unbound member(s): {}",
                    implementation.detectedVersion(), implementation.capabilities().size(),
                    implementation.unresolvedMembers());
            default -> McaCrime.LOGGER.warn("MCA: Crime — Townstead {} is installed but nothing could be bound. "
                            + "The usual cause is a Townstead built against a different MCA build than the one "
                            + "installed; crime behaves as it does without Townstead. Unbound member(s): {}",
                    implementation.detectedVersion(), implementation.unresolvedMembers());
        }
    }

    /** Drops the binding at server stop, so a second world in one session re-negotiates cleanly. */
    public static synchronized void release() {
        ops = null;
        bound = false;
        state = State.ABSENT;
        status = "not initialised";
    }

    /** Re-negotiate on the server thread after a common-config reload, including off-to-on changes. */
    public static synchronized void reload() {
        TownsteadSnapshotCache.clearAll();
        release();
        bind();
    }

    /** Test hook; identical to {@link #release()} and named for what a test means by it. */
    public static synchronized void reset() {
        release();
    }

    /** Whether Townstead is installed at all, regardless of what bound. */
    public static boolean installed() {
        try {
            return ModList.get().isLoaded(MOD_ID);
        } catch (Throwable t) {
            // ModList is unavailable outside a running game (unit tests); absent is the honest answer.
            return false;
        }
    }

    /** Whether any capability is live. */
    public static boolean isAvailable() {
        Ops current = ops;
        return integrationEnabled() && current != null && !current.capabilities().isEmpty();
    }

    public static State state() {
        return state;
    }

    /** A short human-readable state for {@code /crime debug integrations}. */
    public static String status() {
        return status;
    }

    /** The installed Townstead's version, or empty when it is not installed. */
    public static String detectedVersion() {
        Ops current = ops;
        return current == null ? "" : current.detectedVersion();
    }

    public static Optional<String> variant() {
        Ops current = ops;
        return current == null ? Optional.empty() : current.variant();
    }

    public static List<String> unresolvedMembers() {
        Ops current = ops;
        return current == null ? List.of() : current.unresolvedMembers();
    }

    public static Set<TownsteadCapability> capabilities() {
        Ops current = ops;
        return current == null ? Set.of() : current.capabilities();
    }

    /**
     * Whether one capability is live right now.
     *
     * <p>The question every Townstead-aware feature asks before doing anything, and the one the config
     * validator asks to decide whether a switch that is on is actually degraded.
     *
     * <p>Most capabilities are read capabilities and are answered by the reflective binding. Two are
     * not: {@link TownsteadCapability#ACTIVITY_COORDINATION} and
     * {@link TownsteadCapability#EQUIPMENT_PROVENANCE} exist only because mixins were merged into
     * Townstead's own classes, and the binding has nothing to say about either. So they are answered
     * from {@link TownsteadMixinStatus} instead — see {@link #activityCoordinationInstalled()} for why
     * "applied" rather than "has fired" is the honest criterion here.
     */
    public static boolean has(TownsteadCapability capability) {
        if (capability == null) {
            return false;
        }
        if (capability == TownsteadCapability.ACTIVITY_COORDINATION) {
            return activityCoordinationInstalled();
        }
        if (capability == TownsteadCapability.EQUIPMENT_PROVENANCE) {
            return equipmentProvenanceInstalled();
        }
        if (capability == TownsteadCapability.CLIENT_DIALOGUE_ENTRY) {
            return dialogueEntryInstalled();
        }
        if (capability == TownsteadCapability.STORAGE_POLICY) {
            return storagePolicyInstalled();
        }
        Ops current = ops;
        return current != null && current.capabilities().contains(capability);
    }

    /**
     * Whether both halves of the activity-coordination layer were merged into Townstead.
     *
     * <p>Both, not either: refusing a reaction lock without defending the walk order leaves the guard
     * standing still anyway, and defending the walk order without refusing the lock leaves a frozen
     * escort. Half of this capability is not a degraded version of it, it is a different bug.
     *
     * <p>The criterion is "applied", not "has fired", and that distinction is load-bearing here rather
     * than pedantic. {@code ConfigValidator} runs at setup and on every config reload — before any
     * villager has rested or reacted — so a check that waited for a handler to run would report every
     * boot as DEGRADED and never correct itself. "Applied" is what is knowable at that moment and is
     * what the operator needs: the hooks are installed. Whether they have been reached yet is a
     * separate line in {@code /crime debug townstead}, which is where a genuinely moved injection
     * point shows up.
     */
    public static boolean activityCoordinationInstalled() {
        return TownsteadMixinStatus.isApplied(TownsteadMixinStatus.MIXIN_REACTION_LOCK_GATE)
                && TownsteadMixinStatus.isApplied(TownsteadMixinStatus.MIXIN_GUARD_REST_YIELD);
    }

    /**
     * Whether the work-tool provenance mixin was merged into Townstead.
     *
     * <p>One mixin, so no "both halves" argument applies — but the same "applied, not fired" criterion
     * does, and for a sharper reason than the coordination pair. A villager only reaches the copy site
     * at the start of a work shift, so a world that has just loaded at night would report the
     * capability as missing for hours if firing were the test, and {@code ConfigValidator} would tell
     * the operator their switch was degraded when it was merely early. Whether the hook has actually
     * been reached is reported separately by {@code /crime debug townstead}.
     */
    public static boolean equipmentProvenanceInstalled() {
        return TownsteadMixinStatus.isApplied(TownsteadMixinStatus.MIXIN_WORK_TOOL_PROVENANCE);
    }

    /**
     * Whether the storage-policy mixin was merged into Townstead's sourcing search.
     *
     * <p>One mixin, and the same "applied, not fired" criterion as the others, for the same reason:
     * {@code ConfigValidator} runs at setup and on every reload, long before any worker has looked for
     * anything, and a check that waited for the hook to fire would report every boot as degraded. What
     * "applied" buys is the fact that matters to a server owner turning property law on -- the hook is
     * installed, so a reserved container really will be skipped the first time a worker looks at it.
     */
    public static boolean storagePolicyInstalled() {
        return TownsteadMixinStatus.isApplied(TownsteadMixinStatus.MIXIN_STORAGE_POLICY);
    }

    /**
     * Whether the dialogue-entry mixin was merged into Townstead's own screen.
     *
     * <p>Client-only by construction, and that shows up here as an asymmetry worth stating rather than
     * hiding: on a dedicated server this is always false, because the mixin is in the config's
     * {@code client} list and is never applied there. That is the honest answer — the capability is a
     * button, and a dedicated server draws none — and no config switch depends on it, so nothing reports
     * as degraded because of it.
     */
    public static boolean dialogueEntryInstalled() {
        return TownsteadMixinStatus.isApplied(TownsteadMixinStatus.MIXIN_DIALOGUE_ENTRY);
    }

    /**
     * Whether the operator has switched the Townstead integration on at all.
     *
     * <p>Separate from {@link #isAvailable()} because the mixin layer needs it and cannot use the
     * rest: those hooks run inside Townstead's own methods, on installs where the reflective binding
     * may have bound nothing, and the one thing they must still honour is the kill switch. Any throw
     * — no config loaded, a unit test, a hook that somehow ran before mod setup — reads as "off",
     * which is the answer that leaves Townstead's behaviour exactly as it would be without MCA: Crime.
     */
    public static boolean integrationEnabled() {
        try {
            return McaCrimeConfig.COMMON.townsteadEnabled.get();
        } catch (Throwable t) {
            return false;
        }
    }

    // --- queries ---------------------------------------------------------------------------------

    public static TownsteadQueryResult<TownsteadVillagerView> villager(@Nullable Entity entity) {
        return query(current -> current.villager(entity));
    }

    public static TownsteadQueryResult<TownsteadNeedsView> needs(@Nullable Entity entity) {
        return query(current -> current.needs(entity));
    }

    public static TownsteadQueryResult<TownsteadScheduleView> schedule(@Nullable Entity entity) {
        return query(current -> current.schedule(entity));
    }

    public static TownsteadQueryResult<TownsteadLifeStageView> lifeStage(@Nullable Entity entity) {
        return query(current -> current.lifeStage(entity));
    }

    public static TownsteadQueryResult<TownsteadBuildingView> buildingAt(@Nullable ServerLevel level,
                                                                         @Nullable BlockPos pos) {
        return query(current -> current.buildingAt(level, pos));
    }

    /**
     * Every recognised Townstead building overlapping this position, with bounds, kind and the village
     * revision they were read at.
     *
     * <p>Distinct from {@link #buildingAt} in what it is allowed to answer, not only in how much: the
     * facade lookup returns the first match in the nearest village and carries no revision, so it
     * cannot say whether a position sits in two villages' overlapping claims and cannot be used to
     * revalidate a stored reference. This can, and an empty list is a real answer — nothing recognised
     * here — rather than a failure.
     */
    public static TownsteadQueryResult<List<TownsteadBuildingView>> buildingsAt(@Nullable ServerLevel level,
                                                                                @Nullable BlockPos pos) {
        return query(current -> current.buildingsAt(level, pos));
    }

    /** The current revision of a village's building record, which a stored reference is checked against. */
    public static TownsteadQueryResult<Integer> villageRevision(@Nullable ServerLevel level, int villageId) {
        return query(current -> current.villageRevision(level, villageId));
    }

    /**
     * Feeds a villager MCA: Crime is holding, through Townstead's own consumption flow.
     *
     * <p>The value is whether the villager actually started eating. {@code AVAILABLE(false)} covers the
     * ordinary refusals — the item is not food, or they are already eating — and is not an error.
     */
    public static TownsteadQueryResult<Boolean> feedInCustody(@Nullable LivingEntity prisoner,
                                                              @Nullable ItemStack food,
                                                              @Nullable BlockPos source) {
        return query(current -> current.feedInCustody(prisoner, food, source));
    }

    public static TownsteadQueryResult<TownsteadCalendarView> calendar(@Nullable MinecraftServer server) {
        return query(current -> current.calendar(server));
    }

    public static TownsteadQueryResult<TownsteadSpiritView> spirit(@Nullable ServerLevel level, int villageId) {
        return query(current -> current.spirit(level, villageId));
    }

    /** Plays a Townstead reaction for a public consequence; the value is how many reactions played. */
    public static TownsteadQueryResult<Integer> dispatchReaction(@Nullable ServerLevel level,
                                                                 @Nullable LivingEntity villager,
                                                                 @Nullable ResourceLocation taskId,
                                                                 String phase) {
        return query(current -> current.dispatchReaction(level, villager, taskId, phase));
    }

    /**
     * The one place a Townstead call can fail. Nothing below this line escapes into gameplay code: an
     * unbound bridge is "unavailable", and a throwing one is "failed" with the exception's name, which
     * is what {@code /crime debug townstead} prints.
     */
    private static <T> TownsteadQueryResult<T> query(java.util.function.Function<Ops, TownsteadQueryResult<T>> call) {
        Ops current = ops;
        if (current == null) {
            return TownsteadQueryResult.unavailable(status);
        }
        try {
            if (!integrationEnabled()) {
                return TownsteadQueryResult.unavailable("disabled by config");
            }
            TownsteadQueryResult<T> result = call.apply(current);
            return result == null ? TownsteadQueryResult.unavailable("no answer") : result;
        } catch (Throwable t) {
            return TownsteadQueryResult.failed(t.getClass().getSimpleName());
        }
    }
}
