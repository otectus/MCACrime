package dev.otectus.mcacrime.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.otectus.mcacrime.config.CrimeWorldSettings;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** The {@code /crime rules} migration and diagnostics branch. */
public final class CrimeRulesCommand {
    private CrimeRulesCommand() {
    }

    /** Attach this builder directly below the existing {@code /crime} root. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("rules")
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("import")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> apply(ctx.getSource(), CrimeWorldSettings.fromConfig(), "imported")))
                .then(Commands.literal("defaults")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> apply(ctx.getSource(), CrimeWorldSettings.defaults(), "reset")));
    }

    private static int status(CommandSourceStack source) {
        CrimeWorldSettings effective = CrimeWorldSettings.resolve(source.getServer());
        source.sendSuccess(() -> Component.literal("MCA: Crime effective settings (source: "
                + effective.sourceName() + "). mcaCrimeUseWorldRules=" + effective.useWorldRules()
                + "; when false, stored world overrides are preserved."), false);
        for (CrimeWorldSettings.Setting setting : effective.settings()) {
            source.sendSuccess(() -> Component.literal(setting.name() + "=" + setting.value()
                    + " [" + effective.sourceName() + "]"), false);
        }
        return effective.settings().size();
    }

    private static int apply(CommandSourceStack source, CrimeWorldSettings values, String verb) {
        MinecraftServer server = source.getServer();
        CrimeWorldSettings.apply(server, values);
        CrimeWorldSettings effective = CrimeWorldSettings.resolve(server);
        source.sendSuccess(() -> Component.literal("MCA: Crime " + verb + " "
                + CrimeWorldSettings.OVERRIDABLE_VALUE_COUNT
                + " world-rule values and enabled world overrides. Effective source: "
                + effective.sourceName() + "."), true);
        return CrimeWorldSettings.OVERRIDABLE_VALUE_COUNT;
    }
}
