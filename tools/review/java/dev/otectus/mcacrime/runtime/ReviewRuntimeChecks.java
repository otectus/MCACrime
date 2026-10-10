package dev.otectus.mcacrime.runtime;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.block.*;
import dev.otectus.mcacrime.captivity.*;
import dev.otectus.mcacrime.detention.*;
import dev.otectus.mcacrime.enchantment.*;
import dev.otectus.mcacrime.entity.PadlockEntity;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.locks.*;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.restraint.*;
import dev.otectus.mcacrime.state.*;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.tether.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Exercises production services and Forge dispatch with real registries in a disposable world. */
@Mod("crime_review_checks")
public final class ReviewRuntimeChecks {
    private final List<String> results = new ArrayList<>();
    private MinecraftServer server;
    private ServerLevel level;
    private CrimeWorldData data;
    private int ticks;
    private int fixture;
    private boolean finished;
    /** Checks that need the world to run: polled once a tick until they hold or their time is up. */
    private final List<Waiting> waiting = new ArrayList<>();

    private record Waiting(String name, int deadline, java.util.function.Supplier<String> problem, Runnable cleanup) {}

    public ReviewRuntimeChecks() {
        MinecraftForge.EVENT_BUS.addListener(this::started);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
    }

    private void started(ServerStartedEvent event) { server = event.getServer(); }

    private void tick(TickEvent.ServerTickEvent event) {
        if (server == null || finished || event.phase != TickEvent.Phase.END || ++ticks < 5) return;
        if (ticks == 5) {
            level = server.overworld();
            data = CrimeWorldData.get(server);
            try {
                runChecks();
            } catch (Throwable failure) {
                results.add("FAIL harness: " + failure);
                failure.printStackTrace();
            }
        }
        poll();
        if (waiting.isEmpty()) finish();
    }

    private void finish() {
        finished = true;
        results.add("COMPLETE " + results.size() + " checks");
        try { Files.write(Path.of("runtime-results.txt"), results); }
        catch (Exception failure) { throw new RuntimeException(failure); }
        results.forEach(System.out::println);
        server.halt(false);
    }

    /** Registers a check that needs ticks to pass; {@code problem} answers null once it holds. */
    private void await(String name, int timeoutTicks, java.util.function.Supplier<String> problem, Runnable cleanup) {
        waiting.add(new Waiting(name, ticks + timeoutTicks, problem, cleanup));
    }

    private void poll() {
        for (Iterator<Waiting> it = waiting.iterator(); it.hasNext(); ) {
            Waiting check = it.next();
            String problem;
            try {
                problem = check.problem().get();
            } catch (Throwable failure) {
                failure.printStackTrace();
                problem = failure.toString();
                results.add("FAIL " + check.name() + ": " + problem);
                cleanUp(check);
                it.remove();
                continue;
            }
            if (problem == null) {
                results.add("PASS " + check.name());
            } else if (ticks < check.deadline()) {
                continue;
            } else {
                results.add("FAIL " + check.name() + ": " + problem);
            }
            cleanUp(check);
            it.remove();
        }
    }

    private static void cleanUp(Waiting check) {
        try { check.cleanup().run(); }
        catch (Throwable failure) { failure.printStackTrace(); }
    }

    private void runChecks() {
        check("death clone preserves crime data through capability invalidation", this::deathClone);
        check("jail arrival ignores only the arriving prisoner's occupancy", this::jailArrival);
        check("padlock owner initializes exactly one working key", this::padlockKey);
        check("chest joins preserve opening and automation protection", this::chestJoins);
        check("detained player cannot attack or break device", this::detentionActions);
        check("cuffed actor cannot apply cuffs through HIGH event router", this::cuffedActor);
        check("rejected Imbue transfers do not discount captor damage", () -> imbue(false));
        check("cuffed captor can passively transfer accepted Imbue damage", () -> imbue(true));
        check("Forge-canceled Imbue damage cannot grant damage reduction", this::imbueCanceled);
        check("escort takeover restores suspended chain", this::escortTakeover);
        check("boat and minecart seating passes entity interaction dispatch", this::vehicleSeating);
        check("cuffed actor cannot attach chains before restriction handler", this::cuffedChainActor);
        check("struggle slot changes retain accepted-input interval", this::struggleSwitch);
        check("bunk sleep wake reuse and respawn hooks", this::bunkSleep);
        check("bunk refused or disabled spawn changes preserve home", this::bunkRefusal);
        check("custody release restores home after repeated bunk sleep", () -> bunkRelease(false));
        check("offline release restores persisted bunk snapshot on login", () -> bunkRelease(true));
        check("bunk release preserves a newer chosen home", this::bunkNewHome);
        check("bunk release leaves another dimension's chosen spawn intact", this::bunkNewDimension);
        check("forced recruitment sweep leaves an established librarian intact", this::establishedLibrarian);
        // 2026-10-04 corrections (0.7.7), mirrored by the NeoForge CorrectionsGameTests.
        check("cell door and reinforced bars keep every property and default of their vanilla parents", this::cellParentParity);
        check("only a canonical adult Thief may start or finish a mugging", this::thiefActorMatrix);
        check("a child holding a Thief record cannot mug", this::childThief);
        check("a mugging victim may hit the mugger first; killing under NORMAL_LAW is excessive force", this::muggingDefense);
        check("a mugger in custody is protected", this::custodyMugger);
        check("a victim recovers stolen property from a living thief through the search", this::stolenRecovery);
        check("a player thief's death escrows nothing they carry; an NPC thief's death still escrows", this::thiefDeathEscrow);
        check("a guard fighting a monster keeps its own fight when released", this::guardMonster);
        check("a guard is told to fight a Wanted player who refused arrest", this::guardForce);
        check("serving a sentence settles its own Heat and no more", this::sentenceHeat);
        check("captivity cap serves, operator release forgives nothing, a pardon frees", this::sentenceExits);
        check("a cell with its door walled off still has a way out", this::walledCellExit);
    }

    /**
     * 0.7.7: a master librarian in a trading hall has no MCA home village, so the wild route took it and
     * the occupation transaction cleared its trades. A forced sweep at chance 1.0 must leave an
     * established villager's profession, level, XP, offers and job site exactly as they were, while the
     * unemployed adult beside it is recruited by the same pass, which proves the sweep ran.
     */
    private void establishedLibrarian() {
        TestPlayer observer = player();
        BlockPos base = observer.blockPosition();
        var jobs = dev.otectus.mcacrime.job.WorldCriminalJobService.of(server);
        net.minecraft.world.entity.Mob librarian = mcaVillager(base.east(2));
        net.minecraft.world.entity.Mob blank = mcaVillager(base.west(2));
        try {
            var trader = (net.minecraft.world.entity.npc.Villager) librarian;
            ResourceLocation librarianId = new ResourceLocation("minecraft", "librarian");
            require(dev.otectus.mcacrime.compat.McaCompat.setVillagerProfession(librarian, librarianId),
                    "cannot make the fixture a librarian");
            trader.setVillagerData(trader.getVillagerData().setLevel(5));
            trader.setVillagerXp(250);
            var offers = trader.getOffers();
            if (offers.isEmpty()) {
                offers.add(new net.minecraft.world.item.trading.MerchantOffer(new ItemStack(Items.EMERALD),
                        new ItemStack(Items.BOOK), 12, 5, 0.05F));
            }
            offers.get(0).increaseUses();
            CompoundTag offersBefore = offers.createTag();
            var site = net.minecraft.core.GlobalPos.of(level.dimension(), base.north(3));
            dev.otectus.mcacrime.compat.OccupationCompat.setJobSite(librarian, site);
            require(dev.otectus.mcacrime.job.WorldCriminalJobService.established(librarian), "librarian fixture not established");
            require(!dev.otectus.mcacrime.job.WorldCriminalJobService.established(blank), "control fixture already established");
            dev.otectus.mcacrime.job.CriminalJobAssignmentSweep.sweep(server,
                    new dev.otectus.mcacrime.job.CriminalJobAssigner.AssignmentPolicy(true, true, 1.0D, 1.0D, 1.0D, 1, 0));
            require(jobs.get(blank.getUUID()) == dev.otectus.mcacrime.job.CriminalJob.THIEF,
                    "the forced sweep did not recruit the unemployed control, so it proves nothing");
            require(data.criminalVillager(librarian.getUUID()) == null
                    && jobs.get(librarian.getUUID()) == dev.otectus.mcacrime.job.CriminalJob.NONE,
                    "the sweep wrote a criminal record for an established librarian");
            require(librarianId.equals(dev.otectus.mcacrime.compat.McaCompat.getProfessionId(librarian).orElse(null)),
                    "librarian profession became " + dev.otectus.mcacrime.compat.McaCompat.getProfessionId(librarian));
            require(trader.getVillagerData().getLevel() == 5, "librarian lost its level");
            require(trader.getVillagerXp() == 250, "librarian trading XP became " + trader.getVillagerXp());
            var offersAfter = ((dev.otectus.mcacrime.mixin.MerchantOffersAccessor) trader).mcacrime$rawOffers();
            require(offersAfter != null && offersBefore.equals(offersAfter.createTag()), "librarian offers changed or cleared");
            require(site.equals(dev.otectus.mcacrime.compat.OccupationCompat.jobSite(librarian).orElse(null)),
                    "librarian job site memory erased");
        } finally {
            jobs.retireOccupation(blank.getUUID(), dev.otectus.mcacrime.job.OccupationSource.OPERATOR);
            data.removeCriminalVillager(blank.getUUID());
            librarian.discard(); blank.discard(); observer.discard();
        }
    }

    // ------------------------------------------------------------------ 2026-10-04 corrections (0.7.7)

    /** The cell door and bars inherit their parents' state definitions instead of restating them. */
    private void cellParentParity() {
        parentParity(CrimeBlocks.CELL_DOOR.get(), Blocks.IRON_DOOR, "cell door");
        parentParity(CrimeBlocks.REINFORCED_BARS.get(), Blocks.IRON_BARS, "reinforced bars");
    }

    private static void parentParity(Block ours, Block parent, String what) {
        for (var property : parent.getStateDefinition().getProperties()) {
            var mine = ours.getStateDefinition().getProperty(property.getName());
            require(mine != null, what + " lacks the " + property.getName() + " property");
            Object ourDefault = ours.defaultBlockState().getValue(mine);
            Object parentDefault = parent.defaultBlockState().getValue(property);
            require(Objects.equals(ourDefault, parentDefault), what + " defaults " + property.getName() + " to "
                    + ourDefault + " where its parent defaults to " + parentDefault);
        }
    }

    /**
     * A valid Thief may start a mugging; the same villager as a guard may not; made a nitwit by somebody
     * else mid-mugging it loses the session with nothing taken and cannot start another, and the
     * lifecycle then retires the stale record without overwriting the nitwit.
     */
    private void thiefActorMatrix() {
        BlockPos base = fixtureSite();
        ServerPlayer victim = realPlayer(base, false);
        net.minecraft.world.entity.Mob thief = stationThief(base.east(1), base.east(3));
        var jobs = dev.otectus.mcacrime.job.WorldCriminalJobService.of(server);
        ResourceLocation nitwit = new ResourceLocation("minecraft", "nitwit");
        boolean handedOver = false;
        try {
            victim.getInventory().setItem(12, new ItemStack(Items.EMERALD, 64));
            require(execution(jobs, thief).eligible(),
                    "a committed adult Thief may not act: " + jobs.diagnose(thief).executionDetail());
            dev.otectus.mcacrime.compat.McaCompat.setVillagerProfession(thief, new ResourceLocation("mca", "guard"));
            require(execution(jobs, thief).reason() == dev.otectus.mcacrime.job.NpcMuggerEligibilityReason.RESPONDER,
                    "a guard holding a Thief record may act: " + execution(jobs, thief).reason());
            require(dev.otectus.mcacrime.mug.npc.NpcMuggingService.begin(level, thief, victim).isEmpty(),
                    "a guard started a mugging");
            dev.otectus.mcacrime.compat.McaCompat.setVillagerProfession(thief,
                    dev.otectus.mcacrime.job.CriminalProfessions.THIEF_ID);
            require(execution(jobs, thief).eligible(), "restoring the Thief profession did not restore it");
            require(dev.otectus.mcacrime.mug.npc.NpcMuggingService.begin(level, thief, victim).isPresent(),
                    "a valid Thief could not start a mugging");
            dev.otectus.mcacrime.compat.McaCompat.setVillagerProfession(thief, nitwit);
            require(execution(jobs, thief).reason() == dev.otectus.mcacrime.job.NpcMuggerEligibilityReason.NOT_A_THIEF,
                    "a nitwit with a stale Thief record may act: " + execution(jobs, thief).reason());
            String detail = jobs.diagnose(thief).executionDetail();
            require(detail.contains("minecraft:nitwit"), "the diagnosis does not name the nitwit: " + detail);
            dev.otectus.mcacrime.mug.npc.NpcMuggingService.tick(server);
            require(dev.otectus.mcacrime.mug.npc.NpcMuggingService.sessionForThief(thief.getUUID()).isEmpty(),
                    "the mugging survived its actor becoming a nitwit");
            require(victim.getInventory().getItem(12).getCount() == 64, "something was taken");
            require(data.stolenGoodsByThief(thief.getUUID()).isEmpty(), "a theft was filed");
            require(dev.otectus.mcacrime.mug.npc.NpcMuggingService.begin(level, thief, victim).isEmpty(),
                    "a nitwit started a mugging");
            await("the lifecycle retires a stale Thief record without overwriting the new profession", 200, () -> {
                var status = jobs.occupationStatus(thief.getUUID());
                if (status != dev.otectus.mcacrime.job.OccupationStatus.RETIRED) return "occupation is " + status;
                var profession = dev.otectus.mcacrime.compat.McaCompat.getProfessionId(thief);
                return nitwit.equals(profession.orElse(null)) ? null : "the profession became " + profession;
            }, () -> { offline(victim); retireThief(thief); });
            handedOver = true;
        } finally {
            if (!handedOver) { offline(victim); retireThief(thief); }
        }
    }

    private void childThief() {
        BlockPos base = fixtureSite();
        ServerPlayer victim = realPlayer(base, false);
        net.minecraft.world.entity.Mob thief = stationThief(base.east(1), base.east(3));
        var jobs = dev.otectus.mcacrime.job.WorldCriminalJobService.of(server);
        try {
            ((net.minecraft.world.entity.npc.Villager) thief).setAge(-24000);
            require(execution(jobs, thief).reason() == dev.otectus.mcacrime.job.NpcMuggerEligibilityReason.NOT_ADULT,
                    "a child Thief may act: " + execution(jobs, thief).reason());
            require(dev.otectus.mcacrime.mug.npc.NpcMuggingService.begin(level, thief, victim).isEmpty(),
                    "a child started a mugging");
        } finally {
            offline(victim); retireThief(thief);
        }
    }

    private static dev.otectus.mcacrime.job.NpcMuggerEligibility.Result execution(
            dev.otectus.mcacrime.job.WorldCriminalJobService jobs, net.minecraft.world.entity.Mob thief) {
        return jobs.evaluate(thief, dev.otectus.mcacrime.job.NpcMuggerEligibility.Context.EXECUTION);
    }

    /**
     * Under NORMAL_LAW only the threat can excuse the victim: the punch ends the mugging and is charged
     * nothing, the window outlives the session, killing the mugger is excessive defensive force, and an
     * innocent villager hit the same way is assaulted.
     */
    private void muggingDefense() {
        BlockPos base = fixtureSite();
        ServerPlayer victim = realPlayer(base, false);
        net.minecraft.world.entity.Mob thief = stationThief(base.east(1), base.east(3));
        net.minecraft.world.entity.Mob innocent = mcaVillager(base.south(3));
        var policy = McaCrimeConfig.COMMON.thiefCombatPolicy;
        var forgiveness = McaCrimeConfig.COMMON.enableMinorHarmForgiveness;
        var policyBefore = policy.get();
        boolean forgivenessBefore = forgiveness.get();
        policy.set(dev.otectus.mcacrime.justice.ThiefCombatPolicy.NORMAL_LAW);
        forgiveness.set(false);
        dev.otectus.mcacrime.config.CrimeWorldSettings.invalidate();
        try {
            require(dev.otectus.mcacrime.mug.npc.NpcMuggingService.begin(level, thief, victim).isPresent(),
                    "the mugging did not start");
            require(thief.hurt(level.damageSources().playerAttack(victim), 1F), "the punch did no damage");
            require(dev.otectus.mcacrime.mug.npc.NpcMuggingService.sessionFor(victim.getUUID()).isEmpty(),
                    "the victim's punch did not interrupt the mugging");
            dev.otectus.mcacrime.detect.DamageIncidentService.flush(server);
            require(data.recordsForOffender(victim.getUUID()).isEmpty(),
                    "fighting off a mugger was charged: " + data.recordsForOffender(victim.getUUID()));
            require(dev.otectus.mcacrime.engine.CrimeState.getHeat(victim) == 0L, "fighting off a mugger cost Heat");
            require(dev.otectus.mcacrime.mug.npc.MuggingThreats.threatens(thief.getUUID(), victim.getUUID(),
                    level.getGameTime(), McaCrimeConfig.COMMON.thiefDefenseGraceTicks.get()),
                    "the right to answer the threat ended with it");
            thief.hurt(level.damageSources().playerAttack(victim), 1000F);
            dev.otectus.mcacrime.detect.DamageIncidentService.flush(server);
            var killing = data.recordsForOffender(victim.getUUID());
            require(killing.size() == 1, "killing the mugger under NORMAL_LAW was not charged once: " + killing);
            require("excessive_defensive_force".equals(killing.get(0).context()
                            .get(dev.otectus.mcacrime.ledger.CrimeContext.COMBAT_BASIS)),
                    "the killing was not judged excessive defensive force: " + killing.get(0).context());
            require(innocent.hurt(level.damageSources().playerAttack(victim), 1F), "the innocent took no damage");
            dev.otectus.mcacrime.detect.DamageIncidentService.flush(server);
            require(data.recordsForOffender(victim.getUUID()).size() == 2,
                    "hitting an innocent villager was not charged: " + data.recordsForOffender(victim.getUUID()));
        } finally {
            policy.set(policyBefore);
            forgiveness.set(forgivenessBefore);
            dev.otectus.mcacrime.config.CrimeWorldSettings.invalidate();
            dev.otectus.mcacrime.engine.CrimeState.setHeat(victim, 0);
            innocent.discard();
            offline(victim); retireThief(thief);
        }
    }

    private void custodyMugger() {
        BlockPos base = fixtureSite();
        ServerPlayer victim = realPlayer(base, false);
        net.minecraft.world.entity.Mob thief = stationThief(base.east(1), base.east(3));
        var forgiveness = McaCrimeConfig.COMMON.enableMinorHarmForgiveness;
        boolean forgivenessBefore = forgiveness.get();
        forgiveness.set(false);
        try {
            require(dev.otectus.mcacrime.mug.npc.NpcMuggingService.begin(level, thief, victim).isPresent(),
                    "the mugging did not start");
            dev.otectus.mcacrime.mug.npc.NpcMuggingService.abort(victim.getUUID(),
                    dev.otectus.mcacrime.mug.npc.NpcMugAbortReason.GUARD_INTERVENTION);
            require(CustodyService.captureLawful(data, thief.getUUID(), false, CustodyOwner.guard(UUID.randomUUID()),
                    level.getGameTime(), thief.blockPosition(), level.dimension().location()).ok(),
                    "the mugger could not be taken into custody");
            thief.hurt(level.damageSources().playerAttack(victim), 1F);
            dev.otectus.mcacrime.detect.DamageIncidentService.flush(server);
            require(!data.recordsForOffender(victim.getUUID()).isEmpty(),
                    "hitting a mugger in custody was treated as self-defence");
        } finally {
            forgiveness.set(forgivenessBefore);
            dev.otectus.mcacrime.engine.CrimeState.setHeat(victim, 0);
            CustodyService.release(server, thief.getUUID(), CustodyReleaseReason.ADMIN);
            offline(victim); retireThief(thief);
        }
    }

    /**
     * The searcher's own stolen property is first and comes back exactly as taken with no theft filed; a
     * replay moves nothing; another victim's item taken is a new theft that still names that victim; the
     * searcher's purse comes back as currency and another victim's purse cannot be taken.
     */
    private void stolenRecovery() {
        BlockPos base = fixtureSite();
        ServerPlayer victim = realPlayer(base, false);
        net.minecraft.world.entity.Mob thief = mcaVillager(base.east(1));
        UUID other = UUID.randomUUID();
        var restraint = McaCrimeConfig.COMMON.friskRequiresArmRestraint;
        var interval = McaCrimeConfig.COMMON.friskTransferIntervalTicks;
        boolean restraintBefore = restraint.get();
        int intervalBefore = interval.get();
        restraint.set(false);
        interval.set(0); // every take in one tick; the delay itself is covered by unit tests
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.enchant(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS, 3);
        sword.setHoverName(Component.literal("Grandfather's sword"));
        sword.setDamageValue(17);
        java.util.function.Predicate<ItemStack> isSword = stack -> ItemStack.isSameItemSameTags(stack, sword);
        long now = level.getGameTime();
        UUID swordTx = UUID.randomUUID(), breadTx = UUID.randomUUID(), purseTx = UUID.randomUUID(),
                otherPurseTx = UUID.randomUUID();
        String provider = dev.otectus.mcacrime.economy.Currencies.active().id().toString();
        try {
            data.putStolenGoods(dev.otectus.mcacrime.state.world.StolenGoodsRecord.ofStack(breadTx, thief.getUUID(),
                    other, new ItemStack(Items.BREAD, 5), 0L, now - 30));
            data.putStolenGoods(dev.otectus.mcacrime.state.world.StolenGoodsRecord.ofStack(swordTx, thief.getUUID(),
                    victim.getUUID(), sword.copy(), 0L, now - 20));
            data.putStolenGoods(new dev.otectus.mcacrime.state.world.StolenGoodsRecord(purseTx, thief.getUUID(),
                    victim.getUUID(), null, 12L, now - 10, provider));
            data.putStolenGoods(new dev.otectus.mcacrime.state.world.StolenGoodsRecord(otherPurseTx, thief.getUUID(),
                    other, null, 7L, now - 5, provider));
            require(dev.otectus.mcacrime.frisk.FriskingService.open(victim, thief), "the search did not open");
            var session = dev.otectus.mcacrime.frisk.FriskingService.sessionFor(victim);
            require(session != null, "no search session");
            var view = session.view();
            require(view.size() >= 4, "the stolen property is not in the search: " + view);
            require(victim.getUUID().equals(dev.otectus.mcacrime.frisk.StolenGoodsInventoryProvider.ownerOf(thief, view.get(0)))
                    && victim.getUUID().equals(dev.otectus.mcacrime.frisk.StolenGoodsInventoryProvider.ownerOf(thief, view.get(1))),
                    "the searcher's own property is not first: " + view);

            int swordIndex = stolenIndex(thief, view, swordTx);
            int swordRevision = dev.otectus.mcacrime.frisk.FriskRevision.of(
                    dev.otectus.mcacrime.frisk.InventoryProviders.peek(thief, view.get(swordIndex)));
            var refusal = dev.otectus.mcacrime.frisk.FriskValidation.check(dev.otectus.mcacrime.frisk.FriskingService
                    .facts(victim, session, swordIndex, swordRevision, 1, true));
            if (!refusal.accepted()) {
                var a = dev.otectus.mcacrime.frisk.InventoryProviders.peek(thief, view.get(swordIndex));
                var b = dev.otectus.mcacrime.frisk.InventoryProviders.peek(thief, session.slotAt(swordIndex));
                throw new AssertionError("the owner's take was refused before it ran: " + refusal + " (revision "
                        + swordRevision + ", reread " + dev.otectus.mcacrime.frisk.FriskRevision.of(a) + "/"
                        + dev.otectus.mcacrime.frisk.FriskRevision.of(b) + ", tags " + a.getTag() + " | " + b.getTag()
                        + ", refs " + view.get(swordIndex) + " | " + session.slotAt(swordIndex) + ")");
            }
            dev.otectus.mcacrime.frisk.FriskingService.transfer(victim, session.id(), swordIndex, swordRevision, 1);
            require(data.stolenGoods(swordTx) == null, "the recovered sword is still in the ledger");
            require(count(victim, isSword) == 1, "the sword did not come back exactly as it was taken");
            require(data.stolenGoodsByThief(victim.getUUID()).isEmpty(), "recovering it was filed as theft");
            dev.otectus.mcacrime.frisk.FriskingService.transfer(victim, session.id(), swordIndex, swordRevision, 1);
            require(count(victim, isSword) == 1, "a replayed recovery duplicated the sword");

            int breadIndex = stolenIndex(thief, view, breadTx);
            dev.otectus.mcacrime.frisk.FriskingService.transfer(victim, session.id(), breadIndex,
                    dev.otectus.mcacrime.frisk.FriskRevision.of(
                            dev.otectus.mcacrime.frisk.InventoryProviders.peek(thief, view.get(breadIndex))), 2);
            var rest = data.stolenGoods(breadTx);
            require(rest != null && rest.stack().getCount() == 3, "taking two of five breads left " + rest);
            var refiled = data.stolenGoodsByThief(victim.getUUID());
            require(refiled.size() == 1 && other.equals(refiled.get(0).owner()),
                    "taking another victim's property did not stay that victim's: " + refiled);

            int emeraldsBefore = count(victim, stack -> stack.is(Items.EMERALD));
            int purseIndex = stolenIndex(thief, view, purseTx);
            dev.otectus.mcacrime.frisk.FriskingService.transfer(victim, session.id(), purseIndex,
                    dev.otectus.mcacrime.frisk.FriskRevision.of(
                            dev.otectus.mcacrime.frisk.InventoryProviders.peek(thief, view.get(purseIndex))), 1);
            require(data.stolenGoods(purseTx) == null, "the purse is still with the thief");
            require(count(victim, stack -> stack.is(Items.EMERALD)) == emeraldsBefore + 12,
                    "the purse did not come back as its own currency");

            int otherIndex = stolenIndex(thief, view, otherPurseTx);
            int revision = dev.otectus.mcacrime.frisk.FriskRevision.of(
                    dev.otectus.mcacrime.frisk.InventoryProviders.peek(thief, view.get(otherIndex)));
            var facts = dev.otectus.mcacrime.frisk.FriskingService.facts(victim, session, otherIndex, revision, 1, true);
            var result = dev.otectus.mcacrime.frisk.FriskTransaction.commit(victim, thief, session, otherIndex,
                    revision, 1, facts, data, level.getGameTime());
            require(result.refusal() == dev.otectus.mcacrime.frisk.FriskRefusal.NOT_YOUR_PURSE,
                    "somebody else's purse was taken: " + result.refusal());
            require(data.stolenGoods(otherPurseTx) != null, "the other purse moved");
        } finally {
            restraint.set(restraintBefore);
            interval.set(intervalBefore);
            data.stolenGoodsByThief(thief.getUUID()).forEach(record -> data.removeStolenGoods(record.transactionId()));
            data.stolenGoodsByThief(victim.getUUID()).forEach(record -> data.removeStolenGoods(record.transactionId()));
            dev.otectus.mcacrime.frisk.FriskingService.closeAll(victim, SessionCancelCause.CANCELLED);
            dev.otectus.mcacrime.engine.CrimeState.setHeat(victim, 0);
            thief.discard();
            offline(victim);
        }
    }

    /**
     * Confirmed death escrows ledger property only for a thief who holds it in the ledger alone. A player
     * who took something carries it, so escrowing the record as well would duplicate it.
     */
    private void thiefDeathEscrow() {
        BlockPos base = fixtureSite();
        ServerPlayer playerThief = realPlayer(base, false);
        net.minecraft.world.entity.Mob npcThief = mcaVillager(base.east(2));
        UUID owner = UUID.randomUUID(), playerTx = UUID.randomUUID(), npcTx = UUID.randomUUID();
        long now = level.getGameTime();
        try {
            data.putStolenGoods(dev.otectus.mcacrime.state.world.StolenGoodsRecord.ofStack(playerTx,
                    playerThief.getUUID(), owner, new ItemStack(Items.DIAMOND, 2), 0L, now));
            data.putStolenGoods(dev.otectus.mcacrime.state.world.StolenGoodsRecord.ofStack(npcTx,
                    npcThief.getUUID(), owner, new ItemStack(Items.EMERALD, 3), 0L, now));
            dev.otectus.mcacrime.mug.npc.StolenGoodsRecovery.confirmedDeath(level, playerThief);
            require(data.stolenGoods(playerTx) != null, "a player thief's death consumed the provenance record");
            require(data.propertyEscrow().stream().noneMatch(lot -> owner.equals(lot.owner())),
                    "a player thief's death escrowed what they still carry");
            dev.otectus.mcacrime.mug.npc.StolenGoodsRecovery.confirmedDeath(level, npcThief);
            require(data.stolenGoods(npcTx) == null, "an NPC thief's death left its record in the ledger");
            require(data.propertyEscrow().stream().filter(lot -> owner.equals(lot.owner())).count() == 1,
                    "an NPC thief's death did not escrow its property exactly once");
        } finally {
            data.removeStolenGoods(playerTx);
            data.removeStolenGoods(npcTx);
            data.propertyEscrow().stream().filter(lot -> owner.equals(lot.owner()))
                    .map(dev.otectus.mcacrime.state.world.PropertyLot::lotId).toList().forEach(data::removePropertyLot);
            npcThief.discard();
            offline(playerThief);
        }
    }

    private static int stolenIndex(net.minecraft.world.entity.LivingEntity thief,
                                   List<dev.otectus.mcacrime.frisk.FriskSlotRef> view, UUID transaction) {
        for (int i = 0; i < view.size(); i++) {
            var record = dev.otectus.mcacrime.frisk.StolenGoodsInventoryProvider.recordAt(thief, view.get(i));
            if (record != null && record.transactionId().equals(transaction)) return i;
        }
        throw new AssertionError("no slot for " + transaction + " in " + view);
    }

    private static int count(ServerPlayer player, java.util.function.Predicate<ItemStack> test) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && test.test(stack)) n += stack.getCount();
        }
        return n;
    }

    /**
     * A real MCA guard keeps the monster fight MCA gave it when this mod releases it, and damages the
     * monster. Away from the fixture grid: a guard with its brain running answers whatever is in reach.
     */
    private void guardMonster() {
        BlockPos base = isolatedSite(-80, -80);
        net.minecraft.world.entity.Mob guard = mcaGuard(base);
        guard.setNoAi(false);
        var husk = EntityType.HUSK.create(level);
        require(husk != null, "no husk");
        husk.moveTo(base.getX() + 2.5, base.getY(), base.getZ() + .5, 0, 0);
        level.addFreshEntity(husk);
        husk.setNoAi(true);
        float full = husk.getHealth();
        boolean handedOver = false;
        try {
            require(level.isPositionEntityTicking(base), "the guard site is not entity-ticking");
            guard.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET, husk);
            dev.otectus.mcacrime.compat.McaCompat.releaseVillagerControl(guard);
            require(guard.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET)
                    .filter(target -> target == husk).isPresent(), "releasing the guard took MCA's own fight away");
            await("a real MCA guard damages the monster it is fighting", 400,
                    () -> husk.getHealth() < full || husk.isDeadOrDying() ? null : "husk health " + husk.getHealth(),
                    () -> { guard.discard(); husk.discard(); });
            handedOver = true;
        } finally {
            if (!handedOver) { guard.discard(); husk.discard(); }
        }
    }

    /** A Wanted player who refuses arrest is targeted, then actually hurt, by a real MCA guard. */
    private void guardForce() {
        BlockPos base = isolatedSite(-80, -40);
        net.minecraft.world.entity.Mob guard = mcaGuard(base);
        ServerPlayer outlaw = realPlayer(base.east(2), true);
        var memory = net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET;
        Runnable tidy = () -> {
            dev.otectus.mcacrime.enforcement.GuardChallengeService.forget(outlaw.getUUID());
            dev.otectus.mcacrime.enforcement.ArrestStates.clear(outlaw);
            dev.otectus.mcacrime.enforcement.GuardEnforcement.forget(outlaw.getUUID());
            dev.otectus.mcacrime.enforcement.LawHold.clear(guard.getUUID());
            dev.otectus.mcacrime.engine.CrimeState.setHeat(outlaw, 0);
            offline(outlaw);
            guard.discard();
        };
        boolean handedOver = false;
        try {
            dev.otectus.mcacrime.engine.CrimeState.setHeat(outlaw, 100);
            require(dev.otectus.mcacrime.engine.CrimeState.isWanted(outlaw), "the fixture is not Wanted");
            scan();
            var challenge = dev.otectus.mcacrime.enforcement.GuardChallengeService.open(outlaw.getUUID());
            require(challenge != null, "the guard did not confront the Wanted player");
            dev.otectus.mcacrime.enforcement.GuardChallengeService.menuDisplayed(outlaw, challenge.encounterId());
            dev.otectus.mcacrime.enforcement.GuardChallengeService.respond(outlaw, challenge.encounterId(),
                    dev.otectus.mcacrime.enforcement.ChallengeResponse.REFUSE);
            require(dev.otectus.mcacrime.enforcement.GuardChallengeService.forcePermitted(outlaw, level.getGameTime()),
                    "refusing arrest did not permit force");
            guard.setNoAi(false);
            scan();
            require(guard.getBrain().getMemory(memory).filter(target -> target == outlaw).isPresent(),
                    "the guard was not told to fight: " + guard.getBrain().getMemory(memory));
            float full = outlaw.getHealth();
            await("a real MCA guard hurts a Wanted player who refused arrest", 600,
                    () -> outlaw.getHealth() < full ? null : "health " + outlaw.getHealth() + ", target "
                            + guard.getBrain().getMemory(memory), tidy);
            handedOver = true;
        } finally {
            if (!handedOver) tidy.run();
        }
    }

    private void scan() {
        var event = new TickEvent.ServerTickEvent(TickEvent.Phase.END, () -> true, server);
        for (int tick = 0; tick < McaCrimeConfig.COMMON.guardScanIntervalTicks.get(); tick++) {
            dev.otectus.mcacrime.enforcement.GuardEnforcement.onServerTick(event);
        }
    }

    /** Release settles the sentence's own Heat once; decay comes off it first; the cell's own crime stays. */
    private void sentenceHeat() {
        BlockPos base = fixtureSite();
        ServerPlayer prisoner = realPlayer(base, false);
        try {
            dev.otectus.mcacrime.engine.CrimeState.setHeat(prisoner, 120);
            require(dev.otectus.mcacrime.jail.JailService.jail(prisoner, 600, jailAt(base)), "could not jail");
            var jail = CrimeCapabilities.get(prisoner).orElseThrow().getJail();
            require(jail.getSentenceHeat() == 120L, "the obligation is " + jail.getSentenceHeat());
            dev.otectus.mcacrime.engine.CrimeState.addHeat(prisoner, -30);
            dev.otectus.mcacrime.engine.CrimeState.addHeat(prisoner, 20);
            require(jail.getSentenceHeat() == 90L, "decay did not come off the obligation first: " + jail.getSentenceHeat());
            dev.otectus.mcacrime.jail.JailService.release(prisoner, dev.otectus.mcacrime.jail.ReleaseReason.SENTENCE_SERVED);
            require(dev.otectus.mcacrime.engine.CrimeState.getHeat(prisoner) == 20L,
                    "release left " + dev.otectus.mcacrime.engine.CrimeState.getHeat(prisoner) + " Heat, not the cell's own 20");
            require(!dev.otectus.mcacrime.engine.CrimeState.isWanted(prisoner), "the released prisoner is still Wanted");
            require(!dev.otectus.mcacrime.jail.JailService.isJailed(prisoner), "still jailed");
            dev.otectus.mcacrime.jail.JailService.release(prisoner, dev.otectus.mcacrime.jail.ReleaseReason.SENTENCE_SERVED);
            require(dev.otectus.mcacrime.engine.CrimeState.getHeat(prisoner) == 20L, "a second release settled Heat again");
        } finally {
            freePrisoner(prisoner);
        }
    }

    /** The cap serves its cases, an operator's release forgives nothing and holds guards off, a pardon frees. */
    private void sentenceExits() {
        BlockPos base = fixtureSite();
        ServerPlayer prisoner = realPlayer(base, false);
        try {
            var capped = charge(prisoner.getUUID());
            UUID first = sentence(prisoner, capped, base);
            require(dev.otectus.mcacrime.jail.JailService.jail(prisoner, 600, jailAt(base), first, false), "first jailing failed");
            dev.otectus.mcacrime.jail.JailService.release(prisoner, dev.otectus.mcacrime.jail.ReleaseReason.CAPTIVITY_CAP);
            require(data.recordById(capped.id()).map(dev.otectus.mcacrime.ledger.CrimeRecord::resolution).orElse(null)
                    == dev.otectus.mcacrime.ledger.Resolution.SERVED, "the captivity cap left its case open");
            require(data.getCustody(prisoner.getUUID()) == null, "the cap left the custody open");

            var kept = charge(prisoner.getUUID());
            UUID second = sentence(prisoner, kept, base);
            dev.otectus.mcacrime.engine.CrimeState.setHeat(prisoner, 80);
            require(dev.otectus.mcacrime.jail.JailService.jail(prisoner, 600, jailAt(base), second, false), "second jailing failed");
            dev.otectus.mcacrime.jail.JailService.release(prisoner, dev.otectus.mcacrime.jail.ReleaseReason.ADMIN);
            var after = data.recordById(kept.id()).orElseThrow();
            require(after.actionable() && after.sentenceId() == null,
                    "an operator's release served or kept binding the case: " + after.resolution() + " " + after.sentenceId());
            require(dev.otectus.mcacrime.engine.CrimeState.getHeat(prisoner) == 80L, "an operator's release forgave Heat");
            require(dev.otectus.mcacrime.enforcement.ArrestStates.phaseOf(prisoner) == dev.otectus.mcacrime.enforcement.ArrestPhase.RECOVERY,
                    "guards are not held off after an operator's release: " + dev.otectus.mcacrime.enforcement.ArrestStates.phaseOf(prisoner));
            dev.otectus.mcacrime.enforcement.ArrestStates.clear(prisoner);

            var pardoned = charge(prisoner.getUUID());
            UUID third = sentence(prisoner, pardoned, base);
            require(dev.otectus.mcacrime.jail.JailService.jail(prisoner, 6000, jailAt(base), third, false), "third jailing failed");
            dev.otectus.mcacrime.ledger.CrimeCaseService.resolve(server, pardoned.id(), dev.otectus.mcacrime.ledger.Resolution.PARDONED,
                    dev.otectus.mcacrime.McaCrime.id("review_pardon"), "pardon:" + pardoned.id(), null, Map.of(), true);
            require(dev.otectus.mcacrime.jail.JailRecovery.inspect(prisoner).contains(dev.otectus.mcacrime.jail.JailRecovery.Finding.RESOLVED_ELSEWHERE),
                    "a fully pardoned sentence is not recognised as over: " + dev.otectus.mcacrime.jail.JailRecovery.inspect(prisoner));
            // The jail clock runs per player tick; this fixture has no connection ticking it.
            var crime = CrimeCapabilities.get(prisoner).orElseThrow();
            for (int tick = 0; tick < 40 && dev.otectus.mcacrime.jail.JailService.isJailed(prisoner); tick++) {
                crime.incrementOnlineTicks();
                dev.otectus.mcacrime.jail.JailService.tick(prisoner, crime);
            }
            require(!dev.otectus.mcacrime.jail.JailService.isJailed(prisoner), "a pardoned prisoner is still serving");
            require(data.getCustody(prisoner.getUUID()) == null, "the pardon left the custody open");
        } finally {
            freePrisoner(prisoner);
        }
    }

    private void walledCellExit() {
        BlockPos anchor = fixtureSite();
        ResourceLocation dim = level.dimension().location();
        var cell = new dev.otectus.mcacrime.jail.HoldingCell(UUID.randomUUID(), UUID.randomUUID(), anchor, dim, 2,
                level.getGameTime(), Map.of(), Map.of()).withDoor(anchor.east(2), Direction.EAST, null, null);
        BlockPos stand = cell.outsideStand();
        for (int dy = -1; dy <= 9; dy++) level.setBlock(stand.above(dy), Blocks.STONE.defaultBlockState(), 3);
        try {
            require(dev.otectus.mcacrime.jail.SafeCustodyDestination.validate(level, stand, 8).isEmpty(),
                    "the fixture did not wall the door side off");
            var exit = dev.otectus.mcacrime.jail.HoldingCellService.exitFor(server, level, cell, true);
            require(exit != null, "a walled-off door side left the prisoner no way out");
            require(!dev.otectus.mcacrime.jail.JailRegion.contains(anchor, 2, dim, exit.pos(), exit.level().dimension().location()),
                    "the way out is inside the cell: " + exit.pos());
        } finally {
            for (int dy = -1; dy <= 9; dy++) level.setBlock(stand.above(dy), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static dev.otectus.mcacrime.ledger.CrimeRecord charge(UUID offender) {
        return new dev.otectus.mcacrime.ledger.CrimeRecord(UUID.randomUUID(), offender, null,
                dev.otectus.mcacrime.crime.type.CrimeIds.THEFT, OptionalInt.empty(), true, 10L, 40L, -20L, 0L, 600L,
                dev.otectus.mcacrime.ledger.Resolution.UNRESOLVED);
    }

    private UUID sentence(ServerPlayer offender, dev.otectus.mcacrime.ledger.CrimeRecord charge, BlockPos base) {
        require(CustodyService.captureLawful(data, offender.getUUID(), true, CustodyOwner.jail(-1, base, level.dimension().location()),
                level.getGameTime(), base, level.dimension().location()).ok(), "could not take the offender into lawful custody");
        data.addRecord(charge);
        UUID sentence = UUID.randomUUID();
        require(dev.otectus.mcacrime.ledger.SentenceAssignmentService.assign(data, offender.getUUID(), sentence,
                List.of(charge.id()), level.getGameTime()), "the sentence did not bind");
        return sentence;
    }

    private dev.otectus.mcacrime.jail.JailAnchor jailAt(BlockPos base) {
        return new dev.otectus.mcacrime.jail.JailAnchor(base, level.dimension().location(), 3);
    }

    private void freePrisoner(ServerPlayer prisoner) {
        if (dev.otectus.mcacrime.jail.JailService.isJailed(prisoner)) {
            dev.otectus.mcacrime.jail.JailService.release(prisoner, dev.otectus.mcacrime.jail.ReleaseReason.ADMIN);
        }
        dev.otectus.mcacrime.engine.CrimeState.setHeat(prisoner, 0);
        dev.otectus.mcacrime.enforcement.ArrestStates.clear(prisoner);
        offline(prisoner);
    }

    /** The next cell of the fixture grid, floored, without a fake player standing in it. */
    private BlockPos fixtureSite() {
        BlockPos pos = new BlockPos((fixture++ % 8) * 16, 65, (fixture / 8) * 16);
        for (BlockPos ground : BlockPos.betweenClosed(pos.offset(-5, -1, -5), pos.offset(5, -1, 5))) {
            level.setBlock(ground, Blocks.STONE.defaultBlockState(), 2);
        }
        return pos;
    }

    /** A floored site well away from the grid, for fixtures whose brains run. */
    private BlockPos isolatedSite(int dx, int dz) {
        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos pos = new BlockPos(spawn.getX() + dx, 65, spawn.getZ() + dz);
        for (BlockPos ground : BlockPos.betweenClosed(pos.offset(-7, -1, -7), pos.offset(7, -1, 7))) {
            level.setBlock(ground, Blocks.STONE.defaultBlockState(), 2);
        }
        return pos;
    }

    /**
     * A real server player, not a {@link FakePlayer}: crime detection, theft and combat deliberately
     * ignore fake players, so the paths under test only run for this one. Nothing it is sent goes
     * anywhere; the embedded channel only answers Forge's per-connection attribute reads.
     */
    private ServerPlayer realPlayer(BlockPos at, boolean inLevel) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "Real" + fixture);
        ServerPlayer player = new ServerPlayer(server, level, profile);
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet) { }
            @Override public void send(Packet<?> packet, @javax.annotation.Nullable net.minecraft.network.PacketSendListener listener) { }
        };
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(server, connection, player) {
            @Override public void send(Packet<?> packet) { }
            @Override public void send(Packet<?> packet, @javax.annotation.Nullable net.minecraft.network.PacketSendListener listener) { }
        };
        player.setPos(at.getX() + .5, at.getY(), at.getZ() + .5);
        net.minecraftforge.fml.util.ObfuscationReflectionHelper.setPrivateValue(
                net.minecraft.server.level.ServerPlayer.class, player, 0, "f_8921_");
        if (inLevel) level.addNewPlayer(player);
        playerList().add(player);
        onlinePlayers().put(player.getUUID(), player);
        require(CrimeCapabilities.get(player).isPresent(), "player capability missing");
        return player;
    }

    private void offline(ServerPlayer player) {
        playerList().remove(player);
        onlinePlayers().remove(player.getUUID(), player);
        if (level.getEntity(player.getId()) == player) {
            level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
    }

    @SuppressWarnings("unchecked")
    private List<ServerPlayer> playerList() {
        for (java.lang.reflect.Field field : PlayerList.class.getDeclaredFields()) {
            if (!(field.getGenericType() instanceof java.lang.reflect.ParameterizedType type)) continue;
            if (type.getRawType() != List.class || !Arrays.equals(type.getActualTypeArguments(),
                    new java.lang.reflect.Type[]{ServerPlayer.class})) continue;
            try {
                field.setAccessible(true);
                return (List<ServerPlayer>) field.get(server.getPlayerList());
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
        throw new AssertionError("server player list not found");
    }

    /** A committed, station-bound Thief, as the occupation transaction makes one. */
    private net.minecraft.world.entity.Mob stationThief(BlockPos at, BlockPos station) {
        level.setBlockAndUpdate(station, CrimeBlocks.MASK_STATION.get().defaultBlockState());
        net.minecraft.world.entity.Mob thief = mcaVillager(at);
        var result = dev.otectus.mcacrime.job.WorldCriminalJobService.of(server).requestThiefOccupation(
                dev.otectus.mcacrime.job.OccupationRequest.station(thief.getUUID(),
                        dev.otectus.mcacrime.job.OccupationSource.STATION_RECRUITMENT,
                        dev.otectus.mcacrime.state.world.WorksiteRef.of(level, station), false, false));
        require(result.committed(), "the Thief fixture did not commit: " + result.reason());
        require(dev.otectus.mcacrime.job.CriminalProfessions.THIEF_ID.equals(
                dev.otectus.mcacrime.compat.McaCompat.getProfessionId(thief).orElse(null)), "the fixture is not a Thief");
        return thief;
    }

    private void retireThief(net.minecraft.world.entity.Mob thief) {
        dev.otectus.mcacrime.mug.npc.NpcMuggingService.clearAll();
        dev.otectus.mcacrime.job.WorldCriminalJobService.of(server).retireOccupation(thief.getUUID(),
                dev.otectus.mcacrime.job.OccupationSource.OPERATOR);
        data.removeCriminalVillager(thief.getUUID());
        thief.discard();
    }

    /** A real MCA guard, standing still until the check lets its brain run. */
    private net.minecraft.world.entity.Mob mcaGuard(BlockPos pos) {
        net.minecraft.world.entity.Mob guard = mcaVillager(pos);
        require(dev.otectus.mcacrime.compat.McaCompat.setVillagerProfession(guard, new ResourceLocation("mca", "guard")),
                "the guard fixture could not be made a guard");
        require(dev.otectus.mcacrime.compat.McaCompat.isGuard(guard), "the guard fixture is not a guard");
        return guard;
    }

    /** An adult MCA villager standing still at {@code pos}, resolved by name: no MCA type is linked. */
    @SuppressWarnings("unchecked")
    private net.minecraft.world.entity.Mob mcaVillager(BlockPos pos) {
        var type = (EntityType<? extends net.minecraft.world.entity.Mob>) net.minecraftforge.registries.ForgeRegistries
                .ENTITY_TYPES.getValue(new ResourceLocation("mca", "male_villager"));
        require(type != null, "MCA's male_villager type is not registered");
        var mob = type.create(level);
        require(mob != null, "MCA villager could not be created");
        mob.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
        level.addFreshEntity(mob);
        ((net.minecraft.world.entity.npc.Villager) mob).setAge(0);
        mob.setNoAi(true);
        require(dev.otectus.mcacrime.compat.McaCompat.isMcaVillager(mob), "fixture is not an MCA villager");
        return mob;
    }

    private void deathClone() {
        TestPlayer original = player();
        CrimeCapabilities.get(original).orElseThrow().setKarma(-2345);
        CrimeCapabilities.get(original).orElseThrow().setHeat(73);
        CrimeCapabilities.get(original).orElseThrow().setJail(new dev.otectus.mcacrime.jail.JailState(
                1200L, original.blockPosition(), level.dimension().location(), 6,
                dev.otectus.mcacrime.jail.JailContainmentMode.CONTAINMENT));
        for (int cycle = 0; cycle < 3; cycle++) {
            var oldHandle = original.getCapability(CrimeCapabilities.PLAYER_CRIME);
            original.invalidateCaps();
            require(!oldHandle.isPresent() && CrimeCapabilities.get(original).isEmpty(), "removed player still exposes a capability");
            TestPlayer fresh = player();
            MinecraftForge.EVENT_BUS.post(new PlayerEvent.Clone(fresh, original, true));
            var copied = CrimeCapabilities.get(fresh).orElseThrow();
            require(copied.getKarma() == -2345 && copied.getHeat() == 73, "death clone erased karma or heat");
            require(copied.isJailed() && copied.getJail().getRemainingOnlineTicks() == 1200L,
                    "death clone erased the jail sentence");
            require(!oldHandle.isPresent() && CrimeCapabilities.get(original).isEmpty(), "clone revived an old handle or entity");
            original = fresh;
        }
    }

    private void jailArrival() {
        TestPlayer arriving = player(), other = player();
        BlockPos anchor = arriving.blockPosition();
        try {
            require(dev.otectus.mcacrime.jail.SafeCustodyDestination.validate(level, anchor, 0).isEmpty(),
                    "fixture must begin with an occupied anchor");
            require(dev.otectus.mcacrime.jail.SafeCustodyDestination.validate(level, anchor, 0, arriving).isPresent(),
                    "arriving prisoner blocked their own destination");
            other.setPos(arriving.position());
            require(dev.otectus.mcacrime.jail.SafeCustodyDestination.validate(level, anchor, 0, arriving).isEmpty(),
                    "another player was ignored as well");
            other.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
            require(dev.otectus.mcacrime.jail.SafeCustodyDestination.validate(level, anchor, 0, arriving).isPresent(),
                    "spectator blocked intake");
        } finally { arriving.discard(); other.discard(); }
    }

    private void padlockKey() {
        TestPlayer owner = player();
        BlockPos chest = owner.blockPosition().east(2);
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CrimeItems.PADLOCK.get()));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(chest).add(0, 0, -.5), Direction.NORTH, chest, false);
        CrimeItems.PADLOCK.get().useOn(new UseOnContext(owner, InteractionHand.MAIN_HAND, hit));
        LockRecord lock = LockProtection.lockAt(level, chest).orElseThrow();
        PadlockEntity holder = level.getEntitiesOfClass(PadlockEntity.class,
                new net.minecraft.world.phys.AABB(chest).inflate(2)).stream().findFirst().orElseThrow();
        TestPlayer stranger = player();
        ItemStack stolenBlank = new ItemStack(CrimeItems.KEY.get());
        LockInteractions.interact(stranger, level, stolenBlank, holder, chest);
        require(KeyBinding.read(stolenBlank.getTag()).isEmpty(), "stranger initialized owner's lock");
        ItemStack key = new ItemStack(CrimeItems.KEY.get());
        LockInteractions.interact(owner, level, key, holder, chest);
        require(KeyBinding.read(key.getTag()).orElseThrow().opens(data.lock(lock.lockId())), "owner key not bound");
        ItemStack secondBlank = new ItemStack(CrimeItems.KEY.get());
        LockInteractions.interact(owner, level, secondBlank, holder, chest);
        require(KeyBinding.read(secondBlank.getTag()).isEmpty(), "initialized lock copied another blank key");
        LockInteractions.interact(owner, level, key, holder, chest);
        require(!data.lock(lock.lockId()).locked(), "matching key did not unlock");
    }

    private void chestJoins() {
        for (int side : new int[]{-1, 1}) {
            TestPlayer actor = player();
            BlockPos first = actor.blockPosition().east(2);
            level.setBlockAndUpdate(first, Blocks.CHEST.defaultBlockState());
            LockRecord original = LockService.create(data,
                    LockTargetNormalizer.forBlock(level, level.dimension().location(), first), actor.getUUID()).orElseThrow();
            BlockPos other = first.offset(side, 0, 0);
            actor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHEST));
            Items.CHEST.useOn(new UseOnContext(actor, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(other.below()).add(0, .5, 0), Direction.UP, other.below(), false)));
            require(level.getBlockState(other).is(Blocks.CHEST), "second chest not placed");
            require(LockTargetNormalizer.partner(level, first) != null, "fixture did not create a double chest");
            for (BlockPos member : List.of(first, other)) {
                require(LockProtection.lockAt(level, member).orElseThrow().lockId().equals(original.lockId()), "lock identity lost");
                require(LockProtection.blocksAutomation(level, member, LockAutomationPolicy.Operation.EXTRACT), "hopper extraction allowed");
                TestPlayer stranger = player();
                PlayerInteractEvent.RightClickBlock click = new PlayerInteractEvent.RightClickBlock(stranger,
                        InteractionHand.MAIN_HAND, member,
                        new BlockHitResult(Vec3.atCenterOf(member), Direction.UP, member, false));
                MinecraftForge.EVENT_BUS.post(click);
                require(click.isCanceled(), "locked chest click not canceled");
            }
        }
    }

    private void detentionActions() {
        TestPlayer captive = player();
        BlockPos pos = captive.blockPosition();
        level.setBlockAndUpdate(pos, CrimeBlocks.PILLORY.get().defaultBlockState());
        require(DetentionService.claim(data, captive.getUUID(), true, DetentionKind.PILLORY,
                level.dimension().location(), pos, "pillory") == DetentionService.Refusal.NONE, "claim refused");
        AttackEntityEvent attack = new AttackEntityEvent(captive, player());
        MinecraftForge.EVENT_BUS.post(attack);
        require(attack.isCanceled(), "detained player attacked");
        BlockEvent.BreakEvent breaking = new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), captive);
        MinecraftForge.EVENT_BUS.post(breaking);
        require(breaking.isCanceled(), "detained player mined device");
    }

    private void cuffedActor() {
        TestPlayer actor = player();
        TestPlayer target = player();
        target.setPos(actor.getX() + 1, actor.getY(), actor.getZ());
        wear(actor, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS, ItemStack.EMPTY, actor.getUUID());
        wear(target, RestraintSlot.LEGS, RestraintDefinitions.HANDCUFFS_LEGS, ItemStack.EMPTY, actor.getUUID());
        actor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CrimeItems.RESTRAINT_CUFFS.get()));
        PlayerInteractEvent.EntityInteractSpecific click = new PlayerInteractEvent.EntityInteractSpecific(
                actor, InteractionHand.MAIN_HAND, target, new Vec3(0, target.getBbHeight() * .5, 0));
        MinecraftForge.EVENT_BUS.post(click);
        require(!RestraintService.state(target).occupied(RestraintSlot.ARMS), "cuffed actor applied cuffs");
        require(actor.getMainHandItem().getCount() == 1, "refused action consumed cuffs");
        data.removePhysicalRestraint(actor.getUUID());
        MinecraftForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteractSpecific(actor,
                InteractionHand.MAIN_HAND, target, new Vec3(0, target.getBbHeight() * .5, 0)));
        require(RestraintService.state(target).occupied(RestraintSlot.ARMS),
                "positive control could not apply cuffs: " + actor.lastMessage + "; "
                        + RestraintService.evaluate(actor, target, actor.getMainHandItem(), RestraintSlot.ARMS));
    }

    private void imbue(boolean accepted) {
        TestPlayer captor = player();
        TestPlayer recipient = player();
        recipient.invulnerable = !accepted;
        ItemStack gear = new ItemStack(CrimeItems.RESTRAINT_CUFFS.get());
        gear.enchant(CrimeEnchantments.IMBUE.get(), 1);
        wear(recipient, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS, gear, captor.getUUID());
        wear(captor, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS, ItemStack.EMPTY, captor.getUUID());
        ImbueIndex.rebuild(data.physicalRestraints());
        LivingDamageEvent event = new LivingDamageEvent(captor, level.damageSources().generic(), 10F);
        float before = recipient.getHealth();
        ImbueHandler.onDamage(event);
        if (accepted) {
            require(recipient.getHealth() < before, "passive transfer canceled by cuffs");
            require(event.getAmount() < 10F, "accepted transfer did not reduce damage");
        } else {
            require(event.getAmount() == 10F, "rejected transfer granted reduction");
            require(recipient.getHealth() == before, "invulnerable recipient damaged");
        }
    }

    private void escortTakeover() {
        TestPlayer subject = player(), holder = player(), guard = player();
        holder.setPos(subject.getX() + 1, subject.getY(), subject.getZ());
        guard.setPos(subject.getX() - 1, subject.getY(), subject.getZ());
        wear(subject, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS, ItemStack.EMPTY, holder.getUUID());
        TetherRecord chain = TetherService.attach(data, subject, holder, TetherKind.CHAIN, holder.getUUID(), true).orElseThrow();
        require(TetherService.escort(guard, subject).isPresent(), "takeover refused");
        require(data.tether(chain.id()).suspended(), "old chain not suspended");
        TetherService.endEscort(server, subject.getUUID(), TetherService.DetachReason.RELEASED);
        require(TetherService.active(data, subject.getUUID()).orElseThrow().id().equals(chain.id()), "old chain not resumed");
    }

    private void imbueCanceled() {
        TestPlayer captor = player(), recipient = player();
        ItemStack gear = new ItemStack(CrimeItems.RESTRAINT_CUFFS.get());
        gear.enchant(CrimeEnchantments.IMBUE.get(), 1);
        wear(recipient, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS, gear, captor.getUUID());
        ImbueIndex.rebuild(data.physicalRestraints());
        boolean[] reached = {false};
        java.util.function.Consumer<LivingDamageEvent> cancel = event -> {
            if (event.getEntity() == recipient) {
                reached[0] = true;
                event.setCanceled(true);
            }
        };
        MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.HIGHEST, cancel);
        try {
            LivingDamageEvent event = new LivingDamageEvent(captor, level.damageSources().generic(), 10F);
            ImbueHandler.onDamage(event);
            require(reached[0], "recipient damage never reached cancellation listener");
            require(event.getAmount() == 10F, "canceled actual damage granted reduction");
            require(recipient.getHealth() == recipient.getMaxHealth(), "canceled recipient damaged");
        } finally { MinecraftForge.EVENT_BUS.unregister(cancel); }
    }

    private void vehicleSeating() {
        for (boolean boat : new boolean[]{true, false}) {
            TestPlayer actor = player(), captive = player();
            captive.setPos(actor.getX() + 1, actor.getY(), actor.getZ());
            wear(captive, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS, ItemStack.EMPTY, actor.getUUID());
            wear(captive, RestraintSlot.LEGS, RestraintDefinitions.HANDCUFFS_LEGS, ItemStack.EMPTY, actor.getUUID());
            require(TetherService.escort(actor, captive).isPresent(), "escort fixture failed");
            Entity vehicle = boat ? new Boat(level, actor.getX(), actor.getY(), actor.getZ() + 1)
                    : new Minecart(level, actor.getX(), actor.getY(), actor.getZ() + 1);
            level.addFreshEntity(vehicle);
            MinecraftForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(actor, InteractionHand.MAIN_HAND, vehicle));
            require(captive.getVehicle() == vehicle, "captive not seated in " + vehicle.getType());
        }
    }

    private void struggleSwitch() {
        TestPlayer subject = player();
        wear(subject, RestraintSlot.ARMS, RestraintDefinitions.DUCK_TAPE_ARMS, ItemStack.EMPTY, subject.getUUID());
        wear(subject, RestraintSlot.LEGS, RestraintDefinitions.DUCK_TAPE_LEGS, ItemStack.EMPTY, subject.getUUID());
        EscapeService.struggle(subject, 0L, RestraintSlot.ARMS, StruggleInput.LEFT, 1);
        EscapeService.Attempt second = EscapeService.struggle(subject, 0L, RestraintSlot.LEGS, StruggleInput.RIGHT, 2);
        require(second.outcome() == EscapeService.Outcome.REFUSED, "same-tick slot switch accepted");
    }

    private void cuffedChainActor() {
        TestPlayer actor = player(), captive = player();
        captive.setPos(actor.getX() + 1, actor.getY(), actor.getZ());
        wear(captive, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS, ItemStack.EMPTY, actor.getUUID());
        wear(actor, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS, ItemStack.EMPTY, actor.getUUID());
        actor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHAIN));
        MinecraftForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(actor, InteractionHand.MAIN_HAND, captive));
        require(TetherService.active(data, captive.getUUID()).isEmpty(), "cuffed actor attached chain");
        require(actor.getMainHandItem().getCount() == 1, "refused chain consumed");
        data.removePhysicalRestraint(actor.getUUID());
        MinecraftForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(actor, InteractionHand.MAIN_HAND, captive));
        require(TetherService.active(data, captive.getUUID()).isPresent(), "positive control could not attach chain");
    }

    private BlockPos bunk(TestPlayer sleeper) {
        BlockPos foot = sleeper.blockPosition().east();
        BlockState state = CrimeBlocks.BUNK.get().defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        level.setBlock(foot, state.setValue(BedBlock.PART, BedPart.FOOT), 2);
        BlockPos head = foot.north();
        level.setBlock(head, state.setValue(BedBlock.PART, BedPart.HEAD), 3);
        return head;
    }

    private void useBunk(TestPlayer sleeper, BlockPos pos) {
        level.getBlockState(pos).use(level, sleeper, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private void bunkSleep() {
        TestPlayer sleeper = player();
        BlockPos head = bunk(sleeper);
        level.setDayTime(14000);
        level.updateSkyBrightness();
        useBunk(sleeper, head);
        require(sleeper.isSleeping(), "bunk sleep failed: " + sleeper.lastMessage);
        sleeper.stopSleepInBed(true, true);
        require(!level.getBlockState(head).getValue(BedBlock.OCCUPIED), "bunk stuck occupied");
        useBunk(sleeper, head);
        require(sleeper.isSleeping(), "bunk could not be reused");
        sleeper.stopSleepInBed(true, true);
        require(level.getBlockState(head).getRespawnPosition(EntityType.PLAYER, level, head, 0F, sleeper).isPresent(), "bunk has no respawn position");
    }

    private void bunkRefusal() {
        TestPlayer sleeper = player();
        BlockPos home = sleeper.blockPosition().west(10), head = bunk(sleeper);
        sleeper.setRespawnPosition(level.dimension(), home, 90F, true, false);
        level.setDayTime(1000);
        level.updateSkyBrightness();
        useBunk(sleeper, head);
        require(home.equals(sleeper.getRespawnPosition()), "refused sleep overwrote home");
        McaCrimeConfig.COMMON.bunkSetsRespawn.set(false);
        try {
            level.setDayTime(14000);
            level.updateSkyBrightness();
            useBunk(sleeper, head);
            require(home.equals(sleeper.getRespawnPosition()), "disabled bunk respawn overwrote home");
            sleeper.stopSleepInBed(true, true);
        } finally { McaCrimeConfig.COMMON.bunkSetsRespawn.set(true); }
    }

    private void custody(TestPlayer captive, TestPlayer holder) {
        data.putCustody(new CustodyRecord(captive.getUUID(), true, false,
                CustodyOwner.kidnapper(holder.getUUID()), level.getGameTime(), null, null));
        require(data.getCustody(captive.getUUID()) != null, "custody fixture not installed");
    }

    private void sleepAndWake(TestPlayer sleeper, BlockPos head) {
        level.setDayTime(14000);
        level.updateSkyBrightness();
        useBunk(sleeper, head);
        require(sleeper.isSleeping(), "custody bunk refused sleep: " + sleeper.lastMessage);
        sleeper.stopSleepInBed(true, true);
    }

    private void bunkRelease(boolean offline) {
        TestPlayer sleeper = player(), holder = player();
        BlockPos home = sleeper.blockPosition().west(10), head = bunk(sleeper);
        sleeper.setRespawnPosition(level.dimension(), home, 73F, true, false);
        custody(sleeper, holder);
        onlinePlayers().put(sleeper.getUUID(), sleeper);
        sleepAndWake(sleeper, head);
        sleepAndWake(sleeper, head);
        require(home.equals(BunkRespawnPolicy.snapshotFor(sleeper).orElseThrow().pos()),
                "repeated sleep replaced original home with bunk");
        require(head.equals(sleeper.getRespawnPosition()), "bunk did not become temporary spawn");
        if (offline) {
            PlayerCrimeData capability = CrimeCapabilities.get(sleeper).orElseThrow();
            CompoundTag saved = capability.save();
            onlinePlayers().remove(sleeper.getUUID());
            CustodyService.release(server, sleeper.getUUID(), CustodyReleaseReason.ADMIN);
            capability.setBunkRespawnSnapshot(null);
            capability.load(saved);
            require(BunkRespawnPolicy.snapshotFor(sleeper).isPresent(), "saved snapshot missing");
            onlinePlayers().put(sleeper.getUUID(), sleeper);
            BunkRespawnPolicy.onLogin(new PlayerEvent.PlayerLoggedInEvent(sleeper));
        } else {
            CustodyService.release(server, sleeper.getUUID(), CustodyReleaseReason.ADMIN);
        }
        require(home.equals(sleeper.getRespawnPosition()), "release did not restore home");
        require(sleeper.getRespawnAngle() == 73F && sleeper.isRespawnForced(), "home attributes lost");
        require(BunkRespawnPolicy.snapshotFor(sleeper).isEmpty(), "released snapshot not consumed");
    }

    private void bunkNewHome() {
        TestPlayer sleeper = player(), holder = player();
        BlockPos home = sleeper.blockPosition().west(10), newer = home.north(4), head = bunk(sleeper);
        sleeper.setRespawnPosition(level.dimension(), home, 0F, true, false);
        custody(sleeper, holder);
        onlinePlayers().put(sleeper.getUUID(), sleeper);
        sleepAndWake(sleeper, head);
        sleeper.setRespawnPosition(level.dimension(), newer, 20F, true, false);
        // Sleeping in custody again must remember the deliberate new choice, not the old home.
        sleepAndWake(sleeper, head);
        require(newer.equals(BunkRespawnPolicy.snapshotFor(sleeper).orElseThrow().pos()), "new home ignored");
        CustodyService.release(server, sleeper.getUUID(), CustodyReleaseReason.ADMIN);
        require(newer.equals(sleeper.getRespawnPosition()), "release overwrote newer home");
    }

    private void bunkNewDimension() {
        TestPlayer sleeper = player(), holder = player();
        BlockPos head = bunk(sleeper);
        custody(sleeper, holder);
        onlinePlayers().put(sleeper.getUUID(), sleeper);
        sleepAndWake(sleeper, head);
        sleeper.setRespawnPosition(net.minecraft.world.level.Level.NETHER, head, 28F, true, false);
        CustodyService.release(server, sleeper.getUUID(), CustodyReleaseReason.ADMIN);
        require(head.equals(sleeper.getRespawnPosition())
                        && sleeper.getRespawnDimension().equals(net.minecraft.world.level.Level.NETHER),
                "release overwrote deliberately chosen spawn in another dimension");
        require(sleeper.getRespawnAngle() == 28F, "new spawn angle overwritten");
        require(BunkRespawnPolicy.snapshotFor(sleeper).isEmpty(), "obsolete snapshot not consumed");
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, ServerPlayer> onlinePlayers() {
        // Fixtures use server lookup without opening client sockets or changing the online tick list.
        for (java.lang.reflect.Field field : PlayerList.class.getDeclaredFields()) {
            if (!(field.getGenericType() instanceof java.lang.reflect.ParameterizedType type)) continue;
            if (type.getRawType() != Map.class || !Arrays.equals(type.getActualTypeArguments(),
                    new java.lang.reflect.Type[]{UUID.class, ServerPlayer.class})) continue;
            try {
                field.setAccessible(true);
                return (Map<UUID, ServerPlayer>) field.get(server.getPlayerList());
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
        throw new AssertionError("server player lookup map not found");
    }

    private TestPlayer player() {
        TestPlayer player = new TestPlayer(level, new GameProfile(UUID.randomUUID(), "Fixture" + fixture));
        BlockPos pos = new BlockPos((fixture++ % 8) * 16, 65, (fixture / 8) * 16);
        for (BlockPos ground : BlockPos.betweenClosed(pos.offset(-5, -1, -5), pos.offset(5, -1, 5))) {
            level.setBlock(ground, Blocks.STONE.defaultBlockState(), 2);
        }
        player.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        // New server players have a grace period; these fixtures represent players already playing.
        net.minecraftforge.fml.util.ObfuscationReflectionHelper.setPrivateValue(
                net.minecraft.server.level.ServerPlayer.class, player, 0, "f_8921_");
        level.addNewPlayer(player);
        require(CrimeCapabilities.get(player).isPresent(), "player capability missing");
        return player;
    }

    private void wear(TestPlayer subject, RestraintSlot slot, ResourceLocation definition, ItemStack gear, UUID applier) {
        PhysicalRestraintState state = data.physicalRestraint(subject.getUUID());
        if (state == null) state = PhysicalRestraintState.empty(subject.getUUID(), true, level.dimension().location());
        AppliedRestraint worn = new AppliedRestraint(UUID.randomUUID(), definition,
                gear.isEmpty() ? null : gear.save(new CompoundTag()), 100, 1, RestraintApplier.player(applier),
                AppliedRestraint.ApplicationContext.VOLUNTARY, AppliedRestraint.Provenance.SYSTEM_ISSUED,
                AppliedRestraint.ReturnPolicy.NONE, null, level.getGameTime(), 1);
        require(data.putPhysicalRestraint(state.with(slot, worn)), "fixture state refused");
    }

    private void check(String name, Runnable test) {
        try { test.run(); results.add("PASS " + name); }
        catch (Throwable failure) { results.add("FAIL " + name + ": " + failure); failure.printStackTrace(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestPlayer extends FakePlayer {
        boolean invulnerable;
        String lastMessage = "";
        TestPlayer(ServerLevel level, GameProfile profile) { super(level, profile); }
        @Override public boolean isInvulnerableTo(DamageSource source) { return invulnerable; }
        @Override public boolean canHarmPlayer(net.minecraft.world.entity.player.Player other) { return true; }
        @Override public void displayClientMessage(Component message, boolean actionBar) {
            lastMessage = message.getString();
        }
    }
}
