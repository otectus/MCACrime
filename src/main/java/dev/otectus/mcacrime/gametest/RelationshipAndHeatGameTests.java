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
        // 0.7.7: the first light hit is forgiven as minor friendly fire, which would leave nothing to
        // apologize for. This regression is about the apology, so the grace is off for its duration.
        var grace = McaCrimeConfig.COMMON.enableMinorHarmForgiveness;
        boolean gracePrevious = grace.get();
        grace.set(false);
        try {
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
        // 0.7.2 §12.2: the unarmed gesture is the apology, so a refusal names the wait the player is
        // actually in rather than the generic "you could apologize" hint.
        helper.assertTrue(sent.stream().anyMatch(p -> p instanceof ClientboundSystemChatPacket chat
                && chat.content().getContents() instanceof TranslatableContents text
                && text.getKey().equals("mcacrime.apologize.give_space")),
                "Refusal did not explain the wait it is in");
        // Sneaking is left to other mods (§12.2); it claims the click through the villager's own
        // refusal but no longer opens the Crime menu, which keeps its keybind and screen button.
        player.setShiftKeyDown(true);
        var shortcut = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager);
        NeoForge.EVENT_BUS.post(shortcut);
        helper.assertTrue(shortcut.isCanceled(), "Reconciliation shortcut fell through to MCA");
        helper.assertTrue(sent.stream().noneMatch(p -> p instanceof ClientboundCustomPayloadPacket payload
                && payload.payload() instanceof ActionMenuS2CPacket), "Sneaking still opened the Crime menu");
        helper.assertTrue(CrimeActionService.openMenu(player, villager.getUUID()),
                "The Crime menu is no longer reachable through its own entry point");
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
                helper.assertTrue(CrimeActionService.openMenu(player, villager.getUUID()),
                        "The Crime menu could not be reopened after the wait");
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
                // 0.7.7: an accepted apology reopens ordinary conversation at once for something that
                // is not grave. Before, fear and anger alone still refused this click, and the refusal
                // told the player to apologize.
                var afterApology = VictimMemoryService.memories(level.getServer(), villager.getUUID(), player.getUUID()).getFirst();
                helper.assertTrue(afterApology.fear() >= 0.45 || afterApology.anger() >= 0.6,
                        "The regression needs a memory still above the old refusal thresholds");
                sent.clear();
                var ordinary2 = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager);
                NeoForge.EVENT_BUS.post(ordinary2);
                helper.assertTrue(!ordinary2.isCanceled(), "An accepted apology for one punch still refused conversation");
                helper.assertTrue(sent.stream().noneMatch(RelationshipAndHeatGameTests::isRefusalChat),
                        "The reopened conversation still sent a refusal or the apology hint");
                helper.succeed();
            } finally {
                grace.set(gracePrevious);
                CrimeActionService.forgetMenu(player.getUUID());
                ActionSessionManager.forgetActor(player.getUUID());
                CrimeReactionService.clear(level, villager.getUUID());
                villager.discard(); player.discard();
            }
        });
        } catch (Throwable failure) {
            grace.set(gracePrevious);
            throw failure;
        }
    }

    private static boolean isRefusalChat(Packet<?> packet) {
        return packet instanceof ClientboundSystemChatPacket chat
                && chat.content().getContents() instanceof TranslatableContents text
                && (text.getKey().startsWith("mcacrime.apologize.") || text.getKey().startsWith("dialogue.mcacrime.memory_"));
    }

    private static boolean isChat(Packet<?> packet) {
        return packet instanceof ClientboundSystemChatPacket;
    }

    @SuppressWarnings("unchecked")
    private static Mob villagerAt(GameTestHelper helper, BlockPos at) {
        var type = (EntityType<? extends Mob>) BuiltInRegistries.ENTITY_TYPE.getOptional(
                ResourceLocation.fromNamespaceAndPath("mca", "male_villager")).orElseThrow();
        var mob = helper.spawn(type, at);
        ((Villager) mob).setAge(0);
        mob.setNoAi(true);
        mob.setOnGround(true);
        return mob;
    }

    /** 0.7.7: a villager refuses only the player it remembers; a bystander's click is ordinary. */
    @GameTest(template = "cell_parity")
    public static void aSecondPlayerIsNeverRefusedForAnotherPlayersCrime(GameTestHelper helper) {
        var level = helper.getLevel();
        var villager = villager(helper);
        var offender = player(helper, new ArrayList<>());
        var bystanderPackets = new ArrayList<Packet<?>>();
        var bystander = player(helper, bystanderPackets);
        position(helper, bystander, 3);
        var data = CrimeWorldData.get(level.getServer());
        var grace = McaCrimeConfig.COMMON.enableMinorHarmForgiveness;
        boolean previous = grace.get();
        try {
            grace.set(false);
            helper.assertTrue(villager.hurt(level.damageSources().playerAttack(offender), 1), "The punch did no damage");
            DamageIncidentService.flush(level.getServer());
            helper.assertTrue(!VictimMemoryService.memories(level.getServer(), villager.getUUID(), offender.getUUID()).isEmpty(),
                    "The offender's punch left no memory, so the regression proves nothing");
            var click = new PlayerInteractEvent.EntityInteract(bystander, InteractionHand.MAIN_HAND, villager);
            NeoForge.EVENT_BUS.post(click);
            helper.assertTrue(!click.isCanceled(), "A bystander was refused for another player's crime");
            helper.assertTrue(bystanderPackets.stream().noneMatch(RelationshipAndHeatGameTests::isChat),
                    "A bystander was messaged about another player's crime");
            helper.assertTrue(data.recordsForOffender(bystander.getUUID()).isEmpty(), "A bystander was blamed");
            var own = new PlayerInteractEvent.EntityInteract(offender, InteractionHand.MAIN_HAND, villager);
            NeoForge.EVENT_BUS.post(own);
            helper.assertTrue(own.isCanceled(), "The actual offender was not refused");
        } finally {
            grace.set(previous);
            CrimeReactionService.clear(level, villager.getUUID());
            villager.discard(); offender.discard(); bystander.discard();
        }
        helper.succeed();
    }

    /** 0.7.7: a line carries dialogue.speechRadius and no farther, and never into another dimension. */
    @GameTest(template = "cell_parity")
    public static void aListenerFortyBlocksAwayReceivesNothing(GameTestHelper helper) {
        var level = helper.getLevel();
        var villager = villager(helper);
        var nearPackets = new ArrayList<Packet<?>>();
        var near = player(helper, nearPackets);
        var farPackets = new ArrayList<Packet<?>>();
        var far = player(helper, farPackets);
        var origin = helper.absolutePos(new BlockPos(4, 1, 4));
        far.setPos(origin.getX() + 40.5, origin.getY(), origin.getZ() + 0.5);
        var nether = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        var elsewherePackets = new ArrayList<Packet<?>>();
        var profile = new GameProfile(UUID.randomUUID(), "crime-077-nether");
        var elsewhere = new ServerPlayer(level.getServer(), nether, profile, ClientInformation.createDefault());
        elsewhere.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND),
                elsewhere, CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) { elsewherePackets.add(packet); }
            @Override public void send(Packet<?> packet, PacketSendListener listener) { elsewherePackets.add(packet); }
        };
        elsewhere.setPos(villager.getX(), villager.getY(), villager.getZ());
        var event = dev.otectus.mcacrime.dialogue.DialogueEvents.MUG_RECOVERY;
        try {
            helper.assertTrue(dev.otectus.mcacrime.dialogue.CrimeDialogueService.speak(villager, near, event,
                    dev.otectus.mcacrime.dialogue.CrimeDialogueService.context(level, villager, near, villager.getUUID(), event)),
                    "A listener beside the villager heard nothing, so the regression proves nothing");
            helper.assertTrue(nearPackets.stream().anyMatch(RelationshipAndHeatGameTests::isChat), "The near listener got no line");
            helper.assertTrue(!dev.otectus.mcacrime.dialogue.CrimeDialogueService.speak(villager, far, event,
                    dev.otectus.mcacrime.dialogue.CrimeDialogueService.context(level, villager, far, villager.getUUID(), event)),
                    "A listener forty blocks away was spoken to");
            helper.assertTrue(farPackets.stream().noneMatch(RelationshipAndHeatGameTests::isChat),
                    "A listener forty blocks away received a line");
            helper.assertTrue(!dev.otectus.mcacrime.dialogue.CrimeDialogueService.speak(villager, elsewhere, event,
                    dev.otectus.mcacrime.dialogue.CrimeDialogueService.context(level, villager, elsewhere, villager.getUUID(), event)),
                    "A listener in another dimension was spoken to");
            helper.assertTrue(elsewherePackets.stream().noneMatch(RelationshipAndHeatGameTests::isChat),
                    "A listener in another dimension received a line");
            // A line nobody could hear must not have stamped the pair cooldown.
            far.setPos(near.getX(), near.getY(), near.getZ());
            helper.assertTrue(dev.otectus.mcacrime.dialogue.CrimeDialogueService.speak(villager, far, event,
                    dev.otectus.mcacrime.dialogue.CrimeDialogueService.context(level, villager, far, villager.getUUID(), event)),
                    "An unheard line consumed the pair's dialogue cooldown");
        } finally {
            dev.otectus.mcacrime.dialogue.CrimeDialogueService.forget(villager.getUUID());
            villager.discard(); near.discard(); far.discard(); elsewhere.discard();
        }
        helper.succeed();
    }

    /**
     * 0.7.7 murder hearts. MCA charges twenty hearts to every villager within 32 blocks of a killing with
     * no sight check. With the native hooks applied, a neighbour behind a wall keeps its hearts, a
     * witness who saw it loses MCA's twenty exactly once (MCA: Crime adds no loss of its own on top), and
     * with murderHeartPenalty off nobody loses any.
     *
     * <p>Its own batch, because MCA's own 32-block scan reaches villagers in neighbouring test plots.
     */
    @GameTest(template = "cell_parity", batch = "mcacrime_murder_hearts", timeoutTicks = 100)
    public static void anUnseenKillingLeavesAWalledNeighboursHearts(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        helper.assertTrue(dev.otectus.mcacrime.compat.mca.NativeJusticeCapabilities.combatComplete(),
                "The native heart hooks did not apply: applied=" + dev.otectus.mcacrime.mixin.mca.McaJusticeMixinPlugin.APPLIED
                        + " degraded=" + dev.otectus.mcacrime.mixin.mca.McaJusticeMixinPlugin.DEGRADED);
        for (int x = 0; x < 13; x++) for (int z = 0; z < 13; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y < 5; y++) helper.setBlock(new BlockPos(x, y, z), x == 9 ? Blocks.STONE : Blocks.AIR);
        }
        var victim = villagerAt(helper, new BlockPos(4, 1, 4));
        var witness = villagerAt(helper, new BlockPos(4, 1, 7));
        var neighbour = villagerAt(helper, new BlockPos(11, 1, 4));
        var player = player(helper, new ArrayList<>());
        var penalty = McaCrimeConfig.COMMON.murderHeartPenalty;
        var witnessRule = McaCrimeConfig.COMMON.murderHeartsRequireWitness;
        boolean penaltyBefore = penalty.get(), ruleBefore = witnessRule.get();
        Mob second = null;
        try {
            penalty.set(true);
            witnessRule.set(true);
            helper.assertTrue(witness.hasLineOfSight(player) && !neighbour.hasLineOfSight(player)
                    && !neighbour.hasLineOfSight(victim), "The fixture's sight lines are wrong");
            int witnessBefore = McaCompat.getHearts(player, witness);
            int neighbourBefore = McaCompat.getHearts(player, neighbour);
            victim.hurt(level.damageSources().playerAttack(player), 1000F);
            helper.assertTrue(victim.isDeadOrDying(), "The victim survived the killing blow");
            DamageIncidentService.flush(server);
            helper.assertTrue(CrimeWorldData.get(server).recordsForOffender(player.getUUID()).size() == 1,
                    "The killing was not recorded exactly once");
            helper.assertTrue(McaCompat.getHearts(player, neighbour) == neighbourBefore,
                    "A walled-off neighbour lost " + (neighbourBefore - McaCompat.getHearts(player, neighbour)) + " hearts");
            helper.assertTrue(McaCompat.getHearts(player, witness) == witnessBefore - 20,
                    "The sighted witness lost " + (witnessBefore - McaCompat.getHearts(player, witness))
                            + " hearts, not MCA's twenty exactly once");

            penalty.set(false);
            second = villagerAt(helper, new BlockPos(5, 1, 6));
            int witnessMid = McaCompat.getHearts(player, witness);
            int neighbourMid = McaCompat.getHearts(player, neighbour);
            second.hurt(level.damageSources().playerAttack(player), 1000F);
            helper.assertTrue(second.isDeadOrDying(), "The second victim survived the killing blow");
            DamageIncidentService.flush(server);
            helper.assertTrue(McaCompat.getHearts(player, witness) == witnessMid
                            && McaCompat.getHearts(player, neighbour) == neighbourMid,
                    "Hearts moved with murderHeartPenalty off");
        } finally {
            penalty.set(penaltyBefore);
            witnessRule.set(ruleBefore);
            CrimeReactionService.clear(level, witness.getUUID());
            CrimeReactionService.clear(level, neighbour.getUUID());
            victim.discard(); witness.discard(); neighbour.discard(); player.discard();
            if (second != null) second.discard();
        }
        helper.succeed();
    }

    /** 0.7.7 minor friendly fire: one fist hit leaves no charge, a second inside the window is charged once. */
    @GameTest(template = "cell_parity", timeoutTicks = 100)
    public static void oneFistHitLeavesNoChargeAndASecondIsChargedOnce(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        helper.assertTrue(dev.otectus.mcacrime.compat.mca.NativeJusticeCapabilities.combatComplete(),
                "The native heart hooks did not apply, so MCA's hurt hearts cannot be held back");
        var villager = villager(helper);
        var sent = new ArrayList<Packet<?>>();
        var player = player(helper, sent);
        var data = CrimeWorldData.get(server);
        var grace = McaCrimeConfig.COMMON.enableMinorHarmForgiveness;
        boolean previous = grace.get();
        try {
            grace.set(true);
            long karmaBefore = CrimeState.getKarma(player);
            int heartsBefore = McaCompat.getHearts(player, villager);
            helper.assertTrue(villager.hurt(level.damageSources().playerAttack(player), 1), "The first punch did no damage");
            DamageIncidentService.flush(server);
            helper.assertTrue(data.recordsForOffender(player.getUUID()).isEmpty(), "A forgiven punch created a case");
            helper.assertTrue(VictimMemoryService.memories(server, villager.getUUID(), player.getUUID()).isEmpty(),
                    "A forgiven punch created a victim memory");
            helper.assertTrue(CrimeState.getKarma(player) == karmaBefore, "A forgiven punch cost Karma");
            helper.assertTrue(McaCompat.getHearts(player, villager) == heartsBefore, "A forgiven punch cost MCA hearts");
            helper.assertTrue(sent.stream().anyMatch(packet -> packet instanceof ClientboundSystemChatPacket chat
                            && chat.content().getContents() instanceof TranslatableContents text
                            && text.getKey().equals("mcacrime.minor_harm.warned")),
                    "The attacker was not warned that the next hit counts");
            villager.invulnerableTime = 0;
            helper.assertTrue(villager.hurt(level.damageSources().playerAttack(player), 1), "The second punch did no damage");
            DamageIncidentService.flush(server);
            helper.assertTrue(data.recordsForOffender(player.getUUID()).size() == 1, "The second punch was not charged exactly once");
            helper.assertTrue(VictimMemoryService.memories(server, villager.getUUID(), player.getUUID()).size() == 1,
                    "The charged punch did not leave one memory");
            helper.assertTrue(CrimeState.getKarma(player) < karmaBefore, "The charged punch cost no Karma");
            helper.assertTrue(McaCompat.getHearts(player, villager) < heartsBefore, "The charged punch did not cost MCA's hearts");
        } finally {
            grace.set(previous);
            CrimeReactionService.clear(level, villager.getUUID());
            villager.discard(); player.discard();
        }
        helper.succeed();
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
                // 0.7.0 stamps victim protection however a mugging ended, so the second pass needs a
                // victim this thief is not still barred from.
                var protection = dev.otectus.mcacrime.state.CrimeAttachments.get(player);
                protection.setMugProtectionUntilTick(0L);
                protection.recentMuggers().clear();
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
