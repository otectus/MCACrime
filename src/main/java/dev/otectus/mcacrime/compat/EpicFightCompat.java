package dev.otectus.mcacrime.compat;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What MCA: Crime knows about Epic Fight and the two MCA bridges that ship alongside it — a read-only
 * seam, built to the same rule as {@link ReputationBridge}: <b>no {@code epicfight}, {@code efmca} or
 * {@code mcaefcompat} type is named anywhere in this file.</b> Every foreign member is reached by
 * string at runtime, after {@link ModList} has confirmed the mod is there, so a missing mod can never
 * turn into a {@code NoClassDefFoundError} on a class-resolution path nobody can catch.
 *
 * <h2>Why this is detection and not a fix</h2>
 *
 * <p>"EpicFight-MCA Patch" ({@code efmca}) makes MCA villagers immune to <em>all</em> player damage. It
 * does it with a mixin on {@code VillagerEntityMCA.hurt} that cancels at {@code HEAD} whenever the
 * damage source's entity is a {@code Player}, unconditionally. Its {@code friendlyFire} option (the
 * static {@code net.forixaim.mcea.Config.FRIENDLY_FIRE}, default {@code false}) only governs a further
 * {@code LivingAttackEvent} cancel; turning it on does <em>not</em> lift the mixin. MCA: Crime cannot
 * safely undo another mod's mixin, so it does the one honest thing available: it says so, in the log
 * and in {@code /crime debug compat}, rather than leaving an operator to work out for themselves why
 * assault and killing never register.
 *
 * <h2>Epic Fight itself</h2>
 *
 * <p>Epic Fight is not broken and needs no apology. In battle mode, while the player holds a weapon
 * whose guard skill can execute, its {@code ControlEngine} cancels the client use-key event, so
 * {@code Minecraft.startUseItem} never sends the interact packets and no {@code EntityInteract} ever
 * reaches the server. {@code client/EpicFightInteractShim} forwards the armed case; the note printed
 * here tells the player what is and is not forwarded.
 */
public final class EpicFightCompat {

    /** Epic Fight itself: the combat overhaul whose battle mode swallows the use key. */
    public static final String EPIC_FIGHT_ID = "epicfight";

    /** "EpicFight-MCA Patch": the mod that makes MCA villagers immune to player damage. */
    public static final String EFMCA_ID = "efmca";

    /** "MCA Skin x Epic Fight Compatibility": client rendering only, and never a cause. */
    public static final String MCAEF_ID = "mcaefcompat";

    /** The class and field holding efmca's friendly-fire switch, reached by name and never imported. */
    private static final String EFMCA_CONFIG_CLASS = "net.forixaim.mcea.Config";
    private static final String EFMCA_FRIENDLY_FIRE_FIELD = "FRIENDLY_FIRE";

    private static volatile Boolean epicFightLoaded;
    private static volatile Boolean efmcaLoaded;
    private static volatile Boolean mcaefLoaded;

    private EpicFightCompat() {
    }

    /** Whether Epic Fight is installed. Cached: mod presence cannot change while the game is running. */
    public static boolean isEpicFightLoaded() {
        Boolean cached = epicFightLoaded;
        if (cached == null) {
            cached = loaded(EPIC_FIGHT_ID);
            epicFightLoaded = cached;
        }
        return cached;
    }

    /** Whether the EpicFight-MCA Patch is installed. */
    public static boolean isEfmcaLoaded() {
        Boolean cached = efmcaLoaded;
        if (cached == null) {
            cached = loaded(EFMCA_ID);
            efmcaLoaded = cached;
        }
        return cached;
    }

    /** Whether the MCA skin/Epic Fight rendering bridge is installed. Recorded for reports, not acted on. */
    public static boolean isMcaefLoaded() {
        Boolean cached = mcaefLoaded;
        if (cached == null) {
            cached = loaded(MCAEF_ID);
            mcaefLoaded = cached;
        }
        return cached;
    }

    /**
     * Whether player damage to MCA villagers is being blocked by another mod.
     *
     * <p>Presence of efmca is the whole test, because its block is unconditional. There is no config
     * on either side that makes an installed efmca let a player hit a villager.
     */
    public static boolean playerDamageToVillagersBlocked() {
        return isEfmcaLoaded();
    }

    /** The installed version of a mod, or empty when it is absent (or the container cannot be read). */
    public static Optional<String> modVersion(String modId) {
        try {
            return ModList.get().getModContainerById(modId)
                    .map(container -> container.getModInfo().getVersion().toString());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /**
     * efmca's {@code friendlyFire} setting, or empty when efmca is absent or the field cannot be read.
     *
     * <p>Reported only so a bug report shows what the operator has already tried. The value does not
     * change the verdict: {@code true} still leaves the {@code hurt} mixin in place.
     */
    public static Optional<Boolean> efmcaFriendlyFire() {
        if (!isEfmcaLoaded()) {
            return Optional.empty();
        }
        try {
            Object value = Class.forName(EFMCA_CONFIG_CLASS).getField(EFMCA_FRIENDLY_FIRE_FIELD).get(null);
            return Optional.of(((ForgeConfigSpec.BooleanValue) value).get());
        } catch (Throwable t) {
            // A renamed field, a repackaged class, a config not yet loaded: all the same answer here,
            // which is "unreadable". Nothing downstream may fail because a diagnostic could not be read.
            return Optional.empty();
        }
    }

    /**
     * The whole compatibility picture as plain lines, shared by {@code /crime debug compat} and the log.
     *
     * <p>Plain strings rather than components on purpose: the same text has to be pasteable into a bug
     * report and readable in {@code latest.log}, and no line here names a player or a world.
     */
    public static List<String> diagnosticLines() {
        List<String> lines = new ArrayList<>();
        lines.add("epicfight: installed=" + isEpicFightLoaded()
                + " version=" + modVersion(EPIC_FIGHT_ID).orElse("-"));
        lines.add("efmca (EpicFight-MCA Patch): installed=" + isEfmcaLoaded()
                + " version=" + modVersion(EFMCA_ID).orElse("-")
                + " friendlyFire=" + efmcaFriendlyFire().map(String::valueOf).orElse("unreadable"));
        lines.add("mcaefcompat (MCA Skin x Epic Fight): installed=" + isMcaefLoaded()
                + " version=" + modVersion(MCAEF_ID).orElse("-")
                + " (client rendering only)");

        if (playerDamageToVillagersBlocked()) {
            lines.add("Player damage to MCA villagers: BLOCKED by efmca (unconditional mixin on "
                    + "VillagerEntityMCA.hurt; its friendlyFire option does not lift it)");
        } else {
            lines.add("Player damage to MCA villagers: OK");
        }
        if (isEpicFightLoaded()) {
            lines.add("Battle-mode right-click on MCA villagers: forwarded by MCA: Crime's client shim "
                    + "when armed or holding a restraint; empty-hand conversation needs Epic Fight "
                    + "vanilla mode or key_conflict_resolve_scope = NONE");
        }
        return lines;
    }

    /**
     * One notice per affected mod, at common setup, so the log carries the explanation before the first
     * punch that does nothing.
     *
     * <p>efmca warns, because it silently removes a mechanic this mod is built on. Epic Fight informs,
     * because the shim handles the part that matters and the remainder is a documented key-binding
     * choice the player owns.
     */
    public static void logStartupNotices(Logger log) {
        if (isEfmcaLoaded()) {
            log.warn("MCA: Crime — EpicFight-MCA Patch (efmca {}) is installed. It cancels all player "
                            + "damage to MCA villagers with a mixin on VillagerEntityMCA.hurt, "
                            + "unconditionally; its friendlyFire option ({}) only affects a separate "
                            + "attack-event cancel and does not lift the mixin.\n"
                            + "  Assault and killing will therefore never be detected, and anything built "
                            + "on them — heat, witnesses, bounties, arrests — will not fire either. "
                            + "Everything that does not depend on hitting a villager still works.\n"
                            + "  MCA: Crime cannot safely undo another mod's mixin. Remove efmca to "
                            + "restore violent crime. Run /crime debug compat for the full picture.",
                    modVersion(EFMCA_ID).orElse("unknown version"),
                    efmcaFriendlyFire().map(String::valueOf).orElse("unreadable"));
        }
        if (isEpicFightLoaded()) {
            log.info("MCA: Crime — Epic Fight ({}) is installed. In battle mode it consumes the use key, "
                            + "so MCA: Crime forwards the interaction itself when the player is armed or "
                            + "holding a restraint. Empty-hand conversation with MCA villagers still needs "
                            + "Epic Fight's vanilla mode or key_conflict_resolve_scope = NONE. "
                            + "Run /crime debug compat for the full picture.",
                    modVersion(EPIC_FIGHT_ID).orElse("unknown version"));
        }
    }

    /**
     * {@link ModList} is authoritative once mod construction is done, which is the only time anything
     * here is called — but a unit test runs with no mod list at all, and a diagnostic must never be the
     * thing that throws. Absent is the safe answer in both cases.
     */
    private static boolean loaded(String modId) {
        try {
            return ModList.get().isLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Test hook: drops the presence cache. */
    public static void reset() {
        epicFightLoaded = null;
        efmcaLoaded = null;
        mcaefLoaded = null;
    }
}
