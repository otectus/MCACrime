package dev.otectus.mcacrime.compat;

import net.neoforged.fml.ModList;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every optional mod MCA: Crime says anything about, and what it actually does about each
 * (0.7.5 M6.2, specification §15.1).
 *
 * <p>One table, by mod id, and no foreign type named anywhere in it. It exists because the
 * alternative — a claim of support in a README and an empty adapter class in the source — is what
 * upstream ships: its TacZ adapter is an empty class whose mere presence sets an unrelated
 * voice-chat flag ({@code CuffedMod.java:176-179}). Every row here is one of three honest states:
 *
 * <ul>
 *   <li><b>Adapted.</b> A version-probed adapter in an isolated package does real work.</li>
 *   <li><b>Covered by enforcement.</b> Nothing mod-specific is needed: MCA: Crime denies the action
 *       server-side through the events the mod already raises, so the restriction holds whether the
 *       mod is installed or not. This is the honest description of most "combat mod compatibility",
 *       and saying so beats shipping an adapter that does nothing and calling it support.</li>
 *   <li><b>No claim.</b> There is no stable server hook we could bind, so nothing is claimed and the
 *       limitation is reported plainly.</li>
 * </ul>
 *
 * <p>Nothing here changes a user setting, ever. A session-scoped restriction is a restriction on an
 * action, not an edit to somebody's configuration file — which is the specification's constraint and
 * also the reason this table has no "disable their feature" state at all.
 */
public final class OptionalMods {

    /** What MCA: Crime does about one optional mod. */
    public enum Support {
        /** A real, version-probed adapter. */
        ADAPTED,
        /** No adapter needed: our own server-side action enforcement already covers it. */
        ENFORCED,
        /** No stable hook; nothing is claimed. */
        NO_CLAIM
    }

    /** One row of the table. */
    public record Entry(String modId, String displayName, Support support, String note) {
    }

    private static final Map<String, Entry> TABLE = new LinkedHashMap<>();

    static {
        // Combat and movement mods. Every one of them attacks, uses items, sprints or jumps through
        // the vanilla events RestraintHandlers already denies on, so the restriction is enforced
        // server-side whether they are installed or not. No adapter, and no claim beyond that.
        enforced("bettercombat", "Better Combat",
                "attacks are denied server-side while the arms are restrained; no client setting is changed");
        // Epic Fight is the one combat mod with an adapter of its own, and it predates this release:
        // compat/EpicFightCompat detects it (and efmca, and mcaefcompat) by id and reports what those
        // mods take away, and client/EpicFightInteractShim forwards the right-click battle mode
        // cancelled so this mod's menu still opens. No battle mode is ever toggled for the player.
        adapted("epicfight", "Epic Fight",
                "detected by id in compat/EpicFightCompat; the client shim forwards a cancelled "
                        + "interaction so restraint and menu right-clicks still reach the server");
        enforced("parcool", "ParCool",
                "parkour actions that need the limbs are denied by the movement and jump restrictions; "
                        + "its own configuration is never rewritten");
        enforced("elenaidodge2", "Elenai Dodge 2",
                "dodges are denied while movement is restricted; no cooldown is overwritten");
        enforced("combatroll", "Combat Roll",
                "rolls are denied by the same movement restriction; key suppression alone is not claimed "
                        + "as support");

        // Spell mods: a real adapter, because Silence has to drain a pool only they own.
        adapted("irons_spellbooks", "Iron's Spells 'n Spellbooks",
                "Silence drains the magic pool through compat/mana, version-probed");
        adapted("ars_nouveau", "Ars Nouveau",
                "Silence drains the mana capability through compat/mana, version-probed");
        adapted("mana-and-artifice", "Mana and Artifice",
                "Silence drains the casting resource through compat/mana, version-probed");

        // Inventory providers: a real adapter, because a search has to be able to see those slots.
        adapted("curios", "Curios API",
                "curio slots become searchable through compat/inventory, version-probed");
        adapted("cosmeticarmorreworked", "Cosmetic Armor Reworked",
                "cosmetic slots become searchable through compat/inventory, version-probed");

        // Downed-state mod: a real adapter, because "downed" is a state only it knows about.
        adapted("playerrevive", "PlayerRevive",
                "a downed player counts as vulnerable for application and is never executed while downed");

        // No stable server hook. Named anyway, so "unsupported" is visible rather than inferred.
        noClaim("voicechat", "Simple Voice Chat",
                "its plugin API requires a compile-time registered plugin class; head tape mutes no "
                        + "microphone and MCA: Crime claims no voice suppression");
        noClaim("tacz", "Timeless and Classics Zero",
                "no version-probed hook; upstream's adapter is empty and wrongly sets an unrelated "
                        + "voice-chat flag, which is not carried over");
        noClaim("knightsofbritannia", "Knights of Britannia",
                "no explicitly supported build; no scoreboard objective is created speculatively");
    }

    private OptionalMods() {
    }

    private static void adapted(String id, String name, String note) {
        TABLE.put(id, new Entry(id, name, Support.ADAPTED, note));
    }

    private static void enforced(String id, String name, String note) {
        TABLE.put(id, new Entry(id, name, Support.ENFORCED, note));
    }

    private static void noClaim(String id, String name, String note) {
        TABLE.put(id, new Entry(id, name, Support.NO_CLAIM, note));
    }

    /** Every row, in declaration order. */
    public static List<Entry> all() {
        return List.copyOf(TABLE.values());
    }

    /** One row by mod id, or null when this mod says nothing about it. */
    @Nullable
    public static Entry entry(@Nullable String modId) {
        return modId == null ? null : TABLE.get(modId);
    }

    /** Whether a mod is installed. The only question asked before any class is resolved. */
    public static boolean installed(@Nullable String modId) {
        ModList list = ModList.get();
        return modId != null && list != null && list.isLoaded(modId);
    }

    /** The installed version of one mod, or {@code unknown}. */
    public static String version(@Nullable String modId) {
        try {
            ModList list = ModList.get();
            return list == null || modId == null ? "unknown" : list.getModContainerById(modId)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * One line per installed optional mod, for the startup log and {@code /crime debug}.
     *
     * <p>Only installed mods, because a list of every mod somebody could have installed is noise, and
     * the question an operator actually has is "what is MCA: Crime doing about the mods I run".
     */
    public static List<String> report() {
        List<String> lines = new ArrayList<>();
        for (Entry entry : TABLE.values()) {
            if (!installed(entry.modId())) {
                continue;
            }
            lines.add("MCA: Crime — " + entry.displayName() + " " + version(entry.modId()) + ": "
                    + switch (entry.support()) {
                        case ADAPTED -> "adapted";
                        case ENFORCED -> "covered by server-side enforcement";
                        case NO_CLAIM -> "no support claimed";
                    }
                    + " (" + entry.note() + ")");
        }
        return lines;
    }
}
