package dev.otectus.mcacrime.runtime;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.compat.McaMailBridge;
import dev.otectus.mcacrime.compat.mca.McaHandles;
import dev.otectus.mcacrime.config.CrimeGameRules;
import dev.otectus.mcacrime.detect.DamageIncidentService;
import dev.otectus.mcacrime.job.*;
import dev.otectus.mcacrime.mixin.mca.McaJusticeMixinPlugin;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import java.nio.file.*;
import java.util.*;

@Mod("crime_justice_checks")
public final class JusticeRuntimeChecks {
    private MinecraftServer server; private int ticks;
    private final List<String> results = new ArrayList<>();
    public JusticeRuntimeChecks() { MinecraftForge.EVENT_BUS.addListener(this::start); MinecraftForge.EVENT_BUS.addListener(this::tick); }
    private void start(ServerStartedEvent e) { server = e.getServer(); }
    private void tick(TickEvent.ServerTickEvent e) {
        if (server == null || e.phase != TickEvent.Phase.END || ++ticks != 10) return;
        try {
            run(); results.add("COMPLETE " + results.size() + " checks");
        } catch (Throwable failure) { failure.printStackTrace(); results.add("FAIL " + failure); }
        try { Files.write(Path.of("runtime-results.txt"), results); } catch (Exception ex) { throw new RuntimeException(ex); }
        results.forEach(System.out::println); server.halt(false);
    }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    /** What each fixture player was told, by translation key, so a refusal can say which one it was. */
    private final Map<UUID, List<String>> told = new HashMap<>();
    private void heard(UUID id, Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket chat) {
            String said = chat.content().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents key
                    ? key.getKey() : chat.content().getString();
            told.computeIfAbsent(id, unused -> new ArrayList<>()).add(said);
        }
    }
    private String toldTo(ServerPlayer player) { return String.valueOf(told.getOrDefault(player.getUUID(), List.of())); }
    private ServerPlayer player(UUID id) {
        ServerPlayer p = new ServerPlayer(server, server.overworld(), new GameProfile(id, "JusticeFixture"));
        p.connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet) { heard(id, packet); }
            @Override public void send(Packet<?> packet, net.minecraft.network.PacketSendListener listener) { heard(id, packet); }
        }, p);
        p.setPos(0.5, 65, 0.5); server.overworld().addNewPlayer(p);
        try {
            for (var field : net.minecraft.server.players.PlayerList.class.getDeclaredFields()) {
                if (field.getGenericType().getTypeName().equals("java.util.List<net.minecraft.server.level.ServerPlayer>")) {
                    field.setAccessible(true);
                    try { ((List<ServerPlayer>) field.get(server.getPlayerList())).add(p); } catch (UnsupportedOperationException immutableView) { }
                }
                if (field.getGenericType().getTypeName().contains("java.util.UUID, net.minecraft.server.level.ServerPlayer")) {
                    field.setAccessible(true); ((Map<UUID, ServerPlayer>) field.get(server.getPlayerList())).put(id, p);
                }
            }
        } catch (ReflectiveOperationException failure) { throw new RuntimeException(failure); }
        return p;
    }
    /**
     * 0.7.7 murder hearts on the transformed MCA classes: MCA charges twenty hearts to every villager
     * within 32 blocks of a killing, with no sight check. With the native hooks, a neighbour behind a wall
     * keeps its hearts, a witness who saw it loses MCA's twenty exactly once (MCA: Crime adds no loss of
     * its own on top), and with murderHeartPenalty off nobody loses any.
     */
    private void murderHearts() {
        var level = server.overworld();
        int y = 120;
        for (int x = 195; x <= 216; x++) for (int z = -6; z <= 6; z++) {
            level.setBlockAndUpdate(new net.minecraft.core.BlockPos(x, y - 1, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            for (int h = 0; h < 4; h++) level.setBlockAndUpdate(new net.minecraft.core.BlockPos(x, y + h, z), x == 209
                    ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState() : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
        LivingEntity victim = villagerAt(204.5, y, 0.5), witness = villagerAt(204.5, y, 3.5), neighbour = villagerAt(212.5, y, 0.5);
        ServerPlayer killer = player(UUID.randomUUID());
        killer.setPos(206.5, y, 0.5);
        var penalty = dev.otectus.mcacrime.McaCrimeConfig.COMMON.murderHeartPenalty;
        var rule = dev.otectus.mcacrime.McaCrimeConfig.COMMON.murderHeartsRequireWitness;
        boolean penaltyBefore = penalty.get(), ruleBefore = rule.get();
        try {
            penalty.set(true); rule.set(true);
            require(witness.hasLineOfSight(killer) && !neighbour.hasLineOfSight(killer) && !neighbour.hasLineOfSight(victim),
                    "fixture sight lines");
            int witnessBefore = McaCompat.getHearts(killer, witness), neighbourBefore = McaCompat.getHearts(killer, neighbour);
            victim.hurt(level.damageSources().playerAttack(killer), 1000F);
            require(victim.isDeadOrDying(), "victim died");
            DamageIncidentService.flush(server);
            require(McaCompat.getHearts(killer, neighbour) == neighbourBefore,
                    "walled-off neighbour lost " + (neighbourBefore - McaCompat.getHearts(killer, neighbour)) + " hearts");
            require(McaCompat.getHearts(killer, witness) == witnessBefore - 20,
                    "sighted witness lost " + (witnessBefore - McaCompat.getHearts(killer, witness)) + " hearts, not twenty once");
            penalty.set(false);
            LivingEntity second = villagerAt(205.5, y, 2.5);
            int witnessMid = McaCompat.getHearts(killer, witness), neighbourMid = McaCompat.getHearts(killer, neighbour);
            second.hurt(level.damageSources().playerAttack(killer), 1000F);
            require(second.isDeadOrDying(), "second victim died");
            DamageIncidentService.flush(server);
            require(McaCompat.getHearts(killer, witness) == witnessMid && McaCompat.getHearts(killer, neighbour) == neighbourMid,
                    "hearts moved with murderHeartPenalty off");
        } finally {
            penalty.set(penaltyBefore); rule.set(ruleBefore);
            witness.discard(); neighbour.discard();
        }
    }
    private LivingEntity villagerAt(double x, double y, double z) {
        var level = server.overworld();
        LivingEntity v = (LivingEntity) ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("mca", "male_villager")).create(level);
        ((Villager) v).setAge(0); ((Mob) v).setNoAi(true); v.setPos(x, y, z); level.addFreshEntity(v);
        return v;
    }
    private LivingEntity thief(double x) {
        var level = server.overworld();
        LivingEntity v = (LivingEntity) ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("mca", "male_villager")).create(level);
        ((Villager) v).setAge(0); v.setPos(x, 65, 0.5); level.addFreshEntity(v);
        var result = WorldCriminalJobService.of(server).tryAssign(v.getUUID(), CriminalJob.THIEF, true);
        require(result.eligible(), "thief assignment: " + result);
        require(McaCompat.isAdultVillager(v), "fixture adult"); return v;
    }
    private void reportJourney() throws Exception {
        var level = server.overworld();
        ServerPlayer victim = player(UUID.randomUUID()); victim.setPos(20.5,65,0.5);
        LivingEntity robber = thief(22.0);
        var session = dev.otectus.mcacrime.mug.npc.NpcMuggingService.begin(level, robber, victim).orElseThrow(() -> new AssertionError("visible threat began"));
        UUID caseId = session.transactionId();
        var before = CrimeWorldData.get(server).observationsBy(victim.getUUID());
        require(before.stream().anyMatch(o -> o.incidentId().equals(caseId)), "victim observation captured at threat");
        int grace = dev.otectus.mcacrime.McaCrimeConfig.COMMON.thiefDefenseGraceTicks.get();
        level.getGameRules().getRule(CrimeGameRules.THIEF_COMBAT_POLICY).set(1, server);
        dev.otectus.mcacrime.McaCrimeConfig.COMMON.thiefDefenseGraceTicks.set(0);
        server.getWorldData().overworldData().setGameTime(level.getGameTime() + 2);
        require(dev.otectus.mcacrime.justice.ThiefCombatService.sample(robber, victim, "damage").exempt(),
                "identified active threat remains legal with zero post-threat grace");
        dev.otectus.mcacrime.mug.npc.NpcMuggingService.abort(victim.getUUID(), dev.otectus.mcacrime.mug.npc.NpcMugAbortReason.OUT_OF_RANGE);
        require(!dev.otectus.mcacrime.justice.ThiefCombatService.sample(robber, victim, "damage").exempt(),
                "strict zero-grace evidence stops when threat ends");
        dev.otectus.mcacrime.McaCrimeConfig.COMMON.thiefDefenseGraceTicks.set(grace);
        level.getGameRules().getRule(CrimeGameRules.THIEF_COMBAT_POLICY).set(2, server);
        results.add("PASS strict active-threat evidence and zero post-threat grace");
        var record = CrimeWorldData.get(server).recordById(caseId).orElseThrow();
        require(record.type().equals(dev.otectus.mcacrime.crime.type.CrimeIds.ATTEMPTED_MUGGING), "one canonical attempted case");
        dev.otectus.mcacrime.news.CrimeNewsService.reported(new dev.otectus.mcacrime.api.event.CrimeReportEvent.Post(
                UUID.randomUUID(), caseId, UUID.randomUUID(), victim.getUUID(), robber.getUUID(), record.type(), null, 1F, false));
        require(CrimeWorldData.get(server).news().facts.values().stream().noneMatch(f -> f.caseId().equals(caseId)), "synthetic report post cannot publish hidden case");
        results.add("PASS visible-threat escape retains original attempted case and evidence");
        LivingEntity guard = (LivingEntity) ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("mca", "male_villager")).create(level);
        ((Villager)guard).setAge(0);
        ((Villager)guard).setVillagerData(((Villager)guard).getVillagerData().setProfession(ForgeRegistries.VILLAGER_PROFESSIONS.getValue(new ResourceLocation("mca", "guard"))));
        guard.setPos(21,65,1); level.addFreshEntity(guard);
        require(dev.otectus.mcacrime.detect.EntitySelectors.isAvailableResponder(guard), "guard available");
        var menu = dev.otectus.mcacrime.report.PlayerCrimeReportService.open(victim, guard);
        require(menu != null && !menu.choices().isEmpty(), "report menu offers victim evidence");
        var choice = menu.choices().stream().filter(c -> c.caseId().equals(caseId)).findFirst().orElseThrow();
        var accepted = dev.otectus.mcacrime.report.PlayerCrimeReportService.submit(victim, menu.id(), menu.revision(), guard.getUUID(), choice.evidenceId(), UUID.randomUUID());
        require(accepted.accepted(), "report accepted: " + accepted.messageKey());
        require(CrimeWorldData.get(server).reportsAgainst(robber.getUUID()).size() == 1, "one civilian report");
        require(!CrimeWorldData.get(server).reportsAgainst(robber.getUUID()).get(0).authoritative(), "civilian account not caught in act");
        results.add("PASS peaceful report validated and filed once");
        var canonical = CrimeWorldData.get(server).reportsAgainst(robber.getUUID()).get(0);
        var incident = new dev.otectus.mcacrime.enforcement.ActiveIncidentRegistry.ActiveIncident(canonical.reportId(), robber.getUUID(), victim.getUUID(), level.dimension(), level.getGameTime(),
                java.util.EnumSet.of(dev.otectus.mcacrime.ledger.CrimeFlag.NPC_OFFENDER), dev.otectus.mcacrime.enforcement.ActiveIncidentRegistry.Phase.COMMITTED);
        require(dev.otectus.mcacrime.enforcement.NpcArrestService.arrest(level, robber, guard, incident), "reported robbery supports actual arrest");
        require(CrimeWorldData.get(server).getCustody(robber.getUUID()).isLawful(), "lawful custody committed");
        require(!dev.otectus.mcacrime.justice.ThiefCombatService.sample(robber, victim, "damage").exempt(), "prisoner exception immediately applies");
        results.add("PASS reported attempt produces lawful custody and prisoner combat protection");
        var news = CrimeWorldData.get(server).news();
        require(news.facts.values().stream().anyMatch(f -> victim.getUUID().equals(f.recipient()) && caseId.equals(f.caseId())), "canonical report creates personal news");
        var process = dev.otectus.mcacrime.news.CrimeNewsService.class.getDeclaredMethod("process", ServerPlayer.class, long.class);
        process.setAccessible(true); process.invoke(null, victim, level.getGameTime());
        Class<?> mailboxClass = Class.forName(McaHandles.resolution().root() + "server.world.data.PlayerSaveData");
        Object newsMailbox = mailboxClass.getMethod("get", ServerLevel.class, UUID.class).invoke(null, level, victim.getUUID());
        var edition = (net.minecraft.world.item.ItemStack) mailboxClass.getMethod("getMail").invoke(newsMailbox);
        require(!edition.isEmpty() && edition.getTag().toString().contains("news.mcacrime.arrested"), "native digest reports actual custody, replacing stale wanted story");
        results.add("PASS canonical report-to-arrest outcome delivered as native MCA news letter");
    }
    // ------------------------------------------------------------------ 0.7.7: outlaws, ransom, jailbreak

    private void platform(int x0, int x1, int y, int z0, int z1) {
        var level = server.overworld();
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            level.setBlockAndUpdate(new net.minecraft.core.BlockPos(x, y - 1, z),
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            for (int h = 0; h < 6; h++) level.setBlockAndUpdate(new net.minecraft.core.BlockPos(x, y + h, z),
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
    }
    private int cases(UUID offender) { return CrimeWorldData.get(server).recordsForOffender(offender).size(); }
    private long jailbreaks(UUID offender) {
        return CrimeWorldData.get(server).recordsForOffender(offender).stream()
                .filter(r -> r.type().equals(dev.otectus.mcacrime.crime.type.CrimeIds.JAILBREAK)).count();
    }
    private int itemsNear(net.minecraft.core.BlockPos at, double radius) {
        return server.overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(at).inflate(radius)).size();
    }
    private int carried(ServerPlayer player, net.minecraft.world.item.Item item) {
        int n = 0;
        for (var stack : player.getInventory().items) if (stack.is(item)) n += stack.getCount();
        return n;
    }
    private LivingEntity guardAt(double x, double y, double z) {
        var level = server.overworld();
        LivingEntity guard = (LivingEntity) ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("mca", "male_villager")).create(level);
        ((Villager) guard).setAge(0); ((Mob) guard).setNoAi(true);
        ((Villager) guard).setVillagerData(((Villager) guard).getVillagerData().setProfession(
                ForgeRegistries.VILLAGER_PROFESSIONS.getValue(new ResourceLocation("mca", "guard"))));
        guard.setPos(x, y, z); level.addFreshEntity(guard);
        return guard;
    }

    /**
     * Fighting MCA's Outlaw is legal: a blow, an arrow and a kill in front of a witness file nothing, move
     * no Karma or Heat, cost the outlaw's hearts nothing and give a guard no basis; an ordinary villager
     * hit the same way is charged; an outlaw in cuffs is protected; with the setting off it is an
     * ordinary villager again.
     */
    private void outlawVillagerCombat() {
        var level = server.overworld();
        int y = 140;
        platform(236, 266, y, -8, 8);
        var legal = dev.otectus.mcacrime.McaCrimeConfig.COMMON.outlawCombatLegal;
        boolean legalBefore = legal.get();
        LivingEntity outlaw = villagerAt(250.5, y, 0.5), witness = villagerAt(250.5, y, 3.5),
                ordinary = villagerAt(240.5, y, 0.5), cuffed = villagerAt(246.5, y, 0.5);
        LivingEntity guard = guardAt(252.5, y, 4.5);
        ServerPlayer attacker = player(UUID.randomUUID()); attacker.setPos(252.5, y, 0.5);
        try {
            legal.set(true);
            for (LivingEntity v : List.of(outlaw, cuffed)) require(McaCompat.setVillagerProfession(v,
                    new ResourceLocation("mca", "outlaw")), "outlaw profession set");
            require(McaCompat.isOutlaw(outlaw) && !McaCompat.isOutlaw(ordinary), "MCA's Outlaw is recognised by profession");
            var decision = dev.otectus.mcacrime.justice.ThiefCombatService.sample(outlaw, attacker, "damage");
            require(decision != null && decision.reason() == dev.otectus.mcacrime.justice.ThiefCombatPolicy.Reason.OUTLAW
                    && decision.exemptsKill(), "every policy exempts an outlaw, killing included: " + decision);
            int before = cases(attacker.getUUID());
            long karma = dev.otectus.mcacrime.engine.CrimeState.getKarma(attacker), heat = dev.otectus.mcacrime.engine.CrimeState.getHeat(attacker);
            int outlawHearts = McaCompat.getHearts(attacker, outlaw);
            require(outlaw.hurt(level.damageSources().playerAttack(attacker), 3F), "outlaw struck");
            DamageIncidentService.flush(server);
            require(McaCompat.getHearts(attacker, outlaw) == outlawHearts, "MCA's own hurt hearts held back for an outlaw");
            outlaw.invulnerableTime = 0;
            var arrow = new net.minecraft.world.entity.projectile.Arrow(level, attacker);
            require(outlaw.hurt(level.damageSources().arrow(arrow, attacker), 3F), "outlaw shot");
            DamageIncidentService.flush(server);
            int witnessHearts = McaCompat.getHearts(attacker, witness);
            require(witness.hasLineOfSight(attacker) && witness.hasLineOfSight(outlaw), "the witness watched");
            outlaw.invulnerableTime = 0;
            outlaw.hurt(level.damageSources().playerAttack(attacker), 1000F);
            require(outlaw.isDeadOrDying(), "outlaw killed");
            DamageIncidentService.flush(server);
            require(cases(attacker.getUUID()) == before, "no case for hitting, shooting or killing an outlaw");
            require(dev.otectus.mcacrime.engine.CrimeState.getKarma(attacker) == karma
                    && dev.otectus.mcacrime.engine.CrimeState.getHeat(attacker) == heat, "outlaw combat moves no Karma or Heat");
            require(McaCompat.getHearts(attacker, witness) == witnessHearts, "a witness to an outlaw's death keeps every heart");
            require(!dev.otectus.mcacrime.justice.JusticeService.forGuard(level, guard, attacker).mayChallenge(),
                    "a guard has no basis to halt the outlaw's killer");
            results.add("PASS hitting, shooting and killing MCA's Outlaw files nothing and costs no hearts, even watched");
            dev.otectus.mcacrime.restraint.RestraintService.applySystemIssued(cuffed,
                    dev.otectus.mcacrime.restraint.RestraintDefinitions.HANDCUFFS_ARMS,
                    dev.otectus.mcacrime.restraint.RestraintSlot.ARMS,
                    dev.otectus.mcacrime.restraint.AppliedRestraint.ApplicationContext.ADMINISTRATIVE, null);
            require(cuffed.hurt(level.damageSources().playerAttack(attacker), 3F), "cuffed outlaw struck");
            DamageIncidentService.flush(server);
            require(cases(attacker.getUUID()) == before + 1, "an outlaw in cuffs is a prisoner, and hitting one is charged");
            require(ordinary.hurt(level.damageSources().playerAttack(attacker), 3F), "ordinary villager struck");
            DamageIncidentService.flush(server);
            require(cases(attacker.getUUID()) == before + 2, "an ordinary villager is still protected");
            results.add("PASS an outlaw in cuffs and an ordinary villager stay protected");
            if (net.minecraftforge.fml.ModList.get().isLoaded("mcareputation")) {
                try {
                    Class<?> api = Class.forName("dev.otectus.mcareputation.api.CoreIncidentExemptions");
                    Class<? extends Enum> kind = (Class<? extends Enum>) Class.forName("dev.otectus.mcareputation.api.CoreIncidentKind");
                    var query = api.getMethod("exempt", kind, ServerPlayer.class, LivingEntity.class, net.minecraft.world.damagesource.DamageSource.class, float.class);
                    LivingEntity another = villagerAt(244.5, y, 3.5);
                    McaCompat.setVillagerProfession(another, new ResourceLocation("mca", "outlaw"));
                    var hit = level.damageSources().playerAttack(attacker);
                    require(Boolean.TRUE.equals(query.invoke(null, Enum.valueOf(kind, "MCA_VILLAGER_ASSAULT"), attacker, another, hit, 3F))
                            && Boolean.TRUE.equals(query.invoke(null, Enum.valueOf(kind, "MCA_VILLAGER_KILL"), attacker, another, hit, 1000F)),
                            "Reputation is told an outlaw's assault and killing are exempt");
                    legal.set(false);
                    require(Boolean.FALSE.equals(query.invoke(null, Enum.valueOf(kind, "MCA_VILLAGER_KILL"), attacker, another, hit, 1000F)),
                            "with the setting off Reputation keeps the killing");
                    another.discard();
                    results.add("PASS MCA: Reputation exemption follows outlawCombatLegal");
                } catch (ReflectiveOperationException failure) { throw new RuntimeException(failure); }
            }
            legal.set(false);
            LivingEntity plain = villagerAt(248.5, y, -3.5);
            McaCompat.setVillagerProfession(plain, new ResourceLocation("mca", "outlaw"));
            require(plain.hurt(level.damageSources().playerAttack(attacker), 3F), "outlaw struck with the setting off");
            DamageIncidentService.flush(server);
            require(cases(attacker.getUUID()) == before + 3, "off, an outlaw is an ordinary protected villager");
            plain.discard();
            results.add("PASS outlawCombatLegal off restores the outlaw's protection");
        } finally {
            legal.set(legalBefore);
            witness.discard(); ordinary.discard(); cuffed.discard(); guard.discard();
        }
    }

    /**
     * Fighting a Red-band player is legal with PvP crime on: a blow, an arrow and a kill file nothing,
     * the outlaw is neither hunted by the guards nor bounty-eligible, and a Grey player hit the same way
     * is still charged.
     */
    private void outlawPlayerCombat() {
        var level = server.overworld();
        var rules = level.getGameRules();
        boolean pvpBefore = rules.getBoolean(CrimeGameRules.PVP_CRIME);
        rules.getRule(CrimeGameRules.PVP_CRIME).set(true, server);
        var legal = dev.otectus.mcacrime.McaCrimeConfig.COMMON.outlawCombatLegal;
        boolean legalBefore = legal.get();
        try {
            legal.set(true);
            ServerPlayer attacker = player(UUID.randomUUID()); attacker.setPos(270.5, 65, 0.5);
            ServerPlayer outlaw = player(UUID.randomUUID()); outlaw.setPos(271.5, 65, 0.5);
            ServerPlayer citizen = player(UUID.randomUUID()); citizen.setPos(271.5, 65, 2.5);
            dev.otectus.mcacrime.engine.CrimeState.setKarma(outlaw, -500L, dev.otectus.mcacrime.crime.KarmaSource.ADMIN);
            require(dev.otectus.mcacrime.engine.CrimeState.getBand(outlaw) == dev.otectus.mcacrime.crime.Band.RED, "fixture outlaw is Red");
            var status = dev.otectus.mcacrime.enforcement.OutlawResolver.resolve(outlaw);
            require(status.attackLawful() && status.killLawful(), "anybody may fight and kill an outlaw");
            require(!status.lawfulCombatTarget() && !status.bountyEligible(),
                    "with redIsLegalTarget off the guards do not hunt them and nobody is paid for them");
            var hit = level.damageSources().playerAttack(attacker);
            var shot = level.damageSources().arrow(new net.minecraft.world.entity.projectile.Arrow(level, attacker), attacker);
            int before = cases(attacker.getUUID());
            long heat = dev.otectus.mcacrime.engine.CrimeState.getHeat(attacker);
            dev.otectus.mcacrime.detect.CrimeDetector.onHarm(outlaw, hit, 3F, level);
            dev.otectus.mcacrime.detect.CrimeDetector.onHarm(outlaw, shot, 3F, level);
            DamageIncidentService.flush(server);
            outlaw.setHealth(0F);
            dev.otectus.mcacrime.detect.CrimeDetector.onKill(outlaw, hit, level);
            DamageIncidentService.flush(server);
            require(cases(attacker.getUUID()) == before, "no assault or murder case for fighting an outlaw");
            require(dev.otectus.mcacrime.engine.CrimeState.getHeat(attacker) == heat, "no Heat for fighting an outlaw");
            LivingEntity guard = guardAt(270.5, 65, 3.5);
            require(!dev.otectus.mcacrime.justice.JusticeService.forGuard(level, guard, attacker).mayChallenge(),
                    "a guard has no basis to halt the outlaw's killer");
            guard.discard();
            dev.otectus.mcacrime.detect.CrimeDetector.onHarm(citizen, hit, 3F, level);
            DamageIncidentService.flush(server);
            require(cases(attacker.getUUID()) == before + 1, "a Grey player is still protected");
            results.add("PASS fighting a Red player is legal, no hunt and no bounty follow, and a Grey player stays protected");
        } finally {
            legal.set(legalBefore);
            rules.getRule(CrimeGameRules.PVP_CRIME).set(pvpBefore, server);
        }
    }

    /**
     * A paid ransom frees the captive's body: cuffs put on by the kidnapping and a gag added during it
     * come off with exactly one item each, the chain is paid back once, the payment moves once, and a
     * repeated or unaffordable settlement frees nobody and pays nothing.
     */
    private void ransomRelease() {
        var level = server.overworld();
        int y = 160;
        platform(40, 58, y, 34, 46);
        var data = CrimeWorldData.get(server);
        var hand = net.minecraft.world.InteractionHand.MAIN_HAND;
        var arms = dev.otectus.mcacrime.restraint.RestraintSlot.ARMS;
        var head = dev.otectus.mcacrime.restraint.RestraintSlot.HEAD;
        var unlawful = dev.otectus.mcacrime.restraint.AppliedRestraint.ApplicationContext.UNLAWFUL;
        ServerPlayer captor = player(UUID.randomUUID()); captor.setPos(48.5, y, 40.5);
        LivingEntity victim = villagerAt(49.5, y, 40.5);
        captor.setItemInHand(hand, new net.minecraft.world.item.ItemStack(dev.otectus.mcacrime.item.CrimeItems.RESTRAINT_CUFFS.get()));
        var cuffs = dev.otectus.mcacrime.restraint.RestraintService.apply(captor, victim, hand, arms, unlawful);
        require(cuffs.applied(), "cuffs applied: " + cuffs.refusal());
        var custody = data.getCustody(victim.getUUID());
        require(custody != null && !custody.isLawful(), "the cuffs began a kidnapping");
        require(custody.getCustodyId().equals(data.physicalRestraint(victim.getUUID()).slot(arms).orElseThrow().custodyId()),
                "the kidnapping adopted the cuffs that began it");
        captor.setItemInHand(hand, new net.minecraft.world.item.ItemStack(dev.otectus.mcacrime.item.CrimeItems.DUCK_TAPE.get()));
        var gag = dev.otectus.mcacrime.restraint.RestraintService.apply(captor, victim, hand, head, unlawful);
        require(gag.applied(), "gag applied: " + gag.refusal());
        require(custody.getCustodyId().equals(data.physicalRestraint(victim.getUUID()).slot(head).orElseThrow().custodyId()),
                "the gag added during the kidnapping belongs to it");
        require(dev.otectus.mcacrime.tether.TetherService.attach(data, victim, captor,
                dev.otectus.mcacrime.tether.TetherKind.CHAIN, captor.getUUID(), true).isPresent(), "chained to the captor");
        long owed = data.physicalRestraint(victim.getUUID()).slots().values().stream()
                .filter(w -> w.provenance() == dev.otectus.mcacrime.restraint.AppliedRestraint.Provenance.PLAYER_OWNED
                        && w.returnPolicy() != dev.otectus.mcacrime.restraint.AppliedRestraint.ReturnPolicy.NONE).count();
        int chains = carried(captor, net.minecraft.world.item.Items.CHAIN);
        int drops = itemsNear(victim.blockPosition(), 4);
        long paid = dev.otectus.mcacrime.economy.Currencies.active().balance(captor);
        require(dev.otectus.mcacrime.ransom.RansomService.demandFor(captor, victim.getUUID()) == 1,
                "the village fallback settled; the captor was told " + toldTo(captor));
        require(data.getCustody(victim.getUUID()) == null, "the captive is out of custody");
        var after = data.physicalRestraint(victim.getUUID());
        require(after == null || !after.restrained(), "no restraint is left on the ransomed villager: " + after);
        require(dev.otectus.mcacrime.tether.TetherService.forSubject(data, victim.getUUID()).isEmpty(), "no chain is left on them");
        require(carried(captor, net.minecraft.world.item.Items.CHAIN) == chains + 1, "the chain is paid back once");
        require(itemsNear(victim.blockPosition(), 4) == drops + owed, "each supplied restraint dropped exactly once: " + owed);
        long settled = dev.otectus.mcacrime.economy.Currencies.active().balance(captor);
        require(settled > paid, "the captor was paid");
        require(dev.otectus.mcacrime.ransom.RansomService.demandFor(captor, victim.getUUID()) == 0, "a repeated settlement is refused");
        require(dev.otectus.mcacrime.economy.Currencies.active().balance(captor) == settled
                && itemsNear(victim.blockPosition(), 4) == drops + owed, "and moves no money and no items");
        results.add("PASS a paid ransom takes off the kidnapping's cuffs and gag, pays the chain back, and settles once");
        captor.setPos(52.5, y, 43.5);
        LivingEntity second = villagerAt(53.5, y, 43.5);
        captor.setItemInHand(hand, new net.minecraft.world.item.ItemStack(dev.otectus.mcacrime.item.CrimeItems.RESTRAINT_CUFFS.get()));
        require(dev.otectus.mcacrime.restraint.RestraintService.apply(captor, second, hand, arms, unlawful).applied(), "second captive cuffed");
        // Read after the cuffs went into the hand: the first payment landed in the slot they now occupy.
        long unpaid = dev.otectus.mcacrime.economy.Currencies.active().balance(captor);
        var base = dev.otectus.mcacrime.McaCrimeConfig.COMMON.ransomBaseAmount;
        int baseBefore = base.get();
        try {
            base.set(1_000_000);
            require(dev.otectus.mcacrime.ransom.RansomService.demandFor(captor, second.getUUID()) == 0, "an unaffordable village ransom is refused");
            require(data.getCustody(second.getUUID()) != null && data.physicalRestraint(second.getUUID()).restrained(),
                    "a payment that failed frees nobody");
            require(dev.otectus.mcacrime.economy.Currencies.active().balance(captor) == unpaid, "and pays nothing");
        } finally {
            base.set(baseBefore);
            dev.otectus.mcacrime.captivity.CustodyService.release(server, second.getUUID(),
                    dev.otectus.mcacrime.captivity.CustodyReleaseReason.ADMIN);
        }
        require(!data.physicalRestraint(second.getUUID()).restrained(), "an operator's release takes the kidnapping's cuffs off too");
        results.add("PASS a failed ransom payment leaves the captive held, restrained and unpaid for");
    }

    /**
     * jailContainmentMode = PHYSICAL lets a prisoner dig out of an assigned jail, the live setting reaching
     * a sentence already being served; walking out is one jailbreak with no teleport back, and switching
     * back to CONTAINMENT does not pull an escapee in.
     */
    private void physicalJailbreak() {
        var level = server.overworld();
        int y = 180;
        var anchor = new net.minecraft.core.BlockPos(80, y, 40);
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) for (int dy = -1; dy <= 4; dy++) {
            var pos = anchor.offset(dx, dy, dz);
            boolean shell = dy == -1 || dy == 3 || (Math.abs(dx) == 3 && Math.abs(dz) <= 3) || (Math.abs(dz) == 3 && Math.abs(dx) <= 3);
            boolean inside = Math.abs(dx) <= 3 && Math.abs(dz) <= 3 && dy <= 3;
            level.setBlockAndUpdate(pos, (inside && shell) || dy == -1
                    ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()
                    : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
        var mode = dev.otectus.mcacrime.McaCrimeConfig.COMMON.jailContainmentMode;
        var modeBefore = mode.get();
        ServerPlayer prisoner = player(UUID.randomUUID()); prisoner.setPos(80.5, y, 40.5);
        try {
            mode.set(dev.otectus.mcacrime.jail.JailContainmentMode.CONTAINMENT);
            require(dev.otectus.mcacrime.jail.JailService.jail(prisoner, 24000L,
                    new dev.otectus.mcacrime.jail.JailAnchor(anchor, level.dimension().location(), 3)), "jailed in an assigned jail");
            var wall = anchor.offset(3, 0, 0);
            require(!prisoner.gameMode.destroyBlock(wall) && level.getBlockState(wall).is(net.minecraft.world.level.block.Blocks.STONE),
                    "CONTAINMENT refuses the prisoner's break");
            mode.set(dev.otectus.mcacrime.jail.JailContainmentMode.PHYSICAL);
            require(prisoner.gameMode.destroyBlock(wall) && level.getBlockState(wall).isAir(),
                    "switching to PHYSICAL lets a prisoner already serving dig out");
            long charged = jailbreaks(prisoner.getUUID());
            var data = dev.otectus.mcacrime.state.CrimeCapabilities.get(prisoner).orElseThrow();
            prisoner.teleportTo(level, 87.5, y, 40.5, java.util.Set.of(), 0F, 0F);
            dev.otectus.mcacrime.jail.JailConfine.tick(prisoner, data);
            require(data.getJail().isEscaped(), "walking out of a PHYSICAL jail is an escape");
            require(jailbreaks(prisoner.getUUID()) == charged + 1, "charged as exactly one jailbreak");
            require(prisoner.getX() > 86.0, "and not teleported back");
            dev.otectus.mcacrime.jail.JailConfine.tick(prisoner, data);
            require(jailbreaks(prisoner.getUUID()) == charged + 1, "the escape is charged once");
            mode.set(dev.otectus.mcacrime.jail.JailContainmentMode.CONTAINMENT);
            dev.otectus.mcacrime.jail.JailConfine.tick(prisoner, data);
            require(prisoner.getX() > 86.0 && data.getJail().isEscaped(), "switching back to CONTAINMENT does not pull an escapee in");
            results.add("PASS PHYSICAL lets a serving prisoner dig out; leaving is one jailbreak and no snap-back, even after a switch back");
        } finally {
            mode.set(modeBefore);
            dev.otectus.mcacrime.jail.JailService.release(prisoner, dev.otectus.mcacrime.jail.ReleaseReason.ADMIN);
        }
    }

    /**
     * Digging out of a built holding cell under PHYSICAL: the cell's reinforced stone drops nothing even
     * to a diamond pickaxe, HARD_CONTAINMENT still holds a reinforced wall, the escape takes the cell down,
     * and the dug-out floor is restored rather than left a hole.
     */
    private void builtCellDigOut() {
        var level = server.overworld();
        int y = 200;
        platform(110, 132, y, 30, 50);
        var mode = dev.otectus.mcacrime.McaCrimeConfig.COMMON.jailContainmentMode;
        var modeBefore = mode.get();
        var policy = dev.otectus.mcacrime.McaCrimeConfig.COMMON.reinforcedBreakingPolicy;
        String policyBefore = policy.get();
        ServerPlayer prisoner = player(UUID.randomUUID()); prisoner.setPos(120.5, y, 40.5);
        UUID sentence = UUID.randomUUID();
        try {
            mode.set(dev.otectus.mcacrime.jail.JailContainmentMode.PHYSICAL);
            policy.set("PICKAXE_QUALIFIED");
            var cell = dev.otectus.mcacrime.jail.HoldingCellService.provision(level, new net.minecraft.core.BlockPos(120, y, 40),
                    prisoner.getUUID(), sentence);
            require(cell != null, "a holding cell was built");
            require(dev.otectus.mcacrime.jail.JailService.jail(prisoner, 24000L, cell.toAnchor(), sentence, false), "jailed in the built cell");
            var data = dev.otectus.mcacrime.state.CrimeCapabilities.get(prisoner).orElseThrow();
            require(data.getJail().isTemporaryCell(), "the sentence knows its cell is temporary");
            var stone = dev.otectus.mcacrime.block.CrimeBlocks.REINFORCED_STONE.get();
            var floor = cell.placed().entrySet().stream()
                    .filter(e -> e.getKey().getY() < cell.anchor().getY() && e.getValue().is(stone))
                    .map(java.util.Map.Entry::getKey).findFirst().orElseThrow();
            var pillar = cell.placed().entrySet().stream()
                    .filter(e -> e.getKey().getY() >= cell.anchor().getY() && e.getValue().is(stone))
                    .map(java.util.Map.Entry::getKey).findFirst().orElseThrow();
            var original = cell.replaced().get(floor);
            prisoner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
            int drops = itemsNear(floor, 3);
            require(prisoner.gameMode.destroyBlock(floor) && level.getBlockState(floor).isAir(), "PHYSICAL lets the prisoner break the cell floor");
            require(itemsNear(floor, 3) == drops, "a built cell's reinforced stone drops nothing, even to a diamond pickaxe");
            policy.set("HARD_CONTAINMENT");
            require(!prisoner.gameMode.destroyBlock(pillar) && level.getBlockState(pillar).is(stone), "HARD_CONTAINMENT still holds a reinforced wall");
            policy.set("PICKAXE_QUALIFIED");
            long charged = jailbreaks(prisoner.getUUID());
            var out = cell.outsideStand();
            prisoner.teleportTo(level, out.getX() + 0.5, out.getY(), out.getZ() + 0.5, java.util.Set.of(), 0F, 0F);
            dev.otectus.mcacrime.jail.JailConfine.tick(prisoner, data);
            require(data.getJail().isEscaped() && jailbreaks(prisoner.getUUID()) == charged + 1, "out of the cell is one jailbreak");
            require(prisoner.blockPosition().getX() == out.getX() && prisoner.blockPosition().getZ() == out.getZ(),
                    "with no teleport back");
            require(dev.otectus.mcacrime.jail.HoldingCellService.existingFor(server, prisoner.getUUID()) == null, "the cell came down");
            require(level.getBlockState(floor).equals(original), "the dug-out floor was restored to " + original
                    + ", not left a hole: " + level.getBlockState(floor));
            results.add("PASS a built cell dug out under PHYSICAL drops nothing, comes down on the escape and restores the hole");
        } finally {
            mode.set(modeBefore); policy.set(policyBefore);
            dev.otectus.mcacrime.jail.JailService.release(prisoner, dev.otectus.mcacrime.jail.ReleaseReason.ADMIN);
            dev.otectus.mcacrime.jail.HoldingCellService.dismantle(server, prisoner.getUUID());
        }
    }
    private void run() throws Exception {
        Class.forName(McaHandles.resolution().root() + "server.world.data.PlayerSaveData");
        require(McaJusticeMixinPlugin.APPLIED.containsAll(Set.of("VillagerEntityMCA", "Relationship", "PlayerSaveData")),
                "native hooks: " + McaJusticeMixinPlugin.APPLIED);
        results.add("PASS native descriptor-gated hooks applied");
        var rules = server.overworld().getGameRules(); rules.getRule(CrimeGameRules.USE_WORLD_RULES).set(true, server);
        rules.getRule(CrimeGameRules.THIEF_COMBAT_POLICY).set(2, server);
        ServerPlayer actor = player(UUID.fromString("96d28945-733b-4a65-af56-6519c9d3d854"));
        int existingCases = CrimeWorldData.get(server).recordsForOffender(actor.getUUID()).size();
        long karmaBefore = dev.otectus.mcacrime.engine.CrimeState.getKarma(actor);
        long heatBefore = dev.otectus.mcacrime.engine.CrimeState.getHeat(actor);
        LivingEntity thief = thief(2.5); int before = McaCompat.getHearts(actor, thief);
        ServerPlayer remoteOwner = new ServerPlayer(server, server.getLevel(net.minecraft.world.level.Level.NETHER),
                new GameProfile(UUID.randomUUID(), "RemoteArrowOwner"));
        var arrow = new net.minecraft.world.entity.projectile.Arrow(server.overworld(), remoteOwner);
        require(dev.otectus.mcacrime.justice.ThiefCombatService.sample(thief,
                server.overworld().damageSources().arrow(arrow, remoteOwner), "damage").exempt(),
                "attributed arrow keeps policy after its owner changes dimension");
        require(thief.hurt(server.overworld().damageSources().playerAttack(actor), 2), "harm applied");
        require(McaCompat.getHearts(actor, thief) == before, "native hurt hearts preserved");
        DamageIncidentService.flush(server);
        require(CrimeWorldData.get(server).recordsForOffender(actor.getUUID()).size() == existingCases, "exempt damage no crime");
        require(dev.otectus.mcacrime.engine.CrimeState.getKarma(actor) == karmaBefore
                && dev.otectus.mcacrime.engine.CrimeState.getHeat(actor) == heatBefore, "exempt damage preserves exact Karma and Heat");
        results.add("PASS default thief harm suppresses MCA hearts and Crime charge");
        require(dev.otectus.mcacrime.compat.mca.NativeCombatContext.decision(thief, server.overworld().damageSources().playerAttack(actor)) == null, "scope removed after action");
        McaCompat.setVillagerProfession(thief, new ResourceLocation("minecraft", "farmer"));
        require(!dev.otectus.mcacrime.justice.ThiefCombatService.sample(thief, actor, "damage").exempt(), "native job change invalidates exemption before sweep");
        McaCompat.setVillagerProfession(thief, CriminalProfessions.THIEF_ID);
        if (net.minecraftforge.fml.ModList.get().isLoaded("mcareputation")) {
            require(dev.otectus.mcacrime.compat.ReputationExemptionBridge.status().equals("capability 1 registered"), "companion capability registered");
            Class<?> api = Class.forName("dev.otectus.mcareputation.api.CoreIncidentExemptions");
            Class<? extends Enum> kind = (Class<? extends Enum>)Class.forName("dev.otectus.mcareputation.api.CoreIncidentKind");
            var query = api.getMethod("exempt", kind, ServerPlayer.class, LivingEntity.class, net.minecraft.world.damagesource.DamageSource.class, float.class);
            Object assault = Enum.valueOf(kind, "MCA_VILLAGER_ASSAULT");
            dev.otectus.mcacrime.McaCrimeConfig.COMMON.enableReputation.set(false);
            require(Boolean.TRUE.equals(query.invoke(null, assault, actor, thief, server.overworld().damageSources().playerAttack(actor), 2F)), "exemption survives ordinary integration handback");
            McaCompat.setVillagerProfession(thief, new ResourceLocation("minecraft", "farmer"));
            // 3F: above detection.minorHarm.minorHarmMaxDamage, so this is an ordinary assault (0.7.7).
            require(Boolean.FALSE.equals(query.invoke(null, assault, actor, thief, server.overworld().damageSources().playerAttack(actor), 3F)), "ordinary villagers retain companion penalties");
            // 0.7.7: a first light hit MCA: Crime will forgive as minor friendly fire is exempt there too.
            require(Boolean.TRUE.equals(query.invoke(null, assault, actor, thief, server.overworld().damageSources().playerAttack(actor), 1F)), "a forgivable minor hit is exempt for Reputation as well");
            McaCompat.setVillagerProfession(thief, CriminalProfessions.THIEF_ID);
            dev.otectus.mcacrime.McaCrimeConfig.COMMON.enableReputation.set(true);
            results.add("PASS patched companion capability remains per-action during integration handback");
        }
        results.add("PASS next-hit profession revalidation and native scope cleanup");
        rules.getRule(CrimeGameRules.THIEF_COMBAT_POLICY).set(0, server); thief.invulnerableTime = 0;
        // 3 damage: above detection.minorHarm.minorHarmMaxDamage, so MCA's hurt hearts are not held back
        // as a possible minor friendly-fire hit (0.7.7) and land at once, as normal law has them.
        thief.hurt(server.overworld().damageSources().playerAttack(actor), 3);
        require(McaCompat.getHearts(actor, thief) < before, "normal law keeps native blame");
        results.add("PASS normal-law mode preserves native heart loss");
        rules.getRule(CrimeGameRules.THIEF_COMBAT_POLICY).set(2, server);
        LivingEntity killed = thief(4.5); killed.setHealth(1); int killHearts = McaCompat.getHearts(actor, killed);
        killed.hurt(server.overworld().damageSources().playerAttack(actor), 20);
        require(killed.isDeadOrDying(), "lethal hit confirmed");
        require(McaCompat.getHearts(actor, killed) == killHearts, "lethal hit native hearts preserved");
        results.add("PASS lethal native action retains sampled exemption");
        murderHearts();
        results.add("PASS walled-off neighbour keeps hearts, sighted witness loses MCA's twenty once, penalty off costs nobody");
        UUID edition = UUID.fromString("9271a254-58c9-4e61-a03d-c77fdfcde9c5");
        CompoundTag letter = new CompoundTag(); ListTag pages = new ListTag(); pages.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal("Watch — recovered property")))); letter.put("pages", pages);
        boolean restarted = Files.exists(Path.of("justice-mail-stage"));
        var outcome = McaMailBridge.enqueue(actor, edition, letter, server.overworld().getGameTime());
        require(outcome == (restarted ? McaMailBridge.Outcome.ALREADY_DELIVERED : McaMailBridge.Outcome.ENQUEUED), "mail outcome " + outcome);
        Class<?> cls = Class.forName(McaHandles.resolution().root() + "server.world.data.PlayerSaveData");
        Object mailbox = cls.getMethod("get", ServerLevel.class, UUID.class).invoke(null, server.overworld(), actor.getUUID());
        if (!restarted) {
            Object mail = cls.getMethod("getMail").invoke(mailbox);
            require(mail instanceof net.minecraft.world.item.ItemStack && !((net.minecraft.world.item.ItemStack)mail).isEmpty(), "native MCA letter collected");
            require(((SavedData)mailbox).isDirty(), "mail collection marks same object dirty");
            require(McaMailBridge.enqueue(actor, edition, letter, server.overworld().getGameTime()) == McaMailBridge.Outcome.ALREADY_DELIVERED, "post-collection retry deduplicated");
            Files.writeString(Path.of("justice-mail-stage"), "collected");
        } else require(Boolean.FALSE.equals(cls.getMethod("hasMail").invoke(mailbox)), "collected letter stays absent after restart");
        results.add(restarted ? "PASS normal save/restart retains native receipt after collection" : "PASS native inbox append, collection, dirty marking and duplicate retry");
        reportJourney();
        outlawVillagerCombat();
        outlawPlayerCombat();
        ransomRelease();
        physicalJailbreak();
        builtCellDigOut();
        server.saveEverything(false, true, true);
    }
}
