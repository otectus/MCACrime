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
    private ServerPlayer player(UUID id) {
        ServerPlayer p = new ServerPlayer(server, server.overworld(), new GameProfile(id, "JusticeFixture"));
        p.connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet) {}
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
            require(Boolean.FALSE.equals(query.invoke(null, assault, actor, thief, server.overworld().damageSources().playerAttack(actor), 2F)), "ordinary villagers retain companion penalties");
            McaCompat.setVillagerProfession(thief, CriminalProfessions.THIEF_ID);
            dev.otectus.mcacrime.McaCrimeConfig.COMMON.enableReputation.set(true);
            results.add("PASS patched companion capability remains per-action during integration handback");
        }
        results.add("PASS next-hit profession revalidation and native scope cleanup");
        rules.getRule(CrimeGameRules.THIEF_COMBAT_POLICY).set(0, server); thief.invulnerableTime = 0;
        thief.hurt(server.overworld().damageSources().playerAttack(actor), 2);
        require(McaCompat.getHearts(actor, thief) < before, "normal law keeps native blame");
        results.add("PASS normal-law mode preserves native heart loss");
        rules.getRule(CrimeGameRules.THIEF_COMBAT_POLICY).set(2, server);
        LivingEntity killed = thief(4.5); killed.setHealth(1); int killHearts = McaCompat.getHearts(actor, killed);
        killed.hurt(server.overworld().damageSources().playerAttack(actor), 20);
        require(killed.isDeadOrDying(), "lethal hit confirmed");
        require(McaCompat.getHearts(actor, killed) == killHearts, "lethal hit native hearts preserved");
        results.add("PASS lethal native action retains sampled exemption");
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
        server.saveEverything(false, true, true);
    }
}
