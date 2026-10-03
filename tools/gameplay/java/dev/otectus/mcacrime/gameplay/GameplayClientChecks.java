package dev.otectus.mcacrime.gameplay;

import dev.otectus.mcacrime.client.ClientSelfData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Real client commands and server packets; setup uses an operator in a disposable world. */
@Mod("crime_gameplay_checks")
public final class GameplayClientChecks {
    private final Minecraft mc = Minecraft.getInstance();
    private final Path output = Path.of(System.getProperty("crime.gameplay.output"));
    private final String server = System.getProperty("crime.gameplay.server", "");
    private final boolean law = Boolean.getBoolean("crime.gameplay.law");
    private final boolean resume = Boolean.getBoolean("crime.gameplay.resume");
    private final long started = System.currentTimeMillis();
    private final List<String> results = new ArrayList<>();
    private int ticks, entered, stage, before, after, deaths;
    private boolean opening, finished;
    private float targetHealth;

    public GameplayClientChecks() { MinecraftForge.EVENT_BUS.register(this); }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        ticks++;
        try {
            if (System.currentTimeMillis() - started > 240_000) throw new AssertionError("Gameplay client timed out at stage " + stage);
            if (!opening && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
                opening = true;
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(2);
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                if (!server.isEmpty()) {
                    ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(server),
                            new ServerData("Crime QA", server, false), false);
                } else {
                    mc.createWorldOpenFlows().createFreshLevel("gameplay-checks",
                            new LevelSettings("Crime gameplay checks", GameType.SURVIVAL, false,
                                    Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                            new WorldOptions(73L, false, false), registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                                    .getOrThrow(WorldPresets.FLAT).createWorldDimensions());
                }
                entered = ticks;
            }
            if (mc.player != null && mc.level != null && mc.screen != null && mc.screen.isPauseScreen()) {
                mc.setScreen(null);
            }
            if (mc.player == null || mc.level == null || ticks - entered < 80) return;
            if (resume) {
                require(ClientSelfData.karma() == -2345 && ClientSelfData.heat() > 0 && ClientSelfData.heat() <= 37,
                        "saved crime state was not synchronized on reconnect: " + ClientSelfData.karma() + "/" + ClientSelfData.heat());
                pass("persisted karma and heat restored on a new client connection after server restart");
                finish();
                return;
            }
            if (law) { lawChecks(); return; }
            switch (stage) {
                case 0 -> {
                    mc.setScreen(null);
                    command("gamerule doImmediateRespawn true");
                    command("gamerule keepInventory true");
                    command("clear @s");
                    command("give @s minecraft:emerald 256");
                    command("crime set karma @s -1234");
                    command("crime set heat @s 40");
                    next();
                }
                case 1 -> {
                    require(ClientSelfData.karma() == -1234 && ClientSelfData.heat() > 0 && ClientSelfData.heat() <= 40,
                            "operator changes failed to synchronize: " + ClientSelfData.karma() + "/" + ClientSelfData.heat());
                    before = emeralds();
                    require(before == 256, "inventory setup failed: " + before);
                    pass("command changes synchronized to the live client");
                    command("crime payfine");
                    next();
                }
                case 2 -> {
                    require(emeralds() == before && ClientSelfData.heat() > 0,
                            "outlaw fine refusal lost currency or cleared heat");
                    pass("outlaw fine refusal preserved currency and heat");
                    command("crime set karma @s -12");
                    command("crime payfine");
                    next();
                }
                case 3 -> {
                    after = emeralds();
                    require(ClientSelfData.heat() == 0 && after == before - 48,
                            "fine did not clear heat and debit inventory: " + ClientSelfData.heat() + "/" + after);
                    pass("fine cleared heat and synchronized currency debit");
                    command("crime payfine");
                    next();
                }
                case 4 -> {
                    require(emeralds() == after, "repeated fine charged twice");
                    pass("repeated fine did not debit settled debt");
                    command("crime set heat @s 73");
                    next();
                }
                case 5 -> { ClientSelfData.clear(); command("kill @s"); next(); }
                case 6 -> {
                    require(mc.player.isAlive() && ClientSelfData.karma() == -12
                            && ClientSelfData.heat() > 0 && ClientSelfData.heat() <= 73,
                            "death erased heat or respawn failed: " + ClientSelfData.heat());
                    require(emeralds() == after, "keepInventory lost currency across death");
                    if (++deaths < 3) {
                        ClientSelfData.clear();
                        command("kill @s");
                        entered = ticks;
                        return;
                    }
                    pass("three deaths and automatic respawns preserved and freshly synchronized karma, heat and currency");
                    command("crime assignjail ~ ~ ~ 6");
                    command("crime jail @s 1200");
                    next();
                }
                case 7 -> {
                    require(ClientSelfData.jailRemainingTicks() > 0, "jail sentence did not synchronize");
                    pass("jail command established a synchronized active sentence");
                    command("crime release @s");
                    next();
                }
                case 8 -> {
                    require(ClientSelfData.jailRemainingTicks() == 0, "release left stale client sentence");
                    pass("release cleared the client sentence");
                    command("crime set karma @s 0");
                    command("crime set heat @s 0");
                    command("summon mca:male_villager ~2 ~ ~ {NoAI:1b,PersistenceRequired:1b,VillagerData:{profession:\"minecraft:farmer\",level:1,type:\"minecraft:plains\"},CustomName:'{\"text\":\"Crime QA target\"}'}");
                    command("summon mca:female_villager ~ ~ ~2 {NoAI:1b,PersistenceRequired:1b}");
                    next();
                }
                case 9 -> {
                    var target = target();
                    targetHealth = target.getHealth();
                    mc.gameMode.attack(mc.player, target);
                    next();
                }
                case 10 -> {
                    require(target().getHealth() < targetHealth, "real client attack did not damage the villager");
                    require(ClientSelfData.karma() < 0 && ClientSelfData.heat() > 0,
                            "witnessed assault did not synchronize karma and heat");
                    pass("real client attack produced a witnessed assault and synchronized karma/heat");
                    command("kill @e[type=mca:male_villager]");
                    command("crime set karma @s 0");
                    command("crime set heat @s 0");
                    command("item replace entity @s armor.head with mcacrime:leather_mask");
                    command("summon mca:male_villager ~2 ~ ~ {NoAI:1b,PersistenceRequired:1b,VillagerData:{profession:\"minecraft:farmer\",level:1,type:\"minecraft:plains\"},CustomName:'{\"text\":\"Crime QA target\"}'}");
                    next();
                }
                case 11 -> {
                    require(dev.otectus.mcacrime.mask.Masks.isMasked(mc.player), "equipped mask did not synchronize");
                    targetHealth = target().getHealth();
                    mc.gameMode.attack(mc.player, target());
                    next();
                }
                case 12 -> {
                    require(target().getHealth() < targetHealth, "masked attack did not damage the villager");
                    require(ClientSelfData.karma() < 0 && ClientSelfData.heat() == 0,
                            "mask did not defer heat while retaining karma: " + ClientSelfData.karma() + "/" + ClientSelfData.heat());
                    pass("masked real-client assault applied karma and deferred heat");
                    command("item replace entity @s armor.head with minecraft:air");
                    next();
                }
                case 13 -> {
                    require(ClientSelfData.heat() > 0, "witnessed unmask did not restore deferred heat");
                    pass("witnessed unmask synchronized deferred heat");
                    command("crime jail @s 200");
                    next();
                }
                case 14 -> {
                    require(ClientSelfData.jailRemainingTicks() > 0, "short sentence did not begin");
                    next();
                }
                case 15 -> { next(); }
                case 16 -> {
                    require(ClientSelfData.jailRemainingTicks() == 0 && !ClientSelfData.jailPaused(),
                            "completed sentence did not release the prisoner");
                    pass("served sentence expired and synchronized automatic release");
                    command("crime set karma @s -2345");
                    command("crime set heat @s 37");
                    next();
                }
                case 17 -> {
                    require(ClientSelfData.karma() == -2345 && ClientSelfData.heat() > 0, "persistence checkpoint failed");
                    Screenshot.grab(output.toFile(), "gameplay.png", mc.getMainRenderTarget(), message -> {});
                    next();
                }
                case 18 -> {
                    if (server.isEmpty()) { finish(); return; }
                    command("deop CrimeQa");
                    next();
                }
                case 19 -> { command("crime set heat @s 0"); next(); }
                case 20 -> {
                    require(ClientSelfData.heat() > 0, "non-operator changed protected crime state");
                    pass("non-operator cannot use administrative heat command");
                    finish();
                }
                default -> throw new AssertionError("Unknown stage " + stage);
            }
        } catch (Throwable failure) {
            finished = true;
            System.err.println("Gameplay failure context: screen=" + (mc.screen == null ? "none" : mc.screen.getClass().getName())
                    + " paused=" + mc.isPaused() + " stage=" + stage);
            failure.printStackTrace();
            try { Files.writeString(output.resolve("FAIL.txt"), failure.toString()); } catch (Exception ignored) { }
            mc.stop();
        }
    }

    private void lawChecks() throws Exception {
        switch (stage) {
            case 0 -> {
                command("time set day");
                command("crime assignjail ~ ~ ~ 6");
                command("crime set karma @s -1234");
                command("crime set heat @s 100");
                command("summon mca:male_villager ~12 ~ ~ {PersistenceRequired:1b,VillagerData:{profession:\"mca:guard\",level:1,type:\"minecraft:plains\"}}");
                next();
            }
            case 1 -> {
                if (!dev.otectus.mcacrime.client.ClientChallengeData.active()) {
                    require(ticks - entered < 400, "guard did not walk over and confront the wanted player");
                    return;
                }
                pass("live guard walked from twelve blocks away and opened a synchronized confrontation");
                dev.otectus.mcacrime.client.ClientChallengeData.menuDisplayed();
                answer(dev.otectus.mcacrime.enforcement.ChallengeResponse.ASK_CHARGES, false);
                next();
            }
            case 2 -> {
                require(dev.otectus.mcacrime.client.ClientChallengeData.active(), "asking for charges closed the confrontation");
                pass("asking for charges retained the live confrontation");
                answer(dev.otectus.mcacrime.enforcement.ChallengeResponse.SURRENDER, true);
                next();
            }
            case 3 -> {
                require(dev.otectus.mcacrime.client.ClientChallengeData.active() && ClientSelfData.jailRemainingTicks() == 0,
                        "forged encounter identity affected custody");
                pass("forged surrender packet could not change the active encounter");
                answer(dev.otectus.mcacrime.enforcement.ChallengeResponse.SURRENDER, false);
                next();
            }
            case 4 -> {
                if (ClientSelfData.jailRemainingTicks() == 0) {
                    require(ticks - entered < 400, "valid surrender did not lead to imprisonment");
                    return;
                }
                pass("valid client surrender produced guard custody and a synchronized sentence");
                Screenshot.grab(output.toFile(), "guard-custody.png", mc.getMainRenderTarget(), message -> {});
                command("crime release @s");
                command("kill @e[type=mca:male_villager]");
                command("crime set karma @s 0");
                command("crime set heat @s 0");
                // NBT on summon skips MCA age initialization; freeze it after a normal spawn.
                command("summon mca:male_villager ~2 ~ ~");
                command("data merge entity @e[type=mca:male_villager,sort=nearest,limit=1] {NoAI:1b,PersistenceRequired:1b,CustomName:'{\"text\":\"Crime QA target\"}'}");
                next();
            }
            case 5 -> { command("crime job assign thief"); next(); }
            case 6 -> {
                require(dev.otectus.mcacrime.compat.McaCompat.getProfessionId(target()).map(Object::toString)
                        .orElse("").equals("mcacrime:thief"), "thief command did not synchronize its native profession");
                pass("operator thief recruitment synchronized the native MCA profession");
                command("crime job clear");
                next();
            }
            case 7 -> {
                require(!dev.otectus.mcacrime.compat.McaCompat.getProfessionId(target()).map(Object::toString)
                        .orElse("").equals("mcacrime:thief"), "retired thief kept its native profession");
                pass("thief retirement cleared the native MCA profession");
                command("crime set karma @s -2345");
                command("crime set heat @s 37");
                next();
            }
            case 8 -> { finish(); }
            default -> throw new AssertionError("Unknown law stage " + stage);
        }
    }

    private void answer(dev.otectus.mcacrime.enforcement.ChallengeResponse response, boolean forged) {
        var current = dev.otectus.mcacrime.client.ClientChallengeData.current();
        require(current != null, "no challenge to answer");
        var packet = new dev.otectus.mcacrime.network.GuardChallengeResponseC2SPacket(
                forged ? java.util.UUID.randomUUID() : current.encounterId(), response, current.revision());
        dev.otectus.mcacrime.network.CrimeNetwork.CHANNEL.sendToServer(packet);
    }

    private net.minecraft.world.entity.LivingEntity target() {
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof net.minecraft.world.entity.LivingEntity living && living.isAlive()
                    && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                            .toString().equals("mca:male_villager") && entity.distanceToSqr(mc.player) < 16) return living;
        }
        throw new AssertionError("summoned target is missing from the client world");
    }

    private void command(String command) { mc.player.connection.sendCommand(command); }
    private int emeralds() {
        return mc.player.getInventory().items.stream().filter(s -> s.is(Items.EMERALD)).mapToInt(s -> s.getCount()).sum();
    }
    private void pass(String result) { results.add("PASS " + result); }
    private void next() { stage++; entered = ticks; }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void finish() throws Exception {
        Files.writeString(output.resolve("PASS.txt"), String.join("\n", results) + "\n");
        finished = true;
        mc.stop();
    }
}
