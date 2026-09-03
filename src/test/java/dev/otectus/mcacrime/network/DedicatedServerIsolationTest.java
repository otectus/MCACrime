package dev.otectus.mcacrime.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dedicated server must never resolve a client class through the payload registrar (spec §9.4).
 *
 * <p>Registration is common code in 1.21.1 — there is no client-only registration event — so
 * {@code CrimeNetwork} runs on a dedicated server and everything it names is loaded there. The
 * constant pool is where that shows up, so this reads the bytes: if neither class so much as names
 * {@code dev/otectus/mcacrime/client/} or {@code net/minecraft/client/}, no classloader can be asked
 * for one. Same technique as {@code OptionalClassloadTest}, applied to the other seam in the mod.
 */
class DedicatedServerIsolationTest {

    private static final List<String> FORBIDDEN = List.of(
            "dev/otectus/mcacrime/client/",
            "net/minecraft/client/");

    /**
     * The two classes the registrar touches. {@code CrimeClientPayloadRouter} is the seam itself, and
     * is the one that would be easiest to "simplify" back into a direct client call.
     */
    private static final List<String> COMMON_SIDE = List.of(
            "dev/otectus/mcacrime/network/CrimeNetwork.class",
            "dev/otectus/mcacrime/network/CrimeClientPayloadRouter.class");

    private static Path mainClasses() {
        String root = System.getProperty("mcacrime.projectRoot");
        assertTrue(root != null && !root.isBlank(),
                "mcacrime.projectRoot is not set; the test task in build.gradle supplies it");
        return Paths.get(root, "build", "classes", "java", "main");
    }

    @Test
    void neitherRegistrarClassNamesAClientType() throws IOException {
        Path root = mainClasses();
        for (String relative : COMMON_SIDE) {
            Path classFile = root.resolve(relative);
            assertTrue(Files.exists(classFile), relative + " not found under " + root);

            String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            for (String forbidden : FORBIDDEN) {
                assertTrue(!bytes.contains(forbidden),
                        relative + " names " + forbidden + "; a dedicated server would try to load it");
            }
        }
    }

    /**
     * The router only earns its keep if the client implementation is reached through the interface. A
     * nested anonymous no-op is fine; a direct reference to the handler package is not.
     */
    @Test
    void theRouterNamesNothingButPayloadsAndItsOwnHandler() throws IOException {
        Path router = mainClasses().resolve("dev/otectus/mcacrime/network/CrimeClientPayloadRouter.class");
        String bytes = new String(Files.readAllBytes(router), StandardCharsets.ISO_8859_1);
        assertTrue(bytes.contains("CrimeClientPayloadRouter$Handler"),
                "the router should delegate through its own Handler interface");
    }
}
