package dev.otectus.mcacrime.gametest;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.block.CrimeBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * MCA's own Blueprint scan of cells built from MCA: Crime's blocks (0.7.8). The reinforced bars, the gapped
 * bars and the cell door join MCA's {@code mca:iron_bars} and {@code mca:iron_doors} tags, so whatever MCA's
 * scanner makes of iron bars and an iron door it must make of these, too: the cell is checked beside a
 * vanilla cell of the same shape, scanned the same way.
 *
 * <p>Which blocks count is MCA's own rule and changes between builds. On 7.7.36 a room counts the blocks
 * standing on its own floor cells, not the blocks in its walls, so bars set into a wall count toward no
 * room for iron bars either; bars hung inside the room do. MCA is driven by name, as everywhere in this
 * mod: {@code VillageManager.analyzeBuildingAddition}, then {@code commitRoomAddition}, choosing
 * "prison" when MCA asks the player which type a room is.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BlueprintPrisonGameTests {

    private static final String MANAGER = "net.conczin.mca.server.world.data.VillageManager";
    private static final TagKey<Block> BARS = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("mca", "iron_bars"));
    private static final TagKey<Block> DOORS = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("mca", "iron_doors"));

    private BlueprintPrisonGameTests() {
    }

    /** What the Blueprint offered, what it answered, the type it recorded, and the blocks it counted. */
    private record Scan(List<String> offered, String result, String type, Map<String, Integer> counted) {
        @Override
        public String toString() {
            return (type == null ? "no building" : type) + " (" + result + ", offered " + offered + ", counted " + counted + ")";
        }
    }

    private enum Bars { IN_WALLS, HUNG_INSIDE }

    /** The materials one cell is built from. */
    private record Materials(Block bars, Block gapped, Block door) {
        static final Materials CRIME = new Materials(CrimeBlocks.REINFORCED_BARS.get(), CrimeBlocks.REINFORCED_BARS_GAP.get(),
                CrimeBlocks.CELL_DOOR.get());
        static final Materials VANILLA = new Materials(Blocks.IRON_BARS, Blocks.IRON_BARS, Blocks.IRON_DOOR);
    }

    /**
     * A closed room with a 5x3x5 interior at {@code origin} on the template's floor, a door in its south
     * wall, a vanilla bed, and eight bars: seven plain and one gapped, either set into three walls at mid
     * height or hung along the inside of two walls at head height, clear of the floor cells. Returns the
     * interior point, relative to the test.
     */
    private static BlockPos cell(GameTestHelper helper, BlockPos origin, Materials materials, Bars bars) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(origin.offset(x, 0, z), Blocks.STONE);
                helper.setBlock(origin.offset(x, 4, z), Blocks.STONE);
                boolean wall = x == 0 || x == 6 || z == 0 || z == 6;
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(origin.offset(x, y, z), wall ? Blocks.STONE : Blocks.AIR);
                }
            }
        }
        List<BlockPos> slots = new ArrayList<>();
        if (bars == Bars.IN_WALLS) {
            for (int i = 1; i <= 5; i++) {
                slots.add(origin.offset(i, 2, 0));
                slots.add(origin.offset(0, 2, i));
                slots.add(origin.offset(6, 2, i));
            }
        } else {
            for (int i = 1; i <= 5; i++) {
                slots.add(origin.offset(i, 3, 1));
            }
            for (int i = 2; i <= 4; i++) {
                slots.add(origin.offset(1, 3, i));
            }
        }
        for (int i = 0; i < 7; i++) {
            helper.setBlock(slots.get(i), materials.bars());
        }
        helper.setBlock(slots.get(7), materials.gapped());
        BlockState base = materials.door().defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        BlockPos lower = origin.offset(3, 1, 6);
        helper.setBlock(lower, base.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(lower.above(), base.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        helper.setBlock(origin.offset(4, 1, 3), bed.setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(origin.offset(4, 1, 4), bed.setValue(BedBlock.PART, BedPart.HEAD));
        return origin.offset(3, 1, 3);
    }

    /** The Blueprint's add-building on {@code interior}, choosing {@code choose} if MCA asks. */
    private static Scan blueprint(GameTestHelper helper, BlockPos interior, String choose) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(interior);
        try {
            Class<?> type = Class.forName(MANAGER);
            Object manager = type.getMethod("get", ServerLevel.class).invoke(null, level);
            Object scan = type.getMethod("analyzeBuildingAddition", BlockPos.class).invoke(manager, at);
            @SuppressWarnings("unchecked")
            List<String> offered = List.copyOf((List<String>) scan.getClass().getMethod("matchingTypes").invoke(scan));
            String result = String.valueOf(scan.getClass().getMethod("result").invoke(scan));
            boolean ambiguous = (boolean) scan.getClass().getMethod("isAmbiguous").invoke(scan);
            if (!"SUCCESS".equals(result)) {
                return recorded(manager, at, offered, result);
            }
            if (ambiguous && !offered.contains(choose)) {
                return recorded(manager, at, offered, "not offered");
            }
            Method commit = type.getMethod("commitRoomAddition", scan.getClass(), String.class);
            Object committed = commit.invoke(manager, scan, ambiguous ? choose : null);
            return recorded(manager, at, offered, String.valueOf(committed));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("MCA's Blueprint scan could not be driven on this build: " + failure, failure);
        }
    }

    private static Scan recorded(Object manager, BlockPos at, List<String> offered, String result)
            throws ReflectiveOperationException {
        Optional<?> village = (Optional<?>) manager.getClass().getMethod("findNearestVillage", BlockPos.class, int.class)
                .invoke(manager, at, 0);
        if (village.isEmpty()) {
            return new Scan(offered, result, null, Map.of());
        }
        Optional<?> building = (Optional<?>) village.get().getClass().getMethod("getBuildingAt", Vec3i.class)
                .invoke(village.get(), at);
        if (building.isEmpty()) {
            return new Scan(offered, result, null, Map.of());
        }
        Object found = building.get();
        Map<String, Integer> counted = new TreeMap<>();
        @SuppressWarnings("unchecked")
        Map<ResourceLocation, List<BlockPos>> blocks = (Map<ResourceLocation, List<BlockPos>>) found.getClass()
                .getMethod("getBlocks").invoke(found);
        blocks.forEach((id, positions) -> counted.put(id.toString(), positions.size()));
        return new Scan(offered, result, (String) found.getClass().getMethod("getType").invoke(found), counted);
    }

    /** The tags are what MCA reads; this is the merged result in a running game, vanilla entries kept. */
    @GameTest(template = "platform", timeoutTicks = 100)
    public static void crimeBarsAndCellDoorAreInMcasPrisonTags(GameTestHelper helper) {
        helper.assertTrue(CrimeBlocks.REINFORCED_BARS.get().defaultBlockState().is(BARS), "reinforced bars missing from mca:iron_bars");
        helper.assertTrue(CrimeBlocks.REINFORCED_BARS_GAP.get().defaultBlockState().is(BARS), "gapped bars missing from mca:iron_bars");
        helper.assertTrue(CrimeBlocks.CELL_DOOR.get().defaultBlockState().is(DOORS), "cell door missing from mca:iron_doors");
        helper.assertTrue(Blocks.IRON_BARS.defaultBlockState().is(BARS), "vanilla iron bars dropped from mca:iron_bars");
        helper.assertTrue(Blocks.IRON_DOOR.defaultBlockState().is(DOORS), "vanilla iron door dropped from mca:iron_doors");
        helper.succeed();
    }

    /** How many of the blocks MCA recorded for the room are in {@code tag}: what its count of that group saw. */
    private static int countedIn(Scan scan, TagKey<Block> tag) {
        int total = 0;
        for (Map.Entry<String, Integer> entry : scan.counted().entrySet()) {
            Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(ResourceLocation.parse(entry.getKey()));
            if (block.defaultBlockState().is(tag)) {
                total += entry.getValue();
            }
        }
        return total;
    }

    private static void scanAndCheck(GameTestHelper helper, Materials materials, Bars bars, boolean innerDoor, String label) {
        BlockPos interior = cell(helper, new BlockPos(1, 1, 2), materials, bars);
        if (innerDoor) {
            // A second door standing inside the room, on a floor cell of its own.
            BlockState base = materials.door().defaultBlockState().setValue(DoorBlock.FACING, Direction.WEST)
                    .setValue(DoorBlock.OPEN, true);
            BlockPos lower = new BlockPos(1, 1, 2).offset(5, 1, 5);
            helper.setBlock(lower, base.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
            helper.setBlock(lower.above(), base.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        Scan scan = blueprint(helper, interior, "prison");
        McaCrime.LOGGER.info("[blueprint] {}: {}", label, scan);
        // The cell door is the room's way in: a room with no door MCA recognises would not scan at all.
        helper.assertTrue("SUCCESS".equals(scan.result()) || scan.type() != null,
                label + ": MCA did not take the room: " + scan);
        if (bars == Bars.HUNG_INSIDE) {
            helper.assertTrue(countedIn(scan, BARS) == 8,
                    label + ": MCA counted " + countedIn(scan, BARS) + " of the eight bars hung inside: " + scan);
        }
        if (innerDoor && countedIn(scan, DOORS) > 0) {
            helper.assertTrue(scan.offered().contains("prison") && "prison".equals(scan.type()),
                    label + ": eight bars, a door and a bed counted, but no prison: " + scan);
        }
        helper.succeed();
    }

    @GameTest(template = "blueprint_pair", timeoutTicks = 200)
    public static void ironBarsSetIntoTheWalls(GameTestHelper helper) {
        scanAndCheck(helper, Materials.VANILLA, Bars.IN_WALLS, false, "iron, bars in the walls");
    }

    @GameTest(template = "blueprint_pair", timeoutTicks = 200)
    public static void crimeBarsSetIntoTheWallsBehindACellDoor(GameTestHelper helper) {
        scanAndCheck(helper, Materials.CRIME, Bars.IN_WALLS, false, "Crime, bars in the walls");
    }

    @GameTest(template = "blueprint_pair", timeoutTicks = 200)
    public static void ironBarsHungInside(GameTestHelper helper) {
        scanAndCheck(helper, Materials.VANILLA, Bars.HUNG_INSIDE, false, "iron, bars hung inside");
    }

    @GameTest(template = "blueprint_pair", timeoutTicks = 200)
    public static void crimeBarsHungInsideAreCountedLikeIron(GameTestHelper helper) {
        scanAndCheck(helper, Materials.CRIME, Bars.HUNG_INSIDE, false, "Crime, bars hung inside");
    }

    @GameTest(template = "blueprint_pair", timeoutTicks = 200)
    public static void ironBarsAndADoorInside(GameTestHelper helper) {
        scanAndCheck(helper, Materials.VANILLA, Bars.HUNG_INSIDE, true, "iron, bars and a door inside");
    }

    @GameTest(template = "blueprint_pair", timeoutTicks = 200)
    public static void crimeBarsAndACellDoorInside(GameTestHelper helper) {
        scanAndCheck(helper, Materials.CRIME, Bars.HUNG_INSIDE, true, "Crime, bars and a cell door inside");
    }
}
