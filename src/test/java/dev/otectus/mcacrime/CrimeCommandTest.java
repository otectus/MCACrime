package dev.otectus.mcacrime;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import dev.otectus.mcacrime.command.CrimeCommand;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrimeCommandTest {
    private static CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeAll
    static void registerCommands() {
        dispatcher = new CommandDispatcher<>();
        CrimeCommand.onRegisterCommands(new RegisterCommandsEvent(
                dispatcher, Commands.CommandSelection.ALL, null));
    }

    /** A command block has level 2 and no executing player entity. */
    private static CommandSourceStack source(int permission) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, permission,
                "test", Component.literal("test"), null, null);
    }

    private static boolean parses(String command, int permission) {
        ParseResults<CommandSourceStack> result = dispatcher.parse(command, source(permission));
        return result.getContext().getCommand() != null
                && !result.getReader().canRead() && result.getExceptions().isEmpty();
    }

    @Test
    void commandBlockPermissionCanSetHeatWithoutAnExecutingPlayer() {
        // Named targets exercise the same permission path without bootstrapping Minecraft's
        // entity registries, which vanilla selector parsing requires in an in-game test.
        assertNull(source(2).getEntity());
        assertTrue(parses("crime set heat TestPlayer 100", 2));
    }

    @Test
    void heatStillRejectsUnprivilegedSources() {
        assertFalse(parses("crime set heat TestPlayer 100", 0));
        assertFalse(parses("crime set heat TestPlayer 100", 1));
        assertTrue(parses("crime set heat TestPlayer 100", 3));
        assertTrue(parses("crime set heat TestPlayer 100", 4));
    }

    @Test
    void otherAdministrativeCommandsStillRequireLevelThree() {
        for (String command : new String[]{"crime set karma TestPlayer 100", "crime clearheat TestPlayer",
                "crime jail TestPlayer 100", "crime release TestPlayer", "crime assignjail 0 64 0",
                "crime reload", "crime validate"}) {
            assertFalse(parses(command, 2), command);
            assertTrue(parses(command, 3), command);
        }
    }

    @Test
    void heatStillRequiresNonnegativeValue() {
        assertTrue(parses("crime set heat TestPlayer 0", 2));
        assertFalse(parses("crime set heat TestPlayer -1", 2));
        assertFalse(parses("crime set heat TestPlayer", 2));
    }
}
