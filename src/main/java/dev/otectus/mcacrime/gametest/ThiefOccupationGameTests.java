package dev.otectus.mcacrime.gametest;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.compat.OccupationCompat;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.CrimePoiTypes;
import dev.otectus.mcacrime.job.CriminalProfessions;
import dev.otectus.mcacrime.job.OccupationRequest;
import dev.otectus.mcacrime.job.OccupationSource;
import dev.otectus.mcacrime.job.OccupationStatus;
import dev.otectus.mcacrime.job.OccupationTransitionReason;
import dev.otectus.mcacrime.job.OccupationTransitionResult;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.CriminalVillagerRecord;
import dev.otectus.mcacrime.state.world.WorksiteRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The exclusive Thief occupation against a real MCA villager, a real POI manager and the real
 * reconciliation tick (0.7.2 §9–§10).
 *
 * <p>The three things that make the 0.7.2 Thief different from the 0.7.0 overlay are all world state
 * and none of them are reachable from a unit test: the villager's <em>native</em> profession has to
 * read back as {@code mcacrime:thief} through MCA's own setter, the Mask Station's POI ticket has to
 * actually be taken from {@code PoiManager}, and the whole thing has to survive
 * {@code ThiefOccupationLifecycle}'s twenty-tick reconciliation without being retired. This asserts
 * those, and then asserts that retiring gives the station and the profession back.
 *
 * <p>MCA is reached only by name, through {@code McaCompat} and the entity-type registry, exactly as
 * the other MCA-backed gametests do: {@code NoMcaStaticLinkTest} scans this source set too.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ThiefOccupationGameTests {

    /** The station, and the block beside it the candidate stands on. */
    private static final BlockPos STATION = new BlockPos(6, 1, 6);
    private static final BlockPos CANDIDATE = new BlockPos(5, 1, 6);

    private ThiefOccupationGameTests() {
    }

    /**
     * The whole committed path: an unemployed adult becomes a visible Thief bound to a station, keeps
     * it across the lifecycle's reconciliation, and gives both back when the occupation is retired.
     */
    @GameTest(template = "cell_parity", timeoutTicks = 200)
    public static void recruitingAThiefClaimsTheStationAndRetiringGivesItBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos station = place(helper);
        Mob villager = villager(helper);
        UUID id = villager.getUUID();
        WorldCriminalJobService jobs = WorldCriminalJobService.of(level.getServer());
        CrimeWorldData world = CrimeWorldData.get(level.getServer());
        try {
            helper.assertTrue(OccupationCompat.poiExists(level, station, CrimePoiTypes.MASK_STATION_KEY),
                    "A placed Mask Station is not a point of interest");
            helper.assertTrue(OccupationCompat.freeTickets(level, station) == 1,
                    "A fresh station does not offer exactly one ticket: "
                            + OccupationCompat.freeTickets(level, station));
            helper.assertTrue(jobs.occupationStatus(id) == OccupationStatus.NONE,
                    "The fixture villager already had an occupation");

            OccupationTransitionResult result = jobs.requestThiefOccupation(OccupationRequest.station(
                    id, OccupationSource.STATION_RECRUITMENT, WorksiteRef.of(level, station), false, false));
            helper.assertTrue(result.committed(),
                    "Recruitment did not commit: " + result.reason() + " (" + result.detail() + ")");

            helper.assertTrue(CriminalProfessions.THIEF_ID.equals(McaCompat.getProfessionId(villager).orElse(null)),
                    "The villager's native profession is " + McaCompat.getProfessionId(villager));
            helper.assertTrue(jobs.occupationStatus(id) == OccupationStatus.ACTIVE_BOUND_NOVICE,
                    "A freshly bound thief is " + jobs.occupationStatus(id));
            helper.assertTrue(jobs.get(id) == CriminalJob.THIEF, "The crime record does not say THIEF");
            helper.assertTrue(jobs.mayActAsThief(villager), "A committed thief may not act as one");
            helper.assertTrue(OccupationCompat.freeTickets(level, station) == 0,
                    "The station ticket was not claimed: " + OccupationCompat.freeTickets(level, station));
            helper.assertTrue(OccupationCompat.villagerXp(villager) >= 1,
                    "The trading XP floor was not applied: " + OccupationCompat.villagerXp(villager));
            CriminalVillagerRecord record = world.criminalVillager(id);
            helper.assertTrue(record != null && WorksiteRef.of(level, station).equals(record.worksite()),
                    "The record does not name the station it claimed: "
                            + (record == null ? "no record" : record.worksite()));

            // Two full reconciliation passes: a bound novice standing at its own station is exactly
            // the state ThiefOccupationLifecycle is written not to disturb (§10.3-10.4).
            helper.runAfterDelay(45, () -> {
                try {
                    helper.assertTrue(jobs.occupationStatus(id) == OccupationStatus.ACTIVE_BOUND_NOVICE,
                            "Reconciliation moved a bound novice to " + jobs.occupationStatus(id));
                    helper.assertTrue(OccupationCompat.freeTickets(level, station) == 0,
                            "Reconciliation released a held station claim");
                    helper.assertTrue(CriminalProfessions.THIEF_ID.equals(
                                    McaCompat.getProfessionId(villager).orElse(null)),
                            "Reconciliation took the profession away");

                    OccupationTransitionResult retired =
                            jobs.retireOccupation(id, OccupationSource.OPERATOR);
                    helper.assertTrue(retired.committed(), "Retirement did not commit: " + retired.reason());
                    helper.assertTrue(jobs.occupationStatus(id) == OccupationStatus.RETIRED,
                            "A retired thief is " + jobs.occupationStatus(id));
                    helper.assertTrue(OccupationCompat.freeTickets(level, station) == 1,
                            "Retirement did not give the station ticket back: "
                                    + OccupationCompat.freeTickets(level, station));
                    helper.assertTrue(OccupationCompat.poiExists(level, station, CrimePoiTypes.MASK_STATION_KEY),
                            "Retirement destroyed the point of interest");
                    helper.assertTrue(!CriminalProfessions.THIEF_ID.equals(
                                    McaCompat.getProfessionId(villager).orElse(null)),
                            "A retired thief still wears the profession");
                    helper.assertTrue(!jobs.mayActAsThief(villager), "A retired thief may still act as one");
                    helper.succeed();
                } finally {
                    cleanUp(jobs, world, villager);
                }
            });
        } catch (Throwable t) {
            cleanUp(jobs, world, villager);
            throw t;
        }
    }

    /**
     * The two rejections the settlement route owes §10.1: a child is not a candidate, and an employed
     * villager is never secretly converted. Neither may leave a claim or a record behind.
     */
    @GameTest(template = "cell_parity")
    public static void childrenAndEmployedVillagersAreRefusedWithoutSideEffects(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos station = place(helper);
        Mob villager = villager(helper);
        UUID id = villager.getUUID();
        WorldCriminalJobService jobs = WorldCriminalJobService.of(level.getServer());
        CrimeWorldData world = CrimeWorldData.get(level.getServer());
        WorksiteRef site = WorksiteRef.of(level, station);
        try {
            ((Villager) villager).setAge(-24000);
            helper.assertTrue(!McaCompat.isAdultVillager(villager), "The child fixture is still an adult");
            OccupationTransitionResult child = jobs.requestThiefOccupation(OccupationRequest.station(
                    id, OccupationSource.STATION_RECRUITMENT, site, false, false));
            helper.assertTrue(child.rejected() && child.reason() == OccupationTransitionReason.NOT_ELIGIBLE,
                    "A child was refused as " + child.outcome() + "/" + child.reason());
            assertUntouched(helper, level, station, jobs, world, id, "the child attempt");

            ((Villager) villager).setAge(0);
            helper.assertTrue(McaCompat.setVillagerProfession(villager,
                            ResourceLocation.withDefaultNamespace("farmer")),
                    "Cannot give the fixture an ordinary profession");
            OccupationTransitionResult employed = jobs.requestThiefOccupation(OccupationRequest.station(
                    id, OccupationSource.STATION_RECRUITMENT, site, false, false));
            helper.assertTrue(employed.rejected()
                            && employed.reason() == OccupationTransitionReason.ALREADY_EMPLOYED,
                    "An employed villager was refused as " + employed.outcome() + "/" + employed.reason());
            helper.assertTrue(ResourceLocation.withDefaultNamespace("farmer")
                            .equals(McaCompat.getProfessionId(villager).orElse(null)),
                    "The refused request changed the villager's profession anyway");
            assertUntouched(helper, level, station, jobs, world, id, "the employed attempt");
        } finally {
            cleanUp(jobs, world, villager);
        }
        helper.succeed();
    }

    /**
     * The 0.7.7 report: a master librarian in a trading hall has no MCA home village, so the wild route
     * took it and the transaction cleared its trades. A forced sweep at chance 1.0 must leave an
     * established villager exactly as it was (profession, level, XP, offers and job site), while the
     * unemployed adult beside it is recruited by the same pass, which proves the sweep really ran.
     *
     * <p>Its own batch: a sweep at chance 1.0 looks at every villager near every player in the level, so
     * it must not share a batch with tests whose villagers stand nearby.
     */
    @GameTest(template = "cell_parity", batch = "mcacrime_recruitment_sweep", timeoutTicks = 100)
    public static void anEstablishedLibrarianSurvivesAForcedSweep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        place(helper);
        WorldCriminalJobService jobs = WorldCriminalJobService.of(level.getServer());
        CrimeWorldData world = CrimeWorldData.get(level.getServer());
        Mob librarian = villager(helper);
        Mob blank = villagerAt(helper, new BlockPos(9, 1, 9));
        var observer = new net.neoforged.neoforge.common.util.FakePlayer(level,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "SweepObserver"));
        try {
            Villager trader = (Villager) librarian;
            ResourceLocation librarianId = ResourceLocation.withDefaultNamespace("librarian");
            helper.assertTrue(McaCompat.setVillagerProfession(librarian, librarianId),
                    "Cannot make the fixture a librarian");
            trader.setVillagerData(trader.getVillagerData().setLevel(5));
            trader.setVillagerXp(250);
            var offers = trader.getOffers();
            if (offers.isEmpty()) {
                offers.add(new net.minecraft.world.item.trading.MerchantOffer(
                        new net.minecraft.world.item.trading.ItemCost(net.minecraft.world.item.Items.EMERALD, 1),
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOOK), 12, 5, 0.05F));
            }
            offers.get(0).increaseUses();
            var offersBefore = offers.copy();
            var site = net.minecraft.core.GlobalPos.of(level.dimension(), helper.absolutePos(new BlockPos(3, 1, 3)));
            OccupationCompat.setJobSite(librarian, site);
            helper.assertTrue(WorldCriminalJobService.established(librarian), "The librarian fixture is not established");
            helper.assertTrue(!WorldCriminalJobService.established(blank), "The control fixture is already established");

            BlockPos at = helper.absolutePos(new BlockPos(6, 1, 4));
            observer.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            level.addNewPlayer(observer);
            dev.otectus.mcacrime.job.CriminalJobAssignmentSweep.sweep(level.getServer(),
                    new dev.otectus.mcacrime.job.CriminalJobAssigner.AssignmentPolicy(true, true, 1.0D, 1.0D, 1.0D, 1, 0));

            helper.assertTrue(jobs.get(blank.getUUID()) == CriminalJob.THIEF,
                    "The forced sweep did not recruit the unemployed control, so it proves nothing");
            helper.assertTrue(world.criminalVillager(librarian.getUUID()) == null && jobs.get(librarian.getUUID()) == CriminalJob.NONE,
                    "The sweep wrote a criminal record for an established librarian");
            helper.assertTrue(librarianId.equals(McaCompat.getProfessionId(librarian).orElse(null)),
                    "The librarian's profession became " + McaCompat.getProfessionId(librarian));
            helper.assertTrue(trader.getVillagerData().getLevel() == 5, "The librarian lost its level");
            helper.assertTrue(trader.getVillagerXp() == 250, "The librarian's trading XP became " + trader.getVillagerXp());
            var offersAfter = ((dev.otectus.mcacrime.mixin.MerchantOffersAccessor) trader).mcacrime$rawOffers();
            helper.assertTrue(offersAfter != null && offersAfter.size() == offersBefore.size()
                            && offersAfter.get(0).getUses() == offersBefore.get(0).getUses()
                            && net.minecraft.world.item.ItemStack.matches(offersAfter.get(0).getResult(), offersBefore.get(0).getResult()),
                    "The librarian's offers were changed or cleared");
            helper.assertTrue(site.equals(OccupationCompat.jobSite(librarian).orElse(null)),
                    "The librarian's job site memory was erased");
            OccupationTransitionResult forced = jobs.requestThiefOccupation(
                    OccupationRequest.unbound(librarian.getUUID(), OccupationSource.WILD, true));
            helper.assertTrue(forced.rejected() && forced.reason() == OccupationTransitionReason.ALREADY_EMPLOYED,
                    "A wild request for the librarian was " + forced.outcome() + "/" + forced.reason());
        } finally {
            observer.discard();
            cleanUp(jobs, world, librarian);
            cleanUp(jobs, world, blank);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ fixture

    /** A rejection may not consume the station, write a record, or leave the villager half-changed. */
    private static void assertUntouched(GameTestHelper helper, ServerLevel level, BlockPos station,
                                        WorldCriminalJobService jobs, CrimeWorldData world, UUID id,
                                        String what) {
        helper.assertTrue(OccupationCompat.freeTickets(level, station) == 1,
                what + " kept the station ticket");
        helper.assertTrue(jobs.occupationStatus(id) == OccupationStatus.NONE,
                what + " left the occupation at " + jobs.occupationStatus(id));
        helper.assertTrue(world.criminalVillager(id) == null, what + " wrote a criminal record");
    }

    /** Places a Mask Station on the platform and returns its world position. */
    private static BlockPos place(GameTestHelper helper) {
        for (int x = 0; x < 13; x++) {
            for (int z = 0; z < 13; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        helper.setBlock(STATION, CrimeBlocks.MASK_STATION.get().defaultBlockState());
        return helper.absolutePos(STATION);
    }

    /** An adult MCA villager beside the station, resolved by name like every other MCA fixture here. */
    private static Mob villager(GameTestHelper helper) {
        return villagerAt(helper, CANDIDATE);
    }

    @SuppressWarnings("unchecked")
    private static Mob villagerAt(GameTestHelper helper, BlockPos at) {
        EntityType<? extends Mob> type = (EntityType<? extends Mob>) BuiltInRegistries.ENTITY_TYPE
                .getOptional(ResourceLocation.fromNamespaceAndPath("mca", "male_villager")).orElseThrow();
        Mob mob = helper.spawn(type, at);
        ((Villager) mob).setAge(0);
        mob.setNoAi(true);
        mob.setOnGround(true);
        helper.assertTrue(helper.getLevel().getEntity(mob.getUUID()) == mob,
                "MCA villager missing from the level");
        helper.assertTrue(McaCompat.isMcaVillager(mob), "The fixture is not an MCA villager");
        return mob;
    }

    /** Leaves no occupation, no claim and no record behind for the next test in the batch. */
    private static void cleanUp(WorldCriminalJobService jobs, CrimeWorldData world, Mob villager) {
        jobs.retireOccupation(villager.getUUID(), OccupationSource.OPERATOR);
        world.removeCriminalVillager(villager.getUUID());
        villager.discard();
    }
}
