package dev.otectus.mcacrime.gameplay;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.client.ClientPhysicalRestraintData;
import dev.otectus.mcacrime.client.ClientSelfData;
import dev.otectus.mcacrime.client.CrimeKeybinds;
import dev.otectus.mcacrime.client.screen.CrimeInteractionScreen;
import dev.otectus.mcacrime.network.ActionMenuS2CPacket;
import dev.otectus.mcacrime.network.StartActionC2SPacket;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Additional client-observed checks. Commands and packets go through the ordinary server handlers. */
final class ExtendedScenarioChecks {
    private record Message(String text, List<String> keys) {}
    private record Step(int delay, Runnable body) {}
    private static final List<Message> MESSAGES = new ArrayList<>();

    static void received(Component message) {
        List<String> keys = new ArrayList<>();
        collect(message, keys);
        MESSAGES.add(new Message(message.getString(), keys));
    }

    private static void collect(Component value, List<String> keys) {
        if (value.getContents() instanceof TranslatableContents t) {
            keys.add(t.getKey());
            for (Object arg : t.getArgs()) if (arg instanceof Component c) collect(c, keys);
        }
        for (Component sibling : value.getSiblings()) collect(sibling, keys);
    }

    private final Minecraft mc;
    private final Path output;
    private final Consumer<String> pass;
    private final Runnable finish;
    private final List<Step> steps = new ArrayList<>();
    private int index, entered = -1, mark, currency;
    private BlockPos home;
    private StartActionC2SPacket replay;
    private float health;
    private long pvpKarma;
    private net.minecraft.world.entity.player.Player victimBeforeDeath;

    ExtendedScenarioChecks(Minecraft mc, String role, Path output, Consumer<String> pass, Runnable finish) {
        this.mc = mc; this.output = output; this.pass = pass; this.finish = finish;
        if (role.startsWith("extended-pvp-")) {
            pvp(role);
        } else if (role.startsWith("extended-permission-")) {
            permissions(Integer.parseInt(role.substring("extended-permission-".length())));
        } else {
            add(1, () -> {
                home = mc.player.blockPosition();
                commands("gamerule doDaylightCycle false", "gamerule doMobSpawning false", "time set day",
                        "crime set karma @s 0", "crime set heat @s 0", "clear @s");
            });
            switch (role) {
                case "extended-audit" -> audit();
                case "extended-physical" -> physical();
                case "extended-restraint-resume" -> restraintResume();
                case "extended-bail" -> bail();
                case "extended-npc-restraints" -> npcRestraints();
                case "extended-mug" -> mug();
                case "extended-guard-force" -> guardForce();
                case "extended-locks" -> locks();
                default -> throw new AssertionError("Unknown extended role " + role);
            }
            add(20, () -> commands("crime set karma @s -2345", "crime set heat @s 37"));
            add(20, () -> await(() -> ClientSelfData.karma() == -2345 && ClientSelfData.heat() == 37));
            add(1, () -> {
                require(ClientSelfData.karma() == -2345 && ClientSelfData.heat() == 37, "restart checkpoint not synchronized");
                finish.run();
            });
        }
    }

    int step() { return index; }

    void tick(int now) {
        if (entered < 0) entered = now;
        if (index >= steps.size()) return;
        Step current = steps.get(index);
        if (now - entered < current.delay) return;
        current.body.run();
        index++;
        entered = now;
    }

    private void add(int delay, Runnable action) { steps.add(new Step(delay, action)); }

    private void audit() {
        add(20, () -> { mark = MESSAGES.size(); commands("crime validate", "crime debug integrations", "crime status"); });
        add(25, () -> {
            require(saw("mcacrime.command.validate.ok"), "default config validation failed: " + messages());
            require(messages().contains("guard alert gated"), "native guard hook missing: " + messages());
            require(messages().contains("locks reforged: installed=") && messages().contains("reputation: installed="), "integration report missing: " + messages());
            record("A3/A4 default config and native guard hooks validated through real client commands: " + messages());
        });
        long[] karma = {100, 99, -99, -100};
        String[] band = {"BLUE", "GREY", "GREY", "RED"};
        for (int i = 0; i < karma.length; i++) {
            final int n = i;
            final int heat = i % 2 == 0 ? 49 : 50;
            add(1, () -> commands("crime set karma @s " + karma[n], "crime set heat @s " + heat));
            add(12, () -> {
                require(ClientSelfData.karma() == karma[n] && ClientSelfData.band().name().equals(band[n]), "Karma band boundary mismatch");
                require(ClientSelfData.heat() == heat && ClientSelfData.wanted() == (heat >= 50), "Wanted boundary mismatch: " + ClientSelfData.heat());
                record("B1/B2 synchronized Karma=" + karma[n] + " band=" + band[n] + " Heat=" + heat + " Wanted=" + ClientSelfData.wanted());
            });
        }
        add(1, () -> { mc.setScreen(null); KeyMapping.click(CrimeKeybinds.OPEN_DOSSIER.getKey()); });
        add(20, () -> {
            require(mc.screen != null && mc.screen.getClass().getSimpleName().equals("CaseDossierScreen"), "K did not open dossier");
            screenshot("extended-dossier.png"); record("B5 K opened the dossier"); mc.setScreen(null);
            KeyMapping.click(CrimeKeybinds.OPEN_SELF_PANEL.getKey());
        });
        add(20, () -> {
            require(mc.screen instanceof CrimeInteractionScreen, "J did not open a server-populated self panel: " + mc.screen);
            screenshot("extended-self-panel.png"); record("B5 J opened the server-populated self panel");
            mc.setScreen(new InventoryScreen(mc.player));
        });
        add(20, () -> {
            var button = mc.screen.children().stream().filter(w -> w.getClass().getSimpleName().equals("PlayerCardButton")).findFirst();
            require(button.isPresent() == McaCrimeConfig.CLIENT.showPlayerCardButton.get(), "player-card button ignores config");
            if (button.isPresent()) ((AbstractButton)button.get()).onPress();
        });
        add(20, () -> {
            screenshot("extended-inventory-card.png"); record("B5 inventory player-card visibility matches client config"); mc.setScreen(null);
            commands("crime set karma @s 0", "crime set heat @s 120", "crime assignjail ~ ~ ~ 6", "crime jail @s 2400");
        });
        add(30, () -> {
            require(ClientSelfData.jailRemainingTicks() > 0, "administrative jail did not synchronize");
            mark = MESSAGES.size(); command("crime status");
        });
        add(20, () -> {
            require(messages().toLowerCase().contains("sentence") || messages().toLowerCase().contains("jailed"), "sentence status absent: " + messages());
            record("E5 jailed status reached the client: " + messages());
            command("crime release @s");
        });
        add(30, () -> {
            require(ClientSelfData.jailRemainingTicks() == 0 && ClientSelfData.heat() >= 119, "admin release pardoned Heat or retained jail");
            record("E4 administrative release ended jail and retained Heat");
            command("crime release @s");
        });
        add(20, () -> {
            require(ClientSelfData.jailRemainingTicks() == 0 && ClientSelfData.heat() >= 119, "repeated release changed sentence Heat");
            record("E4 repeated administrative release did not forgive Heat");
            mark = MESSAGES.size(); command("crime repair @s inspect");
        });
        add(20, () -> {
            require(saw("mcacrime.repair.none"), "clean release left repair findings: " + messages());
            record("E9 clean administrative release has no repair findings");
            commands("gamemode spectator", "execute in minecraft:the_nether run tp @s 0 100 0");
        });
        add(30, () -> {
            require(mc.level.dimension().location().getPath().equals("the_nether") && ClientSelfData.wanted(), "dimension change lost Wanted state");
            record("J4 Wanted state survived a real Nether transfer");
            command("execute in minecraft:overworld run tp @s " + home.getX() + " " + home.getY() + " " + home.getZ());
            command("gamemode survival");
        });
    }

    private void physical() {
        String[] definitions = {"handcuffs_arms", "handcuffs_legs", "shackles_arms", "shackles_legs", "duck_tape_arms", "duck_tape_legs", "duck_tape_head"};
        for (String definition : definitions) {
            String slotName = definition.substring(definition.lastIndexOf('_') + 1);
            RestraintSlot slot = RestraintSlot.valueOf(slotName.toUpperCase(java.util.Locale.ROOT));
            add(20, () -> command("crime restraint apply @s " + definition));
            add(20, () -> { mark = MESSAGES.size(); command("crime restraint inspect @s"); });
            add(20, () -> {
                var view = ClientPhysicalRestraintData.slot(mc.player.getUUID(), slot);
                screenshot("extended-restraint-state.png");
                require(view.isPresent() && view.get().definitionId().getPath().equals(definition), "restraint not synchronized: " + definition
                        + "; client=" + ClientPhysicalRestraintData.get(mc.player.getUUID()) + "; server inspect=" + messages());
                record("G2 applied and synchronized " + definition + " in " + slotName);
                command("crime restraint apply @s " + definition);
            });
            add(20, () -> {
                require(ClientPhysicalRestraintData.get(mc.player.getUUID()).orElseThrow().slots().size() == 1, "duplicate apply added a second slot");
                commands("crime restraint remove @s " + slotName, "crime restraint remove @s " + slotName);
            });
            add(20, () -> {
                require(!ClientPhysicalRestraintData.restrained(mc.player.getUUID()), "removed restraint remained on client: " + definition);
                require(mc.player.getInventory().items.stream().allMatch(s -> s.isEmpty()), "system-issued removal minted an inventory item");
                record("G2/L2 duplicate application/removal of system-issued " + definition + " created no extra restraint or item");
            });
        }
        add(20, () -> {
            commands("summon mca:male_villager ~2 ~ ~", "data merge entity @e[type=mca:male_villager,sort=nearest,limit=1] {NoAI:1b,PersistenceRequired:1b}",
                    "give @s minecraft:diamond_sword", "crime restraint apply @s handcuffs_arms");
        });
        add(20, () -> { health = target().getHealth(); mc.gameMode.attack(mc.player, target()); });
        add(20, () -> {
            require(target().getHealth() == health, "cuffed player damaged the villager");
            record("G2 actual attack while arm-restrained dealt no damage");
            KeyMapping.click(CrimeKeybinds.OPEN_SELF_PANEL.getKey());
        });
        add(20, () -> {
            require(mc.screen instanceof CrimeInteractionScreen, "restrained self panel failed to open");
            screenshot("extended-restraint-panel.png"); mc.setScreen(null);
            command("crime restraint remove @s arms");
        });
        add(20, () -> { health = target().getHealth(); mc.gameMode.attack(mc.player, target()); });
        add(20, () -> {
            require(target().getHealth() < health, "released player still cannot attack");
            require(ClientSelfData.karma() < 0, "weapon assault did not produce Karma loss");
            record("C1/G2 removal restored actual attacks and ordinary assault consequences");
        });
    }

    private void npcRestraints() {
        add(20, this::spawnTarget);
        String[] definitions = {"handcuffs_arms", "handcuffs_legs", "shackles_arms", "shackles_legs", "duck_tape_arms", "duck_tape_legs", "duck_tape_head"};
        for (String definition : definitions) {
            String slotName = definition.substring(definition.lastIndexOf('_') + 1);
            RestraintSlot slot = RestraintSlot.valueOf(slotName.toUpperCase(java.util.Locale.ROOT));
            add(20, () -> command("crime restraint apply " + target().getUUID() + " " + definition));
            add(20, () -> {
                var state = ClientPhysicalRestraintData.slot(target().getUUID(), slot);
                require(state.isPresent() && state.get().definitionId().getPath().equals(definition), "observer did not receive " + definition);
                record("G2/K1 tracked villager synchronized " + definition);
                command("crime restraint apply " + target().getUUID() + " " + definition);
            });
            add(20, () -> {
                require(ClientPhysicalRestraintData.get(target().getUUID()).orElseThrow().slots().size() == 1, "duplicate NPC application created another restraint");
                commands("crime restraint remove " + target().getUUID() + " " + slotName,
                        "crime restraint remove " + target().getUUID() + " " + slotName);
            });
            add(20, () -> {
                require(!ClientPhysicalRestraintData.restrained(target().getUUID()), "observer retained removed " + definition);
                require(mc.player.getInventory().items.stream().allMatch(s -> s.isEmpty()), "duplicate system removal minted items");
                record("G2/L2 duplicate NPC application/removal of " + definition + " synchronized without extra items");
            });
        }
        add(20, () -> command("item replace entity @s weapon.mainhand with mcacrime:restraint_cuffs"));
        add(20, () -> { mc.setScreen(null); mc.gameMode.interactAt(mc.player, target(), new net.minecraft.world.phys.EntityHitResult(target(), target().position().add(0, target().getBbHeight() * 0.6, 0)), net.minecraft.world.InteractionHand.MAIN_HAND); });
        add(120, () -> {
            require(ClientPhysicalRestraintData.restrained(target().getUUID()), "ordinary cuff item interaction did not restrain target");
            require(mc.player.getMainHandItem().isEmpty(), "ordinary cuff application did not consume the cuff");
            require(ClientSelfData.karma() < 0, "private restraint did not charge kidnapping");
            record("G2/G3 real cuff-item interaction restrained a villager, consumed one cuff and charged private kidnapping");
            screenshot("extended-kidnap.png"); mark = MESSAGES.size(); commands("crime ledger @s", "crime releasecaptive");
        });
        add(40, () -> {
            record("G3 private custody release command response: " + messages());
            commands("crime restraint remove " + target().getUUID() + " arms", "kill @e[type=mca:male_villager]");
        });
    }

    private void mug() {
        add(20, this::spawnTarget);
        add(20, () -> send(new dev.otectus.mcacrime.network.RequestActionMenuC2SPacket(target().getUUID())));
        add(20, () -> {
            var row = menu().actions().stream().filter(a -> a.actionId().equals(McaCrime.id("mug"))).findFirst();
            require(row.isEmpty() || (!row.get().available() && row.get().reasonKey().equals("mcacrime.action.requires_weapon")), "unarmed menu permitted mugging: " + row);
            record("G1 default unarmed menu excludes coercive mug action"); mc.setScreen(null);
            command("item replace entity @s weapon.mainhand with minecraft:diamond_sword");
        });
        add(20, () -> send(new dev.otectus.mcacrime.network.RequestActionMenuC2SPacket(target().getUUID())));
        add(20, () -> {
            var menu = menu(); var row = menu.actions().stream().filter(a -> a.actionId().equals(McaCrime.id("mug"))).findFirst().orElseThrow();
            require(row.available(), "armed mug unavailable: " + row.reasonKey());
            replay = new StartActionC2SPacket(UUID.randomUUID(), menu.menuId(), menu.revision(), row.actionId(), menu.targetId());
            currency = emeralds(); command("tp @s ~20 ~ ~"); mc.setScreen(null);
        });
        add(20, () -> { mark = MESSAGES.size(); send(replay); });
        add(30, () -> {
            require(emeralds() == currency && ClientSelfData.karma() == 0, "out-of-range action committed a mugging");
            record("L1 server refused out-of-range issued mug action: " + messages()); command("tp @s ~-20 ~ ~");
        });
        add(20, () -> send(new dev.otectus.mcacrime.network.RequestActionMenuC2SPacket(target().getUUID())));
        add(20, () -> {
            var menu = menu(); replay = new StartActionC2SPacket(UUID.randomUUID(), menu.menuId(), menu.revision(), McaCrime.id("mug"), menu.targetId());
            mark = MESSAGES.size(); send(replay); mc.setScreen(null);
        });
        add(100, () -> {
            require(saw("mcacrime.mug.success") && ClientSelfData.karma() < 0, "legitimate mug failed: " + messages());
            record("G1 timed real-client mug completed and charged theft: " + messages());
            currency = emeralds(); send(replay);
        });
        add(20, () -> {
            require(emeralds() == currency, "replayed completed mug changed currency");
            send(new dev.otectus.mcacrime.network.RequestActionMenuC2SPacket(target().getUUID()));
        });
        add(20, () -> {
            var row = menu().actions().stream().filter(a -> a.actionId().equals(McaCrime.id("mug"))).findFirst().orElseThrow();
            require(!row.available() && row.reasonKey().equals("mcacrime.mug.repeat"), "same-target cooldown absent: " + row);
            record("G1/L1 completed mug cannot be replayed and same target has repeat cooldown");
            screenshot("extended-mug-cooldown.png"); mc.setScreen(null); command("kill @e[type=mca:male_villager]");
        });
    }

    private void guardForce() {
        add(20, () -> commands("difficulty normal", "gamerule naturalRegeneration false", "effect give @s minecraft:instant_health 1 10",
                "crime set heat @s 100", "summon mca:male_villager ~4 ~ ~ {PersistenceRequired:1b,VillagerData:{profession:\"mca:guard\",level:1,type:\"minecraft:plains\"}}"));
        add(20, () -> await(() -> dev.otectus.mcacrime.client.ClientChallengeData.active()));
        add(1, () -> {
            health = mc.player.getHealth(); var encounter = dev.otectus.mcacrime.client.ClientChallengeData.current();
            dev.otectus.mcacrime.client.ClientChallengeData.menuDisplayed();
            send(new dev.otectus.mcacrime.network.GuardChallengeResponseC2SPacket(encounter.encounterId(),
                    dev.otectus.mcacrime.enforcement.ChallengeResponse.REFUSE, encounter.revision())); mc.setScreen(null);
        });
        add(20, () -> await(() -> mc.player.getHealth() < health));
        add(1, () -> {
            record("D1/D2 live guard confrontation refusal caused actual player damage " + health + " -> " + mc.player.getHealth());
            screenshot("extended-guard-force.png"); commands("kill @e[type=mca:male_villager]", "crime clearheat @s");
        });
    }

    private void locks() {
        add(20, () -> {
            command("setblock " + position(home.offset(2, 0, 0)) + " minecraft:chest");
            command("item replace entity @s weapon.mainhand with mcacrime:padlock");
            mc.options.keyShift.setDown(true);
        });
        add(20, () -> { mark = MESSAGES.size(); useBlock(home.offset(2, 0, 0)); });
        add(20, () -> {
            require(saw("mcacrime.lock.padlock_placed") && mc.player.getMainHandItem().isEmpty(), "padlock placement failed: " + messages());
            record("G6 physical padlock placed on chest and consumed one item"); mc.options.keyShift.setDown(false);
            command("item replace entity @s weapon.mainhand with mcacrime:key");
        });
        add(20, () -> { mark = MESSAGES.size(); usePadlock(); });
        add(20, () -> {
            require(saw("mcacrime.lock.bound"), "first key did not bind: " + messages());
            record("G6 first blank key initialized a physical padlock"); mark = MESSAGES.size(); usePadlock();
        });
        add(20, () -> {
            require(saw("mcacrime.lock.unlocked_now") || saw("mcacrime.lock.locked_now"), "bound key did not toggle: " + messages());
            record("G6 bound key toggled the padlock");
            command("item replace entity @s weapon.mainhand with mcacrime:key");
        });
        add(20, () -> { mark = MESSAGES.size(); usePadlock(); });
        add(20, () -> {
            require(!saw("mcacrime.lock.bound") && !saw("mcacrime.lock.unlocked_now") && !saw("mcacrime.lock.locked_now"), "second blank key gained authority");
            require(dev.otectus.mcacrime.locks.LockInteractions.isBlankKey(mc.player.getMainHandItem()), "second blank key acquired a binding");
            record("G6 second blank key refused: " + messages());
            command("item replace entity @s weapon.mainhand with mcacrime:creative_key");
        });
        add(20, () -> { mark = MESSAGES.size(); usePadlock(); });
        add(20, () -> {
            require(saw("mcacrime.lock.locked_now"), "authorized operator master key did not relock: " + messages());
            record("G6 authorized survival operator master key relocked the chest");
            mc.options.keyShift.setDown(false); command("crime lock inspect " + position(home.offset(2, 0, 0)));
            command("item replace entity @s weapon.mainhand with minecraft:air");
        });
        add(20, () -> useBlock(home.offset(2, 0, 0)));
        add(20, () -> {
            require(padlock().renderLocked(), "relocked padlock did not synchronize locked state");
            require(mc.screen instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen, "owner/operator exemption did not allow chest opening");
            record("G6 locked state synchronized while owner/operator could still open their own chest"); mc.player.closeContainer();
            record("G6 lock inspection after interactions: " + messages()); screenshot("extended-padlock.png");
            command("item replace entity @s weapon.mainhand with mcacrime:lockpick"); mc.options.keyShift.setDown(false);
        });
        add(20, this::usePadlock);
        add(20, () -> {
            require(mc.screen instanceof dev.otectus.mcacrime.client.screen.LockpickScreen, "lockpick interaction did not open the live dial: " + mc.screen);
            screenshot("extended-lockpick.png");
            send(new dev.otectus.mcacrime.network.LockpickAttemptC2SPacket(Long.MAX_VALUE, 0, 0));
        });
        add(10, () -> {
            require(mc.screen instanceof dev.otectus.mcacrime.client.screen.LockpickScreen, "forged lockpick session changed the current screen");
            require(((Number)field(mc.screen, "phase")).intValue() == 0, "forged lockpick session advanced the phase");
            record("L1 forged lockpick session id did not advance the active dial");
        });
        for (int i = 0; i < 8; i++) add(5, () -> {
            if (mc.screen instanceof dev.otectus.mcacrime.client.screen.LockpickScreen screen) {
                send(new dev.otectus.mcacrime.network.LockpickAttemptC2SPacket(((Number)field(screen, "sessionId")).longValue(),
                        ((Number)field(screen, "phase")).intValue(), ((Number)field(screen, "targetMilliDegrees")).intValue()));
            }
        });
        add(10, () -> {
            require(!(mc.screen instanceof dev.otectus.mcacrime.client.screen.LockpickScreen) && !hasPadlock(), "correct live lockpick inputs did not detach the defeated padlock");
            command("item replace entity @s weapon.mainhand with minecraft:air"); mc.options.keyShift.setDown(false);
        });
        add(20, () -> useBlock(home.offset(2, 0, 0)));
        add(20, () -> {
            require(mc.screen instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen, "successful lockpick did not permit chest access: " + mc.screen);
            record("G6 correct inputs against successive server-issued phases picked the padlock and opened the chest");
            mc.player.closeContainer();
        });
    }

    private boolean hasPadlock() {
        for (var entity : mc.level.entitiesForRendering()) if (entity instanceof dev.otectus.mcacrime.entity.PadlockEntity && entity.isAlive()) return true;
        return false;
    }

    private dev.otectus.mcacrime.entity.PadlockEntity padlock() {
        for (var e : mc.level.entitiesForRendering()) if (e instanceof dev.otectus.mcacrime.entity.PadlockEntity p && ((net.minecraft.world.entity.Entity)p).isAlive()) return p;
        throw new AssertionError("physical padlock missing");
    }

    private void usePadlock() {
        mc.setScreen(null); mc.gameMode.interact(mc.player, padlock(), net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    private void spawnTarget() {
        commands("summon mca:male_villager ~2 ~ ~", "data merge entity @e[type=mca:male_villager,sort=nearest,limit=1] {NoAI:1b,PersistenceRequired:1b}");
    }

    private String position(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }

    private Object field(Object value, String name) {
        try { var field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void useBlock(BlockPos pos) {
        mc.setScreen(null);
        mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.WEST, pos, false));
    }

    private void bail() {
        add(20, () -> commands("give @s minecraft:emerald 256", "crime assignjail ~ ~ ~ 6", "crime set heat @s 100", "crime jail @s 2400"));
        add(30, () -> {
            require(ClientSelfData.jailRemainingTicks() > 0, "bail fixture did not enter jail");
            KeyMapping.click(CrimeKeybinds.OPEN_SELF_PANEL.getKey());
        });
        add(20, () -> {
            var menu = menu();
            var row = menu.actions().stream().filter(a -> a.actionId().equals(McaCrime.id("bail"))).findFirst().orElseThrow();
            require(row.available(), "bail not available: " + row.reasonKey());
            currency = emeralds();
            replay = new StartActionC2SPacket(UUID.randomUUID(), menu.menuId(), menu.revision(), row.actionId(), menu.targetId());
            screenshot("extended-bail-offer.png"); send(replay);
        });
        add(30, () -> {
            require(ClientSelfData.jailRemainingTicks() == 0 && emeralds() < currency, "bail did not release and charge currency");
            record("E2 server-menu bail debited currency and released the prisoner");
            currency = emeralds(); send(replay); send(replay);
        });
        add(30, () -> {
            require(ClientSelfData.jailRemainingTicks() == 0 && emeralds() == currency, "replayed bail charged again");
            record("L1/L2 replayed bail packets neither charged again nor restored custody"); mc.setScreen(null);
        });
    }

    private void restraintResume() {
        add(20, () -> {
            require(ClientPhysicalRestraintData.slot(mc.player.getUUID(), RestraintSlot.ARMS).isPresent(),
                    "reconnect did not restore the server's saved restraint");
            record("J1 reconnect full-state packet restored the previously missing restraint");
            screenshot("extended-restraint-after-reconnect.png");
            command("crime restraint remove @s arms");
        });
        add(20, () -> { mark = MESSAGES.size(); command("crime restraint inspect @s"); });
        add(20, () -> {
            screenshot("extended-restraint-after-removal.png");
            require(!ClientPhysicalRestraintData.restrained(mc.player.getUUID()),
                    "removed restraint remains on own client after reconnect; client="
                            + ClientPhysicalRestraintData.get(mc.player.getUUID()) + "; server inspect=" + messages());
            record("G2/J1 removal after reconnect cleared the client restraint");
        });
    }

    private void pvp(String role) {
        boolean enabled = role.endsWith("-on");
        if (role.contains("resume")) {
            add(20, () -> {
                long expected = Long.parseLong(read("pvp-karma"));
                require(ClientSelfData.karma() == expected, "PvP Karma changed across restart");
                record("C5/J1 PvP setting=" + enabled + " Karma=" + expected + " persisted across restart"); finish.run();
            });
        } else if (role.contains("victim")) {
            add(1, () -> { victimBeforeDeath = mc.player; signal("pvp-victim-ready", "ready"); });
            await(() -> exists("pvp-first-hit") && mc.player.getHealth() < 20);
            add(1, () -> {
                require(ClientSelfData.karma() == 0 && ClientSelfData.heat() == 0, "victim received attacker's crime state");
                record("C5/K1 victim took real player damage and retained their own zero Karma/Heat");
                signal("pvp-injured", "confirmed");
            });
            await(() -> mc.player != victimBeforeDeath && mc.player.getHealth() > 0);
            add(1, () -> {
                require(ClientSelfData.karma() == 0 && ClientSelfData.heat() == 0, "victim's death copied attacker consequences");
                record("C5/J2/K1 actual player murder and automatic respawn preserved innocent victim state");
                signal("pvp-respawned", "confirmed");
            });
            await(() -> exists("pvp-actor-done"));
            add(1, finish);
        } else {
            await(() -> exists("pvp-victim-ready"));
            add(1, () -> commands("gamerule doImmediateRespawn true", "gamerule keepInventory false", "gamerule doMobSpawning false",
                    "difficulty normal", "gamerule naturalRegeneration false", "clear @s", "give @s minecraft:diamond_sword",
                    "crime set karma @s 0", "crime set heat @s 0", "tp CrimeBystander ~2 ~ ~"));
            add(30, () -> { mc.gameMode.attack(mc.player, victim()); signal("pvp-first-hit", "sent"); });
            await(() -> exists("pvp-injured"));
            add(20, () -> {
                pvpKarma = ClientSelfData.karma();
                require(enabled ? pvpKarma < 0 : pvpKarma == 0, "PvP assault did not respect config: " + pvpKarma);
                record("C5 PvP assault setting=" + enabled + " produced Karma=" + pvpKarma);
                command("tp CrimeBystander ~2 ~ ~");
            });
            for (int hit = 0; hit < 4; hit++) {
                add(25, () -> { if (!exists("pvp-respawned")) command("tp CrimeBystander ~2 ~ ~"); });
                add(10, () -> {
                    if (!exists("pvp-respawned")) {
                        mc.gameMode.attack(mc.player, victim());
                        command("data get entity CrimeBystander Health");
                    }
                });
            }
            await(() -> exists("pvp-respawned"));
            add(20, () -> {
                require(enabled ? ClientSelfData.karma() < pvpKarma : ClientSelfData.karma() == 0, "PvP murder did not respect config");
                record("C5 actual PvP murder setting=" + enabled + " produced Karma=" + ClientSelfData.karma());
                signal("pvp-karma", Long.toString(ClientSelfData.karma())); signal("pvp-actor-done", "done");
                mark = MESSAGES.size(); command("crime ledger @s");
            });
            add(20, () -> { record("C5 server ledger response: " + messages()); finish.run(); });
        }
    }

    private void await(java.util.function.BooleanSupplier ready) {
        add(5, () -> { if (!ready.getAsBoolean()) index--; });
    }

    private net.minecraft.world.entity.player.Player victim() {
        return mc.level.players().stream().filter(p -> p.getGameProfile().getName().equals("CrimeBystander")).findFirst().orElseThrow();
    }

    private Path signalPath(String name) { return Path.of(System.getProperty("crime.gameplay.sync")).resolve(name); }
    private boolean exists(String name) { return Files.isRegularFile(signalPath(name)); }
    private String read(String name) {
        try { return Files.readString(signalPath(name)); } catch (Exception e) { throw new AssertionError(e); }
    }
    private void signal(String name, String value) {
        try { Files.writeString(signalPath(name), value); } catch (Exception e) { throw new AssertionError(e); }
    }

    private void permissions(int level) {
        String[][] cases = {{"karma", "0"}, {"status", "0"}, {"query @s", "2"}, {"ledger @s", "2"},
                {"warrant @s", "2"}, {"bounty @s", "2"}, {"set heat @s 10", "2"}, {"set karma @s 111", "3"},
                {"clearheat @s", "3"}, {"jail @s 40", "3"}, {"release @s", "3"}, {"repair @s inspect", "3"},
                {"assignjail ~ ~ ~", "3"}, {"job list", "3"}, {"mugtest", "3"}, {"validate", "3"},
                {"reload", "3"}, {"recovery inspect", "3"}};
        for (String[] entry : cases) {
            String command = "crime " + entry[0]; int required = Integer.parseInt(entry[1]);
            add(5, () -> {
                var parsed = mc.player.connection.getCommands().parse(command, mc.player.connection.getSuggestionsProvider());
                // Hidden operator nodes must remain inaccessible even when a client sends the text directly.
                if (level < required) {
                    require(parsed.getReader().canRead(), "unauthorized command is present in client tree: " + command);
                    mark = MESSAGES.size(); command(command);
                } else {
                    require(!parsed.getReader().canRead(), "authorized command branch absent: " + command);
                    record("B4/K2 permission " + level + " exposes allowed command branch: " + command);
                }
            });
            add(12, () -> {
                if (level < required) {
                    require(saw("command.unknown.command") || saw("command.unknown.argument"),
                            "unauthorized command lacked rejection: " + command + " / " + messages());
                    record("B4/K2 permission " + level + " server rejected " + command);
                }
            });
        }
        add(20, () -> {
            require(ClientSelfData.karma() == 0 && ClientSelfData.heat() == 0 && ClientSelfData.jailRemainingTicks() == 0,
                    "rejected administrative commands mutated state");
            record("B4/K2 rejected commands left Karma, Heat and jail unchanged"); finish.run();
        });
    }

    private ActionMenuS2CPacket menu() {
        require(mc.screen instanceof CrimeInteractionScreen, "no server action menu: " + mc.screen);
        try {
            var field = CrimeInteractionScreen.class.getDeclaredField("menu"); field.setAccessible(true);
            return (ActionMenuS2CPacket) field.get(mc.screen);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private LivingEntity target() {
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living && living.isAlive() && entity != mc.player
                    && dev.otectus.mcacrime.compat.McaCompat.isMcaVillager(entity) && entity.distanceToSqr(mc.player) < 16) return living;
        }
        throw new AssertionError("fixture villager missing");
    }

    private void send(Object packet) { dev.otectus.mcacrime.network.CrimeNetwork.CHANNEL.sendToServer(packet); }
    private int emeralds() { return mc.player.getInventory().items.stream().filter(s -> s.is(Items.EMERALD)).mapToInt(s -> s.getCount()).sum(); }
    private void command(String text) { mc.player.connection.sendCommand(text); }
    private void commands(String... values) { for (String value : values) command(value); }
    private boolean saw(String key) { return MESSAGES.subList(mark, MESSAGES.size()).stream().anyMatch(m -> m.keys.contains(key)); }
    private String messages() { return MESSAGES.subList(mark, MESSAGES.size()).toString(); }
    private void screenshot(String name) { Screenshot.grab(output.toFile(), name, mc.getMainRenderTarget(), message -> {}); }
    private void record(String text) {
        pass.accept(text);
        try { Files.writeString(output.resolve("extended-results.txt"), "PASS " + text + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
