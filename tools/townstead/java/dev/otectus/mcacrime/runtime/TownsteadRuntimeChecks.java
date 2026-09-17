package dev.otectus.mcacrime.runtime;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.activity.*;
import dev.otectus.mcacrime.compat.*;
import dev.otectus.mcacrime.property.*;
import dev.otectus.mcacrime.captivity.*;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.world.Container;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.schedule.Schedule;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import dev.otectus.mcacrime.ai.NpcAwareness;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Executed only in disposable production servers; never included in MCA: Crime's jar. */
@Mod("townstead_runtime_checks")
public final class TownsteadRuntimeChecks {
    private static final String ROOT = "com.aetherianartificer.townstead.";
    private final List<String> results = new ArrayList<>();
    private MinecraftServer server;
    private int ticks;

    public TownsteadRuntimeChecks() {
        MinecraftForge.EVENT_BUS.addListener(this::started);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
    }

    private void started(ServerStartedEvent event) { server = event.getServer(); }

    private void tick(TickEvent.ServerTickEvent event) {
        if (server == null || event.phase != TickEvent.Phase.END || ++ticks != 5) return;
        try {
            runChecks();
        } catch (Throwable failure) {
            results.add("FAIL harness: " + failure);
            failure.printStackTrace();
        } finally {
            try { Files.write(Path.of("runtime-results.txt"), results); }
            catch (Exception e) { throw new RuntimeException(e); }
            results.forEach(System.out::println);
            server.halt(false);
        }
    }

    private void runChecks() throws Exception {
        ServerLevel level = server.overworld();
        LivingEntity villager = villager(level);
        boolean installed = TownsteadBridge.installed();
        boolean enabled = McaCrimeConfig.COMMON.townsteadEnabled.get();
        check("bridge state", () -> require(TownsteadBridge.state() == (!installed
                ? TownsteadBridge.State.ABSENT : enabled ? TownsteadBridge.State.FULL : TownsteadBridge.State.OFF),
                TownsteadBridge.status()));
        check("dedicated server excludes dialogue mixin", () -> require(
                !TownsteadMixinStatus.isApplied(TownsteadMixinStatus.MIXIN_DIALOGUE_ENTRY), "client mixin applied"));
        check("null queries fail safely", () -> {
            require(!TownsteadBridge.villager(null).isAvailable(), "null villager");
            require(!TownsteadBridge.needs(null).isAvailable(), "null needs");
            require(!TownsteadBridge.calendar(null).isAvailable(), "null calendar");
            require(!TownsteadBridge.buildingAt(null, null).isAvailable(), "null building");
            require(!TownsteadBridge.feedInCustody(null, null, null).isAvailable(), "null feeding");
        });
        if (!installed || !enabled) {
            check("disabled queries unavailable", () -> {
                require(!TownsteadBridge.villager(villager).isAvailable(), "villager read active");
                require(!TownsteadBridge.calendar(server).isAvailable(), "calendar read active");
                require(TownsteadSnapshotCache.snapshot(villager) == TownsteadSnapshotCache.UNKNOWN, "cache active");
            });
            return;
        }
        Object state = invoke("villager.TownsteadVillagers", "get", villager);
        Object life = call(state, "life");
        call(life, "setRoot", "townstead_roots:overworlder");
        call(life, "setCurrentStageId", "adult");
        check("all declared capabilities bound", () -> require(TownsteadBridge.capabilities().size() == 12
                && TownsteadBridge.unresolvedMembers().isEmpty(), TownsteadBridge.status()));
        check("live villager identity", () -> require(TownsteadBridge.villager(villager).orElse(null).uuid()
                .equals(villager.getUUID()), "wrong UUID"));
        check("live needs and schedule", () -> {
            require(TownsteadBridge.needs(villager).isAvailable(), TownsteadBridge.needs(villager).describe());
            require(TownsteadBridge.schedule(villager).isAvailable(), TownsteadBridge.schedule(villager).describe());
        });
        check("live life stage", () -> require(TownsteadBridge.lifeStage(villager).isAvailable(),
                TownsteadBridge.lifeStage(villager).describe()));
        check("live calendar", () -> require(TownsteadBridge.calendar(server).isAvailable(),
                TownsteadBridge.calendar(server).describe()));
        check("empty building enumeration", () -> require(TownsteadBridge.buildingsAt(level,
                new BlockPos(10000, 80, 10000)).orElse(null).isEmpty(), "unexpected building"));
        check("missing village revision and spirit", () -> {
            require(!TownsteadBridge.villageRevision(level, Integer.MAX_VALUE).isAvailable(), "invented revision");
            require(!TownsteadBridge.spirit(level, Integer.MAX_VALUE).isAvailable(), "invented spirit");
        });
        check("snapshot cache invalidation", () -> {
            var first = TownsteadSnapshotCache.snapshot(villager);
            require(first != TownsteadSnapshotCache.UNKNOWN, "cache read failed");
            require(first == TownsteadSnapshotCache.snapshot(villager), "cache not reused");
            TownsteadSnapshotCache.clearAll();
            require(first != TownsteadSnapshotCache.snapshot(villager), "cache not cleared");
        });
        check("collapse blocks witnesses and guards, recovery restores them", () -> {
            Object needs = call(state, "needs");
            call(needs, "setCollapsed", true);
            TownsteadSnapshotCache.clearAll();
            require(!NpcAwareness.canObserveAct(villager), "collapsed witness active");
            require(!NpcAwareness.canRespondAsGuard(villager), "collapsed guard active");
            call(needs, "setCollapsed", false);
            TownsteadSnapshotCache.clearAll();
            require(NpcAwareness.canObserveAct(villager), "recovered witness inactive");
        });
        check("guard rest yields to its actual argument", () -> {
            Villager guard = (Villager) villager(level);
            var profession = ForgeRegistries.VILLAGER_PROFESSIONS.getValue(new ResourceLocation("mca", "guard"));
            require(profession != null, "guard profession absent");
            guard.setVillagerData(guard.getVillagerData().setProfession(profession));
            guard.getBrain().setSchedule(Schedule.VILLAGER_DEFAULT);
            guard.getBrain().eraseMemory(MemoryModuleType.HOME);
            level.setDayTime(14000);
            WalkTarget walk = new WalkTarget(new BlockPos(10, 80, 10), 0.6F, 1);
            guard.getBrain().setMemory(MemoryModuleType.WALK_TARGET, walk);
            TownsteadTickContext.observe(guard);
            invoke("tick.GuardRestEnforcerTicker", "tick", guard);
            require(guard.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isEmpty(), "fixture never reached rest erasure");
            guard.getBrain().setMemory(MemoryModuleType.WALK_TARGET, walk);
            claim(guard, level.getGameTime());
            TownsteadTickContext.observe(villager);
            invoke("tick.GuardRestEnforcerTicker", "tick", guard);
            require(guard.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null) == walk, "escort walk erased");
        });
        check("reaction locks respect escort and release", () -> {
            long now = level.getGameTime();
            claim(villager, now);
            invoke("reaction.ReactionLockTracker", "lock", villager, now, 40, new ResourceLocation("mcacrime", "test"));
            require(!(boolean) invoke("reaction.ReactionLockTracker", "isLocked", villager, now), "escort locked");
            CrimeActivityRegistry.forget(villager.getUUID());
            invoke("reaction.ReactionLockTracker", "lock", villager, now, 40, new ResourceLocation("mcacrime", "test"));
            require((boolean) invoke("reaction.ReactionLockTracker", "isLocked", villager, now), "free villager not locked");
        });
        check("existing reaction yields when escort takes control", () -> {
            Villager actor = (Villager) villager(level);
            long now = level.getGameTime();
            WalkTarget oldWalk = new WalkTarget(new BlockPos(1, 80, 1), 0.6F, 1);
            WalkTarget escortWalk = new WalkTarget(new BlockPos(12, 80, 12), 0.6F, 1);
            actor.getBrain().setMemory(MemoryModuleType.WALK_TARGET, oldWalk);
            invoke("reaction.ReactionLockTracker", "lock", actor, now, 40, new ResourceLocation("mcacrime", "test"));
            claim(actor, now);
            actor.getBrain().setMemory(MemoryModuleType.WALK_TARGET, escortWalk);
            invoke("reaction.ReactionLockTracker", "tickFreeze", actor, now + 1);
            require(actor.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null) == escortWalk,
                    "existing reaction erased escort walk");
            actor.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            invoke("reaction.ReactionLockTracker", "tickFreeze", actor, now + 41);
            require(actor.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isEmpty(), "stale pre-escort walk restored");
        });
        check("work tool display and stash restore", () -> {
            LivingEntity worker = worker(level);
            ItemStack original = worker.getMainHandItem();
            TownsteadTickContext.observe(worker);
            invoke("tick.WorkToolTicker", "tick", worker);
            require(TownsteadEquipmentProvenance.classify(worker, EquipmentSlot.MAINHAND, worker.getMainHandItem())
                    == TownsteadEquipmentProvenance.Origin.TEMPORARY_DISPLAY, "display untracked");
            require(ItemStack.matches(original, TownsteadEquipmentProvenance.stashedOriginal(worker.getUUID())), "stash lost");
            ((Villager) worker).setVillagerData(((Villager) worker).getVillagerData().setProfession(VillagerProfession.NONE));
            invoke("tick.WorkToolTicker", "tick", worker);
            require(ItemStack.matches(original, worker.getMainHandItem()), "hand not restored");
            require(!TownsteadEquipmentProvenance.tracked(worker.getUUID()), "provenance not cleared");
        });
        check("work tool copies use argument without tick context", () -> {
            LivingEntity worker = worker(level);
            TownsteadTickContext.clear();
            invoke("tick.WorkToolTicker", "tick", worker);
            require(TownsteadEquipmentProvenance.classify(worker, EquipmentSlot.MAINHAND, worker.getMainHandItem())
                    == TownsteadEquipmentProvenance.Origin.TEMPORARY_DISPLAY, "display untracked outside tick");
        });
        check("work tool forget uses argument, not previous tick", () -> {
            LivingEntity target = worker(level);
            LivingEntity previous = worker(level);
            TownsteadEquipmentProvenance.displayTool(target.getUUID(), new ItemStack(Items.IRON_HOE));
            TownsteadEquipmentProvenance.displayTool(previous.getUUID(), new ItemStack(Items.IRON_HOE));
            TownsteadTickContext.observe(previous);
            invoke("tick.WorkToolTicker", "forget", target);
            require(!TownsteadEquipmentProvenance.tracked(target.getUUID()), "target record remains");
            require(TownsteadEquipmentProvenance.tracked(previous.getUUID()), "unrelated record deleted");
        });
        check("protected storage outside entity tick", () -> storage(level, null));
        check("storage uses its own dimension", () -> storage(server.getLevel(Level.NETHER), villager));
        check("property switch off restores sourcing", () -> {
            McaCrimeConfig.COMMON.townsteadPropertyLaw.set(false);
            TownsteadTickContext.observe(villager);
            require(!protectedStorage(level, new BlockPos(32, 80, 32)), "protection stayed active");
        });
        check("non-food cannot start custody consumption", () -> require(
                !TownsteadBridge.feedInCustody(villager, new ItemStack(Items.STONE), null).orElse(true), "stone accepted"));
        check("custody feeding consumes one supply and does not double-feed", () -> {
            BlockPos hold = new BlockPos(0, 80, 0);
            level.getChunkAt(hold);
            level.setBlockAndUpdate(hold, Blocks.CHEST.defaultBlockState());
            Container chest = (Container) level.getBlockEntity(hold);
            chest.setItem(0, new ItemStack(Items.BREAD, 2));
            Object needs = call(state, "needs");
            call(needs, "setHunger", 0);
            call(needs, "setThirst", 20);
            CustodyRecord record = new CustodyRecord(villager.getUUID(), false, true,
                    CustodyOwner.guard(UUID.randomUUID()), RestraintType.CUFFS, 0, hold, level.dimension().location());
            record.setRemainingJailTicks(1200);
            CustodyCareService.clearAll();
            var data = CrimeWorldData.get(server);
            require(CustodyCareService.tick(level, data, record, villager) == CustodyCareService.Result.FED,
                    "hungry prisoner was not fed");
            require(chest.getItem(0).getCount() == 1, "meal did not consume exactly one bread");
            require(CustodyCareService.tick(level, data, record, villager) == CustodyCareService.Result.SKIPPED,
                    "care interval ignored");
            require(chest.getItem(0).getCount() == 1, "repeat care consumed another bread");
            require(record.getRemainingJailTicks() == 1200, "feeding altered sentence");
            level.removeBlock(hold, false);
        });
        check("custody recovery preserves sentence through save and load", () -> {
            BlockPos hold = new BlockPos(100, 80, 100);
            level.getChunkAt(hold);
            CustodyRecord record = new CustodyRecord(villager.getUUID(), false, true,
                    CustodyOwner.guard(UUID.randomUUID()), RestraintType.CUFFS, 0, hold, level.dimension().location());
            record.setRemainingJailTicks(1200);
            CustodyCareService.clearAll();
            require(CustodyCareService.tick(level, CrimeWorldData.get(server), record, villager)
                    == CustodyCareService.Result.RECOVERING, "starving prisoner remained confined");
            record = CustodyRecord.load(record.save());
            require(record.getRemainingJailTicks() == 1200, "recovery erased sentence");
            call(call(state, "needs"), "setHunger", 100);
            CustodyCareService.clearAll();
            require(CustodyCareService.tick(level, CrimeWorldData.get(server), record, villager)
                    == CustodyCareService.Result.RECOVERED, "recovered prisoner did not resume custody");
            require(record.getRemainingJailTicks() == 1200, "recovery completion erased sentence");
        });
        check("global switch disables live queries and can rebind", () -> {
            try {
                McaCrimeConfig.COMMON.townsteadEnabled.set(false);
                require(!TownsteadBridge.villager(villager).isAvailable(), "query ignored kill switch");
                require(!TownsteadBridge.isAvailable(), "bridge still effective");
                require(TownsteadSnapshotCache.snapshot(villager) == TownsteadSnapshotCache.UNKNOWN, "stale cache active");
                TownsteadBridge.reload();
                require(TownsteadBridge.state() == TownsteadBridge.State.OFF, "reload did not turn off");
                McaCrimeConfig.COMMON.townsteadEnabled.set(true);
                TownsteadBridge.reload();
                require(TownsteadBridge.villager(villager).isAvailable(), "reload did not re-enable");
            } finally {
                McaCrimeConfig.COMMON.townsteadEnabled.set(true);
                TownsteadBridge.reload();
            }
        });
        check("cleanup while disabled cannot resurrect a stash", () -> {
            try {
                TownsteadEquipmentProvenance.stashedOriginal(villager.getUUID(), new ItemStack(Items.DIAMOND));
                McaCrimeConfig.COMMON.townsteadEquipmentProvenance.set(false);
                invoke("tick.WorkToolTicker", "forget", villager);
                McaCrimeConfig.COMMON.townsteadEquipmentProvenance.set(true);
                require(!TownsteadEquipmentProvenance.tracked(villager.getUUID()), "obsolete stash survived cleanup");
            } finally { McaCrimeConfig.COMMON.townsteadEquipmentProvenance.set(true); }
        });
        check("all common mixins applied", () -> {
            Class.forName(ROOT + "tick.GuardRestEnforcerTicker");
            for (String name : List.of("GuardRestYieldMixin", "ReactionLockGateMixin", "WorkToolProvenanceMixin", "StoragePolicyMixin"))
                require(TownsteadMixinStatus.isApplied(name), name + " did not apply");
        });
    }

    private void storage(ServerLevel level, LivingEntity previous) throws Exception {
        McaCrimeConfig.COMMON.townsteadPropertyLaw.set(true);
        BlockPos pos = new BlockPos(level == server.overworld() ? 32 : 64, 80, 32);
        var policy = PropertyPolicy.container(level.dimension().location(), pos, null, PropertyOwnerKind.FACILITY,
                null, PropertyAccessRule.FORBIDDEN, true, PropertySource.MANUAL, "runtime-check", level.getGameTime());
        require(PropertyRegistry.put(server, policy), "could not register policy");
        if (previous == null) TownsteadTickContext.clear(); else TownsteadTickContext.observe(previous);
        require(protectedStorage(level, pos), "reserved storage can be sourced");
        require(!protectedStorage(level, new BlockPos(33, 80, 32)), "unclaimed storage protected");
    }

    private boolean protectedStorage(ServerLevel level, BlockPos pos) throws Exception {
        Object context = Class.forName(ROOT + "storage.StorageSearchContext").getConstructor(ServerLevel.class).newInstance(level);
        return (boolean) context.getClass().getMethod("isProtectedStorage", BlockPos.class,
                net.minecraft.world.level.block.state.BlockState.class).invoke(context, pos, Blocks.CHEST.defaultBlockState());
    }

    private LivingEntity worker(ServerLevel level) {
        LivingEntity worker = villager(level);
        level.setDayTime(6000);
        worker.setId((int) ((10 - level.getGameTime() % 10) % 10));
        Villager v = (Villager) worker;
        v.setVillagerData(v.getVillagerData().setProfession(VillagerProfession.FARMER));
        v.getBrain().setSchedule(Schedule.VILLAGER_DEFAULT);
        ((AbstractVillager) worker).getInventory().setItem(0, new ItemStack(Items.IRON_HOE));
        worker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
        return worker;
    }

    private LivingEntity villager(ServerLevel level) {
        var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("mca", "male_villager"));
        if (type == null || !ForgeRegistries.ENTITY_TYPES.getKey(type).toString().equals("mca:male_villager")) throw new AssertionError("MCA male entity type missing");
        return (LivingEntity) type.create(level);
    }

    private void claim(LivingEntity entity, long now) {
        require(CrimeActivityRegistry.claim(entity.getUUID(), entity.level().dimension().location(),
                CrimeActivityView.Kind.ESCORT, "runtime-check", now) != 0, "claim refused");
    }

    private static Object invoke(String type, String name, Object... args) throws Exception {
        for (Method method : Class.forName(ROOT + type).getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                return method.invoke(null, args);
            }
        }
        throw new NoSuchMethodException(type + "." + name);
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length)
                return method.invoke(target, args);
        }
        throw new NoSuchMethodException(name);
    }

    private void check(String name, Checked body) {
        try { body.run(); results.add("PASS " + name); }
        catch (Throwable failure) { results.add("FAIL " + name + ": " + failure); failure.printStackTrace(); }
        finally { TownsteadTickContext.clear(); CrimeActivityRegistry.clearAll(); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
