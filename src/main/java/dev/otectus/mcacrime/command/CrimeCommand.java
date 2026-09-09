package dev.otectus.mcacrime.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.ai.thief.ThiefBehaviorController;
import dev.otectus.mcacrime.bounty.BountyClaimLedger;
import dev.otectus.mcacrime.bounty.BountyService;
import dev.otectus.mcacrime.ai.thief.ThiefBehaviorService;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.api.McaCrimeApi;
import dev.otectus.mcacrime.action.CrimeActionIds;
import dev.otectus.mcacrime.action.CrimeActionService;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.compat.LocksReforgedBridge;
import dev.otectus.mcacrime.compat.McaQuestsBridge;
import dev.otectus.mcacrime.compat.ReputationBridge;
import dev.otectus.mcacrime.config.ConfigValidator;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.crime.KarmaSource;
import dev.otectus.mcacrime.crime.type.CrimeTypeRegistry;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.economy.FineService;
import dev.otectus.mcacrime.economy.SurrenderService;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.enforcement.LegalTarget;
import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import dev.otectus.mcacrime.jail.JailRegistry;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.jail.ReleaseReason;
import dev.otectus.mcacrime.integration.CrimeIntegrationOperation;
import dev.otectus.mcacrime.ledger.CrimeLedger;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Warrant;
import dev.otectus.mcacrime.ledger.WarrantService;
import dev.otectus.mcacrime.mug.MuggingService;
import dev.otectus.mcacrime.mug.npc.NpcMugSession;
import dev.otectus.mcacrime.mug.npc.NpcMuggingService;
import dev.otectus.mcacrime.ransom.RansomService;
import dev.otectus.mcacrime.state.CrimeAttachments;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.world.BountyClaimRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.CriminalVillagerRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Brigadier commands under {@code /crime} (spec §17), permission-tiered like the Quests command. The
 * 0.1.0 subset: player read commands ({@code karma}/{@code status}), op read/debug
 * ({@code query}/{@code debug villager}), and op mutators ({@code set karma|heat}/{@code clearheat}) plus
 * {@code validate}. Every mutator routes through {@link CrimeState} (the server-authoritative chokepoint);
 * commands never write capability NBT directly.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeCommand {

    /** How far {@code /crime job} looks for the villager the operator means. */
    private static final double JOB_TARGET_RADIUS = 8.0D;

    /** How far {@code /crime mugtest} will look for a thief. Wider than the job radius on purpose. */
    private static final double MUGTEST_RADIUS = 32.0D;

    /** Chat is not a spreadsheet: a save with hundreds of criminals prints the first twenty. */
    private static final int JOB_LIST_LIMIT = 20;

    /** How many recent bounty claims the debug dump prints. A long-lived world has thousands. */
    private static final int BOUNTY_CLAIM_LIST_LIMIT = 10;

    private CrimeCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("crime")
                .then(RecoveryCommand.tree())
                .then(Commands.literal("collectbounty").executes(ctx -> BountyService.collect(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("karma")
                        .executes(CrimeCommand::karma))
                .then(Commands.literal("status")
                        .executes(CrimeCommand::status))
                .then(Commands.literal("payfine")
                        .executes(CrimeCommand::payFine))
                .then(Commands.literal("surrender")
                        .executes(CrimeCommand::surrender))
                .then(Commands.literal("ransom")
                        .executes(CrimeCommand::ransom))
                .then(Commands.literal("payransom")
                        .executes(CrimeCommand::payRansom))
                .then(Commands.literal("mug")
                        .executes(CrimeCommand::mug))
                .then(Commands.literal("escape")
                        .executes(CrimeCommand::escape))
                .then(Commands.literal("releasecaptive")
                        .executes(CrimeCommand::releaseCaptive))
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
                .then(Commands.literal("clear").requires(src -> src.hasPermission(3))
                        .then(Commands.literal("memory")
                                .then(Commands.argument("villager", EntityArgument.entity())
                                        .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                                            if (!dev.otectus.mcacrime.state.world.ServerMutationGate.allows(ctx.getSource().getServer())) return 0;
                                            var target = EntityArgument.getEntity(ctx, "villager");
                                            var offender = EntityArgument.getPlayer(ctx, "player");
                                            var data = CrimeWorldData.get(ctx.getSource().getServer());
                                            data.villagerProfile(target.getUUID()).ifPresent(profile -> profile.clearMemories(offender.getUUID()));
                                            data.setDirty();
                                            ctx.getSource().sendSuccess(() -> Component.literal("Cleared crime memories for this villager and player."), true);
                                            return 1;
                                        })))))
                .then(Commands.literal("set")
                        // Command blocks have level 2 and need heat for scripted crime areas.
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("karma")
                                .requires(src -> src.hasPermission(3))
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
                .then(Commands.literal("job")
                        .requires(src -> src.hasPermission(3))
                        .then(Commands.literal("assign")
                                .then(Commands.literal("thief")
                                        .executes(ctx -> assignJob(ctx, CriminalJob.THIEF)))
                                .then(Commands.literal("fence")
                                        .executes(ctx -> assignJob(ctx, CriminalJob.FENCE))))
                        .then(Commands.literal("clear")
                                .executes(ctx -> assignJob(ctx, CriminalJob.NONE)))
                        .then(Commands.literal("list")
                                .executes(CrimeCommand::listJobs)))
                .then(Commands.literal("warrant")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(CrimeCommand::warrant)))
                .then(Commands.literal("bounty")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(CrimeCommand::bounty)))
                .then(Commands.literal("mugtest")
                        .requires(src -> src.hasPermission(3))
                        .executes(CrimeCommand::mugTest))
                .then(Commands.literal("debug")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("witness").executes(ctx -> debugAwareness(ctx, "witness", null))
                                .then(Commands.argument("villager", EntityArgument.entity()).executes(ctx -> debugAwareness(ctx, "witness", EntityArgument.getEntity(ctx, "villager")))))
                        .then(Commands.literal("threat").executes(ctx -> debugAwareness(ctx, "threat", null))
                                .then(Commands.argument("villager", EntityArgument.entity()).executes(ctx -> debugAwareness(ctx, "threat", EntityArgument.getEntity(ctx, "villager")))))
                        .then(Commands.literal("memory").executes(ctx -> debugAwareness(ctx, "memory", null))
                                .then(Commands.argument("villager", EntityArgument.entity()).executes(ctx -> debugAwareness(ctx, "memory", EntityArgument.getEntity(ctx, "villager")))))
                        .then(Commands.literal("crimeevents").executes(ctx -> {
                            var active = dev.otectus.mcacrime.ai.CrimeReactionService.snapshot();
                            for (var reaction : active) ctx.getSource().sendSuccess(() -> Component.literal(
                                    reaction.villagerId() + " " + reaction.state() + " observation=" + reaction.observationId()), false);
                            return active.size();
                        }))
                        .then(Commands.literal("thieves")
                                .executes(CrimeCommand::debugThieves))
                        .then(Commands.literal("bounty")
                                .executes(CrimeCommand::debugBounty))
                        .then(Commands.literal("jobs")
                                .executes(CrimeCommand::debugJobs))
                        .then(Commands.literal("villager")
                                .executes(CrimeCommand::debugVillager))
                        .then(Commands.literal("custody")
                                .executes(CrimeCommand::debugCustody))
                        .then(Commands.literal("actions")
                                .executes(CrimeCommand::debugActions))
                        .then(Commands.literal("integrations")
                                .executes(CrimeCommand::debugIntegrations))
                        .then(Commands.literal("guards")
                                .executes(CrimeCommand::debugGuards))
                        .then(Commands.literal("arrest")
                                .executes(CrimeCommand::debugArrest))
                        .then(Commands.literal("weapon")
                                .executes(CrimeCommand::debugWeapon))
                        .then(Commands.literal("outbox")
                                .executes(ctx -> debugOutbox(ctx, false))
                                .then(Commands.literal("dead")
                                        .executes(ctx -> debugOutbox(ctx, true))))));
    }

    /**
     * Where an operator starts when an attack scores twice, or not at all. Reports presence, the
     * handshake, who owns detection, and what is stuck — and deliberately no UUIDs beyond operation
     * ids, so pasting the output into a bug report leaks nothing about players.
     */
    private static int debugIntegrations(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("mcacrime api v" + McaCrimeApi.getApiVersion()), false);
        source.sendSuccess(() -> Component.literal("reputation: installed="
                + ModList.get().isLoaded("mcareputation")
                + " enabled=" + McaCrimeConfig.COMMON.enableReputation.get()
                + " state=" + ReputationBridge.status()), false);
        source.sendSuccess(() -> Component.literal("  bridge available=" + ReputationBridge.isAvailable()
                + " holds detection authority=" + ReputationBridge.holdsAuthority()), false);
        if (ReputationBridge.isAvailable() && !ReputationBridge.holdsAuthority()) {
            source.sendSuccess(() -> Component.literal(
                    "  villager assault/killing is still being recorded by MCA: Reputation itself; "
                            + "MCA: Crime is not producing those incidents.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        source.sendSuccess(() -> Component.literal("locks reforged: installed="
                + ModList.get().isLoaded("locks")
                + " enabled=" + McaCrimeConfig.COMMON.locksReforgedFenceTrades.get()
                + " state=" + LocksReforgedBridge.status()), false);
        source.sendSuccess(() -> Component.literal("mca quests: installed="
                + ModList.get().isLoaded("mcaquests")
                + " enabled=" + McaCrimeConfig.COMMON.mcaQuestsBounties.get()
                + " state=" + McaQuestsBridge.status()), false);

        CrimeWorldData data = CrimeWorldData.get(source.getServer());
        source.sendSuccess(() -> Component.literal("outbox: pending=" + data.pendingOperationCount()
                + " dead=" + data.deadLetterCount()), false);
        List<CrimeIntegrationOperation> dead = data.deadLetters();
        if (!dead.isEmpty()) {
            CrimeIntegrationOperation newest = dead.get(dead.size() - 1);
            source.sendSuccess(() -> Component.literal("  last failure: " + newest.target()
                    + " -> " + newest.lastError()).withStyle(ChatFormatting.RED), false);
        }
        return data.pendingOperationCount();
    }

    private static int debugAwareness(CommandContext<CommandSourceStack> ctx, String mode, Entity selected)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Entity target = selected == null ? nearestMcaVillager(player, 8) : selected;
        if (!(target instanceof LivingEntity villager) || !McaCompat.isMcaVillager(villager)) {
            ctx.getSource().sendFailure(Component.literal("No MCA villager selected.")); return 0;
        }
        var server = ctx.getSource().getServer();
        if (mode.equals("threat")) {
            var context = dev.otectus.mcacrime.ai.ThreatContexts.build(villager, player,
                    dev.otectus.mcacrime.action.ActionSessionManager.activeCoerciveAgainst(villager.getUUID()).isPresent());
            var evaluation = dev.otectus.mcacrime.ai.ThreatEvaluator.evaluate(context, dev.otectus.mcacrime.ai.ThreatContexts.options());
            ctx.getSource().sendSuccess(() -> Component.literal(context + "\n" + evaluation), false);
        } else if (mode.equals("memory")) {
            for (var memory : McaCrimeApi.victimMemories(server, villager.getUUID(), player.getUUID()))
                ctx.getSource().sendSuccess(() -> Component.literal(memory.toString()), false);
        } else {
            for (var observation : CrimeWorldData.get(server).observationsBy(villager.getUUID()))
                ctx.getSource().sendSuccess(() -> Component.literal(observation.toString()), false);
        }
        return 1;
    }

    private static int debugActions(CommandContext<CommandSourceStack> ctx) {
        int active = dev.otectus.mcacrime.action.ActionSessionManager.activeCount();
        ctx.getSource().sendSuccess(() -> Component.literal("active action sessions: " + active), false);
        return active;
    }

    /** Lists queued or given-up-on cross-mod writes, bounded so a big backlog cannot flood chat. */
    private static int debugOutbox(CommandContext<CommandSourceStack> ctx, boolean deadOnly) {
        CommandSourceStack source = ctx.getSource();
        CrimeWorldData data = CrimeWorldData.get(source.getServer());
        List<CrimeIntegrationOperation> entries = deadOnly
                ? data.deadLetters()
                : data.dueOperations(source.getServer().overworld().getGameTime(), 20);
        source.sendSuccess(() -> Component.literal((deadOnly ? "dead letters: " : "due now: ")
                + entries.size()), false);
        entries.stream().limit(20).forEach(operation -> source.sendSuccess(() -> Component.literal(
                        "  " + operation.operationId() + " " + operation.action().getPath()
                                + " attempts=" + operation.attempts()
                                + (operation.lastError().isEmpty() ? "" : " last=" + operation.lastError()))
                .withStyle(ChatFormatting.DARK_GRAY), false));
        return entries.size();
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

    // --- player action fallbacks ---
    //
    // Every one of these is an accessibility path into the same server action contract the UI uses
    // (spec §8.5). None of them touches a service directly any more: a command must not be able to
    // skip a target lock, a nonce, a cooldown, or a replay check that a menu click applies.

    private static int payFine(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return CrimeActionService.startSelfFromCommand(
                ctx.getSource().getPlayerOrException(), CrimeActionIds.SETTLE_CASE);
    }

    private static int surrender(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return CrimeActionService.startSelfFromCommand(
                ctx.getSource().getPlayerOrException(), CrimeActionIds.SURRENDER);
    }

    private static int ransom(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return CrimeActionService.demandRansomFromCommand(ctx.getSource().getPlayerOrException());
    }

    private static int payRansom(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return CrimeActionService.startSelfFromCommand(
                ctx.getSource().getPlayerOrException(), CrimeActionIds.PAY_RANSOM);
    }

    private static int mug(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return CrimeActionService.startMugFromCommand(ctx.getSource().getPlayerOrException());
    }

    /** A captive's attempt to break free of an unlawful captor (escaping kidnapping is no crime, §8.1). */
    private static int escape(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return CrimeActionService.startSelfFromCommand(
                ctx.getSource().getPlayerOrException(), CrimeActionIds.ESCAPE);
    }

    private static int releaseCaptive(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return CrimeActionService.releaseOwnedCaptives(ctx.getSource().getPlayerOrException());
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

    /** The universal "free this player" backstop (§8.4): clears jail AND any kidnapping custody (as captive or captor). */
    private static int release(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        MinecraftServer server = ctx.getSource().getServer();
        boolean did = false;
        if (JailService.isJailed(target)) {
            JailService.release(target, ReleaseReason.ADMIN);
            did = true;
        }
        if (server != null) {
            if (CustodyRegistry.isCaptive(server, target.getUUID())) {
                CustodyService.release(server, target.getUUID(), CustodyReleaseReason.ADMIN);
                did = true;
            }
            for (CustodyRecord held : CustodyRegistry.byOwner(server, target.getUUID())) {
                CustodyService.release(server, held.getCaptive(), CustodyReleaseReason.ADMIN);
                did = true;
            }
        }
        if (did) {
            ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.release.ok", target.getName()), true);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.release.notjailed", target.getName()), false);
        return 0;
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

    /**
     * What the guard-population pass sees in this dimension, per village.
     *
     * <p>Without this the only signal that the feature works is villagers slowly changing clothes, and
     * the first question anybody enabling it will ask -- "is this even doing anything?" -- has no other
     * answer. It also makes the overlap with MCA's own pass legible: a village already at target
     * because MCA got there first reports needed=0 rather than looking broken.
     */
    private static int debugGuards(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        StringBuilder sb = new StringBuilder("Guard population debug (ratio ")
                .append(McaCrimeConfig.COMMON.guardPopulationRatio.get())
                .append(", minimum ").append(McaCrimeConfig.COMMON.guardPopulationMinimum.get())
                .append("):");
        for (String line : dev.otectus.mcacrime.enforcement.GuardPopulationService.report(level)) {
            sb.append("\n  ").append(line);
        }
        for (LivingEntity guard : level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(McaCrimeConfig.COMMON.guardAggroRadius.get()),
                dev.otectus.mcacrime.detect.EntitySelectors::isResponder).stream().limit(16).toList()) {
            var decision = dev.otectus.mcacrime.justice.JusticeService.forGuard(level, guard, player);
            sb.append("\n  responder=").append(guard.getUUID())
                    .append(" jurisdiction=").append(decision.jurisdiction())
                    .append(" basis=").append(decision.basis())
                    .append(" caseCount=").append(decision.cases().size())
                    .append(" cases=").append(decision.caseIds().stream().limit(16).toList());
        }
        var challenge = dev.otectus.mcacrime.enforcement.GuardChallengeService.open(player.getUUID());
        if (challenge != null) sb.append("\n  encounter=").append(challenge.encounterId())
                .append(" revision=").append(challenge.revision()).append(" fine=").append(challenge.assessedFine())
                .append(" remainingTicks=").append(challenge.remaining(level.getGameTime()));
        String out = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    /**
     * How the held item classifies, and which rule layer said so.
     *
     * <p>"My sword does not open the menu" is otherwise unanswerable without reading the config, the
     * tags and the auto-detection order at once. This prints the one line that names the deciding layer.
     */
    private static int debugWeapon(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        net.minecraft.world.item.ItemStack stack = player.getMainHandItem();
        dev.otectus.mcacrime.item.weapon.WeaponMatch match =
                dev.otectus.mcacrime.item.weapon.WeaponDetector.classify(stack);
        String id = stack.isEmpty() ? "-" : String.valueOf(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()));
        ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.debug.weapon",
                id, match.weaponClass().name(), match.layer(),
                dev.otectus.mcacrime.item.weapon.WeaponDetector.minAttackDamage()), false);
        return match.isWeapon() ? 1 : 0;
    }

    /** The arrest lifecycle for the calling player: one authoritative phase, and what it is gating. */
    private static int debugArrest(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        dev.otectus.mcacrime.enforcement.ArrestPhase phase =
                dev.otectus.mcacrime.enforcement.ArrestStates.phaseOf(player);
        dev.otectus.mcacrime.enforcement.ArrestState state =
                dev.otectus.mcacrime.enforcement.ArrestStates.of(player);
        StringBuilder sb = new StringBuilder("Arrest debug:");
        sb.append("\n  phase=").append(phase);
        sb.append("\n  restrained=").append(
                dev.otectus.mcacrime.enforcement.ArrestPhases.isRestrained(phase));
        sb.append("\n  challengeable=").append(
                dev.otectus.mcacrime.enforcement.ArrestPhases.canOpenChallenge(phase));
        sb.append("\n  guard=").append(state == null ? "-" : state.getGuard());
        sb.append("\n  destination=").append(state == null || state.anchor() == null
                ? "-" : state.anchor().pos() + " in " + state.anchor().dim());
        sb.append("\n  sentenceTicks=").append(state == null ? 0L : state.getSentenceTicks());
        sb.append("\n  jailRemaining=").append(
                dev.otectus.mcacrime.util.TickFormat.compact(JailService.remainingTicks(player)));
        sb.append("\n  openChallenges=")
                .append(dev.otectus.mcacrime.enforcement.GuardChallengeService.openCount())
                .append(", escorts=")
                .append(dev.otectus.mcacrime.enforcement.EscortService.activeCount());
        String out = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    /** Dumps custody + the ⚠ relationship-adapter results for the nearest villager — the in-world verification harness. */
    private static int debugCustody(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        PlayerCrimeData data = CrimeAttachments.get(player);
        StringBuilder sb = new StringBuilder("Custody debug:");
        sb.append("\n  heldCaptive=").append(data.getHeldCaptiveRef());
        sb.append("\n  heldBy=").append(data.getHeldByRef());
        if (server != null) {
            CustodyRecord rec = CrimeWorldData.get(server).getCustody(player.getUUID());
            sb.append("\n  asCaptive=").append(rec == null ? "-"
                    : (rec.isLawful() ? "lawful" : "kidnap") + " by " + rec.getOwner().type());
        }
        Entity villager = nearestMcaVillager(player, 10.0D);
        if (villager != null) {
            sb.append("\n  relationshipApi=").append(McaCompat.isRelationshipApiAvailable());
            sb.append("\n  villager.spouse=").append(McaCompat.getSpouseUuid(villager).map(UUID::toString).orElse("-"));
            sb.append("\n  villager.parents=").append(McaCompat.getParentUuids(villager).size());
            sb.append("\n  villager.children=").append(McaCompat.getChildUuids(villager).size());
            sb.append("\n  villager.siblings=").append(McaCompat.getSiblingUuids(villager).size());
            sb.append("\n  villager.isAdult=").append(McaCompat.isAdult(villager));
        }
        String message = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    // --- criminal jobs (0.5.1) ---

    /**
     * Assigns or clears a criminal job on the nearest MCA villager.
     *
     * <p>The target is the nearest villager within 8 blocks rather than an entity selector, because
     * the thing an operator actually wants to say is "this one, the one I am looking at", and a
     * selector for an MCA villager needs a UUID nobody has to hand.
     */
    private static int assignJob(CommandContext<CommandSourceStack> ctx, CriminalJob job)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Entity villager = nearestMcaVillager(player, JOB_TARGET_RADIUS);
        if (villager == null) {
            ctx.getSource().sendFailure(Component.translatable("mcacrime.command.job.none_nearby",
                    (int) JOB_TARGET_RADIUS));
            return 0;
        }
        MinecraftServer server = ctx.getSource().getServer();
        WorldCriminalJobService jobs = WorldCriminalJobService.of(server);
        jobs.set(villager.getUUID(), job);
        Component name = McaCompat.getVillagerDisplayName(villager);
        ctx.getSource().sendSuccess(() -> job == CriminalJob.NONE
                ? Component.translatable("mcacrime.command.job.cleared", name)
                : Component.translatable("mcacrime.command.job.assigned", name, job.id()), true);
        return 1;
    }

    // --- warrants and bounties (0.5.1) ---

    /** What the law currently has open on somebody. The identity every bounty claim is keyed on. */
    private static int warrant(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        Warrant warrant = CrimeWorldData.get(ctx.getSource().getServer()).warrant(target.getUUID());
        if (warrant == null) {
            ctx.getSource().sendSuccess(() ->
                    Component.translatable("mcacrime.command.warrant.none", target.getName()), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.warrant.status",
                target.getName(),
                warrant.open() ? "open" : "closed",
                warrant.revision(),
                warrant.topOffense() == null ? "-" : warrant.topOffense().toString()), false);
        return warrant.open() ? 1 : 0;
    }

    /**
     * What somebody is worth right now.
     *
     * <p>Reports nothing rather than zero when they are not eligible, because "no price on their
     * head" and "a price of nothing" are different answers and only one of them is a number.
     */
    private static int bounty(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        Optional<Long> quote = BountyService.quote(target);
        if (quote.isEmpty()) {
            ctx.getSource().sendSuccess(() ->
                    Component.translatable("mcacrime.command.warrant.none", target.getName()), false);
            return 0;
        }
        long amount = quote.get();
        ctx.getSource().sendSuccess(() ->
                Component.translatable("mcacrime.command.bounty.quote", target.getName(),
                        Currencies.active().format(amount)), false);
        return (int) Math.min(Integer.MAX_VALUE, amount);
    }

    /** Every open warrant and the most recent claims, for working out why a payout did or did not land. */
    private static int debugBounty(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CrimeWorldData data = CrimeWorldData.get(source.getServer());
        source.sendSuccess(() -> Component.literal("bounty: enabled="
                + McaCrimeConfig.COMMON.bountyEnabled.get()
                + " kills=" + McaCrimeConfig.COMMON.payForKills.get()
                + " alive=" + McaCrimeConfig.COMMON.payForAliveCapture.get()
                + " claims=" + data.bountyClaims().size()), false);
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            WarrantService.open(source.getServer(), player.getUUID()).ifPresent(warrant ->
                    source.sendSuccess(() -> Component.literal("  " + player.getGameProfile().getName()
                                    + " rev=" + warrant.revision()
                                    + " quote=" + BountyService.quote(player).map(String::valueOf).orElse("-"))
                            .withStyle(ChatFormatting.DARK_GRAY), false));
        }
        data.bountyClaims().values().stream()
                .sorted(Comparator.comparingLong(BountyClaimRecord::claimedAt).reversed())
                .limit(BOUNTY_CLAIM_LIST_LIMIT)
                .forEach(record -> source.sendSuccess(() -> Component.literal("  claim " + record.target()
                                + " rev=" + record.revision()
                                + " " + record.type().name().toLowerCase(Locale.ROOT)
                                + " reward=" + record.reward()
                                + " day=" + BountyClaimLedger.claimedDay(record))
                        .withStyle(ChatFormatting.DARK_GRAY), false));
        return data.bountyClaims().size();
    }

    // --- thieves (0.5.1) ---

    /**
     * Points the nearest thief at the caller.
     *
     * <p>A thief picks victims on a jittered scan and refuses anybody armed, so waiting for one to
     * choose you is a poor way to test four seconds of interaction. Everything after selection still
     * applies: guard risk, reach, the weapon check and the cancellable attempt event all run.
     */
    private static int mugTest(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        WorldCriminalJobService jobs = WorldCriminalJobService.of(server);
        AABB box = player.getBoundingBox().inflate(MUGTEST_RADIUS);
        Entity thief = player.level().getEntities(player, box, entity -> McaCompat.isMcaVillager(entity)
                        && jobs.get(entity.getUUID()) == CriminalJob.THIEF).stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(player)))
                .orElse(null);
        if (!(thief instanceof net.minecraft.world.entity.LivingEntity living)
                || !ThiefBehaviorService.forceTarget(living, player)) {
            ctx.getSource().sendFailure(Component.translatable("mcacrime.command.mugtest.no_thief",
                    (int) MUGTEST_RADIUS));
            return 0;
        }
        Component name = McaCompat.getVillagerDisplayName(thief);
        ctx.getSource().sendSuccess(() -> Component.translatable("mcacrime.command.mugtest.started", name), true);
        return 1;
    }

    /**
     * Every thief this server is currently driving, and every mugging in progress.
     *
     * <p>The think and scan schedules are in the output on purpose: spec §"Performance requirements"
     * asks for proof that no controller thinks every tick, and this is where an operator sees it.
     */
    private static int debugThieves(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<ThiefBehaviorController> controllers = ThiefBehaviorService.snapshot();
        List<NpcMugSession> sessions = NpcMuggingService.snapshot();
        long now = source.getServer().overworld().getGameTime();

        StringBuilder sb = new StringBuilder("Thief debug:");
        sb.append("\n  tracked=").append(controllers.size())
                .append(" muggings=").append(sessions.size())
                .append(" enableThieves=").append(McaCrimeConfig.COMMON.enableThieves.get());
        for (ThiefBehaviorController controller : controllers) {
            sb.append("\n  ").append(controller.thiefId())
                    .append(" ").append(controller.state())
                    .append(" for ").append(controller.ticksInState(now)).append("t")
                    .append(" victim=").append(controller.victimId() == null ? "<none>" : controller.victimId())
                    .append(" risk=").append(String.format(java.util.Locale.ROOT, "%.2f",
                            controller.guardRisk().riskScore()))
                    .append(" guards=").append(controller.guardRisk().nearbyCount())
                    .append(" failures=").append(controller.failures());
        }
        for (NpcMugSession session : sessions) {
            sb.append("\n  mug ").append(session.transactionId())
                    .append(" thief=").append(session.thiefId())
                    .append(" victim=").append(session.victimId())
                    .append(" ").append(session.progress()).append("/").append(session.requiredTicks());
        }
        String message = sb.toString();
        source.sendSuccess(() -> Component.literal(message), false);
        return controllers.size();
    }

    /** Every persisted criminal, loaded or not, bounded so a large save cannot flood chat. */
    private static int listJobs(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<CriminalVillagerRecord> records =
                List.copyOf(WorldCriminalJobService.of(source.getServer()).all());
        source.sendSuccess(() -> Component.translatable("mcacrime.command.job.list_header", records.size()), false);
        records.stream().limit(JOB_LIST_LIMIT).forEach(record -> source.sendSuccess(() -> Component.literal(
                        "  " + record.villager() + " " + record.job().id()
                                + " assignedDay=" + record.assignedDay()
                                + " lastSeenDay=" + record.lastSeenDay()
                                + (record.wildOrigin() ? " wild" : ""))
                .withStyle(ChatFormatting.DARK_GRAY), false));
        return records.size();
    }

    /**
     * What the assignment sweep is working with here: the settings, the totals, and the nearest
     * villager's own eligibility.
     *
     * <p>Without the last part the only signal that assignment works is waiting for a thief to appear,
     * and "nothing has happened yet" and "nothing can ever happen" look identical from the outside.
     */
    private static int debugJobs(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        WorldCriminalJobService jobs = WorldCriminalJobService.of(server);
        int thieves = 0;
        int fences = 0;
        for (CriminalVillagerRecord record : jobs.all()) {
            if (record.job() == CriminalJob.THIEF) thieves++;
            if (record.job() == CriminalJob.FENCE) fences++;
        }
        StringBuilder sb = new StringBuilder("Criminal jobs debug:");
        sb.append("\n  thieves=").append(thieves).append(" fences=").append(fences);
        sb.append("\n  enableThieves=").append(McaCrimeConfig.COMMON.enableThieves.get())
                .append(" enableFences=").append(McaCrimeConfig.COMMON.enableFences.get());
        sb.append("\n  presentThief=").append(McaCrimeConfig.COMMON.presentThiefAsMcaProfession.get())
                .append(" presentFence=").append(McaCrimeConfig.COMMON.presentFenceAsMcaProfession.get());
        Entity villager = nearestMcaVillager(player, JOB_TARGET_RADIUS);
        if (villager == null) {
            sb.append("\n  nearest: none within ").append((int) JOB_TARGET_RADIUS).append(" blocks");
        } else {
            sb.append("\n  nearest=").append(villager.getUUID())
                    .append(" job=").append(jobs.get(villager.getUUID()).id())
                    .append(" adult=").append(McaCompat.isAdultVillager(villager))
                    .append(" law=").append(McaCompat.isGuard(villager) || McaCompat.isArcher(villager))
                    .append(" village=").append(McaCompat.getHomeVillageId(villager).stream()
                            .mapToObj(Integer::toString).findFirst().orElse("<none>"));
        }
        String message = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        return thieves + fences;
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
