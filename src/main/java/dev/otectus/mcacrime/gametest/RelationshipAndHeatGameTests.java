package dev.otectus.mcacrime.gametest;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.CrimeAttemptEvent;
import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.action.handler.ApologizeActionHandler;
import dev.otectus.mcacrime.ai.CrimeReactionService;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.detect.DamageIncidentService;
import dev.otectus.mcacrime.memory.ApologyStatus;
import dev.otectus.mcacrime.memory.VictimMemoryService;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.enforcement.*;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import dev.otectus.mcacrime.justice.JusticeService;
import dev.otectus.mcacrime.justice.LegalDecision;
import dev.otectus.mcacrime.mug.npc.NpcMugAbortReason;
import dev.otectus.mcacrime.mug.npc.NpcMugSession;
import dev.otectus.mcacrime.mug.npc.NpcMuggingService;
import dev.otectus.mcacrime.network.ActionProgressS2CPacket;
import dev.otectus.mcacrime.network.ActionMenuS2CPacket;
import dev.otectus.mcacrime.network.StartActionC2SPacket;
import dev.otectus.mcacrime.network.GuardChallengeS2CPacket;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Real MCA entities and server services; outgoing packets are recorded without a client. */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RelationshipAndHeatGameTests {
    private RelationshipAndHeatGameTests() {}

    @SuppressWarnings("unchecked")
    private static Mob villager(GameTestHelper helper) {
        // Establish actual collision blocks instead of relying on the bounds template's floor.
        for (int x = 0; x < 13; x++) for (int z = 0; z < 13; z++)
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        var type = (EntityType<? extends Mob>) BuiltInRegistries.ENTITY_TYPE.getOptional(
                ResourceLocation.fromNamespaceAndPath("mca", "male_villager")).orElseThrow();
        var mob = helper.spawn(type, new BlockPos(4, 1, 4));
        ((Villager) mob).setAge(0);
        mob.setNoAi(true);
        mob.setOnGround(true); // The synchronous fixture stands directly on the template's stone floor.
        return mob;
    }

    private static ServerPlayer player(GameTestHelper helper, List<Packet<?>> sent) {
        var level = helper.getLevel();
        var profile = new GameProfile(UUID.randomUUID(), "crime-062-test");
        var player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND),
                player, CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) { sent.add(packet); }
            @Override public void send(Packet<?> packet, PacketSendListener listener) { sent.add(packet); }
        };
        position(helper, player, 6);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static void position(GameTestHelper helper, ServerPlayer player, int x) {
        var pos = helper.absolutePos(new BlockPos(x, 1, 4));
        player.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
    }

    /** Only the test fixture touches login indexes; getPlayers() is an unmodifiable view in NeoForge. */
    @SuppressWarnings("unchecked")
    private static AutoCloseable online(ServerPlayer player) throws ReflectiveOperationException {
        var playerList = player.serverLevel().getServer().getPlayerList();
        var listField = PlayerList.class.getDeclaredField("players");
        var mapField = PlayerList.class.getDeclaredField("playersByUUID");
        listField.setAccessible(true);
        mapField.setAccessible(true);
        var players = (List<ServerPlayer>) listField.get(playerList);
        var byId = (Map<UUID, ServerPlayer>) mapField.get(playerList);
        if (byId.containsKey(player.getUUID())) throw new IllegalStateException("Fixture UUID is already online");
        players.add(player);
        byId.put(player.getUUID(), player);
        return () -> {
            byId.remove(player.getUUID(), player);
            players.remove(player);
        };
    }

    private static void hearts(GameTestHelper helper, ServerPlayer player, Mob villager, int value) {
        McaCompat.addHearts(player, villager, value - McaCompat.getHearts(player, villager));
        helper.assertTrue(McaCompat.getHearts(player, villager) == value, "MCA heart fixture did not reach " + value);
    }

    private static ActionMenuS2CPacket lastMenu(List<Packet<?>> sent) {
        return sent.stream().filter(p -> p instanceof ClientboundCustomPayloadPacket)
                .map(p -> ((ClientboundCustomPayloadPacket) p).payload())
                .filter(p -> p instanceof ActionMenuS2CPacket).map(p -> (ActionMenuS2CPacket) p)
                .reduce((old, next) -> next).orElseThrow();
    }

    @GameTest(template = "cell_parity", timeoutTicks = 1300)
    public static void oneUnarmedHitAllowsApologyThroughRefusedInteractionAfterOneMinute(GameTestHelper helper) {
        var level = helper.getLevel();
        var villager = villager(helper);
        var sent = new ArrayList<Packet<?>>();
        var player = player(helper, sent);
        var origin = villager.position();
        // Inventory weapons must not count as drawn weapons.
        player.getInventory().setItem(1, new ItemStack(Items.DIAMOND_SWORD));
        helper.assertTrue(McaCrimeConfig.COMMON.requireWeaponForCrimeMenu.get(), "Regression needs the default weapon gate");
        helper.assertTrue(villager.hurt(level.damageSources().playerAttack(player), 1), "Single punch did no damage");
        DamageIncidentService.flush(level.getServer());
        var data = CrimeWorldData.get(level.getServer());
        var memories = VictimMemoryService.memories(level.getServer(), villager.getUUID(), player.getUUID());
        helper.assertTrue(memories.size() == 1 && memories.getFirst().repeatCount() == 1,
                "A single punch did not produce exactly one victim memory");
        helper.assertTrue(data.recordsForOffender(player.getUUID()).size() == 1, "Single hit duplicated its charge");
        var handler = new ApologizeActionHandler();
        var actor = new PlayerActor(player);
        helper.assertTrue(handler.evaluate(actor, villager, level, level.getGameTime()).reason()
                .equals("mcacrime.apologize.give_space"), "Fresh assault did not explain the initial wait");

        var ordinary = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager);
        NeoForge.EVENT_BUS.post(ordinary);
        helper.assertTrue(ordinary.isCanceled(), "Frightened villager unexpectedly accepted ordinary conversation");
        helper.assertTrue(sent.stream().anyMatch(p -> p instanceof ClientboundSystemChatPacket chat
                && chat.content().getContents() instanceof TranslatableContents text
                && text.getKey().equals("mcacrime.apologize.hint")), "Refusal omitted the apology shortcut");
        player.setShiftKeyDown(true);
        var shortcut = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager);
        NeoForge.EVENT_BUS.post(shortcut);
        helper.assertTrue(shortcut.isCanceled(), "Reconciliation shortcut fell through to MCA");
        var waitingMenu = lastMenu(sent);
        helper.assertTrue(waitingMenu.actions().stream().anyMatch(row -> row.actionId().equals(CrimeActionIds.APOLOGIZE)
                && !row.available() && row.reasonKey().equals("mcacrime.apologize.give_space")),
                "Unarmed menu omitted the apology or gave the wrong waiting reason");
        helper.assertTrue(waitingMenu.actions().stream().noneMatch(row -> ActionHandlerRegistry.get(row.actionId()).coercive()),
                "Unarmed menu exposed coercive actions");
        var forgedMug = new StartActionC2SPacket(UUID.randomUUID(), waitingMenu.menuId(), waitingMenu.revision(),
                CrimeActionIds.MUG, villager.getUUID());
        helper.assertTrue(!CrimeActionService.startFromMenu(player, forgedMug).accepted(), "Unoffered mugging was accepted");
        sent.clear();
        var offhand = new PlayerInteractEvent.EntityInteract(player, InteractionHand.OFF_HAND, villager);
        NeoForge.EVENT_BUS.post(offhand);
        helper.assertTrue(offhand.isCanceled() && sent.isEmpty(), "Off-hand interaction reopened or bypassed reconciliation");

        helper.runAfterDelay(1201, () -> {
            try {
                villager.setPos(origin);
                position(helper, player, 6);
                // Reopening must issue a fresh, usable menu after the wait and the old menu's TTL.
                NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager));
                var menu = lastMenu(sent);
                helper.assertTrue(menu.actions().stream().anyMatch(row -> row.actionId().equals(CrimeActionIds.APOLOGIZE)
                        && row.available()), "One-hit apology remained unavailable after a minute");
                int beforeHearts = McaCompat.getHearts(player, villager);
                var request = new StartActionC2SPacket(UUID.randomUUID(), menu.menuId(), menu.revision(),
                        CrimeActionIds.APOLOGIZE, villager.getUUID());
                helper.assertTrue(CrimeActionService.startFromMenu(player, request).accepted(), "Valid apology was rejected");
                var accepted = VictimMemoryService.memories(level.getServer(), villager.getUUID(), player.getUUID()).getFirst();
                helper.assertTrue(accepted.apologized(), "Accepted apology did not update victim memory");
                int expectedHearts = beforeHearts < 0 ? beforeHearts + 1 : beforeHearts;
                helper.assertTrue(McaCompat.getHearts(player, villager) == expectedHearts, "Apology repaired hearts incorrectly");
                helper.assertTrue(CrimeActionService.startFromMenu(player, request).accepted(), "Same-nonce retry lost its result");
                helper.assertTrue(McaCompat.getHearts(player, villager) == expectedHearts, "Retry farmed hearts");
                helper.assertTrue(handler.evaluate(actor, villager, level, level.getGameTime()).reason()
                        .equals("mcacrime.apologize.already_apologized"), "Repeated apology did not explain prior acceptance");
                helper.assertTrue(data.recordsForOffender(player.getUUID()).size() == 1
                        && data.recordsForOffender(player.getUUID()).getFirst().resolution()
                        == dev.otectus.mcacrime.ledger.Resolution.UNRESOLVED, "Apology erased the legal charge");
                helper.succeed();
            } finally {
                CrimeActionService.forgetMenu(player.getUUID());
                ActionSessionManager.forgetActor(player.getUUID());
                CrimeReactionService.clear(level, villager.getUUID());
                villager.discard(); player.discard();
            }
        });
    }

    @GameTest(template = "cell_parity")
    public static void apologiesDistinguishHeldWeaponsOtherPlayersThreatsAndDisabledSettings(GameTestHelper helper) {
        var level = helper.getLevel();
        var villager = villager(helper);
        var player = player(helper, new ArrayList<>());
        var handler = new ApologizeActionHandler();
        var actor = new PlayerActor(player);
        var apologies = McaCrimeConfig.COMMON.enableApologies;
        boolean previous = apologies.get();
        var threat = new ActionSession(UUID.randomUUID(), UUID.randomUUID(), CrimeActionIds.MUG, UUID.randomUUID(),
                villager.getUUID(), level.dimension().location(), player.position(), level.getGameTime(), 100);
        try {
            CrimeActionService.bootstrap();
            for (var hand : InteractionHand.values()) {
                player.setItemInHand(hand, new ItemStack(Items.IRON_SWORD));
                helper.assertTrue(handler.evaluate(actor, villager, level, level.getGameTime()).reason()
                        .equals("mcacrime.apologize.lower_weapon"), "Held weapon not rejected in " + hand);
                player.setItemInHand(hand, ItemStack.EMPTY);
            }
            helper.assertTrue(ActionSessionManager.begin(threat), "Threat fixture failed to acquire target");
            helper.assertTrue(handler.evaluate(actor, villager, level, level.getGameTime()).reason()
                    .equals("mcacrime.apologize.active_threat"), "Another actor's threat was mislabeled as a held weapon");
            ActionSessionManager.finish(threat, ActionResult.accepted("test"));
            apologies.set(false);
            helper.assertTrue(handler.evaluate(actor, villager, level, level.getGameTime()).reason()
                    .equals(ApologyStatus.DISABLED.reason()), "Disabled apologies were described as missing history");
        } finally {
            apologies.set(previous);
            ActionSessionManager.finish(threat, ActionResult.accepted("test"));
            ActionSessionManager.forgetActor(threat.actorId());
            villager.discard(); player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "cell_parity")
    public static void realMcaHeartsProtectEachPlayerAtTheConfiguredBoundary(GameTestHelper helper) {
        var level = helper.getLevel();
        var thief = villager(helper);
        var player = player(helper, new ArrayList<>());
        var stranger = player(helper, new ArrayList<>());
        var jobs = WorldCriminalJobService.of(level.getServer());
        var threshold = McaCrimeConfig.COMMON.thiefMugProtectionHearts;
        int previous = threshold.get();
        try {
            jobs.assign(thief.getUUID(), CriminalJob.THIEF, false);
            threshold.set(50);
            hearts(helper, player, thief, 49);
            helper.assertTrue(NpcMuggingService.canTarget(level, thief, player), "49 hearts unexpectedly protected");
            hearts(helper, player, thief, 50);
            helper.assertTrue(!NpcMuggingService.canTarget(level, thief, player), "50 hearts did not protect");
            helper.assertTrue(NpcMuggingService.begin(level, thief, player).isEmpty(), "Protected mugging started");
            helper.assertTrue(NpcMuggingService.canTarget(level, thief, stranger), "Friendship protected a different player");
            threshold.set(75);
            helper.assertTrue(NpcMuggingService.canTarget(level, thief, player), "Custom threshold was ignored");
            hearts(helper, player, thief, 75);
            helper.assertTrue(!NpcMuggingService.canTarget(level, thief, player), "Custom boundary was not inclusive");
            threshold.set(0);
            helper.assertTrue(!NpcMuggingService.canTarget(level, thief, stranger), "Zero threshold did not protect neutral hearts");
            threshold.set(-1);
            helper.assertTrue(NpcMuggingService.canTarget(level, thief, player), "Disabled relationship protection still applied");
            player.setGameMode(GameType.CREATIVE);
            helper.assertTrue(!NpcMuggingService.canTarget(level, thief, player), "Disabled threshold bypassed creative protection");
        } finally {
            threshold.set(previous);
            NpcMuggingService.abort(player.getUUID(), NpcMugAbortReason.CANCELLED);
            jobs.set(thief.getUUID(), CriminalJob.NONE);
            thief.discard(); player.discard(); stranger.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "cell_parity")
    public static void startedListenerCannotMakeAProtectedMuggingProceed(GameTestHelper helper) {
        var level = helper.getLevel();
        var thief = villager(helper);
        var player = player(helper, new ArrayList<>());
        var jobs = WorldCriminalJobService.of(level.getServer());
        var threshold = McaCrimeConfig.COMMON.thiefMugProtectionHearts;
        int previous = threshold.get();
        Consumer<CrimeAttemptEvent.Started> listener = event -> {
            if (event.getOffender().equals(thief.getUUID()) && event.getVictim().equals(player.getUUID()))
                hearts(helper, player, thief, 50);
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            jobs.assign(thief.getUUID(), CriminalJob.THIEF, false);
            threshold.set(50);
            hearts(helper, player, thief, 49);
            helper.assertTrue(NpcMuggingService.begin(level, thief, player).isEmpty(), "Listener's heart change was ignored");
            helper.assertTrue(McaCompat.getHearts(player, thief) == 50, "Started listener was never reached");
            helper.assertTrue(!NpcMuggingService.isVictim(player.getUUID()), "Rejected start reserved the victim");
            helper.assertTrue(NpcMuggingService.sessionForThief(thief.getUUID()).isEmpty(), "Rejected start reserved the thief");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            threshold.set(previous);
            NpcMuggingService.abort(player.getUUID(), NpcMugAbortReason.CANCELLED);
            jobs.set(thief.getUUID(), CriminalJob.NONE);
            thief.discard(); player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "cell_parity")
    public static void newlyProtectedMuggingAbortsDuringTimerAndBeforeCommit(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var thief = villager(helper);
        var sent = new ArrayList<Packet<?>>();
        var player = player(helper, sent);
        var jobs = WorldCriminalJobService.of(level.getServer());
        var threshold = McaCrimeConfig.COMMON.thiefMugProtectionHearts;
        int previous = threshold.get();
        try (var registration = online(player)) {
            jobs.assign(thief.getUUID(), CriminalJob.THIEF, false);
            player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 64));
            for (boolean finalCheck : new boolean[] {false, true}) {
                threshold.set(50);
                hearts(helper, player, thief, 49);
                var session = NpcMuggingService.begin(level, thief, player).orElseThrow();
                if (finalCheck) {
                    while (!session.complete()) session.advance();
                    threshold.set(49); // A config change on the final tick must also prevent theft.
                    NpcMuggingService.complete(session);
                } else {
                    hearts(helper, player, thief, 50);
                    NpcMuggingService.tick(level.getServer());
                }
                helper.assertTrue(session.phase() == NpcMugSession.Phase.ABORTED, "Protected session was not aborted");
                helper.assertTrue(!NpcMuggingService.isVictim(player.getUUID()), "Aborted session retained its claim");
                helper.assertTrue(player.getInventory().getItem(0).getCount() == 64, "Protected attempt stole property");
                helper.assertTrue(sent.stream().anyMatch(packet -> packet instanceof ClientboundCustomPayloadPacket payload
                        && payload.payload() instanceof ActionProgressS2CPacket notice
                        && notice.sessionId().equals(session.transactionId())
                        && notice.phase() == ActionProgressS2CPacket.Phase.CANCELLED
                        && notice.outcomeKey().equals(NpcMugAbortReason.RELATIONSHIP_PROTECTED.outcomeKey())),
                        "Abort did not close the HUD with the friendship reason");
            }
        } finally {
            threshold.set(previous);
            NpcMuggingService.abort(player.getUUID(), NpcMugAbortReason.CANCELLED);
            jobs.set(thief.getUUID(), CriminalJob.NONE);
            thief.discard(); player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "cell_parity")
    public static void commandHeatMakesAGuardApproachAndConfront(GameTestHelper helper) throws Exception {
        wantedConfrontation(helper, "guard");
    }

    @GameTest(template = "cell_parity")
    public static void commandHeatMakesAnArcherApproachAndConfront(GameTestHelper helper) throws Exception {
        wantedConfrontation(helper, "archer");
    }

    private static void scan(GameTestHelper helper) {
        var event = new ServerTickEvent.Post(() -> true, helper.getLevel().getServer());
        for (int tick = 0; tick < McaCrimeConfig.COMMON.guardScanIntervalTicks.get(); tick++)
            GuardEnforcement.onServerTick(event);
    }

    private static void wantedConfrontation(GameTestHelper helper, String profession) throws Exception {
        var level = helper.getLevel();
        var server = level.getServer();
        var guard = villager(helper);
        var sent = new ArrayList<Packet<?>>();
        var player = player(helper, sent);
        helper.assertTrue(McaCompat.setVillagerProfession(guard, ResourceLocation.fromNamespaceAndPath("mca", profession)),
                "Cannot assign MCA " + profession);
        helper.assertTrue(EntitySelectors.isAvailableResponder(guard), "MCA " + profession + " is not available");
        hearts(helper, player, guard, 75); // Friendship must not exempt somebody from law enforcement.
        position(helper, player, 11); // Outside conversation range, still on the 13-block test platform.
        var path = guard.getNavigation().createPath(player.blockPosition(), 1);
        helper.assertTrue(path != null && path.canReach(), "Fixture has no walkable route to the player");
        // Exercise the public online-player scan synchronously, with both login indexes restored afterwards.
        try (var registration = online(player)) {
            var source = player.createCommandSourceStack().withPermission(2).withSuppressedOutput();
            server.getCommands().getDispatcher().execute("crime set heat @s 100", source);
            helper.assertTrue(CrimeState.getHeat(player) == 100 && CrimeState.isWanted(player), "Command failed to make player Wanted");
            var decision = JusticeService.forGuard(level, guard, player);
            helper.assertTrue(decision.basis().contains(LegalDecision.Basis.WANTED), "Wanted did not authorize this responder");
            helper.assertTrue(decision.cases().isEmpty(), "Heat command manufactured a local case");
            scan(helper);
            helper.assertTrue(guard.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                    || !guard.getNavigation().isDone(), "Enforcement scan did not order the responder to approach");
            helper.assertTrue(GuardChallengeService.open(player.getUUID()) == null, "Challenge opened beyond conversation range");
            helper.assertTrue(guard.getTarget() == null, "Responder attacked before confronting");
            // Navigation timing is not under test: bring the player into the normal conversation radius.
            position(helper, player, 6);
            scan(helper);
            var challenge = GuardChallengeService.open(player.getUUID());
            helper.assertTrue(challenge != null && challenge.guardId().equals(guard.getUUID()), "Scan did not open a confrontation");
            helper.assertTrue(challenge.chargeCount() == 0 && !challenge.canPay(), "Heat-only stop invented charges or a fine");
            helper.assertTrue(ArrestStates.phaseOf(player) == ArrestPhase.CONFRONTED, "Arrest phase did not track the challenge");
            helper.assertTrue(!GuardChallengeService.forcePermitted(player, level.getGameTime()), "Force allowed during response window");
            helper.assertTrue(sent.stream().anyMatch(packet -> packet instanceof ClientboundCustomPayloadPacket payload
                    && payload.payload() instanceof GuardChallengeS2CPacket notice && notice.open()), "Confrontation payload was not sent");
            GuardChallengeService.menuDisplayed(player, challenge.encounterId());
            int beforeReply = sent.size();
            GuardChallengeService.respond(player, challenge.encounterId(), ChallengeResponse.ASK_CHARGES);
            var reply = sent.subList(beforeReply, sent.size()).stream()
                    .filter(packet -> packet instanceof ClientboundSystemChatPacket)
                    .map(packet -> ((ClientboundSystemChatPacket) packet).content()).findFirst().orElseThrow();
            helper.assertTrue(reply.getContents() instanceof TranslatableContents text
                    && text.getKey().equals("mcacrime.challenge.reason.wanted")
                    && text.getFallback() != null && !text.getFallback().isBlank(),
                    "Ask Charges sent a translation key without a readable fallback");
            helper.assertTrue(reply.getString().startsWith("You are Wanted because of your Heat."),
                    "Dedicated-server chat rendered a raw key instead of the Wanted explanation");
            helper.assertTrue(GuardChallengeService.open(player.getUUID()) != null
                    && ArrestStates.phaseOf(player) == ArrestPhase.CONFRONTED
                    && !GuardChallengeService.forcePermitted(player, level.getGameTime()),
                    "Asking for charges closed the encounter or enabled force");
            GuardChallengeService.respond(player, challenge.encounterId(), ChallengeResponse.REFUSE);
            CrimeState.setHeat(player, 0);
            helper.assertTrue(JusticeService.forGuard(level, guard, player).basis().contains(LegalDecision.Basis.RESISTING_ARREST),
                    "Refusal lost its authority after Heat dropped");
            helper.assertTrue(GuardChallengeService.forcePermitted(player, level.getGameTime()), "Refusal did not enable pursuit and force");
            helper.assertTrue(CrimeWorldData.get(server).actionableFor(player.getUUID()).isEmpty(), "Heat-only stop created an offense");
        } finally {
            GuardChallengeService.forget(player.getUUID());
            ArrestStates.clear(player);
            GuardEnforcement.forget(player.getUUID());
            LawHold.clear(guard.getUUID());
            guard.discard(); player.discard();
        }
        helper.succeed();
    }
}
