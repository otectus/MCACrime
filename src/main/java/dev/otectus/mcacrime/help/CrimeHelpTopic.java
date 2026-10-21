package dev.otectus.mcacrime.help;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The in-game help topics (0.8.0): their ids, aliases, titles and how they link to each other.
 *
 * <p>One registry for every surface that points at help -- the {@code /crime help} command, item and
 * device tooltips, the Mask Station's help button and the targeting error messages -- so a topic is named
 * once and a tooltip can never send a player to a page that does not exist. Common code: nothing here
 * names a client class, so a tooltip and a server command read the same table.
 *
 * <p>The page bodies live in {@link CrimeHelpPages}, which resolves the live server values a page quotes
 * (a ring's capacity, the blade delay) through the owning services.
 */
public enum CrimeHelpTopic {

    COMMANDS("commands", "command"),
    TARGETING("targeting", "target", "targets"),
    GUILLOTINE("guillotine", "pillory", "execution"),
    KEYS("keys", "key", "lock", "locks"),
    KEYRING("keyring", "key_ring", "ring"),
    KEYMOLD("keymold", "key_mold", "mold", "molds"),
    MASKS("masks", "mask", "dye", "dyes");

    private final String id;
    private final List<String> aliases;

    CrimeHelpTopic(String id, String... aliases) {
        this.id = id;
        this.aliases = List.of(aliases);
    }

    /** The topic's canonical name, as typed after {@code /crime help}. */
    public String id() {
        return id;
    }

    /** Other names that open the same topic ({@code keyring} also answers to {@code ring}). */
    public List<String> aliases() {
        return aliases;
    }

    public String titleKey() {
        return "mcacrime.help.topic." + id;
    }

    /** One line describing the topic in the overview. */
    public String summaryKey() {
        return "mcacrime.help.topic." + id + ".summary";
    }

    /** The command that opens this topic. */
    public String command() {
        return "/crime help " + id;
    }

    /** The topics a page points to at its foot. */
    public List<CrimeHelpTopic> related() {
        return switch (this) {
            case COMMANDS -> List.of(TARGETING, GUILLOTINE, KEYS, MASKS);
            case TARGETING -> List.of(COMMANDS);
            case GUILLOTINE -> List.of(COMMANDS, TARGETING);
            case KEYS -> List.of(KEYRING, KEYMOLD);
            case KEYRING -> List.of(KEYS, KEYMOLD);
            case KEYMOLD -> List.of(KEYS, KEYRING);
            case MASKS -> List.of(COMMANDS);
        };
    }

    /**
     * A clickable "/crime help keys" link. Help is read-only, so the link runs the command; links to
     * commands that change anything are built with {@link #suggest} instead and only fill the chat box.
     */
    public MutableComponent link() {
        return Component.literal(command()).withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("mcacrime.help.link.hover", Component.translatable(titleKey())))));
    }

    /** The tooltip line an item uses to point at this topic: "More: /crime help keys". */
    public MutableComponent tooltipHint() {
        return Component.translatable("mcacrime.help.tooltip_hint", command()).withStyle(ChatFormatting.DARK_GRAY);
    }

    /**
     * A command written into a help page: clicking it suggests the command for the player to review,
     * never runs it, because an example on a help page may change the world.
     */
    public static MutableComponent suggest(String command) {
        return Component.literal(command).withStyle(Style.EMPTY
                .withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("mcacrime.help.suggest.hover"))));
    }

    /** The topic with this id or alias, ignoring case. */
    public static Optional<CrimeHelpTopic> byName(@Nullable String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (CrimeHelpTopic topic : values()) {
            if (topic.id.equals(wanted) || topic.aliases.contains(wanted)) {
                return Optional.of(topic);
            }
        }
        return Optional.empty();
    }

    /** Every id and alias, for command suggestions. */
    public static List<String> allNames() {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (CrimeHelpTopic topic : values()) {
            names.add(topic.id);
            names.addAll(topic.aliases);
        }
        return List.copyOf(names);
    }
}
