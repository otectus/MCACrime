package dev.otectus.mcacrime.resource;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two cuff icons are MCA: Crime's own art and are not replaced by the 0.7.5 absorption.
 *
 * <p>A hash rather than "the file still exists", because the failure this guards against is not a
 * deletion — it is a well-meaning redraw. The 0.7.5 work re-points both item ids at new restraint
 * definitions ({@code restraint_cuffs} becomes the shackles family, {@code restraint_locked_cuffs}
 * the handcuff family) and imports a foreign art set beside them; the one thing that must survive
 * that byte for byte is the pixels players already have on their hotbars.
 *
 * <p>Read from {@code src/main/resources} rather than from the classpath so the assertion is about
 * the committed file, not about whatever a previous build happened to copy into {@code build/}. The
 * NeoForge unit-test runner works out of {@code build/minecraft-junit}, so the path is resolved from
 * the {@code mcacrime.projectRoot} system property the {@code test} task supplies rather than
 * relatively — a relative path here would resolve to nothing and fail as "file missing".
 */
class ProtectedTextureHashTest {

    private static final Path TEXTURES = projectRoot()
            .resolve(Path.of("src", "main", "resources", "assets", "mcacrime", "textures", "item"));

    private static Path projectRoot() {
        String root = System.getProperty("mcacrime.projectRoot");
        assertTrue(root != null && !root.isBlank(),
                "mcacrime.projectRoot is not set; the test task in build.gradle supplies it");
        return Path.of(root);
    }

    /** MCA: Crime's regular cuffs, protected since before this integration was planned. */
    private static final String CUFFS_SHA256 =
            "df1a7d30af0e4795cf706c94a4b37036dd748c4f9c750b793550daeb6f6997f4";

    /** MCA: Crime's locked cuffs. */
    private static final String LOCKED_CUFFS_SHA256 =
            "b0a86e25652e6d1acc7b2d1cf58a4be73b9e3bc4218760917337e324fd7d084f";

    private static String sha256(Path file) {
        assertTrue(Files.isRegularFile(file), file.toAbsolutePath() + " is missing");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file.toAbsolutePath(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JRE", e);
        }
    }

    @Test
    void theRegularCuffIconIsUnchanged() {
        assertEquals(CUFFS_SHA256, sha256(TEXTURES.resolve("restraint_cuffs.png")),
                "restraint_cuffs.png is protected art: its bytes may not change, whatever the item's "
                        + "restraint definition becomes");
    }

    @Test
    void theLockedCuffIconIsUnchanged() {
        assertEquals(LOCKED_CUFFS_SHA256, sha256(TEXTURES.resolve("restraint_locked_cuffs.png")),
                "restraint_locked_cuffs.png is protected art: its bytes may not change, whatever the "
                        + "item's restraint definition becomes");
    }

    /** The two icons are distinct files; a copy-paste that unified them would still hash-fail above. */
    @Test
    void theTwoProtectedTexturesAreNotTheSameImage() {
        assertTrue(!CUFFS_SHA256.equals(LOCKED_CUFFS_SHA256));
    }
}
