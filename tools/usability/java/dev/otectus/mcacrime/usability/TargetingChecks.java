package dev.otectus.mcacrime.usability;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.ActionSessionManager;
import dev.otectus.mcacrime.action.CancelReason;
import dev.otectus.mcacrime.action.CrimeActionService;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import static dev.otectus.mcacrime.usability.UsabilityRuntimeChecks.require;

/**
 * Workstream A, asserted on a real server with real MCA villagers (0.8.0).
 *
 * <p>Each case is the 0.7.9 failure the baseline probe recorded, now required to behave as the
 * instructions specify: the aimed villager is the one changed, a wall or a box corner never selects
 * anybody, every failure says which failure it is, and gameplay commands target exactly what the
 * player looks at within four blocks.
 */
final class TargetingChecks {

    private static final int Y = 100;
    private final Fixtures f;
    private final UsabilityRuntimeChecks out;

    TargetingChecks(Fixtures fixtures, UsabilityRuntimeChecks out) {
        this.f = fixtures;
        this.out = out;
    }

    void run() {
        f.arena(16, 60, 16, 44, Y);
        out.check("targeting: the 4-block gameplay ray hits the aimed villager", this::rayHits);
        out.check("targeting: /crime mug starts on the aimed villager", this::mugStarts);
        out.check("targeting: /crime mug with nothing aimed asks the player to look at a villager", this::mugNothing);
        out.check("targeting: /crime mug names a vanilla villager as the wrong kind", this::mugVanilla);
        out.check("targeting: /crime mug names an MCA zombie villager as the wrong kind", this::mugZombie);
        out.check("targeting: /crime mug past four blocks says how far", this::mugOutOfReach);
        out.check("targeting: /crime mug through a wall says to move into view", this::mugBehindWall);
        out.check("targeting: /crime mug never picks a bystander when nothing is aimed", this::mugNoFallback);
        out.check("targeting: /crime report opens on the aimed guard", this::reportOpens);
        out.check("targeting: /crime report at a non-guard villager names the wrong role", this::reportNotGuard);
        out.check("targeting: /crime report with nothing aimed asks for a guard", this::reportNothing);
        out.check("targeting: /crime job changes the aimed villager, not a closer bystander", this::jobAimed);
        out.check("targeting: /crime job never selects through the search-box corner", this::jobCorner);
        out.check("targeting: /crime job never selects a villager behind a wall", this::jobBehindWall);
        out.check("targeting: /crime job names an aimed vanilla villager as the wrong kind", this::jobVanilla);
        out.check("targeting: /crime job asks which one when several are in view", this::jobAmbiguous);
        out.check("targeting: /crime job falls back to the single visible villager", this::jobSole);
        out.check("targeting: /crime job takes a villager at exactly 8 blocks and refuses 8.3", this::jobBoundary);
        out.check("targeting: /crime job leaves a child's refusal to the job service", this::jobChild);
        out.check("targeting: /crime job from the console needs and accepts an explicit target", this::jobConsole);
        out.check("targeting: an explicit wrong-kind target is refused, not replaced", this::jobExplicitWrongKind);
        out.check("targeting: a villager in another dimension is never implicitly selected", this::otherDimension);
        out.check("targeting: job mutation stays at permission level 3", this::jobPermission);
        out.check("targeting: /crime debug target explains the aim (level 2) and is refused at level 0", this::debugTarget);
        out.check("targeting: /crime debug mca reports the binding from the console", this::debugMca);
        out.check("help: /crime, /crime help and every topic work for an ordinary player and the console", this::help);
        out.check("help: still available with gameplay command fallbacks disabled", this::helpWithFallbackOff);
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

    private void reset(LivingEntity... villagers) {
        for (LivingEntity villager : villagers) {
            WorldCriminalJobService.of(f.server).tryAssign(villager.getUUID(), CriminalJob.NONE, true);
        }
    }

    private void lookUp(ServerPlayer player) {
        Fixtures.aimAt(player, player.getEyePosition().add(0, 10, 0));
    }

    private void wall(int x, int z) {
        f.set(x, Y, z, Blocks.STONE.defaultBlockState());
        f.set(x, Y + 1, z, Blocks.STONE.defaultBlockState());
    }

    private void unwall(int x, int z) {
        f.set(x, Y, z, Blocks.AIR.defaultBlockState());
        f.set(x, Y + 1, z, Blocks.AIR.defaultBlockState());
    }

    private void rayHits() {
        ServerPlayer player = actor("RayHits");
        LivingEntity adult = f.mca(24.5, Y, 30.5, false);
        try {
            Fixtures.aim(player, adult);
            require(CrimeActionService.rayTraceLiving(player, 4.0D) == adult, "the ray did not return the villager");
        } finally {
            Fixtures.discard(adult);
            f.remove(player);
        }
    }

    private void mugStarts() {
        ServerPlayer player = actor("MugStarts");
        LivingEntity adult = f.mca(24.5, Y, 30.5, false);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            Fixtures.aim(player, adult);
            int code = f.run(player, 0, "crime mug");
            require(code == 1, "mug did not start: " + f.toldTo(player));
            require(ActionSessionManager.forActor(player.getUUID()).isPresent(), "no mug session for the actor");
            require(f.heard(player).stream().noneMatch(h -> h.key().startsWith("mcacrime.target.")
                    || h.key().equals("mcacrime.mug.notarget")), "a targeting error was sent: " + f.toldTo(player));
        } finally {
            ActionSessionManager.clearFor(player.getUUID(), CancelReason.ACTOR_GONE);
            Fixtures.discard(adult);
            f.remove(player);
        }
    }

    private void expectSingle(ServerPlayer player, String command, String key) {
        int code = f.run(player, 0, command);
        require(code == 0, command + " returned " + code);
        require(f.wasTold(player, key), command + " said " + f.toldTo(player) + ", expected " + key);
        long errors = f.heard(player).stream().filter(h -> !h.overlay()).count();
        require(errors == 1, command + " sent " + errors + " lines, expected exactly one: " + f.toldTo(player));
    }

    private void mugNothing() {
        ServerPlayer player = actor("MugNothing");
        LivingEntity aside = f.mca(22.5, Y, 32.5, false);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            lookUp(player);
            expectSingle(player, "crime mug", "mcacrime.target.none.gameplay");
        } finally {
            Fixtures.discard(aside);
            f.remove(player);
        }
    }

    private void mugVanilla() {
        ServerPlayer player = actor("MugVanilla");
        LivingEntity vanilla = f.vanilla(EntityType.VILLAGER, 24.5, Y, 30.5);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            Fixtures.aim(player, vanilla);
            expectSingle(player, "crime mug", "mcacrime.target.wrong_kind");
            require(f.textTo(player).contains("vanilla villager"), "the message did not name the kind: " + f.textTo(player));
        } finally {
            Fixtures.discard(vanilla);
            f.remove(player);
        }
    }

    private void mugZombie() {
        ServerPlayer player = actor("MugZombie");
        LivingEntity zombie = f.mcaOfType("male_zombie_villager", 24.5, Y, 30.5, false);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            Fixtures.aim(player, zombie);
            expectSingle(player, "crime mug", "mcacrime.target.wrong_kind");
            require(f.textTo(player).contains("MCA zombie villager"), "the message did not name the kind: " + f.textTo(player));
        } finally {
            Fixtures.discard(zombie);
            f.remove(player);
        }
    }

    private void mugOutOfReach() {
        ServerPlayer player = actor("MugFar");
        LivingEntity far = f.mca(26.1, Y, 30.5, false);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            Fixtures.aim(player, far);
            expectSingle(player, "crime mug", "mcacrime.target.out_of_reach");
            require(ActionSessionManager.forActor(player.getUUID()).isEmpty(), "a session started out of reach");
        } finally {
            Fixtures.discard(far);
            f.remove(player);
        }
    }

    private void mugBehindWall() {
        ServerPlayer player = actor("MugWall");
        LivingEntity walled = f.mca(24.5, Y, 30.5, false);
        wall(23, 30);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            Fixtures.aim(player, walled);
            expectSingle(player, "crime mug", "mcacrime.target.obstructed");
        } finally {
            unwall(23, 30);
            Fixtures.discard(walled);
            f.remove(player);
        }
    }

    private void mugNoFallback() {
        ServerPlayer player = actor("MugNoFallback");
        LivingEntity beside = f.mca(22.0, Y, 31.5, false);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            Fixtures.aimAt(player, new Vec3(30.5, Y + 1.5, 30.5));
            expectSingle(player, "crime mug", "mcacrime.target.none.gameplay");
            require(ActionSessionManager.forActor(player.getUUID()).isEmpty(), "a bystander was mugged");
        } finally {
            Fixtures.discard(beside);
            f.remove(player);
        }
    }

    private void reportOpens() {
        ServerPlayer player = actor("ReportOpens");
        LivingEntity guard = f.mca(24.5, Y, 30.5, false);
        try {
            require(McaCompat.makeGuard(guard) && McaCompat.isGuard(guard), "fixture guard");
            Fixtures.aim(player, guard);
            int code = f.run(player, 0, "crime report");
            require(code == 1, "the report menu did not open: " + f.toldTo(player));
        } finally {
            Fixtures.discard(guard);
            f.remove(player);
        }
    }

    private void reportNotGuard() {
        ServerPlayer player = actor("ReportNotGuard");
        LivingEntity villager = f.mca(24.5, Y, 30.5, false);
        try {
            Fixtures.aim(player, villager);
            expectSingle(player, "crime report", "mcacrime.target.not_responder");
        } finally {
            Fixtures.discard(villager);
            f.remove(player);
        }
    }

    private void reportNothing() {
        ServerPlayer player = actor("ReportNothing");
        LivingEntity guard = f.mca(22.5, Y, 32.5, false);
        try {
            McaCompat.makeGuard(guard);
            lookUp(player);
            expectSingle(player, "crime report", "mcacrime.target.none.responder");
        } finally {
            Fixtures.discard(guard);
            f.remove(player);
        }
    }

    private void jobAimed() {
        ServerPlayer player = actor("JobAimed");
        LivingEntity aimed = f.mca(26.5, Y, 30.5, false);
        LivingEntity bystander = f.mca(20.0, Y, 30.5, false);
        try {
            Fixtures.aim(player, aimed);
            int code = f.run(player, 4, "crime job assign fence");
            require(code == 1, "assignment failed: " + f.toldTo(player));
            require("fence".equals(job(aimed)), "aimed villager is " + job(aimed));
            require("none".equals(job(bystander)), "bystander became " + job(bystander));
        } finally {
            reset(aimed, bystander);
            Fixtures.discard(aimed, bystander);
            f.remove(player);
        }
    }

    private void jobCorner() {
        ServerPlayer player = actor("JobCorner");
        LivingEntity far = f.mca(21.5 + 7.5, Y, 30.5 + 7.5, false);
        try {
            lookUp(player);
            int code = f.run(player, 4, "crime job assign fence");
            require(code == 0, "the corner villager was selected");
            require("none".equals(job(far)), "the corner villager became " + job(far));
            require(f.wasTold(player, "mcacrime.target.none.operator"), "said " + f.toldTo(player));
        } finally {
            reset(far);
            Fixtures.discard(far);
            f.remove(player);
        }
    }

    private void jobBehindWall() {
        ServerPlayer player = actor("JobWall");
        LivingEntity walled = f.mca(24.5, Y, 30.5, false);
        wall(23, 30);
        try {
            Fixtures.aim(player, walled);
            int code = f.run(player, 4, "crime job assign fence");
            require(code == 0 && "none".equals(job(walled)), "the walled villager was changed: " + job(walled));
            require(f.wasTold(player, "mcacrime.target.obstructed"), "said " + f.toldTo(player));
            f.forget(player);
            lookUp(player);
            f.run(player, 4, "crime job assign fence");
            require("none".equals(job(walled)), "the walled villager was changed by the fallback");
            require(f.wasTold(player, "mcacrime.target.obstructed.nearby"), "fallback said " + f.toldTo(player));
        } finally {
            unwall(23, 30);
            reset(walled);
            Fixtures.discard(walled);
            f.remove(player);
        }
    }

    private void jobVanilla() {
        ServerPlayer player = actor("JobVanilla");
        LivingEntity vanilla = f.vanilla(EntityType.VILLAGER, 24.5, Y, 30.5);
        LivingEntity mcaBeside = f.mca(23.0, Y, 33.0, false);
        try {
            Fixtures.aim(player, vanilla);
            int code = f.run(player, 4, "crime job assign fence");
            require(code == 0, "a job was assigned");
            require(f.wasTold(player, "mcacrime.target.wrong_kind"), "said " + f.toldTo(player));
            require("none".equals(job(mcaBeside)), "the MCA villager beside the vanilla one was changed");
        } finally {
            reset(mcaBeside);
            Fixtures.discard(vanilla, mcaBeside);
            f.remove(player);
        }
    }

    private void jobAmbiguous() {
        ServerPlayer player = actor("JobAmbiguous");
        LivingEntity a = f.mca(24.5, Y, 33.5, false);
        LivingEntity b = f.mca(25.5, Y, 27.5, false);
        try {
            lookUp(player);
            int code = f.run(player, 4, "crime job assign fence");
            require(code == 0, "an ambiguous selection was resolved");
            require(f.wasTold(player, "mcacrime.target.ambiguous"), "said " + f.toldTo(player));
            require("none".equals(job(a)) && "none".equals(job(b)), "a villager was changed");
        } finally {
            reset(a, b);
            Fixtures.discard(a, b);
            f.remove(player);
        }
    }

    private void jobSole() {
        ServerPlayer player = actor("JobSole");
        LivingEntity only = f.mca(27.5, Y, 30.5, false);
        try {
            lookUp(player);
            int code = f.run(player, 4, "crime job assign fence");
            require(code == 1 && "fence".equals(job(only)), "the single visible villager was not chosen: " + f.toldTo(player));
        } finally {
            reset(only);
            Fixtures.discard(only);
            f.remove(player);
        }
    }

    private void jobBoundary() {
        ServerPlayer player = actor("JobBoundary");
        LivingEntity atEight = f.mca(21.5 + 8.0, Y, 30.5, false);
        try {
            lookUp(player);
            require(f.run(player, 4, "crime job assign fence") == 1 && "fence".equals(job(atEight)),
                    "exactly eight blocks was refused: " + f.toldTo(player));
        } finally {
            reset(atEight);
            Fixtures.discard(atEight);
        }
        LivingEntity past = f.mca(21.5 + 8.3, Y, 30.5, false);
        try {
            f.forget(player);
            require(f.run(player, 4, "crime job assign fence") == 0 && "none".equals(job(past)),
                    "8.3 blocks was accepted");
        } finally {
            reset(past);
            Fixtures.discard(past);
            f.remove(player);
        }
    }

    private void jobChild() {
        ServerPlayer player = actor("JobChild");
        LivingEntity child = f.mca(24.5, Y, 30.5, true);
        try {
            Fixtures.aim(player, child);
            int code = f.run(player, 4, "crime job assign fence");
            require(code == 0 && "none".equals(job(child)), "a child was given a job");
            require(f.wasTold(player, "mcacrime.command.job.rejected"), "said " + f.toldTo(player));
            require(f.heard(player).stream().noneMatch(h -> h.key().startsWith("mcacrime.target.")),
                    "a recognised child was reported as a targeting failure");
        } finally {
            reset(child);
            Fixtures.discard(child);
            f.remove(player);
        }
    }

    private void jobConsole() {
        LivingEntity adult = f.mca(24.5, Y, 30.5, false);
        try {
            Fixtures.Console implicit = new Fixtures.Console();
            require(f.runConsole(implicit, "crime job assign fence") == 0, "implicit console selection succeeded");
            require(implicit.keys().contains("mcacrime.target.needs_explicit"), "console said " + implicit.keys());
            Fixtures.Console explicit = new Fixtures.Console();
            require(f.runConsole(explicit, "crime job assign fence " + adult.getStringUUID()) == 1,
                    "explicit console assignment failed: " + explicit.text());
            require("fence".equals(job(adult)), "the named villager is " + job(adult));
            Fixtures.Console cleared = new Fixtures.Console();
            require(f.runConsole(cleared, "crime job clear " + adult.getStringUUID()) == 1, "explicit clear failed");
            require("none".equals(job(adult)), "the named villager kept " + job(adult));
        } finally {
            reset(adult);
            Fixtures.discard(adult);
        }
    }

    private void jobExplicitWrongKind() {
        LivingEntity vanilla = f.vanilla(EntityType.VILLAGER, 24.5, Y, 34.5);
        LivingEntity mca = f.mca(23.5, Y, 34.5, false);
        try {
            Fixtures.Console console = new Fixtures.Console();
            require(f.runConsole(console, "crime job assign fence " + vanilla.getStringUUID()) == 0, "accepted");
            require(console.keys().contains("mcacrime.target.wrong_kind"), "said " + console.keys());
            require("none".equals(job(mca)), "a different villager was changed");
        } finally {
            reset(mca);
            Fixtures.discard(vanilla, mca);
        }
    }

    private void otherDimension() {
        ServerLevel nether = f.server.getLevel(Level.NETHER);
        require(nether != null, "no nether");
        ServerPlayer player = actor("OtherDimension");
        LivingEntity elsewhere = (LivingEntity) net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES
                .getValue(new net.minecraft.resources.ResourceLocation("mca", "male_villager")).create(nether);
        try {
            elsewhere.setPos(24.5, Y, 30.5);
            ((net.minecraft.world.entity.Mob) elsewhere).setNoAi(true);
            nether.addFreshEntity(elsewhere);
            lookUp(player);
            require(f.run(player, 4, "crime job assign fence") == 0, "a villager in the nether was selected");
            require(f.wasTold(player, "mcacrime.target.none.operator"), "said " + f.toldTo(player));
        } finally {
            reset(elsewhere);
            Fixtures.discard(elsewhere);
            f.remove(player);
        }
    }

    private void jobPermission() {
        ServerPlayer player = actor("JobPermission");
        LivingEntity adult = f.mca(24.5, Y, 30.5, false);
        try {
            Fixtures.aim(player, adult);
            require(f.run(player, 2, "crime job assign fence") == 0, "level 2 assigned a job");
            require(f.run(player, 2, "crime job assign fence " + adult.getStringUUID()) == 0,
                    "level 2 assigned a job by name");
            require("none".equals(job(adult)), "the villager was changed at level 2");
        } finally {
            reset(adult);
            Fixtures.discard(adult);
            f.remove(player);
        }
    }

    private void debugTarget() {
        ServerPlayer player = actor("DebugTarget");
        LivingEntity vanilla = f.vanilla(EntityType.VILLAGER, 24.5, Y, 30.5);
        try {
            Fixtures.aim(player, vanilla);
            require(f.run(player, 0, "crime debug target") == 0 && !f.wasTold(player, "mcacrime.target.debug.header"),
                    "level 0 ran the diagnostic");
            f.forget(player);
            f.run(player, 2, "crime debug target");
            String text = f.textTo(player);
            require(f.wasTold(player, "mcacrime.target.debug.header"), "no header: " + f.toldTo(player));
            require(text.contains("minecraft:villager"), "the registry id was not reported: " + text);
            require(text.contains("not_supported"), "the outcome was not reported: " + text);
            require(text.contains("binding: status="), "the binding was not reported: " + text);
        } finally {
            Fixtures.discard(vanilla);
            f.remove(player);
        }
    }

    private void debugMca() {
        Fixtures.Console console = new Fixtures.Console();
        require(f.runConsole(console, "crime debug mca") == 1, "debug mca failed: " + console.text());
        require(console.text().contains("villager recognition: available"), "said " + console.text());
        out.info("debug mca: " + console.text());
    }

    private void help() {
        ServerPlayer player = actor("HelpReader");
        try {
            require(f.run(player, 0, "crime") == 7, "bare /crime did not show the overview: " + f.toldTo(player));
            require(f.wasTold(player, "mcacrime.help.overview"), "said " + f.toldTo(player));
            for (String topic : new String[] {"commands", "targeting", "guillotine", "keys", "keyring", "keymold", "masks"}) {
                f.forget(player);
                require(f.run(player, 0, "crime help " + topic) > 0, topic + " failed: " + f.toldTo(player));
                require(f.wasTold(player, "mcacrime.help.header"), topic + " has no header");
                require(!f.textTo(player).contains("[Operator]"), topic + " showed operator lines to a player");
            }
            f.forget(player);
            require(f.run(player, 0, "crime help ring") > 0 && f.textTo(player).contains("Key Ring"), "the ring alias");
            f.forget(player);
            require(f.run(player, 0, "crime help guillotine 2") > 0 && f.textTo(player).contains("page 2 of"),
                    "page 2: " + f.textTo(player));
            f.forget(player);
            require(f.run(player, 0, "crime help nonsense") == 0 && f.wasTold(player, "mcacrime.help.unknown"),
                    "unknown topic said " + f.toldTo(player));
            f.forget(player);
            f.run(player, 3, "crime help guillotine 2");
            require(f.textTo(player).contains("[Operator]"), "an operator does not see the operator lines");
            Fixtures.Console console = new Fixtures.Console();
            require(f.runConsole(console, "crime help keymold") > 0 && console.text().contains("Baked Key Mold"),
                    "console help: " + console.text());
            out.info("help keyring as a player: " + helpText("keyring"));
        } finally {
            f.remove(player);
        }
    }

    private String helpText(String topic) {
        Fixtures.Console console = new Fixtures.Console();
        f.runConsole(console, "crime help " + topic);
        return console.text();
    }

    private void helpWithFallbackOff() {
        var fallback = McaCrimeConfig.COMMON.allowGameplayCommandFallback;
        boolean before = fallback.get();
        ServerPlayer player = actor("HelpFallbackOff");
        try {
            fallback.set(false);
            require(f.run(player, 0, "crime help commands") > 0, "help refused with fallbacks off");
            require(f.textTo(player).contains("turned the gameplay command shortcuts off"),
                    "the page did not say the shortcuts are off: " + f.textTo(player));
            f.forget(player);
            f.run(player, 0, "crime mug");
            require(f.wasTold(player, "mcacrime.action.commands_disabled"), "mug said " + f.toldTo(player));
        } finally {
            fallback.set(before);
            f.remove(player);
        }
    }
}
