package dev.otectus.mcacrime.usability;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/**
 * The 0.8.0 usability fixtures: command targeting, the guillotine, keys/rings/molds and mask dyeing,
 * exercised through production services with real registries and a real MCA install.
 *
 * <p>Three suites. {@code baseline} uses only APIs that existed before 0.8.0, and records what the jar it
 * runs against actually does ({@code OBSERVED} lines, never a failure), so it can be pointed at an older
 * release to capture the behaviour 0.8.0 replaces. {@code full} asserts the 0.8.0 behaviour
 * ({@code PASS}/{@code FAIL}). {@code upgrade} seeds a world on the previous release (phase 0, old-API
 * calls only) and verifies it on 0.8.0 after the runner swaps the jar (phase 1). The runner's phase file
 * selects first-run or after-restart checks.
 */
@Mod("crime_usability_checks")
public final class UsabilityRuntimeChecks {

    /** A check that needs ticks to pass; {@code problem} answers null once it holds. */
    record Waiting(String name, int deadline, Supplier<String> problem, Runnable cleanup) {
    }

    final List<String> results = new ArrayList<>();
    private final List<Waiting> waiting = new ArrayList<>();
    /** Steps that must run last, after every waiting check, immediately before the server stops. */
    private final List<Runnable> atEnd = new ArrayList<>();
    private MinecraftServer server;
    int ticks;
    private boolean finished;

    public UsabilityRuntimeChecks() {
        MinecraftForge.EVENT_BUS.addListener(this::started);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
    }

    private void started(ServerStartedEvent event) {
        server = event.getServer();
    }

    static String suite() {
        return System.getProperty("crime.usability.suite", "full");
    }

    static int phase() {
        try {
            return Integer.parseInt(Files.readString(Path.of("usability-phase.txt")).trim());
        } catch (Exception missing) {
            return 0;
        }
    }

    private void tick(TickEvent.ServerTickEvent event) {
        if (server == null || finished || event.phase != TickEvent.Phase.END || ++ticks < 5) {
            return;
        }
        if (ticks == 5) {
            Fixtures fixtures = new Fixtures(server);
            results.add("INFO suite=" + suite() + " phase=" + phase());
            try {
                if ("baseline".equals(suite())) {
                    new TargetingBaselineProbe(fixtures, this).run();
                } else if ("upgrade".equals(suite())) {
                    // Phase 0 runs on the previous release and seeds the world; phase 1 reopens it on 0.8.0.
                    if (phase() == 0) {
                        new UpgradeSeed(fixtures, this).run();
                    } else {
                        new UpgradeVerify(fixtures, this).run();
                    }
                } else {
                    FullSuite.run(fixtures, this);
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

    // --- reporting ----------------------------------------------------------------------------------

    void observed(String name, String what) {
        results.add("OBSERVED " + name + ": " + what);
    }

    void info(String line) {
        results.add("INFO " + line);
    }

    /** Runs one synchronous check: PASS when it returns without throwing, FAIL with the message otherwise. */
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

    /** Runs {@code step} after every other check has finished, right before the server stops. */
    void atEnd(Runnable step) {
        atEnd.add(step);
    }

    void await(String name, int timeoutTicks, Supplier<String> problem, Runnable cleanup) {
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
                results.add("FAIL " + check.name() + ": " + failure);
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
        try {
            if (check.cleanup() != null) {
                check.cleanup().run();
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
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
