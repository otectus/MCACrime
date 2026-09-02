package dev.otectus.mcacrime.dialogue;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.resources.ResourceLocation;

/**
 * The dialogue events this mod fires. The matrix in spec §16.4 requires every shipped action to cover
 * opening, blocked, channel start, both interruptions, success, partial, resistance, witnessed,
 * repeat, and recovery; these ids are that matrix, and {@code DialogueCoverageTest} fails the build if
 * any of them has no shipped definition.
 *
 * <p>Ids are declared in code rather than data for the same reason action ids are: a pack may replace
 * what an event says, but it cannot invent an event, because nothing would ever fire it.
 */
public final class DialogueEvents {

    // --- mugging (§9) ---
    public static final ResourceLocation MUG_OPENING = McaCrime.id("mug_opening");
    public static final ResourceLocation MUG_BLOCKED = McaCrime.id("mug_blocked");
    public static final ResourceLocation MUG_CHANNEL_START = McaCrime.id("mug_channel_start");
    public static final ResourceLocation MUG_ACTOR_INTERRUPTED = McaCrime.id("mug_actor_interrupted");
    public static final ResourceLocation MUG_TARGET_INTERRUPTED = McaCrime.id("mug_target_interrupted");
    public static final ResourceLocation MUG_SUCCESS = McaCrime.id("mug_success");
    public static final ResourceLocation MUG_EMPTY = McaCrime.id("mug_empty");
    public static final ResourceLocation MUG_RESIST = McaCrime.id("mug_resist");
    public static final ResourceLocation MUG_WITNESSED = McaCrime.id("mug_witnessed");
    public static final ResourceLocation MUG_REPEAT = McaCrime.id("mug_repeat");
    public static final ResourceLocation MUG_RECOVERY = McaCrime.id("mug_recovery");

    // --- ransom (§15.6) ---
    public static final ResourceLocation RANSOM_DEMAND = McaCrime.id("ransom_demand");
    public static final ResourceLocation RANSOM_REFUSE = McaCrime.id("ransom_refuse");
    public static final ResourceLocation RANSOM_COUNTER = McaCrime.id("ransom_counter");
    public static final ResourceLocation RANSOM_PAID = McaCrime.id("ransom_paid");

    // --- captivity and rescue (§14) ---
    public static final ResourceLocation CAPTIVE_TAKEN = McaCrime.id("captive_taken");
    public static final ResourceLocation CAPTIVE_RELEASED = McaCrime.id("captive_released");
    public static final ResourceLocation CAPTIVE_RESCUED = McaCrime.id("captive_rescued");

    // --- law (§13) ---
    public static final ResourceLocation GUARD_CHALLENGE = McaCrime.id("guard_challenge");
    public static final ResourceLocation GUARD_STAND_DOWN = McaCrime.id("guard_stand_down");
    public static final ResourceLocation REPORT_FILED = McaCrime.id("report_filed");

    // --- restorative (§10.2) ---
    public static final ResourceLocation APOLOGY_ACCEPTED = McaCrime.id("apology_accepted");

    /** Every id above, in declaration order. Used by the coverage test and by {@code /crime validate}. */
    public static final java.util.List<ResourceLocation> ALL = java.util.List.of(
            MUG_OPENING, MUG_BLOCKED, MUG_CHANNEL_START, MUG_ACTOR_INTERRUPTED, MUG_TARGET_INTERRUPTED,
            MUG_SUCCESS, MUG_EMPTY, MUG_RESIST, MUG_WITNESSED, MUG_REPEAT, MUG_RECOVERY,
            RANSOM_DEMAND, RANSOM_REFUSE, RANSOM_COUNTER, RANSOM_PAID,
            CAPTIVE_TAKEN, CAPTIVE_RELEASED, CAPTIVE_RESCUED,
            GUARD_CHALLENGE, GUARD_STAND_DOWN, REPORT_FILED,
            APOLOGY_ACCEPTED);

    private DialogueEvents() {
    }
}
