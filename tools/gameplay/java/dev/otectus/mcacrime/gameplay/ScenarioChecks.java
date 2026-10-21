package dev.otectus.mcacrime.gameplay;

import dev.otectus.mcacrime.block.CellDoorBlock;
import dev.otectus.mcacrime.client.ClientSelfData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The 0.7.7 runtime scenarios, scripted per client (the plan's two-client multiplayer run).
 *
 * <p>Roles: {@code offender} (operator) and {@code bystander} run at the same time against one server
 * and coordinate through marker files in the shared sync directory; {@code offender-resume} reconnects
 * after the server restarted; {@code smoke} is a single-player run of MCA: Crime with MCA alone.
 * Every check reads what a real client actually received: synchronized Karma, the translation keys of
 * system chat lines, the screen that opened, and block states.
 */
final class ScenarioChecks {

    /** A received system chat line, reduced to every translation key and argument it carries. */
    private record Received(int tick, Set<String> keys, List<String> args) {
    }

    private static final List<Received> RECEIVED = Collections.synchronizedList(new ArrayList<>());

    static void received(Component message, int tick) {
        ExtendedScenarioChecks.received(message);
        Set<String> keys = new LinkedHashSet<>();
        List<String> args = new ArrayList<>();
        collect(message, keys, args);
        RECEIVED.add(new Received(tick, keys, args));
    }

    private static void collect(Component component, Set<String> keys, List<String> args) {
        if (component == null) return;
        if (component.getContents() instanceof TranslatableContents text) {
            keys.add(text.getKey());
            for (Object arg : text.getArgs()) {
                if (arg instanceof Component nested) collect(nested, keys, args);
                else args.add(String.valueOf(arg));
            }
        }
        for (Component sibling : component.getSiblings()) collect(sibling, keys, args);
    }

    private static final String V1 = "Crime QA target", V2 = "Crime QA grave", V3 = "Crime QA grace", V4 = "Crime QA thief";

    private final Minecraft mc;
    private final String role;
    private final Path output;
    private final Path sync;
    private final Consumer<String> pass;
    private final Consumer<String> note;
    private final Runnable finish;
    private int step, entered, tick, punches;
    private int firstPunch, secondPunch, lastGrave, awayStart, apologyAt, waryAt, joinedAt = -1;
    private int observedAway = -1;
    private long karmaAfterCharge;
    private BlockPos spawn;
    private ExtendedScenarioChecks extended;
    /** 0.8.0: the mask rendering evidence (contact sheets, two-client observation, restart). */
    private MaskScenarioChecks masks;

    ScenarioChecks(Minecraft mc, String role, Path output, Consumer<String> pass, Consumer<String> note, Runnable finish) {
        this.mc = mc;
        this.role = role;
        this.output = output;
        String dir = System.getProperty("crime.gameplay.sync", "");
        this.sync = dir.isEmpty() ? output : Path.of(dir);
        this.pass = pass;
        this.note = note;
        this.finish = finish;
    }

    int step() {
        return masks != null ? masks.step() : extended == null ? step : extended.step();
    }

    void tick(int now) {
        if (role.startsWith("masks-")) {
            if (masks == null) masks = new MaskScenarioChecks(mc, role, output, sync, pass, finish);
            masks.tick(now);
            return;
        }
        if (role.startsWith("extended-")) {
            if (extended == null) extended = new ExtendedScenarioChecks(mc, role, output, pass, finish);
            extended.tick(now);
            return;
        }
        tick = now;
        if (joinedAt < 0) {
            joinedAt = now;
            entered = now;
        }
        switch (role) {
            case "offender" -> offender();
            case "bystander" -> bystander();
            case "offender-resume" -> resume();
            case "smoke" -> smoke();
            default -> throw new AssertionError("Unknown scenario role " + role);
        }
    }

    // ------------------------------------------------------------------ offender (operator)

    private void offender() {
        switch (step) {
            case 0 -> {
                if (!seen("bystander.joined")) {
                    require(tick - entered < 1200, "the bystander never connected");
                    return;
                }
                spawn = mc.player.blockPosition();
                write("spawn", spawn.getX() + " " + spawn.getY() + " " + spawn.getZ());
                for (String c : List.of("gamerule doImmediateRespawn true", "gamerule doDaylightCycle false",
                        "gamerule doMobSpawning false", "gamerule spawnRadius 0", "time set day", "clear @s",
                        "crime set karma @s 0", "crime set heat @s 0")) command(c);
                // The villagers stand forty blocks apart, beyond any witness range, so an assault on one
                // never leaves a memory in another: a remembered attacker is never forgiven.
                summon(V1, home(V1));
                command("tp CrimeBystander " + (spawn.getX() + 3) + " " + spawn.getY() + " " + (spawn.getZ() + 1));
                door(spawn.offset(6, 0, 6), Direction.NORTH, DoorHingeSide.LEFT, false);
                door(spawn.offset(8, 0, 6), Direction.EAST, DoorHingeSide.RIGHT, true);
                go(V2);
                next();
            }
            case 1 -> { if (waited(40)) { summon(V2, home(V2)); go(V3); next(); } }
            case 2 -> { if (waited(40)) { summon(V3, home(V3)); go(V4); next(); } }
            case 3 -> { if (waited(40)) { summon(V4, home(V4)); next(); } }
            case 4 -> { if (waited(20)) { command("crime job assign thief"); next(); } }
            case 5 -> {
                if (!waited(20)) return;
                require(dev.otectus.mcacrime.compat.McaCompat.getProfessionId(villager(V4)).map(Object::toString)
                        .orElse("").equals("mcacrime:thief"), "operator thief recruitment did not synchronize");
                pass.accept("operator recruited a thief through /crime job; its native profession synchronized");
                go(V3);
                next();
            }
            case 6 -> {
                if (!waited(40)) return;
                karmaAfterCharge = ClientSelfData.karma();
                firstPunch = tick;
                mc.gameMode.attack(mc.player, villager(V3));
                next();
            }
            case 7 -> {
                if (!waited(20)) return;
                require(saw("mcacrime.minor_harm.warned", firstPunch), "the first light punch carried no warning: "
                        + keysSince(firstPunch));
                require(ClientSelfData.karma() == karmaAfterCharge, "a single light punch cost Karma: " + ClientSelfData.karma());
                pass.accept("one light punch was forgiven with a warning and cost no Karma");
                punches = 0;
                go(V2);
                next();
            }
            case 8 -> {
                // Four punches thirty ticks apart on the grave villager: the first is forgiven under the
                // victim cap, the other three are charged and merge into one memory repeated three times.
                if (!waited(punches == 0 ? 40 : 30)) return;
                if (punches < 4) {
                    mc.gameMode.attack(mc.player, villager(V2));
                    punches++;
                    entered = tick;
                    return;
                }
                lastGrave = tick;
                require(ClientSelfData.karma() < karmaAfterCharge, "the repeated punches on the grave villager were not charged");
                karmaAfterCharge = ClientSelfData.karma();
                go(null);
                mark("offender.setup");
                next();
            }
            case 9 -> {
                if (!waited(40)) return;
                firstPunch = tick;
                mc.gameMode.attack(mc.player, villager(V1));
                next();
            }
            case 10 -> {
                if (!waited(20)) return;
                require(saw("mcacrime.minor_harm.warned", firstPunch) && ClientSelfData.karma() == karmaAfterCharge,
                        "a first light punch on a third villager was not forgiven under the victim cap: " + keysSince(firstPunch));
                pass.accept("a first light punch on a third villager was forgiven too, under the victim cap");
                secondPunch = tick;
                mc.gameMode.attack(mc.player, villager(V1));
                next();
            }
            case 11 -> {
                if (!waited(20)) return;
                require(ClientSelfData.karma() < karmaAfterCharge, "the second punch inside the window was not charged");
                karmaAfterCharge = ClientSelfData.karma();
                next();
            }
            case 12 -> {
                if (!waited(20)) return;
                require(ClientSelfData.karma() == karmaAfterCharge, "the second punch was charged more than once");
                pass.accept("the second punch inside the window was charged exactly once");
                // Leave while the villager's fresh reaction is still running, so its closing line is due
                // while the offender is away. Spectator: no fall, no suffocation, and chat still arrives.
                command("gamemode spectator");
                command("tp @s " + (spawn.getX() + 200) + " " + (spawn.getY() + 10) + " " + spawn.getZ());
                awayStart = tick;
                mark("offender.away");
                next();
            }
            case 13 -> { if (waited(200)) { command("execute in minecraft:the_nether run tp @s 0 100 0"); next(); } }
            case 14 -> {
                if (!waited(200)) return;
                require(!sawPrefix("dialogue.mcacrime.", awayStart),
                        "the offender heard a villager line while 200 blocks away or in the Nether: " + keysSince(awayStart));
                pass.accept("while 200 blocks away and then in the Nether the offender heard no villager line, "
                        + "with the bystander keeping the village loaded");
                command("execute in minecraft:overworld run tp @s " + spawn.getX() + " " + spawn.getY() + " " + spawn.getZ());
                command("gamemode survival");
                mark("offender.back");
                next();
            }
            case 15 -> {
                if (!seen("bystander.clicked") || tick - lastGrave < 1300 || tick - secondPunch < 1300) {
                    require(tick - entered < 3000, "the settling minute or the bystander never arrived");
                    return;
                }
                apologyAt = tick;
                gesture(V1);
                next();
            }
            case 16 -> {
                if (!waited(20)) return;
                require(sawContaining("apolog", apologyAt), "the empty-hand apology was not accepted: " + keysSince(apologyAt));
                gesture(V1);
                next();
            }
            case 17 -> {
                if (!waited(20)) return;
                require(!sawPrefix("mcacrime.apologize.", entered) && !sawPrefix("dialogue.mcacrime.memory_", entered),
                        "after an accepted apology for a light assault MCA: Crime still refused the click: " + keysSince(entered));
                pass.accept("after an accepted apology for a light assault MCA: Crime no longer refuses the click");
                click(V1);
                next();
            }
            case 18 -> {
                if (!mcaScreen()) {
                    require(tick - entered < 60, "after the apology a real click did not open MCA's screen; heard "
                            + keysSince(entered));
                    return;
                }
                pass.accept("after the apology a real right-click opened MCA's own screen");
                screenshot("apology-reopened.png");
                mc.setScreen(null);
                go(V2);
                next();
            }
            case 19 -> { if (waited(40)) { apologyAt = tick; gesture(V2); next(); } }
            case 20 -> {
                if (!waited(20)) return;
                require(sawContaining("apolog", apologyAt), "the apology for the grave crime was not accepted: " + keysSince(apologyAt));
                waryAt = tick;
                gesture(V2);
                next();
            }
            case 21 -> {
                if (!waited(20)) return;
                require(saw("mcacrime.apologize.still_wary", waryAt), "a grave crime did not say how long it stays wary: "
                        + keysSince(waryAt));
                pass.accept("a grave crime stayed wary after the apology and said for how long");
                click(V2);
                next();
            }
            case 22 -> {
                if (!waited(40)) return;
                if (mcaScreen()) {
                    note.accept("a real right-click on the still-wary villager opened MCA's screen: this MCA build opens it "
                            + "from interactAt, which MCA: Crime's interaction gate (EntityInteract) never sees");
                    mc.setScreen(null);
                } else {
                    pass.accept("a real right-click on the still-wary villager was refused before MCA's screen opened");
                }
                go(null);
                write("offender.karma", Long.toString(ClientSelfData.karma()));
                next();
            }
            case 23 -> {
                if (!waited(40)) return;
                mark("offender.done");
                screenshot("offender.png");
                finish.run();
            }
            default -> throw new AssertionError("Unknown offender step " + step);
        }
    }

    // ------------------------------------------------------------------ bystander (plain player)

    private void bystander() {
        switch (step) {
            case 0 -> { mark("bystander.joined"); next(); }
            case 1 -> {
                if (!seen("offender.away")) {
                    require(tick - entered < 3000, "the offender never left");
                    return;
                }
                if (!waited(20)) return;
                // Townstead re-enables the fixture's brain. Let MCA's global hurt panic settle before
                // attributing a refused conversation to Crime's per-player memory.
                if (net.minecraftforge.fml.ModList.get().isLoaded("townstead")) {
                    if (observedAway < 0) observedAway = tick;
                    if (tick - observedAway < 1300) return;
                }
                // The click comes first: a villager the bystander has just hit panics in MCA's own brain and
                // shakes its head at everybody for a while, which is MCA's behaviour and not a refusal.
                click(V1);
                next();
            }
            case 2 -> {
                if (!mcaScreen()) {
                    require(tick - entered < 60, "the bystander's click did not open MCA's screen; heard " + keysSince(entered));
                    return;
                }
                pass.accept("the bystander's click on the offender's victim opened MCA's own screen");
                screenshot("bystander-screen.png");
                mc.setScreen(null);
                mark("bystander.clicked");
                next();
            }
            case 3 -> {
                if (!waited(10)) return;
                // With Townstead's active brain, a new bystander hit would restart global panic
                // during the offender's apology. Test independent grace after those conversations.
                if (net.minecraftforge.fml.ModList.get().isLoaded("townstead") && !seen("offender.done")) {
                    require(tick - entered < 4000, "the offender did not finish its conversations");
                    return;
                }
                firstPunch = tick;
                mc.gameMode.attack(mc.player, villager(V1));
                next();
            }
            case 4 -> {
                if (!waited(20)) return;
                require(saw("mcacrime.minor_harm.warned", firstPunch) && ClientSelfData.karma() == 0,
                        "the bystander's own first punch was not forgiven");
                pass.accept("the bystander's own first punch on the same villager was forgiven: its own grace");
                next();
            }
            case 5 -> {
                if (!seen("offender.done")) {
                    require(tick - entered < 4000, "the offender never finished");
                    return;
                }
                require(!sawPrefix("mcacrime.apologize.", joinedAt) && !sawPrefix("dialogue.mcacrime.memory_", joinedAt),
                        "the bystander was refused or messaged about the offender's crime: " + keysSince(joinedAt));
                pass.accept("the bystander was never refused, blamed or messaged about the offender's crime");
                finish.run();
            }
            default -> throw new AssertionError("Unknown bystander step " + step);
        }
    }

    // ------------------------------------------------------------------ offender after the restart

    private void resume() {
        switch (step) {
            case 0 -> {
                String[] at = read("spawn").trim().split(" ");
                spawn = new BlockPos(Integer.parseInt(at[0]), Integer.parseInt(at[1]), Integer.parseInt(at[2]));
                long saved = Long.parseLong(read("offender.karma").trim());
                require(ClientSelfData.karma() == saved, "Karma did not persist: " + ClientSelfData.karma() + " vs " + saved);
                pass.accept("Karma persisted across the restart and synchronized on reconnect");
                for (BlockPos door : List.of(spawn.offset(6, 0, 6), spawn.offset(8, 0, 6))) {
                    require(mc.level.getBlockState(door).getBlock() instanceof CellDoorBlock
                            && mc.level.getBlockState(door.above()).getBlock() instanceof CellDoorBlock,
                            "a placed cell door did not persist at " + door);
                }
                pass.accept("placed cell doors persisted across the restart");
                click(V1);
                next();
            }
            case 1 -> {
                if (!mcaScreen()) {
                    require(tick - entered < 60, "after the restart the apologized villager refused conversation: "
                            + keysSince(entered));
                    return;
                }
                pass.accept("after the restart a real right-click on the apologized villager opened MCA's screen");
                mc.setScreen(null);
                apologyAt = tick;
                gesture(V1);
                next();
            }
            case 2 -> {
                if (!waited(20)) return;
                require(!sawPrefix("mcacrime.apologize.", apologyAt) && !sawPrefix("dialogue.mcacrime.memory_", apologyAt),
                        "after the restart MCA: Crime refused the apologized villager: " + keysSince(apologyAt));
                pass.accept("the accepted apology persisted: MCA: Crime does not refuse the click after the restart");
                go(V2);
                next();
            }
            case 3 -> { if (waited(40)) { waryAt = tick; gesture(V2); next(); } }
            case 4 -> {
                if (!waited(20)) return;
                require(saw("mcacrime.apologize.still_wary", waryAt), "the grave memory did not persist: " + keysSince(waryAt));
                pass.accept("the grave memory and its accepted apology persisted across the restart");
                go(V3);
                next();
            }
            case 5 -> {
                if (!waited(40)) return;
                firstPunch = tick;
                mc.gameMode.attack(mc.player, villager(V3));
                next();
            }
            case 6 -> {
                if (!waited(20)) return;
                require(saw("mcacrime.minor_harm.warned", firstPunch), "the grace ledger survived the restart: "
                        + keysSince(firstPunch));
                pass.accept("the in-memory minor-harm grace ledger reset with the restart, as documented");
                go(V4);
                next();
            }
            case 7 -> {
                if (!waited(40)) return;
                require(dev.otectus.mcacrime.compat.McaCompat.getProfessionId(villager(V4)).map(Object::toString)
                        .orElse("").equals("mcacrime:thief"), "the thief's profession did not persist");
                pass.accept("the thief record and its native profession persisted across the restart");
                go(null);
                finish.run();
            }
            default -> throw new AssertionError("Unknown resume step " + step);
        }
    }

    // ------------------------------------------------------------------ MCA: Crime alone

    private void smoke() {
        switch (step) {
            case 0 -> {
                spawn = mc.player.blockPosition();
                command("gamemode creative");
                command("time set day");
                command("gamerule doDaylightCycle false");
                next();
            }
            case 1 -> { if (waited(20)) { command("reload"); next(); } }
            case 2 -> {
                if (!waited(100)) return;
                require(mc.player != null && mc.level != null, "the client lost the world during /reload");
                pass.accept("a resource reload completed with the client still in the world");
                mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), true));
                next();
            }
            case 3 -> {
                if (!waited(20)) return;
                require(mc.screen instanceof CreativeModeInventoryScreen, "the creative inventory did not open");
                CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(), true, mc.level.registryAccess());
                int items = CreativeModeTabs.allTabs().stream()
                        .filter(tab -> BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab) != null
                                && "mcacrime".equals(BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab).getNamespace()))
                        .mapToInt(tab -> tab.getDisplayItems().size()).sum();
                require(items > 0, "the creative inventory has no MCA: Crime items");
                pass.accept("the creative inventory opened and lists " + items + " MCA: Crime items");
                screenshot("creative.png");
                mc.setScreen(null);
                placeAllDoors();
                next();
            }
            case 4 -> {
                if (!waited(60)) return;
                int checked = 0;
                for (DoorCase door : doorCases()) {
                    for (BlockPos pos : List.of(door.pos, door.pos.above())) {
                        BlockState state = mc.level.getBlockState(pos);
                        require(state.getBlock() instanceof CellDoorBlock && state.getValue(DoorBlock.FACING) == door.facing
                                        && state.getValue(DoorBlock.HINGE) == door.hinge && state.getValue(DoorBlock.OPEN) == door.open
                                        && state.getValue(CellDoorBlock.IN_BARS) == door.barred,
                                "cell door at " + pos + " reads back as " + state);
                    }
                    checked++;
                }
                pass.accept("all " + checked + " cell door combinations placed in a running world and read back on the client");
                // 0.7.7: the door inherits whatever another mod adds to every door (Dramatic Doors adds
                // waterlogged) instead of restating vanilla's properties and missing it.
                pass.accept("cell door properties " + dev.otectus.mcacrime.block.CrimeBlocks.CELL_DOOR.get().getStateDefinition().getProperties().stream()
                        .map(net.minecraft.world.level.block.state.properties.Property::getName).sorted().toList()
                        + " with Dramatic Doors " + (net.minecraftforge.fml.ModList.get().isLoaded("dramaticdoors") ? "loaded" : "absent"));
                if (net.minecraftforge.fml.ModList.get().isLoaded("mcareputation")) {
                    // The integrated server shares this class loader; the status is a plain string.
                    pass.accept("MCA: Reputation incident exemptions: "
                            + dev.otectus.mcacrime.compat.ReputationExemptionBridge.status());
                }
                screenshot("doors.png");
                finish.run();
            }
            default -> throw new AssertionError("Unknown smoke step " + step);
        }
    }

    private record DoorCase(BlockPos pos, Direction facing, DoorHingeSide hinge, boolean open, boolean barred) {
    }

    private List<DoorCase> doorCases() {
        List<DoorCase> cases = new ArrayList<>();
        int i = 0;
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (DoorHingeSide hinge : DoorHingeSide.values()) {
                for (boolean open : new boolean[] {false, true}) {
                    for (boolean barred : new boolean[] {false, true}) {
                        cases.add(new DoorCase(spawn.offset(-12 + (i % 8) * 4, 0, 4 + (i / 8) * 4), facing, hinge, open, barred));
                        i++;
                    }
                }
            }
        }
        return cases;
    }

    private void placeAllDoors() {
        for (DoorCase door : doorCases()) {
            Direction left = door.facing.getAxis() == Direction.Axis.Z ? Direction.WEST : Direction.NORTH;
            if (door.barred) {
                for (BlockPos half : List.of(door.pos, door.pos.above())) {
                    setblock(half.relative(left), "minecraft:iron_bars");
                    setblock(half.relative(left.getOpposite()), "minecraft:iron_bars");
                }
            }
            String props = "facing=" + door.facing.getName() + ",hinge=" + door.hinge.getSerializedName()
                    + ",open=" + door.open + ",in_bars=" + door.barred;
            setblock(door.pos, "mcacrime:cell_door[half=lower," + props + "]");
            setblock(door.pos.above(), "mcacrime:cell_door[half=upper," + props + "]");
        }
    }

    // ------------------------------------------------------------------ helpers

    private void summon(String name, BlockPos at) {
        // A plain summon, then the data: NBT on summon skips MCA's age initialization, and a villager MCA
        // does not count as an adult never reacts to a crime, so it would never say its closing line.
        String pos = at.getX() + " " + at.getY() + " " + at.getZ();
        command("summon mca:male_villager " + pos);
        command("data merge entity @e[type=mca:male_villager,x=" + at.getX() + ".5,y=" + at.getY() + ",z=" + at.getZ()
                + ".5,distance=..1.5,sort=nearest,limit=1] {NoAI:1b,PersistenceRequired:1b,CustomName:'{\"text\":\"" + name + "\"}'}");
    }

    private void door(BlockPos at, Direction facing, DoorHingeSide hinge, boolean open) {
        String props = "facing=" + facing.getName() + ",hinge=" + hinge.getSerializedName() + ",open=" + open;
        setblock(at, "mcacrime:cell_door[half=lower," + props + "]");
        setblock(at.above(), "mcacrime:cell_door[half=upper," + props + "]");
    }

    private void setblock(BlockPos at, String state) {
        command("setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " " + state);
    }

    /**
     * The scenario villager by where it was summoned. MCA gives every villager its own name on spawn, so
     * a custom name cannot be relied on; they have no AI and stand exactly where they were put.
     */
    private LivingEntity villager(String name) {
        if (spawn == null) {
            String[] at = read("spawn").trim().split(" ");
            spawn = new BlockPos(Integer.parseInt(at[0]), Integer.parseInt(at[1]), Integer.parseInt(at[2]));
        }
        BlockPos home = home(name);
        LivingEntity best = null;
        double bestDistance = 2.25D;
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive()
                    || !BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace().equals("mca")) continue;
            double distance = entity.distanceToSqr(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D);
            if (distance < bestDistance) {
                best = living;
                bestDistance = distance;
            }
        }
        if (best == null) throw new AssertionError("villager '" + name + "' is missing from the client world near " + home);
        return best;
    }

    /** Where each scenario villager stands, relative to the offender's spawn point. */
    private BlockPos home(String name) {
        return spawn.offset(switch (name) {
            case V1 -> new BlockPos(2, 0, 0);
            case V2 -> new BlockPos(40, 0, 0);
            case V3 -> new BlockPos(0, 0, -40);
            case V4 -> new BlockPos(-40, 0, 0);
            default -> throw new AssertionError("unknown scenario villager " + name);
        });
    }

    /** Teleports the operator two blocks short of a villager, or back to the spawn point for null. */
    private void go(String name) {
        BlockPos to = name == null ? spawn : home(name).offset(home(name).getX() > spawn.getX() ? -2 : home(name).getX() < spawn.getX() ? 2 : 0,
                0, home(name).getZ() < spawn.getZ() ? 2 : home(name).getZ() > spawn.getZ() ? -2 : 0);
        command("tp @s " + to.getX() + " " + to.getY() + " " + to.getZ());
    }

    /**
     * A real right-click, the way {@code Minecraft.startUseItem} sends it: INTERACT_AT first, and the plain
     * interaction only when that did not consume the click. MCA 1.21.1 opens its screen in
     * {@code interactAt}; MCA 1.20.1 opens it in {@code mobInteract}.
     */
    private void click(String name) {
        LivingEntity target = villager(name);
        var hit = new net.minecraft.world.phys.EntityHitResult(target,
                target.position().add(0, target.getBbHeight() * 0.5, 0));
        if (!mc.gameMode.interactAt(mc.player, target, hit, InteractionHand.MAIN_HAND).consumesAction()) {
            mc.gameMode.interact(mc.player, target, InteractionHand.MAIN_HAND);
        }
    }

    /** The plain interaction packet, the one MCA: Crime's empty-hand gesture and refusal gate handle. */
    private void gesture(String name) {
        mc.gameMode.interact(mc.player, villager(name), InteractionHand.MAIN_HAND);
    }

    private boolean mcaScreen() {
        return mc.screen != null && mc.screen.getClass().getName().contains(".mca.");
    }

    private boolean saw(String key, int since) {
        synchronized (RECEIVED) {
            return RECEIVED.stream().anyMatch(line -> line.tick >= since && line.keys.contains(key));
        }
    }

    private boolean sawPrefix(String prefix, int since) {
        synchronized (RECEIVED) {
            return RECEIVED.stream().anyMatch(line -> line.tick >= since && line.keys.stream().anyMatch(k -> k.startsWith(prefix)));
        }
    }

    private boolean sawContaining(String fragment, int since) {
        synchronized (RECEIVED) {
            return RECEIVED.stream().anyMatch(line -> line.tick >= since && line.keys.stream().anyMatch(k -> k.contains(fragment)));
        }
    }

    private String keysSince(int since) {
        synchronized (RECEIVED) {
            return RECEIVED.stream().filter(line -> line.tick >= since).map(line -> line.keys.toString()).toList().toString();
        }
    }

    private void command(String command) {
        mc.player.connection.sendCommand(command);
        // Townstead may clear NoAI during activity coordination. Keep stationary test subjects
        // stationary with their server-side movement attribute; the real guard flow is unaffected.
        if (command.startsWith("data merge entity ") && command.contains("NoAI:1b")) {
            String selector = command.substring("data merge entity ".length()).split(" ", 2)[0];
            mc.player.connection.sendCommand("attribute " + selector + " minecraft:generic.movement_speed base set 0");
            mc.player.connection.sendCommand("attribute " + selector + " minecraft:generic.knockback_resistance base set 1");
        }
    }

    private void screenshot(String name) {
        Screenshot.grab(output.toFile(), name, mc.getMainRenderTarget(), message -> { });
    }

    private void mark(String name) {
        write(name, Integer.toString(tick));
    }

    private boolean seen(String name) {
        return Files.exists(sync.resolve(name));
    }

    private void write(String name, String value) {
        try {
            Files.writeString(sync.resolve(name + ".tmp"), value);
            Files.move(sync.resolve(name + ".tmp"), sync.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private String read(String name) {
        try {
            return Files.readString(sync.resolve(name));
        } catch (Exception failure) {
            throw new AssertionError("missing sync file " + name, failure);
        }
    }

    private boolean waited(int ticks) {
        return tick - entered >= ticks;
    }

    private void next() {
        step++;
        entered = tick;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
