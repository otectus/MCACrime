package dev.otectus.mcacrime.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.config.ConfigValidator;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.crime.KarmaSource;
import dev.otectus.mcacrime.crime.type.CrimeTypeRegistry;
import dev.otectus.mcacrime.economy.FineService;
import dev.otectus.mcacrime.economy.SurrenderService;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.enforcement.LegalTarget;
import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.jail.JailRegistry;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.jail.ReleaseReason;
import dev.otectus.mcacrime.ledger.CrimeLedger;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.List;

/**
 * Brigadier commands under {@code /crime} (spec §17), permission-tiered like the Quests command. The
 * 0.1.0 subset: player read commands ({@code karma}/{@code status}), op read/debug
 * ({@code query}/{@code debug villager}), and op mutators ({@code set karma|heat}/{@code clearheat}) plus
 * {@code validate}. Every mutator routes through {@link CrimeState} (the server-authoritative chokepoint);
 * commands never write capability NBT directly.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeCommand {

    private CrimeCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("crime")
                .then(Commands.literal("karma")
                        .executes(CrimeCommand::karma))
                .then(Commands.literal("status")
                        .executes(CrimeCommand::status))
                .then(Commands.literal("payfine")
                        .executes(CrimeCommand::payFine))
                .then(Commands.literal("surrender")
                        .executes(CrimeCommand::surrender))
                .then(Commands.literal("query")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(CrimeCommand::query)))
                .then(Commands.literal("validate")
                        .requires(src -> src.hasPermission(3))
                        .executes(CrimeCommand::validate))
                .then(Commands.literal("ledger")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(CrimeCommand::ledger)))
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(3))
                        .executes(CrimeCommand::reload))
                .then(Commands.literal("clearheat")
                        .requires(src -> src.hasPermission(3))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(CrimeCommand::clearHeat)))
                .then(Commands.literal("set")
                        .requires(src -> src.hasPermission(3))
                        .then(Commands.literal("karma")
                                .then(Commands.argument("target", EntityArgument.player())
                                        .then(Commands.argument("value", IntegerArgumentType.integer())
                                                .executes(CrimeCommand::setKarma))))
                        .then(Commands.literal("heat")
                                .then(Commands.argument("target", EntityArgument.player())
                                        .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                                .executes(CrimeCommand::setHeat)))))
                .then(Commands.literal("jail")
                        .requires(src -> src.hasPermission(3))
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(1))
                                        .executes(CrimeCommand::jail))))
                .then(Commands.literal("release")
                        .requires(src -> src.hasPermission(3))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(CrimeCommand::release)))
                .then(Commands.literal("assignjail")
                        .requires(src -> src.hasPermission(3))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> assignJail(ctx, McaCrimeConfig.COMMON.jailRadiusDefault.get()))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> assignJail(ctx, IntegerArgumentType.getInteger(ctx, "radius"))))))
                .then(Commands.literal("debug")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("villager")
                                .executes(CrimeCommand::debugVillager))));
    }

    // --- player reads ---

    private static int karma(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        long karma = CrimeState.getKarma(player);
        Band band = CrimeState.getBand(player);
        ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.karma", karma, bandComponent(band)), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        sendStatus(ctx.getSource(), player);
        return 1;
    }

    private static int query(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.query.header", target.getName()), false);
        sendStatus(ctx.getSource(), target);
        return 1;
    }

    private static void sendStatus(CommandSourceStack src, ServerPlayer player) {
        long karma = CrimeState.getKarma(player);
        Band band = CrimeState.getBand(player);
        long heat = CrimeState.getHeat(player);
        boolean wanted = CrimeState.isWanted(player);
        src.sendSuccess(() -> Component.translatable("mcacrime.command.status.karma", karma, bandComponent(band)), false);
        Component wantedSuffix = wanted
                ? Component.translatable("mcacrime.command.status.wanted").withStyle(ChatFormatting.RED)
                : Component.empty();
        src.sendSuccess(() -> Component.translatable("mcacrime.command.status.heat", heat, wantedSuffix), false);
        long jailTicks = JailService.remainingTicks(player);
        Component jailInfo;
        if (jailTicks > 0) {
            String s = (jailTicks / 20L) + "s" + (LegalTarget.isEscapedPrisoner(player) ? " (escaped)" : "");
            jailInfo = Component.literal(s);
        } else {
            jailInfo = Component.translatable("mcacrime.command.status.jail.none");
        }
        src.sendSuccess(() -> Component.translatable("mcacrime.command.status.jail", jailInfo), false);
    }

    // --- op mutators (all via CrimeState) ---

    private static int setKarma(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        int value = IntegerArgumentType.getInteger(ctx, "value");
        CrimeState.setKarma(target, value, KarmaSource.ADMIN);
        Band band = CrimeState.getBand(target);
        long karma = CrimeState.getKarma(target);
        ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.set.karma",
                target.getName(), karma, bandComponent(band)), true);
        return 1;
    }

    private static int setHeat(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        int value = IntegerArgumentType.getInteger(ctx, "value");
        CrimeState.setHeat(target, value);
        long heat = CrimeState.getHeat(target);
        ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.set.heat",
                target.getName(), heat), true);
        return 1;
    }

    private static int clearHeat(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        CrimeState.clearHeat(target);
        ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.clearheat", target.getName()), true);
        return 1;
    }

    private static int validate(CommandContext<CommandSourceStack> ctx) {
        List<String> problems = ConfigValidator.validateCurrentConfig();
        if (problems.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.validate.ok"), false);
            return 1;
        }
        ctx.getSource().sendFailure(Component.translatable("mcacrime.command.validate.header", problems.size()));
        problems.forEach(p -> ctx.getSource().sendFailure(Component.literal(" - " + p)));
        return 0;
    }

    private static int ledger(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        MinecraftServer server = ctx.getSource().getServer();
        List<CrimeRecord> records = CrimeLedger.forOffender(server, target.getUUID());
        if (records.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.ledger.none", target.getName()), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() ->
                Component.translatable("mcacrime.command.ledger.header", target.getName(), records.size()), false);
        records.forEach(r -> ctx.getSource().sendSuccess(() -> Component.literal(" - " + formatRecord(r)), false));
        return records.size();
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        src.sendSuccess(() -> Component.translatable("mcacrime.command.reload.start"), true);
        server.reloadResources(server.getPackRepository().getSelectedIds())
                .thenRunAsync(() -> src.sendSuccess(() -> Component.translatable("mcacrime.command.reload.done",
                        CrimeTypeRegistry.size(), CrimeTypeRegistry.lastErrors().size()), true), server);
        return 1;
    }

    private static String formatRecord(CrimeRecord r) {
        String village = r.villageId().isPresent() ? Integer.toString(r.villageId().getAsInt()) : "-";
        return r.type().getPath()
                + (r.witnessed() ? " [witnessed]" : " [unwitnessed]")
                + " karma=" + r.karmaDelta() + " heat=" + r.heatGenerated()
                + " village=" + village
                + " " + r.resolution().name().toLowerCase(java.util.Locale.ROOT)
                + " @" + r.timeCommitted();
    }

    // --- player release valves ---

    private static int payFine(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return FineService.payFine(ctx.getSource().getPlayerOrException());
    }

    private static int surrender(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return SurrenderService.surrender(ctx.getSource().getPlayerOrException());
    }

    // --- op jail control (all via JailService — server-authoritative, idempotent) ---

    private static int jail(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
        if (JailService.jail(target, ticks, null)) {
            ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.jail.ok", target.getName(), ticks), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.translatable("mcacrime.command.jail.refused", target.getName()));
        return 0;
    }

    private static int release(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        if (!JailService.isJailed(target)) {
            ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.release.notjailed", target.getName()), false);
            return 0;
        }
        JailService.release(target, ReleaseReason.ADMIN);
        ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.release.ok", target.getName()), true);
        return 1;
    }

    private static int assignJail(CommandContext<CommandSourceStack> ctx, int radius) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        CommandSourceStack src = ctx.getSource();
        ResourceLocation dim = src.getLevel().dimension().location();
        JailRegistry.assign(src.getServer(), new JailAnchor(pos, dim, radius));
        src.sendSuccess(() -> Component.translatable("mcacrime.command.assignjail.ok", pos.toShortString(), radius), true);
        return 1;
    }

    // --- op debug: exercises the whole McaCompat adapter end-to-end ---

    private static int debugVillager(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Entity target = nearestMcaVillager(player, 10.0D);
        if (target == null) {
            ctx.getSource().sendFailure(Component.translatable("mcacrime.command.debug.none", 10));
            return 0;
        }
        String profession = McaCompat.getProfessionId(target).map(net.minecraft.resources.ResourceLocation::toString).orElse("<none>");
        String villageId = McaCompat.getHomeVillageId(target).stream().mapToObj(Integer::toString).findFirst().orElse("<none>");
        String message = "MCA villager debug:"
                + "\n  uuid=" + McaCompat.getVillagerUuid(target)
                + "\n  name=" + McaCompat.getVillagerDisplayName(target).getString()
                + "\n  profession=" + profession
                + "\n  isGuard=" + McaCompat.isGuard(target)
                + "\n  hearts=" + McaCompat.getHearts(player, target)
                + "\n  villageId=" + villageId;
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static Entity nearestMcaVillager(ServerPlayer player, double radius) {
        AABB box = player.getBoundingBox().inflate(radius);
        List<Entity> candidates = player.level().getEntities(player, box, McaCompat::isMcaVillager);
        return candidates.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
                .orElse(null);
    }

    /** A band's localized name, tinted to its band color for command output. */
    private static MutableComponent bandComponent(Band band) {
        ChatFormatting color = switch (band) {
            case BLUE -> ChatFormatting.BLUE;
            case RED -> ChatFormatting.RED;
            case GREY -> ChatFormatting.GRAY;
        };
        return Component.translatable("mcacrime.band." + band.lower()).withStyle(color);
    }
}
