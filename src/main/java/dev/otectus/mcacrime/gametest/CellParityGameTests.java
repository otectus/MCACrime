package dev.otectus.mcacrime.gametest;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.jail.CellBlueprint;
import dev.otectus.mcacrime.jail.CellBuilder;
import dev.otectus.mcacrime.jail.CellOccupants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

/** Real block collision and entity occupancy at generated-cell construction and repair. */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CellParityGameTests {
    private CellParityGameTests() {}

    @SuppressWarnings("unchecked")
    private static Mob villager(GameTestHelper helper, BlockPos at) {
        // MCA replaces vanilla villagers on EntityJoinLevelEvent; test the entity actually in the world.
        var type = (EntityType<? extends Mob>) BuiltInRegistries.ENTITY_TYPE.getOptional(
                ResourceLocation.fromNamespaceAndPath("mca", "male_villager")).orElseThrow();
        var mob = helper.spawnWithNoFreeWill(type, at);
        helper.assertTrue(helper.getLevel().getEntity(mob.getUUID()) == mob, "Test villager was not added to the level");
        return mob;
    }

    @GameTest(template = "cell_parity")
    public static void generatedCellAvoidsLivingBystanders(GameTestHelper helper) {
        var level = helper.getLevel();
        var bystander = villager(helper, new BlockPos(4, 1, 4));
        var cell = CellBuilder.build(level, helper.absolutePos(new BlockPos(6, 1, 6)), UUID.randomUUID(), UUID.randomUUID());
        helper.assertTrue(cell != null, "No cell found on the clear test platform");
        try {
            helper.assertTrue(!CellBlueprint.bounds(cell.anchor()).inflate(0.35, 0, 0.35)
                    .intersects(bystander.getBoundingBox()), "Construction enclosed the bystander");
            helper.assertTrue(bystander.isAlive() && level.getEntity(bystander.getUUID()) == bystander,
                    "Construction removed the bystander");
        } finally {
            CellBuilder.demolish(level, cell);
            bystander.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "cell_parity")
    public static void oldCellRepairMovesOnlyTheBystander(GameTestHelper helper) {
        var level = helper.getLevel();
        var prisoner = villager(helper, new BlockPos(10, 1, 10));
        var cell = CellBuilder.build(level, helper.absolutePos(new BlockPos(6, 1, 6)), prisoner.getUUID(), UUID.randomUUID());
        helper.assertTrue(cell != null, "No cell found on the clear test platform");
        var bystander = villager(helper, new BlockPos(11, 1, 10));
        try {
            prisoner.teleportTo(cell.anchor().getX() + 0.5, cell.anchor().getY(), cell.anchor().getZ() + 0.5);
            bystander.teleportTo(cell.anchor().getX() + 0.5, cell.anchor().getY(), cell.anchor().getZ() + 0.5);
            CellOccupants.freeBystanders(level, cell);
            helper.assertTrue(CellBlueprint.bounds(cell.anchor()).intersects(prisoner.getBoundingBox()),
                    "Repair released the actual prisoner");
            helper.assertTrue(!CellBlueprint.bounds(cell.anchor()).intersects(bystander.getBoundingBox()),
                    "Repair left the bystander trapped");
            helper.assertTrue(level.noCollision(bystander), "Repair moved the bystander into a block");
        } finally {
            CellBuilder.demolish(level, cell);
            prisoner.discard(); bystander.discard();
        }
        helper.succeed();
    }
}
