package dev.otectus.mcacrime.gametest;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.api.event.CrimeObservationEvent;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.detect.WitnessResult;
import dev.otectus.mcacrime.memory.CrimeObservation;
import dev.otectus.mcacrime.memory.ObservationService;
import dev.otectus.mcacrime.state.CrimeAttachments;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-side smoke coverage for the four things a unit test cannot reach (spec §13.1): the two
 * attachment copy filters, the world store's real {@code SavedData} round trip, and the public
 * cancellable event actually preventing a write.
 *
 * <p>Excluded from the production jar by {@code jar { exclude 'dev/otectus/mcacrime/gametest/**' }}
 * and asserted absent by {@code checkJarContents}; this package exists only for
 * {@code runGameTestServer}.
 *
 * <p>Both player tests drive {@link ServerPlayer#restoreFrom} rather than {@code PlayerList.respawn}
 * or {@code changeDimension}. That is not a shortcut around the real code path - it <em>is</em> the
 * code path: {@code restoreFrom} is the single line {@code PlayerList.respawn} runs on the new player
 * object (PlayerList.java:467), and it is where NeoForge fires {@code PlayerEvent.Clone}, whose only
 * listener is {@code AttachmentInternals#onPlayerClone} calling {@code copyEntityAttachments(from, to,
 * isDeath)}. {@code isDeath} is {@code !keepEverything}, so {@code restoreFrom(old, false)} is exactly
 * the {@code copyOnDeath} filter and {@code restoreFrom(old, true)} is exactly the keep-everything
 * filter. Going through {@code PlayerList.respawn} or {@code ServerPlayer#changeDimension} instead
 * would need a mock player holding a live {@code Connection} (and, for the dimension case, would not
 * even swap the player object any more), while every assertion would still land on the same two lines.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CrimeGameTests {

    /** Values chosen so a zeroed default cannot pass by accident. */
    private static final long KARMA = -4200L;
    private static final long HEAT = 913L;

    private CrimeGameTests() {
    }

    /**
     * Spec §7.4 row 8. Death used to be handled by a {@code PlayerEvent.Clone} listener of this mod's
     * own; now it is {@code .copyOnDeath()} on the attachment type, and no source-level check proves
     * that builder call is still there.
     */
    @GameTest(template = "platform")
    public static void respawnPreservesPlayerCrimeData(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer died = mockPlayer(level);
        PlayerCrimeData before = populate(died);

        ServerPlayer respawned = mockPlayer(level);
        // keepInventory=false, so wasDeath=true: the copyOnDeath filter.
        respawned.restoreFrom(died, false);

        assertCarried(helper, respawned, before, "death/respawn");
        discard(died, respawned);
        helper.succeed();
    }

    /**
     * Spec §7.4 row 9. The non-death copy filter, exercised across a genuine level boundary: the
     * receiving player object is built in the Nether, which is the shape a portal transfer had before
     * {@code changeDimension} started reusing the entity.
     */
    @GameTest(template = "platform")
    public static void dimensionTransferPreservesPlayerCrimeData(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            helper.fail("the gametest server has no " + Level.NETHER.location() + " dimension");
            return;
        }

        ServerPlayer travelling = mockPlayer(level);
        PlayerCrimeData before = populate(travelling);

        ServerPlayer arrived = mockPlayer(nether);
        // keepEverything=true, so wasDeath=false: every serializable attachment is copied.
        arrived.restoreFrom(travelling, true);

        helper.assertTrue(arrived.level().dimension() == Level.NETHER,
                "the receiving player must be in the Nether");
        assertCarried(helper, arrived, before, "dimension transfer");
        discard(travelling, arrived);
        helper.succeed();
    }

    /**
     * The world store survives a real {@code save}/{@code load} pair against the server's own registry
     * access, and still stamps the schema this build claims to write.
     */
    @GameTest(template = "platform")
    public static void worldDataRoundTrip(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CrimeWorldData data = CrimeWorldData.get(server);

        String counter = "mcacrime:gametest_round_trip";
        long before = data.actionCounter(counter);
        data.addActionCounter(counter, 7L);

        CompoundTag tag = data.save(new CompoundTag(), server.registryAccess());
        helper.assertTrue(CrimeDataMigrations.CURRENT_SCHEMA == 6,
                "this build writes schema " + CrimeDataMigrations.CURRENT_SCHEMA + ", expected 6");
        helper.assertTrue(CrimeDataMigrations.schemaOf(tag) == CrimeDataMigrations.CURRENT_SCHEMA,
                "saved tag is schema " + CrimeDataMigrations.schemaOf(tag));

        CrimeWorldData reloaded = CrimeWorldData.load(tag, server.registryAccess());
        helper.assertTrue(reloaded.actionCounter(counter) == before + 7L,
                "action counter did not survive the round trip: " + reloaded.actionCounter(counter));
        helper.succeed();
    }

    /**
     * The §12.5 seam: a listener that cancels {@link CrimeObservationEvent.Pre} stops the observation
     * being stored at all, rather than storing it and hiding it. The fired-count assertion is there so
     * a config that disabled observations outright could not make this pass vacuously.
     */
    @GameTest(template = "platform")
    public static void cancelledObservationPreIsNotRecorded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        int before = data.observationCount();

        ServerPlayer offender = mockPlayer(level);
        Cow witness = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(1, 2, 1));
        AtomicInteger fired = new AtomicInteger();
        CancellingListener listener = new CancellingListener(fired);
        NeoForge.EVENT_BUS.register(listener);
        try {
            List<CrimeObservation> stored = ObservationService.record(level, offender, null,
                    McaCrime.id("gametest_observation"), UUID.randomUUID(),
                    WitnessResult.of(Set.of(witness.getUUID()), 1, 1));
            helper.assertTrue(fired.get() > 0, "CrimeObservationEvent.Pre never fired");
            helper.assertTrue(stored.isEmpty(), "a cancelled observation was returned as stored");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }

        helper.assertTrue(data.observationCount() == before,
                "a cancelled observation reached the world store");
        helper.assertTrue(data.observationsBy(witness.getUUID()).isEmpty(),
                "the witness kept a cancelled observation");
        witness.discard();
        discard(offender);
        helper.succeed();
    }

    /** Cancels every observation it sees and counts how many that was. */
    private static final class CancellingListener {

        private final AtomicInteger fired;

        private CancellingListener(AtomicInteger fired) {
            this.fired = fired;
        }

        @SubscribeEvent
        public void onObservation(CrimeObservationEvent.Pre event) {
            fired.incrementAndGet();
            event.setCanceled(true);
        }
    }

    /**
     * A server player that is not in the player list and has no connection. Everything
     * {@link ServerPlayer#restoreFrom} touches is null-safe without one ({@code onUpdateAbilities}
     * checks the connection itself), and staying out of the list keeps the test from disturbing the
     * rest of the batch.
     */
    private static ServerPlayer mockPlayer(ServerLevel level) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "mcacrime-gametest");
        return new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
    }

    /** Writes the fields the copy filters have to carry, and returns the source data. */
    private static PlayerCrimeData populate(ServerPlayer player) {
        PlayerCrimeData data = CrimeAttachments.get(player);
        data.setKarma(KARMA);
        data.setHeat(HEAT);
        data.setCachedBand(Band.RED);
        data.setWantedCached(true);
        return data;
    }

    private static void assertCarried(GameTestHelper helper, ServerPlayer player,
                                      PlayerCrimeData source, String path) {
        PlayerCrimeData carried = CrimeAttachments.get(player);
        helper.assertTrue(carried != source, path + " handed over the same object instead of a copy");
        helper.assertTrue(carried.getKarma() == KARMA, path + " lost karma: " + carried.getKarma());
        helper.assertTrue(carried.getHeat() == HEAT, path + " lost heat: " + carried.getHeat());
        helper.assertTrue(carried.getCachedBand() == Band.RED,
                path + " lost the cached band: " + carried.getCachedBand());
        helper.assertTrue(carried.isWantedCached(), path + " lost the wanted flag");
    }

    private static void discard(ServerPlayer... players) {
        for (ServerPlayer player : players) {
            player.discard();
        }
    }
}
