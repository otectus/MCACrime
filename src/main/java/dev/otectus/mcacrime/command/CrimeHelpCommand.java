package dev.otectus.mcacrime.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.otectus.mcacrime.help.CrimeHelpPages;
import dev.otectus.mcacrime.help.CrimeHelpTopic;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * {@code /crime help [topic] [page]} (0.8.0): read-only, open to every player and to the console, and
 * deliberately outside the gameplay-command fallback switch -- a server that turns the command shortcuts
 * off still wants its players able to read how things work.
 */
public final class CrimeHelpCommand {

    private CrimeHelpCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("help")
                .executes(ctx -> overview(ctx.getSource()))
                .then(Commands.argument("topic", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CrimeHelpTopic.allNames(), builder))
                        .executes(ctx -> topic(ctx.getSource(), StringArgumentType.getString(ctx, "topic"), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> topic(ctx.getSource(), StringArgumentType.getString(ctx, "topic"),
                                        IntegerArgumentType.getInteger(ctx, "page")))));
    }

    /** The overview, also what a bare {@code /crime} shows. */
    public static int overview(CommandSourceStack source) {
        send(source, CrimeHelpPages.overview());
        return CrimeHelpTopic.values().length;
    }

    static int topic(CommandSourceStack source, String name, int page) {
        CrimeHelpTopic topic = CrimeHelpTopic.byName(name).orElse(null);
        if (topic == null) {
            MutableComponent known = Component.empty();
            for (CrimeHelpTopic each : CrimeHelpTopic.values()) {
                known.append(Component.literal(" ")).append(each.link());
            }
            source.sendFailure(Component.translatable("mcacrime.help.unknown", name).append(known));
            return 0;
        }
        List<Component> lines = CrimeHelpPages.page(topic, page, source);
        send(source, lines);
        return lines.size();
    }

    private static void send(CommandSourceStack source, List<Component> lines) {
        for (Component line : lines) {
            source.sendSuccess(() -> line, false);
        }
    }
}
