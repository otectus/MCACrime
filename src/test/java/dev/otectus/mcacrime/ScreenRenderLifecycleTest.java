package dev.otectus.mcacrime;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the compiled client rendering contract without loading client classes or needing OpenGL. */
class ScreenRenderLifecycleTest {
    private static final String SCREENS = "dev/otectus/mcacrime/client/screen/";
    private static final String GUARD = SCREENS + "GuardChallengeScreen";
    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String CLIENT_DATA = "dev/otectus/mcacrime/client/ClientChallengeData";

    @Test
    void screenBackgroundsNeverRestartTheScreenRenderLoop() throws IOException {
        Path directory = classesDirectory().resolve(SCREENS);
        assertTrue(Files.isDirectory(directory), "Compiled screens must exist before this test runs");
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith("Screen.class")).toList()) {
                for (Call call : calls(file)) {
                    if (!call.from().equals("renderBackground")) continue;
                    assertFalse((call.owner().equals(SCREEN) || call.owner().startsWith(SCREENS))
                                    && (call.name().equals("render") || call.name().equals("renderWithTooltip")),
                            file.getFileName() + ": background reenters screen rendering: " + call);
                }
            }
        }
    }

    @Test
    void guardFrameRendersWidgetsThenStatusThenAcknowledgesDisplayExactlyOnce() throws IOException {
        List<Call> calls = calls(classesDirectory().resolve(GUARD + ".class"));
        Call widgets = new Call("render", SCREEN, "render");
        Call status = new Call("render", GUARD, "drawStatusLine");
        Call displayed = new Call("render", CLIENT_DATA, "menuDisplayed");
        for (Call expected : List.of(widgets, status, displayed)) {
            var matching = calls.stream().filter(c -> c.owner().equals(expected.owner())
                    && c.name().equals(expected.name())).toList();
            assertEquals(List.of(expected), matching,
                    "Each frame operation belongs only in render(), once: " + expected);
        }
        assertTrue(calls.indexOf(widgets) < calls.indexOf(status), "Countdown must draw above widgets");
        assertTrue(calls.indexOf(status) < calls.indexOf(displayed),
                "The response timer starts only after the whole menu has rendered");
    }

    private static Path classesDirectory() {
        // NeoForge's runner changes its working directory; Forge runs from the project root.
        return Path.of(System.getProperty("mcacrime.projectRoot", "."), "build/classes/java/main");
    }

    private static List<Call> calls(Path file) throws IOException {
        List<Call> calls = new ArrayList<>();
        new ClassReader(Files.readAllBytes(file)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String target,
                                                String desc, boolean isInterface) {
                        calls.add(new Call(name, owner, target));
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return calls;
    }

    private record Call(String from, String owner, String name) {}
}
