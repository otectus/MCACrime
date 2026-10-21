package dev.otectus.mcacrime.usability;

import dev.otectus.mcacrime.action.CrimeActionService;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.compat.mca.McaBinding;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;

/**
 * What command targeting does, recorded rather than judged.
 *
 * <p>Uses only APIs that existed before 0.8.0 ({@code CrimeActionService.rayTraceLiving}, the
 * {@code /crime} grammar, {@code McaCompat}), so the same probe runs against the 0.7.9 jar to record the
 * behaviour behind the player report, and against 0.8.0 to record what replaced it. Every line is an
 * {@code OBSERVED} fact: which entity a ray returned, which villager a command changed, and the exact
 * translation keys the player was sent.
 */
final class TargetingBaselineProbe {

    private static final int Y = 100;
    private final Fixtures f;
    private final UsabilityRuntimeChecks out;

    TargetingBaselineProbe(Fixtures fixtures, UsabilityRuntimeChecks out) {
        this.f = fixtures;
        this.out = out;
    }

    void run() {
        out.info("mca binding: " + McaBinding.describe());
        f.arena(16, 60, 16, 44, Y);
        identity();
        rayAimedAdult();
        mugAimedAdult();
        reportAimedGuard();
        jobBystander();
        jobCorner();
        jobBehindWall();
        jobOnlyVanilla();
        jobOnlyZombie();
        jobChild();
        jobConsole();
        debugVillagerVanilla();
    }

    private ServerPlayer actor(String name) {
        ServerPlayer player = f.player(name, 21.5, Y, 30.5);
        player.setYRot(-90.0F);
        player.setXRot(0.0F);
        return player;
    }

    private String job(LivingEntity villager) {
        return WorldCriminalJobService.of(f.server).get(villager.getUUID()).id();
    }

    private void identity() {
        LivingEntity adult = f.mca(40.5, Y, 20.5, false);
        LivingEntity child = f.mca(42.5, Y, 20.5, true);
        LivingEntity zombie = f.mcaOfType("male_zombie_villager", 44.5, Y, 20.5, false);
        LivingEntity vanilla = f.vanilla(EntityType.VILLAGER, 46.5, Y, 20.5);
        out.observed("identity", "isMcaVillager adult=" + McaCompat.isMcaVillager(adult)
                + " child=" + McaCompat.isMcaVillager(child)
                + " mcaZombie=" + McaCompat.isMcaVillager(zombie)
                + " vanillaVillager=" + McaCompat.isMcaVillager(vanilla)
                + "; adultVillager adult=" + McaCompat.isAdultVillager(adult)
                + " child=" + McaCompat.isAdultVillager(child));
        Fixtures.discard(adult, child, zombie, vanilla);
    }

    /** The gameplay ray that /crime mug and /crime report both use, aimed straight at a villager. */
    private void rayAimedAdult() {
        ServerPlayer player = actor("RayProbe");
        LivingEntity adult = f.mca(24.5, Y, 30.5, false);
        Fixtures.aim(player, adult);
        LivingEntity hit = CrimeActionService.rayTraceLiving(player, 4.0D);
        out.observed("ray-aimed-adult-3-blocks", "rayTraceLiving(4) returned "
                + (hit == null ? "null" : hit.getType().toShortString() + (hit == adult ? " (the aimed villager)" : " (another entity)"))
                + "; distance=" + String.format("%.2f", player.distanceTo(adult))
                + " lineOfSight=" + player.hasLineOfSight(adult));
        Fixtures.discard(adult);
        f.remove(player);
    }

    private void mugAimedAdult() {
        ServerPlayer player = actor("MugProbe");
        LivingEntity adult = f.mca(24.5, Y, 30.5, false);
        Fixtures.aim(player, adult);
        int code = f.run(player, 0, "crime mug");
        out.observed("mug-aimed-adult-3-blocks", "result=" + code + " told=" + f.toldTo(player)
                + " text=\"" + f.textTo(player) + "\"");
        Fixtures.discard(adult);
        f.remove(player);
    }

    private void reportAimedGuard() {
        ServerPlayer player = actor("ReportProbe");
        LivingEntity guard = f.mca(24.5, Y, 30.5, false);
        boolean madeGuard = McaCompat.makeGuard(guard);
        Fixtures.aim(player, guard);
        int code = f.run(player, 0, "crime report");
        out.observed("report-aimed-guard-3-blocks", "guard=" + madeGuard + "/" + McaCompat.isGuard(guard)
                + " result=" + code + " told=" + f.toldTo(player) + " text=\"" + f.textTo(player) + "\"");
        Fixtures.discard(guard);
        f.remove(player);
    }

    /** The operator looks at one villager five blocks ahead; another stands a block and a half behind. */
    private void jobBystander() {
        ServerPlayer player = actor("JobBystander");
        LivingEntity aimed = f.mca(26.5, Y, 30.5, false);
        LivingEntity bystander = f.mca(20.0, Y, 30.5, false);
        Fixtures.aim(player, aimed);
        int code = f.run(player, 4, "crime job assign fence");
        out.observed("job-aimed-vs-closer-bystander", "result=" + code + " aimed=" + job(aimed)
                + " bystander=" + job(bystander) + " told=" + f.toldTo(player));
        reset(aimed, bystander);
        Fixtures.discard(aimed, bystander);
        f.remove(player);
    }

    /** The only villager stands in the search box's diagonal corner, well beyond eight blocks. */
    private void jobCorner() {
        ServerPlayer player = actor("JobCorner");
        LivingEntity far = f.mca(21.5 + 7.5, Y, 30.5 + 7.5, false);
        int code = f.run(player, 4, "crime job assign fence");
        out.observed("job-diagonal-corner", "distance=" + String.format("%.2f", player.distanceTo(far))
                + " result=" + code + " villager=" + job(far) + " told=" + f.toldTo(player));
        reset(far);
        Fixtures.discard(far);
        f.remove(player);
    }

    /** The only villager stands three blocks away behind a two-high stone wall. */
    private void jobBehindWall() {
        ServerPlayer player = actor("JobWall");
        LivingEntity walled = f.mca(24.5, Y, 30.5, false);
        f.set(23, Y, 30, Blocks.STONE.defaultBlockState());
        f.set(23, Y + 1, 30, Blocks.STONE.defaultBlockState());
        Fixtures.aim(player, walled);
        int code = f.run(player, 4, "crime job assign fence");
        out.observed("job-behind-wall", "lineOfSight=" + player.hasLineOfSight(walled) + " result=" + code
                + " villager=" + job(walled) + " told=" + f.toldTo(player));
        f.set(23, Y, 30, Blocks.AIR.defaultBlockState());
        f.set(23, Y + 1, 30, Blocks.AIR.defaultBlockState());
        reset(walled);
        Fixtures.discard(walled);
        f.remove(player);
    }

    private void jobOnlyVanilla() {
        ServerPlayer player = actor("JobVanilla");
        LivingEntity vanilla = f.vanilla(EntityType.VILLAGER, 24.5, Y, 30.5);
        Fixtures.aim(player, vanilla);
        int code = f.run(player, 4, "crime job assign fence");
        out.observed("job-only-vanilla-villager", "result=" + code + " told=" + f.toldTo(player)
                + " text=\"" + f.textTo(player) + "\"");
        Fixtures.discard(vanilla);
        f.remove(player);
    }

    private void jobOnlyZombie() {
        ServerPlayer player = actor("JobZombie");
        LivingEntity zombie = f.mcaOfType("male_zombie_villager", 24.5, Y, 30.5, false);
        Fixtures.aim(player, zombie);
        int code = f.run(player, 4, "crime job assign fence");
        out.observed("job-only-mca-zombie", "result=" + code + " told=" + f.toldTo(player)
                + " text=\"" + f.textTo(player) + "\"");
        Fixtures.discard(zombie);
        f.remove(player);
    }

    private void jobChild() {
        ServerPlayer player = actor("JobChild");
        LivingEntity child = f.mca(24.5, Y, 30.5, true);
        Fixtures.aim(player, child);
        int code = f.run(player, 4, "crime job assign fence");
        out.observed("job-only-mca-child", "result=" + code + " villager=" + job(child) + " told="
                + f.toldTo(player) + " text=\"" + f.textTo(player) + "\"");
        reset(child);
        Fixtures.discard(child);
        f.remove(player);
    }

    private void jobConsole() {
        LivingEntity adult = f.mca(24.5, Y, 30.5, false);
        Fixtures.Console console = new Fixtures.Console();
        int code = f.runConsole(console, "crime job assign fence");
        out.observed("job-from-console", "result=" + code + " output=" + console.keys()
                + " text=\"" + console.text() + "\"");
        reset(adult);
        Fixtures.discard(adult);
    }

    private void debugVillagerVanilla() {
        ServerPlayer player = actor("DebugVanilla");
        LivingEntity vanilla = f.vanilla(EntityType.VILLAGER, 24.5, Y, 30.5);
        Fixtures.aim(player, vanilla);
        int code = f.run(player, 2, "crime debug villager");
        out.observed("debug-villager-only-vanilla", "result=" + code + " told=" + f.toldTo(player)
                + " text=\"" + f.textTo(player) + "\"");
        Fixtures.discard(vanilla);
        f.remove(player);
    }

    private void reset(LivingEntity... villagers) {
        for (LivingEntity villager : villagers) {
            WorldCriminalJobService.of(f.server).tryAssign(villager.getUUID(), CriminalJob.NONE, true);
        }
    }
}
