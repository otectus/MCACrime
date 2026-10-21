package dev.otectus.mcacrime.usability;

import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/**
 * The 0.8.0 upgrade fixture on a real NeoForge server: the counterpart of the Forge build's
 * {@code tools/usability} harness, carrying only its {@code upgrade} suite. Everything else that harness
 * proves runs here as GameTests; an upgrade needs a world saved by one release and reopened by another,
 * which a GameTest cannot give.
 *
 * <p>Phase 0 (the runner's phase file) seeds the world on the previous release with old-API calls only;
 * phase 1 verifies it after the runner swapped the jar.
 */
@Mod("crime_usability_checks")
public final class UsabilityRuntimeChecks {

    /** A check that needs ticks to pass; {@code problem} answers null once it holds. */
    record Waiting(String name, int deadline, Supplier<String> problem) {
    }

    final List<String> results = new ArrayList<>();
    private final List<Waiting> waiting = new ArrayList<>();
    private final List<Runnable> atEnd = new ArrayList<>();
    private MinecraftServer server;
    int ticks;
    private boolean finished;

    public UsabilityRuntimeChecks() {
        NeoForge.EVENT_BUS.addListener(this::started);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void started(ServerStartedEvent event) {
        server = event.getServer();
        // 1.21.1 keeps only a 2-chunk spawn area loaded and ticking (1.20.1 kept 11). The fixtures reach 120
        // blocks out, and a guillotine whose chunk never ticks would never let its persisted blade land. The
        // rule is saved with the world, so the reopened world loads and ticks the same area.
        server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_SPAWN_CHUNK_RADIUS).set(10, server);
    }

    /** The farthest fixture position, which has to be entity-ticking before any fixture is placed or read. */
    private static final net.minecraft.core.BlockPos FARTHEST = new net.minecraft.core.BlockPos(120, 100, 44);
    private int start = -1;

    static int phase() {
        try {
            return Integer.parseInt(Files.readString(Path.of("usability-phase.txt")).trim());
        } catch (Exception missing) {
            return 0;
        }
    }

    private void tick(ServerTickEvent.Post event) {
        if (server == null || finished || ++ticks < 5) {
            return;
        }
        if (start < 0) {
            if (!server.overworld().isPositionEntityTicking(FARTHEST)) {
                if (ticks > 1200) {
                    results.add("FAIL harness: the fixture area never became entity-ticking");
                    finish();
                }
                return;
            }
            start = ticks;
            Fixtures fixtures = new Fixtures(server);
            results.add("INFO suite=upgrade phase=" + phase());
            try {
                if (phase() == 0) {
                    new UpgradeSeed(fixtures, this).run();
                } else {
                    new UpgradeVerify(fixtures, this).run();
                }
            } catch (Throwable failure) {
                results.add("FAIL harness: " + failure);
                failure.printStackTrace();
            }
        }
        poll();
        if (waiting.isEmpty() && !atEnd.isEmpty()) {
            List<Runnable> last = List.copyOf(atEnd);
            atEnd.clear();
            for (Runnable step : last) {
                try {
                    step.run();
                } catch (Throwable failure) {
                    results.add("FAIL final step: " + failure);
                    failure.printStackTrace();
                }
            }
            return; // stop on the next tick, so what the last steps set in motion is saved mid-flight
        }
        if (waiting.isEmpty()) {
            finish();
        }
    }

    void info(String line) {
        results.add("INFO " + line);
    }

    void check(String name, ThrowingRunnable body) {
        try {
            body.run();
            results.add("PASS " + name);
        } catch (Throwable failure) {
            results.add("FAIL " + name + ": " + failure.getMessage());
            failure.printStackTrace();
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    void atEnd(Runnable step) {
        atEnd.add(step);
    }

    void await(String name, int timeoutTicks, Supplier<String> problem) {
        waiting.add(new Waiting(name, ticks + timeoutTicks, problem));
    }

    private void poll() {
        for (Iterator<Waiting> it = waiting.iterator(); it.hasNext(); ) {
            Waiting check = it.next();
            String problem;
            try {
                problem = check.problem().get();
            } catch (Throwable failure) {
                failure.printStackTrace();
                results.add("FAIL " + check.name() + ": " + failure);
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
            it.remove();
        }
    }

    private void finish() {
        finished = true;
        results.add("COMPLETE " + results.size() + " lines");
        try {
            Files.write(Path.of("runtime-results.txt"), results);
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
        results.forEach(System.out::println);
        server.halt(false);
    }
}
