package dev.otectus.mcacrime.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.compat.CapitalsDiagnostics;
import dev.otectus.mcacrime.decree.Decree;
import dev.otectus.mcacrime.decree.DecreeProvenance;
import dev.otectus.mcacrime.decree.DecreeReason;
import dev.otectus.mcacrime.decree.DecreeService;
import dev.otectus.mcacrime.decree.DecreeText;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * {@code /crime decree} (0.7.8): the command door into royal decrees, with exactly the checks the decree
 * screen applies, because both go through {@link DecreeService}.
 *
 * <p>No operator level is needed to issue, withdraw or pay: authority is the throne of the village the
 * player stands in, decided by the service, never by a permission node. Only {@code void}, which clears
 * a decree without asking Capitals anything, is an operator's tool.
 */
public final class DecreeCommand {

    private DecreeCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("decree")
                .then(Commands.literal("issue")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> issue(ctx, DecreeReason.OTHER.id()))
                                .then(Commands.argument("reason", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                Arrays.stream(DecreeReason.values()).map(DecreeReason::id), builder))
                                        .executes(ctx -> issue(ctx, StringArgumentType.getString(ctx, "reason"))))))
                .then(Commands.literal("withdraw")
                        .then(Commands.argument("target", GameProfileArgument.gameProfile())
                                .executes(DecreeCommand::withdraw)))
                .then(Commands.literal("pay").executes(DecreeCommand::pay))
                .then(Commands.literal("list").executes(DecreeCommand::list))
                .then(Commands.literal("void")
                        .requires(source -> source.hasPermission(3))
                        .then(Commands.argument("target", GameProfileArgument.gameProfile())
                                .executes(DecreeCommand::voidDecrees)));
    }

    private static int issue(CommandContext<CommandSourceStack> ctx, String rawReason) throws CommandSyntaxException {
        ServerPlayer issuer = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        DecreeReason reason = DecreeReason.parse(rawReason).orElse(null);
        if (reason == null) {
            ctx.getSource().sendFailure(Component.translatable("mcacrime.command.decree.reasons",
                    String.join(", ", Arrays.stream(DecreeReason.values()).map(DecreeReason::id).toList())));
            return 0;
        }
        DecreeService.Outcome outcome = DecreeService.issue(issuer, target, reason, DecreeProvenance.COMMAND);
        if (!outcome.ok()) {
            ctx.getSource().sendFailure(Component.translatable(outcome.refusal().messageKey()));
            return 0;
        }
        return 1;
    }

    private static int withdraw(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer actor = ctx.getSource().getPlayerOrException();
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "target");
        if (profiles.size() != 1) {
            ctx.getSource().sendFailure(Component.translatable("mcacrime.decree.refused.target_unknown"));
            return 0;
        }
        DecreeService.Outcome outcome = DecreeService.withdraw(actor, profiles.iterator().next().getId(),
                DecreeProvenance.COMMAND);
        if (!outcome.ok()) {
            ctx.getSource().sendFailure(Component.translatable(outcome.refusal().messageKey()));
            return 0;
        }
        return 1;
    }

    private static int pay(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return DecreeService.payHere(ctx.getSource().getPlayerOrException()).paid() ? 1 : 0;
    }

    /** The decrees against the caller, and, for a ruler, the open decrees of the village they stand in. */
    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        long now = server.overworld().getGameTime();
        List<Decree> against = DecreeService.inForceAnywhere(server, player.getUUID());
        if (against.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.decree.none_against"), false);
        }
        for (Decree decree : against) {
            ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.decree.against_row",
                    DecreeText.village(server, decree.community()), DecreeText.name(decree.issuedByName()),
                    DecreeText.reason(decree.reason()), DecreeText.time(decree.remaining(now))), false);
        }
        CrimeCommunityKey here = DecreeService.communityOf(player).orElse(null);
        if (here != null && DecreeService.rules(player, here)) {
            int count = 0;
            for (Decree decree : CrimeWorldData.get(server).decrees()) {
                if (decree.open() && decree.community().equals(here)) {
                    count++;
                    ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.decree.issued_row",
                            DecreeText.name(decree.offenderName()), DecreeText.reason(decree.reason()),
                            DecreeText.time(decree.remaining(now))), false);
                }
            }
            int shown = count;
            ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.decree.issued_count", shown,
                    DecreeText.village(server, here)), false);
        }
        return against.size();
    }

    private static int voidDecrees(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "target");
        MinecraftServer server = ctx.getSource().getServer();
        int total = 0;
        for (GameProfile profile : profiles) {
            total += DecreeService.voidDecrees(server, profile.getId(), null,
                    ctx.getSource().getEntity() == null ? null : ctx.getSource().getEntity().getUUID());
        }
        int voided = total;
        String names = String.join(", ", profiles.stream().map(GameProfile::getName).toList());
        ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.decree.voided", voided, names), true);
        return voided;
    }

    /** {@code /crime debug capitals}: the seam's state, and the capital at the caller's feet. */
    public static int debugCapitals(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<String> lines = new java.util.ArrayList<>(CapitalsDiagnostics.summary());
        lines.addAll(CapitalsDiagnostics.here(source.getLevel(), net.minecraft.core.BlockPos.containing(source.getPosition())));
        CrimeWorldData data = CrimeWorldData.get(source.getServer());
        long open = data.decrees().stream().filter(Decree::open).count();
        lines.add("  decrees: " + open + " open, " + (data.decrees().size() - open) + " ended and retained");
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return lines.size();
    }

}
