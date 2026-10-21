package dev.otectus.mcacrime.command;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import dev.otectus.mcacrime.economy.account.ReconciliationService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Permission-three financial diagnostics. A resolution requires the token from a fresh inspection. */
public final class RecoveryCommand {
    private static final int PAGE_SIZE = 10;
    private RecoveryCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> tree() {
        var root = Commands.literal("recovery").requires(source -> source.hasPermission(3));
        for (String kind : List.of("receipts", "escrow", "quarantine", "audit")) {
            root.then(Commands.literal(kind).executes(ctx -> list(ctx, kind, 1))
                    .then(Commands.argument("page", IntegerArgumentType.integer(1))
                            .executes(ctx -> list(ctx, kind, IntegerArgumentType.getInteger(ctx, "page")))));
        }
        root.then(Commands.literal("inspect").then(Commands.argument("receipt", UuidArgument.uuid())
                .executes(RecoveryCommand::inspect)));
        var token = Commands.argument("revision", UuidArgument.uuid());
        for (var decision : ReconciliationService.Decision.values()) {
            token.then(Commands.literal(decision.name().toLowerCase(Locale.ROOT))
                    .then(Commands.argument("note", StringArgumentType.greedyString())
                            .executes(ctx -> resolve(ctx, decision))));
        }
        root.then(Commands.literal("resolve").then(Commands.argument("receipt", UuidArgument.uuid()).then(token)));
        root.then(Commands.literal("export").executes(RecoveryCommand::export));
        // The physical-state recovery tool (0.7.5 §3.14, M6.5). Everything else in this tree is
        // financial; this one exists for the same reason they do -- something went wrong and an
        // operator needs a way to put it right that is visible, bounded and not a release.
        root.then(Commands.literal("restraints")
                .then(Commands.argument("target", net.minecraft.commands.arguments.EntityArgument.entity())
                        .executes(RecoveryCommand::recoverRestraints)));
        return root;
    }

    private static CrimeWorldData data(CommandContext<CommandSourceStack> ctx) {
        return CrimeWorldData.get(ctx.getSource().getServer());
    }
    private static void say(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private static int list(CommandContext<CommandSourceStack> ctx, String kind, int page) {
        if (!ctx.getSource().hasPermission(3)) return 0;
        var data = data(ctx);
        List<String> rows = switch (kind) {
            case "receipts" -> data.transactions().stream().filter(r -> !r.state().terminal())
                    .map(r -> r.id() + " " + r.state() + " " + r.reason() + " " + r.amount()
                            + " " + r.providerId() + " -> " + r.to()).toList();
            case "escrow" -> data.propertyEscrow().stream().map(l -> l.lotId() + " owner=" + l.owner()
                    + " currency=" + l.currency() + " provider=" + l.providerId() + " items=" + l.hasStack()
                    + " receipt=" + dev.otectus.mcacrime.state.world.PropertyEscrow.deliveryId(l)).toList();
            case "audit" -> data.reconciliationDecisions().stream().map(d -> d.id() + " receipt=" + d.receiptId()
                    + " " + d.action() + " by " + d.operator() + " at " + d.time() + ": " + d.note()).toList();
            default -> data.quarantined().stream().map(Object::toString)
                    .map(s -> s.length() > 240 ? s.substring(0, 240) + "... (full record in export)" : s).toList();
        };
        say(ctx.getSource(), kind + ": " + rows.size() + " entries; page " + page + "/" + Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE));
        int start = (int) Math.min(rows.size(), (long) (page - 1) * PAGE_SIZE);
        rows.subList(start, Math.min(rows.size(), start + PAGE_SIZE)).forEach(row -> say(ctx.getSource(), row));
        return Math.min(PAGE_SIZE, rows.size() - start);
    }

    private static int inspect(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!ctx.getSource().hasPermission(3)) return 0;
        var receipt = data(ctx).transaction(UuidArgument.getUuid(ctx, "receipt"));
        if (receipt == null) { ctx.getSource().sendFailure(Component.literal("Receipt not found.")); return 0; }
        say(ctx.getSource(), receipt.save().toString());
        say(ctx.getSource(), "Revision token: " + ReconciliationService.revision(data(ctx), receipt));
        var lot = ReconciliationService.linkedProperty(data(ctx), receipt.id());
        if (lot != null) say(ctx.getSource(), "Property lot: " + lot.lotId() + "; exact item data available in export.");
        data(ctx).bountyClaims().values().stream().filter(claim -> dev.otectus.mcacrime.bounty.BountyPayments.id(
                dev.otectus.mcacrime.bounty.BountyPayments.key(claim)).equals(receipt.id()))
                .forEach(claim -> say(ctx.getSource(), "Bounty claim: " + claim.save()));
        say(ctx.getSource(), "delivered = externally verified/compensated; cancelled = verified no outstanding transfer (no property lots); retry = verified nothing from this remainder arrived (bounty/property only). Record a note. No immediate credit is issued.");
        return 1;
    }

    private static int resolve(CommandContext<CommandSourceStack> ctx, ReconciliationService.Decision decision)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!ctx.getSource().hasPermission(3)) return 0;
        var id = UuidArgument.getUuid(ctx, "receipt");
        var entity = ctx.getSource().getEntity();
        String operator = entity == null ? "console:" + ctx.getSource().getTextName() : entity.getUUID().toString();
        var result = ReconciliationService.resolve(data(ctx), id, UuidArgument.getUuid(ctx, "revision"), decision,
                operator, StringArgumentType.getString(ctx, "note"), ctx.getSource().getServer().overworld().getGameTime());
        if (result != ReconciliationService.Result.APPLIED) {
            ctx.getSource().sendFailure(Component.literal("Recovery refused: " + result + ". Inspect the receipt before trying again."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Recorded " + decision + " for receipt " + id + ". No immediate payment was issued."), true);
        return 1;
    }

    private static int export(CommandContext<CommandSourceStack> ctx) {
        if (!ctx.getSource().hasPermission(3)) return 0;
        var data = data(ctx);
        JsonObject report = new JsonObject();
        report.addProperty("format", "mcacrime-recovery-v1");
        report.addProperty("schema", dev.otectus.mcacrime.state.world.CrimeDataMigrations.CURRENT_SCHEMA);
        report.addProperty("readOnly", !dev.otectus.mcacrime.state.world.ServerMutationGate.allows(data));
        JsonArray receipts = new JsonArray(), escrow = new JsonArray(), quarantine = new JsonArray(), audit = new JsonArray(), claims = new JsonArray();
        // Full fidelity SNBT payloads retain item NBT without Gson reflecting Minecraft implementation internals.
        data.transactions().forEach(r -> receipts.add(r.save().toString()));
        data.propertyEscrow().forEach(l -> escrow.add(l.save().toString()));
        data.quarantined().forEach(q -> quarantine.add(q.toString()));
        data.reconciliationDecisions().forEach(d -> audit.add(d.save().toString()));
        data.bountyClaims().values().forEach(c -> claims.add(c.save().toString()));
        report.add("receiptsSnbt", receipts); report.add("escrowSnbt", escrow);
        report.add("quarantineSnbt", quarantine); report.add("auditSnbt", audit);
        report.add("bountyClaimsSnbt", claims);
        try {
            var directory = ctx.getSource().getServer().getWorldPath(LevelResource.ROOT).resolve("mcacrime-recovery");
            Files.createDirectories(directory);
            var file = directory.resolve("recovery-" + UUID.randomUUID() + ".json");
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            say(ctx.getSource(), "Recovery export: " + file.toAbsolutePath());
            return 1;
        } catch (java.io.IOException failure) {
            ctx.getSource().sendFailure(Component.literal("Could not export recovery data: " + failure.getMessage()));
            return 0;
        }
    }

    /**
     * Clears every physical claim on one subject: worn gear, holds and any device occupancy.
     *
     * <p><b>Not a release.</b> The custody record, the sentence and every case behind it are left
     * exactly as they are, which is the entire difference between this and {@code /crime release}: a
     * prisoner whose gear is stuck is freed from the gear, not from the law. That is also why it is
     * reported item by item rather than as a single success — an operator running a recovery tool
     * should be able to see what it actually did.
     */
    private static int recoverRestraints(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var target = net.minecraft.commands.arguments.EntityArgument.getEntity(ctx, "target");
        var server = ctx.getSource().getServer();
        CrimeWorldData data = data(ctx);
        int removed = 0;
        if (target instanceof net.minecraft.world.entity.LivingEntity subject) {
            for (var slot : dev.otectus.mcacrime.restraint.RestraintSlot.values()) {
                if (dev.otectus.mcacrime.restraint.RemovalService.remove(subject, slot,
                        dev.otectus.mcacrime.restraint.RemovalService.Reason.ADMINISTRATIVE, null)
                        .removed()) {
                    removed++;
                }
            }
        }
        int holds = dev.otectus.mcacrime.tether.TetherService.detachAll(server, target.getUUID(),
                dev.otectus.mcacrime.tether.TetherService.DetachReason.ADMINISTRATIVE);
        boolean device = dev.otectus.mcacrime.detention.DetentionService.releaseSubject(server, data,
                target.getUUID(), dev.otectus.mcacrime.detention.DetentionService.ReleaseReason
                        .ADMINISTRATIVE).isPresent();
        boolean escort = dev.otectus.mcacrime.enforcement.CondemnedEscortService.cancel(server,
                target.getUUID(), "an operator recovered the subject's physical state");
        say(ctx.getSource(), "Recovered " + target.getDisplayName().getString() + ": " + removed
                + " restraint(s), " + holds + " hold(s)" + (device ? ", one device" : "")
                + (escort ? ", one escort" : "")
                + ". Custody, sentence and cases are untouched.");
        return removed + holds;
    }
}
