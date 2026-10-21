package dev.otectus.mcacrime.help;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.CrimeTargetResolver;
import dev.otectus.mcacrime.detention.ExecutionAuthorization;
import dev.otectus.mcacrime.enforcement.ExecutionSiteRegistry;
import dev.otectus.mcacrime.item.lock.BakedKeyMoldItem;
import dev.otectus.mcacrime.locks.LockInteractions;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The bodies of the {@code /crime help} topics (0.8.0).
 *
 * <p>Every line is a translation key; the values a line quotes -- a ring's capacity, the blade delay,
 * whether executions are on -- are read from the server's own settings and services when the page is
 * built, so a page never repeats a client default as if it were the server's rule. Operator lines carry
 * the permission level they need and are dropped for a source without it; links to commands that
 * change anything only fill the chat box ({@link CrimeHelpTopic#suggest}).
 *
 * <p>Pages are short on purpose: {@link #PAGE_LINES} lines, then a link to the next page.
 */
public final class CrimeHelpPages {

    /** Lines per page, not counting the header and the footer. */
    public static final int PAGE_LINES = 8;

    /** One body line and the permission level a source needs to see it. */
    public record Line(Component text, int permission) {

        static Line everyone(Component text) {
            return new Line(text, 0);
        }

        static Line operator(int level, Component text) {
            return new Line(Component.translatable("mcacrime.help.operator_prefix").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(" ")).append(text), level);
        }
    }

    private CrimeHelpPages() {
    }

    // --- assembly ------------------------------------------------------------------------------------

    /** Every line of a topic this source may see, before pagination. */
    public static List<Component> visibleLines(CrimeHelpTopic topic, CommandSourceStack source) {
        List<Component> visible = new ArrayList<>();
        for (Line line : lines(topic)) {
            if (line.permission() <= 0 || source.hasPermission(line.permission())) {
                visible.add(line.text());
            }
        }
        return visible;
    }

    public static int pageCount(CrimeHelpTopic topic, CommandSourceStack source) {
        return Math.max(1, (visibleLines(topic, source).size() + PAGE_LINES - 1) / PAGE_LINES);
    }

    /** One page: a header, up to {@link #PAGE_LINES} lines, and a footer with related topics. */
    public static List<Component> page(CrimeHelpTopic topic, int requestedPage, CommandSourceStack source) {
        List<Component> lines = visibleLines(topic, source);
        int pages = Math.max(1, (lines.size() + PAGE_LINES - 1) / PAGE_LINES);
        int page = Math.min(Math.max(1, requestedPage), pages);
        List<Component> out = new ArrayList<>();
        out.add(Component.translatable("mcacrime.help.header", Component.translatable(topic.titleKey()),
                page, pages).withStyle(ChatFormatting.GOLD));
        int from = (page - 1) * PAGE_LINES;
        for (int i = from; i < Math.min(lines.size(), from + PAGE_LINES); i++) {
            out.add(lines.get(i));
        }
        MutableComponent footer = Component.translatable("mcacrime.help.related").withStyle(ChatFormatting.DARK_GRAY);
        for (CrimeHelpTopic related : topic.related()) {
            footer.append(Component.literal(" ")).append(related.link());
        }
        if (page < pages) {
            String next = topic.command() + " " + (page + 1);
            footer.append(Component.literal("  ")).append(Component.translatable("mcacrime.help.next")
                    .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                            .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                    net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, next))
                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                    net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, Component.literal(next)))));
        }
        out.add(footer);
        return out;
    }

    /** The overview: every topic with its one-line summary. */
    public static List<Component> overview() {
        List<Component> out = new ArrayList<>();
        out.add(Component.translatable("mcacrime.help.overview").withStyle(ChatFormatting.GOLD));
        for (CrimeHelpTopic topic : CrimeHelpTopic.values()) {
            out.add(topic.link().append(Component.literal(" ").withStyle(ChatFormatting.RESET))
                    .append(Component.translatable(topic.summaryKey()).withStyle(ChatFormatting.GRAY)));
        }
        return out;
    }

    // --- the topics ----------------------------------------------------------------------------------

    /** Every line of a topic, permission-tagged. Live values are read now, from the server's settings. */
    public static List<Line> lines(CrimeHelpTopic topic) {
        return switch (topic) {
            case COMMANDS -> commands();
            case TARGETING -> targeting();
            case GUILLOTINE -> guillotine();
            case KEYS -> keys();
            case KEYRING -> keyRing();
            case KEYMOLD -> keyMold();
            case MASKS -> masks();
        };
    }

    private static Component cmd(String command) {
        return CrimeHelpTopic.suggest(command);
    }

    private static Component t(String key, Object... args) {
        return Component.translatable(key, args);
    }

    private static Component onOff(Supplier<Boolean> value, boolean fallback) {
        return Component.translatable(read(value, fallback) ? "mcacrime.help.state.on" : "mcacrime.help.state.off");
    }

    private static boolean read(Supplier<Boolean> value, boolean fallback) {
        try {
            Boolean result = value.get();
            return result == null ? fallback : result;
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return fallback;
        }
    }

    private static List<Line> commands() {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.everyone(t("mcacrime.help.commands.1", cmd("/crime status"), cmd("/crime karma"),
                cmd("/crime payfine"), cmd("/crime surrender"))));
        lines.add(Line.everyone(t("mcacrime.help.commands.2", cmd("/crime escape"), cmd("/crime payransom"))));
        lines.add(Line.everyone(t("mcacrime.help.commands.3", CrimeTargetResolver.Policy.GAMEPLAY.rangeText(),
                cmd("/crime mug"), cmd("/crime report"))));
        lines.add(Line.everyone(t("mcacrime.help.commands.4", cmd("/crime reports"), cmd("/crime news on"))));
        if (!read(() -> McaCrimeConfig.COMMON.allowGameplayCommandFallback.get(), true)) {
            lines.add(Line.everyone(t("mcacrime.help.commands.fallback_off")));
        }
        lines.add(Line.operator(2, t("mcacrime.help.commands.op.1", cmd("/crime debug target"),
                cmd("/crime debug mca"), cmd("/crime debug villager"))));
        lines.add(Line.operator(3, t("mcacrime.help.commands.op.2", cmd("/crime job assign thief"),
                cmd("/crime job assign fence"), cmd("/crime job clear"))));
        MutableComponent topics = Component.translatable("mcacrime.help.commands.topics");
        for (CrimeHelpTopic other : CrimeHelpTopic.values()) {
            if (other != CrimeHelpTopic.COMMANDS) {
                topics.append(Component.literal(" ")).append(other.link());
            }
        }
        lines.add(Line.everyone(topics));
        return lines;
    }

    private static List<Line> targeting() {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.everyone(t("mcacrime.help.targeting.1", cmd("/crime mug"), cmd("/crime report"),
                CrimeTargetResolver.Policy.GAMEPLAY.rangeText())));
        lines.add(Line.everyone(t("mcacrime.help.targeting.2", CrimeTargetResolver.Policy.OPERATOR.rangeText())));
        lines.add(Line.everyone(t("mcacrime.help.targeting.3", cmd("/crime job assign thief "))));
        lines.add(Line.everyone(t("mcacrime.help.targeting.4")));
        lines.add(Line.everyone(t("mcacrime.help.targeting.5")));
        lines.add(Line.everyone(t("mcacrime.help.targeting.6", cmd("/crime debug mca"))));
        lines.add(Line.everyone(t("mcacrime.help.targeting.7")));
        lines.add(Line.operator(2, t("mcacrime.help.targeting.op.1", cmd("/crime debug target"),
                CrimeTargetResolver.Policy.DIAGNOSTIC.rangeText())));
        return lines;
    }

    private static List<Line> guillotine() {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.everyone(t("mcacrime.help.guillotine.1")));
        lines.add(Line.everyone(t("mcacrime.help.guillotine.2")));
        lines.add(Line.everyone(t("mcacrime.help.guillotine.3")));
        lines.add(Line.everyone(t("mcacrime.help.guillotine.4")));
        lines.add(Line.everyone(t("mcacrime.help.guillotine.5", ExecutionAuthorization.bladeTicks())));
        lines.add(Line.everyone(t("mcacrime.help.guillotine.6")));
        boolean guardKilling = read(() -> McaCrimeConfig.COMMON.guardKillingIsCapital.get(), true);
        lines.add(Line.everyone(t(guardKilling ? "mcacrime.help.guillotine.7" : "mcacrime.help.guillotine.7.off")));
        boolean npc = read(() -> McaCrimeConfig.COMMON.npcOffendersEligible.get(), false);
        lines.add(Line.everyone(t("mcacrime.help.guillotine.8",
                onOff(() -> McaCrimeConfig.COMMON.capitalPunishmentEnabled.get(), true),
                onOff(() -> McaCrimeConfig.COMMON.guillotineEnabled.get(), true),
                t(npc ? "mcacrime.help.npc.can" : "mcacrime.help.npc.cannot"))));
        lines.add(Line.everyone(t("mcacrime.help.guillotine.9", ExecutionAuthorization.windowTicks())));
        lines.add(Line.everyone(t("mcacrime.help.guillotine.10")));
        lines.add(Line.operator(2, t("mcacrime.help.guillotine.op.1", cmd("/crime capital inspect "))));
        lines.add(Line.operator(3, t("mcacrime.help.guillotine.op.2", cmd("/crime capital execute "),
                cmd("/crime facility assign execution_site"), ExecutionSiteRegistry.SITE_DEVICE_RADIUS,
                onOff(() -> McaCrimeConfig.COMMON.guardMayExecute.get(), true))));
        lines.add(Line.operator(3, t("mcacrime.help.guillotine.op.3", cmd("/crime capital commute "),
                cmd("/crime capital pardon "))));
        return lines;
    }

    private static List<Line> keys() {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.everyone(t("mcacrime.help.keys.1")));
        lines.add(Line.everyone(t("mcacrime.help.keys.2")));
        lines.add(Line.everyone(t("mcacrime.help.keys.3", CrimeHelpTopic.KEYMOLD.link(), CrimeHelpTopic.KEYRING.link())));
        lines.add(Line.everyone(t("mcacrime.help.keys.4")));
        lines.add(Line.everyone(t("mcacrime.help.keys.5")));
        lines.add(Line.everyone(t("mcacrime.help.keys.6")));
        lines.add(Line.everyone(t("mcacrime.help.keys.7")));
        if (net.neoforged.fml.ModList.get() != null && net.neoforged.fml.ModList.get().isLoaded("locks")) {
            lines.add(Line.everyone(t("mcacrime.help.keys.locks_reforged")));
        }
        return lines;
    }

    private static List<Line> keyRing() {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.everyone(t("mcacrime.help.keyring.1")));
        lines.add(Line.everyone(t("mcacrime.help.keyring.2")));
        lines.add(Line.everyone(t("mcacrime.help.keyring.3")));
        lines.add(Line.everyone(t("mcacrime.help.keyring.4", LockInteractions.maxKeysPerRing())));
        lines.add(Line.everyone(t("mcacrime.help.keyring.5")));
        lines.add(Line.everyone(t("mcacrime.help.keyring.6")));
        return lines;
    }

    private static List<Line> keyMold() {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.everyone(t("mcacrime.help.keymold.1")));
        lines.add(Line.everyone(t("mcacrime.help.keymold.2")));
        lines.add(Line.everyone(t("mcacrime.help.keymold.3")));
        lines.add(Line.everyone(t("mcacrime.help.keymold.4", BakedKeyMoldItem.STARTING_QUALITY)));
        lines.add(Line.everyone(t("mcacrime.help.keymold.5")));
        lines.add(Line.everyone(t("mcacrime.help.keymold.6")));
        return lines;
    }

    private static List<Line> masks() {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.everyone(t("mcacrime.help.masks.1")));
        lines.add(Line.everyone(t("mcacrime.help.masks.2")));
        lines.add(Line.everyone(t("mcacrime.help.masks.3")));
        lines.add(Line.everyone(t("mcacrime.help.masks.4")));
        lines.add(Line.everyone(t("mcacrime.help.masks.5")));
        lines.add(Line.everyone(t("mcacrime.help.masks.6")));
        lines.add(Line.everyone(t("mcacrime.help.masks.7",
                onOff(() -> McaCrimeConfig.COMMON.enableMaskStationCrafting.get(), true),
                onOff(() -> McaCrimeConfig.COMMON.enableMaskRestyling.get(), true))));
        return lines;
    }
}
