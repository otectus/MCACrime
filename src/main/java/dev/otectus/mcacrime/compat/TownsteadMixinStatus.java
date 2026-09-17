package dev.otectus.mcacrime.compat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evidence that the Townstead mixin layer is really installed and really running.
 *
 * <h2>Why "applied" and "fired" are two different facts</h2>
 *
 * <p>The Townstead mixins are the opposite of MCA: Crime's vanilla ones. Their config is
 * {@code required: false}, every injector carries {@code require = 0}, and
 * {@code TownsteadMixinPlugin} refuses to apply anything when Townstead is absent or when the target
 * class has moved. That is deliberate — a settlement mod's point release must never be able to stop
 * the game from starting — but it means silence is ambiguous: a mixin that was never applied, one
 * that applied with every injection point missing, and one that is quietly working all look
 * identical from the outside.
 *
 * <p>So two things are recorded, from two different places:
 *
 * <ul>
 *   <li>{@link #applied(String)} is written by the config plugin's {@code postApply}. It means Mixin
 *       accepted the class and merged it into a Townstead class that exists.</li>
 *   <li>{@link #injected(String)} is written by the handler itself, the first time it runs. It means
 *       the {@code @At} really matched an instruction in the method it named, and Townstead really
 *       reached it.</li>
 * </ul>
 *
 * <p>{@code TownsteadDiagnostics} spends that distinction: applied and fired reads
 * {@code available (mixin)}; applied but never fired reads {@code degraded (mixin applied, hook not
 * yet observed)}; neither reads {@code unavailable}. An operator can therefore tell "Townstead moved
 * the method" from "nothing has happened yet in this world" without reading a log.
 *
 * <h2>What this class must never do</h2>
 *
 * <p>It names no Townstead type and no MCA type, and it is written to from inside somebody else's
 * class after the mixin is merged. It is therefore deliberately trivial: two string sets, no
 * listeners, no logging, nothing that can throw on a path that had no permission to fail.
 */
public final class TownsteadMixinStatus {

    /** The reaction-lock refusal hook, in {@code ReactionLockGateMixin}. */
    public static final String HOOK_REACTION_LOCK = "reaction_lock";

    /** The guard-rest yield hook, in {@code GuardRestYieldMixin}. */
    public static final String HOOK_GUARD_REST = "guard_rest";

    /**
     * The work-tool copy hook, in {@code WorkToolProvenanceMixin}.
     *
     * <p>One id for both redirects rather than two. They are made by the same instruction pair in the
     * same method: either Townstead still swaps work tools the way it did, in which case both match,
     * or it does not, in which case neither does. Reporting them separately would invite a reader to
     * treat "only the stash fired" as a state worth diagnosing, when what it really means is that the
     * villager was already holding the right tool.
     */
    public static final String HOOK_WORK_TOOL_COPY = "work_tool_copy";

    /** The shift-end hook that drops a villager's work-tool record, in {@code WorkToolProvenanceMixin}. */
    public static final String HOOK_WORK_TOOL_RESTORE = "work_tool_restore";

    /** The forget hook that drops a villager's work-tool record, in {@code WorkToolProvenanceMixin}. */
    public static final String HOOK_WORK_TOOL_FORGET = "work_tool_forget";

    /**
     * The dialogue screen's {@code init()} hook, in {@code client/RpgDialogueEntryMixin}.
     *
     * <p>The one that carries the capability: it fires the first time anybody opens a Townstead
     * conversation, and until then "applied but not observed" is the honest report.
     */
    public static final String HOOK_DIALOGUE_INIT = "dialogue_init";

    /**
     * The dialogue screen's {@code removed()} hook, in {@code client/RpgDialogueEntryMixin}.
     *
     * <p>Recorded separately from the init hook rather than folded into it, because the two really can
     * come apart: a screen can be opened without ever being closed during a session, and an operator
     * reading a report where only the init hook has fired is looking at a conversation still on screen
     * rather than at a moved injection point.
     */
    public static final String HOOK_DIALOGUE_REMOVED = "dialogue_removed";

    /** The mixin that refuses a reaction lock on a claimed villager. */
    public static final String MIXIN_REACTION_LOCK_GATE = "ReactionLockGateMixin";

    /** The mixin that keeps the guard-rest ticker from erasing MCA: Crime's walk order. */
    public static final String MIXIN_GUARD_REST_YIELD = "GuardRestYieldMixin";

    /** The mixin that tells a Townstead display tool apart from a villager's own equipment. */
    public static final String MIXIN_WORK_TOOL_PROVENANCE = "WorkToolProvenanceMixin";

    /**
     * The client mixin that adds MCA: Crime's law action to Townstead's dialogue screen.
     *
     * <p>Client-only, so it is never applied on a dedicated server and the capability behind it reports
     * unavailable there. That is correct rather than a gap: the entry point is a button, and a
     * dedicated server has no screens to put one on.
     */
    public static final String MIXIN_DIALOGUE_ENTRY = "RpgDialogueEntryMixin";

    private static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();
    private static final Set<String> INJECTED = ConcurrentHashMap.newKeySet();

    /**
     * The cheap first question for a per-entity hot path.
     *
     * <p>A plain {@code volatile boolean} rather than {@code !APPLIED.isEmpty()} because the tick
     * context consults it on every living entity tick on the server, and on the overwhelmingly common
     * install — no Townstead at all — the honest answer must cost one field read.
     */
    private static volatile boolean any;

    private TownsteadMixinStatus() {
    }

    /**
     * Records that Mixin merged one of our classes into a Townstead class.
     *
     * <p>Both the fully qualified name and its simple name are stored, because the plugin knows the
     * first and every reader asks with the second.
     */
    public static void applied(String mixinClassName) {
        if (mixinClassName == null || mixinClassName.isBlank()) {
            return;
        }
        APPLIED.add(mixinClassName);
        int dot = mixinClassName.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < mixinClassName.length()) {
            APPLIED.add(mixinClassName.substring(dot + 1));
        }
        any = true;
    }

    /** Records that one injected handler actually ran. Called from inside a transformed method. */
    public static void injected(String hookId) {
        if (hookId != null && !hookId.isBlank()) {
            INJECTED.add(hookId);
        }
    }

    /** Whether Mixin applied this mixin; accepts either the simple name or the qualified one. */
    public static boolean isApplied(String mixinClassName) {
        return mixinClassName != null && APPLIED.contains(mixinClassName);
    }

    /** Whether this hook's handler has run at least once since the game started. */
    public static boolean isInjected(String hookId) {
        return hookId != null && INJECTED.contains(hookId);
    }

    /** Whether any Townstead mixin applied at all. One field read; safe on a per-tick path. */
    public static boolean anyApplied() {
        return any;
    }

    /** Everything recorded so far, for {@code /crime debug townstead} and for tests. */
    public static Snapshot snapshot() {
        return new Snapshot(Set.copyOf(APPLIED), Set.copyOf(INJECTED));
    }

    /**
     * Drops every record.
     *
     * <p>Only tests call this. A running game never does: mixins are applied once per JVM, so
     * clearing the record at world unload would make a second world in one session look like a
     * Townstead that had gone missing.
     */
    public static void clear() {
        APPLIED.clear();
        INJECTED.clear();
        any = false;
    }

    /**
     * What was applied and what has fired, as of one moment.
     *
     * @param applied  mixin class names, each present in both qualified and simple form
     * @param injected hook ids whose handler has run
     */
    public record Snapshot(Set<String> applied, Set<String> injected) {
        public Snapshot {
            applied = Set.copyOf(applied);
            injected = Set.copyOf(injected);
        }

        /** A short operator line. */
        public String describe() {
            return "applied=" + applied.stream().filter(name -> name.indexOf('.') < 0).sorted().toList()
                    + " fired=" + injected.stream().sorted().toList();
        }
    }
}
